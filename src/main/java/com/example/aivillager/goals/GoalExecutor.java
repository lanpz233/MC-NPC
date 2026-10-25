package com.example.aivillager.goals;

import com.example.aivillager.AiVillagerPlugin;
import com.example.aivillager.intent.Intent;
import com.example.aivillager.npc.FakePlayerBody;
import com.example.aivillager.pathfinding.PathFinder;
import com.example.aivillager.skills.SkillRunner;
import com.example.aivillager.survival.SurvivalController;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 目标层：把「像玩家一样发展」拆成一个个中期目标，由这里确定性执行。
 *
 * <p>为什么需要它：原来的自主决策每 20 秒问一次模型“现在做什么”，模型只能回一个原子动作
 * （走一步/挖一个方块），没法表达“砍 16 个木头→做木板→做木镐→挖石头→做石镐”这种多步计划。
 * 现在模型只负责<b>定目标</b>，执行、进度统计、失败重试全部在这里做，目标完成后再回到模型。
 *
 * <p>线程约定：所有方法都在主线程（周期任务/Brain 回调），不跨线程访问世界。
 */
public final class GoalExecutor {

    private static final Logger LOG = Logger.getLogger("AIVillager");

    /** 玩家背包主背包槽位数（0-8 快捷栏 + 9-35 主背包）；护甲槽不参与材料统计。 */
    private static final int MAIN_SLOTS = 36;
    /** 采集时单次搜索半径；技能层内部还会再限制到 8 格。 */
    private static final int GATHER_RADIUS = 8;
    /** 连续多少次采集没有进度就判定这个目标做不到。 */
    private static final int MAX_STALE_CHECKS = 8;
    /** 连续多少次找不到目标方块就放弃（中间会先随机走走换位置）。 */
    private static final int MAX_GATHER_FAILS = 6;
    /** 盖一个 3x3 小屋需要的方块数：两层墙 16 + 屋顶 9。 */
    private static final int SHELTER_BLOCKS = 25;

    private static final String OK = "ok";

    private final AiVillagerPlugin plugin;
    private final FakePlayerBody npc;
    private final SkillRunner skills;
    private BukkitTask task;

    private boolean active;
    private String type = "-";
    private String targetId = "-";
    /**
     * 目标开始时所处的维度。
     * 传送门会把 NPC 送到另一个维度，旧目标（挖矿/采集）在新维度里已经不成立，
     * 必须立即结束，让规划器按新维度重新排步骤。
     */
    private String goalDimension = "";
    private int targetCount;
    private int attempts;
    private int outcomeCount;
    private int lastProgress = -1;
    /**
     * gather 开始那一刻背包里已有的数量。
     * 进度只算"本轮新采到多少"，否则背包里本来就有 15 个竹子时，
     * 「采集 8 个」会在第一秒宣布完成，模型又下一遍同样的目标，NPC 看着就像站着不动。
     */
    private int baselineCount;
    private int staleChecks;
    /** dryland 连续失败次数：失败 3 次后跳过，避免无限重试泡在水里 20 秒又 20 秒。 */
    private int drylandFailures;
    private boolean exploreStarted;
    /**
     * 上一条矿道挖空了（一块矿没出，或被悬空/流体堵死）：下一轮掘进前先挪一段距离。
     * 在刚挖空的区域原地再下发 strip 只会重复同一条走廊
     * （实测 105,19,61 ↔ 112,19,61 往返 7 轮，步数凑满 24 但一块矿都没有）。
     */
    private boolean relocateBeforeStrip;
    /** 已经处理过的 strip 结果：同一个结果字符串不要每 tick 重复触发"换点"。 */
    private String handledStripOutcome = "-";
    private String status = "无目标";
    private String lastOutcome = "-";
    /** 挖掘类目标的资源定义（方块 ID、掉落物、目标深度）；其他目标为 null。 */
    private OreTarget oreTarget;
    /** 熔炼时放下的熔炉位置；null 表示还没放置。 */
    private BlockPos furnacePos;
    private int smeltTicks;
    private int smeltBaseline;
    /** 狩猎目标开始时的肉类数量，进度只看本轮新打到的。 */
    private int huntBaseline;
    private int huntAttempts;
    /** 定点猎杀：目标实体 ID、对应掉落物、开始时背包里的掉落数量。 */
    private String slayEntity = "";
    private String slayDrop = "string";
    private int slayBaseline;
    private int slayAttempts;
    /** 长途行进的目标坐标（travel 目标使用）。 */
    private double travelX;
    private double travelY;
    private double travelZ;
    /** endportal 目标：0 前往房间、1 镶满眼睛、2 走进传送门。 */
    private int endPhase;
    /** 末地屠龙：0 拆水晶、1 打龙。 */
    private int dragonPhase;
    private int dragonAttempts;
    /** 传送门选址结果：origin 是框架左下角，alongX 表示门面横向沿 X 轴。 */
    private BlockPos portalOrigin;
    private boolean portalAlongX = true;
    /** portal 目标的阶段：0 选址建造、1 等建造完成、2 进入、3 等传送结果。 */
    private int portalPhase;
    private int enterAttempts;

    /** 一种可挖掘资源：挖什么方块、掉什么、挖到哪一层。 */
    private record OreTarget(String block, String drop, int y) {
    }

    /**
     * 资源别名表。玩家说"挖铁"时，目标层要知道对应方块、掉落物和合理深度：
     * 铁在 Y=16 附近最密集，钻石在 Y=-54，煤和铁同一层（Y=16），石头随处都是。
     */
    private static OreTarget oreTargetOf(String resource) {
        return switch (resource) {
            case "stone", "cobblestone", "石头" -> new OreTarget("stone", "cobblestone", 50);
            // 煤和铁放在同一层（Y=16）：煤在 0~192 全高度都有，没必要为它单独爬回地表。
            // 实测按 Y=48 挖时 NPC 每次都要 ascend 三十多级到丛林地表，那里又全是水和树，
            // 一条矿道挖穿也见不到煤，白白耗掉一整个目标周期；和铁共用一个矿层效率高得多。
            case "coal", "coal_ore", "煤" -> new OreTarget("coal_ore", "coal", 16);
            case "iron", "iron_ore", "铁", "raw_iron" -> new OreTarget("iron_ore", "raw_iron", 16);
            case "gold", "gold_ore", "金" -> new OreTarget("gold_ore", "raw_gold", 16);
            case "diamond", "diamond_ore", "钻石" -> new OreTarget("diamond_ore", "diamond", -54);
            case "emerald", "emerald_ore" -> new OreTarget("emerald_ore", "emerald", 32);
            case "redstone", "redstone_ore" -> new OreTarget("redstone_ore", "redstone", -40);
            case "lapis", "lapis_ore" -> new OreTarget("lapis_ore", "lapis_lazuli", 16);
            case "copper", "copper_ore" -> new OreTarget("copper_ore", "raw_copper", 48);
            default -> null;
        };
    }

    public GoalExecutor(AiVillagerPlugin plugin, FakePlayerBody npc, SkillRunner skills) {
        this.plugin = plugin;
        this.npc = npc;
        this.skills = skills;
    }

    public void start() {
        // 0.5 秒检查一次：足够跟上技能结束，又不会在主线程上空转。
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::safeTick, 20L, 10L);
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 目标层启动");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        active = false;
        type = "-";
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 目标层停止");
    }

    public boolean active() {
        return active;
    }

    public String status() {
        return active ? status : "无目标";
    }

    public String lastOutcome() {
        return lastOutcome;
    }

    /** 模型/玩家给出的动作是不是一个中期目标。 */
    public static boolean isGoalAction(String action) {
        return "gather".equals(action) || "craft".equals(action)
                || "build".equals(action) || "explore".equals(action)
                || "dig".equals(action) || "smelt".equals(action) || "hunt".equals(action)
                || "flint".equals(action) || "obsidian".equals(action) || "portal".equals(action)
                || "enterportal".equals(action) || "slay".equals(action) || "travel".equals(action)
                || "endportal".equals(action) || "dragon".equals(action);
    }

    /**
     * 开始一个新目标。返回 false 表示参数不合法（不会改变当前目标）。
     */
    public boolean start(Intent intent) {
        String action = intent.action();
        String newTarget;
        int newCount;
        switch (action) {
            case "gather" -> {
                newTarget = normalizeBlock(intent.param("block", ""));
                if (newTarget.isEmpty()) {
                    lastOutcome = "gather 缺少 block 参数";
                    return false;
                }
                newCount = clamp(intent.intParam("count", 8), 1, 64);
            }
            case "craft" -> {
                newTarget = normalizeItem(intent.param("item", ""));
                if (newTarget.isEmpty()) {
                    lastOutcome = "craft 缺少 item 参数";
                    return false;
                }
                newCount = clamp(intent.intParam("count", 1), 1, 16);
            }
            case "build" -> {
                newTarget = intent.param("structure", "shelter");
                newCount = 1;
            }
            case "explore" -> {
                newTarget = "explore";
                newCount = clamp(intent.intParam("distance", 32), 16, 48);
            }
            case "dig" -> {
                newTarget = normalizeBlock(intent.param("resource", ""));
                OreTarget target = oreTargetOf(newTarget);
                if (target == null) {
                    lastOutcome = "dig 不支持的资源 " + newTarget;
                    return false;
                }
                oreTarget = target;
                newTarget = target.block();
                newCount = clamp(intent.intParam("count", 8), 1, 64);
            }
            case "smelt" -> {
                newTarget = normalizeItem(intent.param("item", ""));
                if (smeltInputFor(newTarget) == null) {
                    lastOutcome = "smelt 不支持的产品 " + newTarget;
                    return false;
                }
                newCount = clamp(intent.intParam("count", 8), 1, 64);
            }
            case "hunt" -> {
                newTarget = "hunt";
                newCount = clamp(intent.intParam("count", 3), 1, 8);
            }
            case "flint" -> {
                newTarget = "flint";
                newCount = clamp(intent.intParam("count", 1), 1, 8);
            }
            case "obsidian" -> {
                newTarget = "obsidian";
                newCount = clamp(intent.intParam("count", 10), 1, 32);
            }
            case "portal" -> {
                newTarget = "portal";
                newCount = 1;
            }
            case "enterportal" -> {
                // 已经点亮的门只需要走进去；坐标由 tickEnterPortal 每轮重新扫描，
                // 这里不接受外部坐标参数（玩家/模型给的坐标常常对不准门中心）。
                newTarget = "enterportal";
                newCount = 1;
            }
            case "slay" -> {
                newTarget = normalizeEntity(intent.param("entity", ""));
                if (newTarget.isEmpty()) {
                    lastOutcome = "slay 缺少 entity 参数";
                    return false;
                }
                newCount = clamp(intent.intParam("count", 3), 1, 64);
            }
            case "travel" -> {
                newTarget = "travel";
                newCount = 1;
                travelX = intent.doubleParam("x", npc.getX());
                travelY = intent.doubleParam("y", npc.getY());
                travelZ = intent.doubleParam("z", npc.getZ());
            }
            case "endportal" -> {
                newTarget = "endportal";
                newCount = 1;
                // 规划器会把要塞传送门房间的坐标传进来；缺省则原地扫描。
                travelX = intent.doubleParam("x", npc.getX());
                travelY = intent.doubleParam("y", npc.getY());
                travelZ = intent.doubleParam("z", npc.getZ());
            }
            case "dragon" -> {
                newTarget = "dragon";
                newCount = 1;
            }
            default -> {
                return false;
            }
        }

        // 新目标覆盖旧目标：同时取消旧动作，避免走到一半还按旧路径继续走。
        if (active) {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 目标被替换 | old=" + type + " new=" + action);
            skills.stop();
        }
        type = action;
        targetId = newTarget;
        targetCount = newCount;
        attempts = 0;
        staleChecks = 0;
        lastProgress = -1;
        // 必须在 targetId 赋值之后算：gatherCount() 要看目标物品。
        baselineCount = "gather".equals(type) ? gatherCount()
                : "dig".equals(type) ? countItem(oreTarget.drop()) : 0;
        smeltTicks = 0;
        smeltBaseline = "smelt".equals(type) ? countItem(targetId) : 0;
        furnacePos = null;
        huntBaseline = "hunt".equals(type) ? countHuntLoot() : 0;
        huntAttempts = 0;
        // 定点猎杀按"对应掉落物"统计进度：杀 1 只掉 0 个的随机性很大，
        // 按击杀数算会让目标在掉落没涨时误判完成。
        slayEntity = "slay".equals(type) ? targetId : "";
        slayDrop = "slay".equals(type) ? dropOfEntity(targetId) : "string";
        slayBaseline = "slay".equals(type) ? countItem(slayDrop) : 0;
        slayAttempts = 0;
        endPhase = 0;
        dragonPhase = 0;
        dragonAttempts = 0;
        portalOrigin = null;
        portalPhase = 0;
        enterAttempts = 0;
        exploreStarted = false;
        active = true;
        goalDimension = npc.level().dimension().identifier().toString();
        status = describeStart();
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 接受目标 | " + status);
        return true;
    }

    private String describeStart() {
        return switch (type) {
            case "gather" -> "采集 " + targetId + " " + targetCount + " 个";
            case "craft" -> "合成 " + targetId + " x" + targetCount;
            case "build" -> "建造 " + targetId;
            case "dig" -> "挖矿 " + targetId + " → " + oreTarget.drop()
                    + " x" + targetCount + "（Y≈" + oreTarget.y() + "）";
            case "smelt" -> "熔炼 " + targetId + " x" + targetCount;
            case "hunt" -> "狩猎 " + targetCount + " 只动物";
            case "flint" -> "找燧石 x" + targetCount;
            case "obsidian" -> "浇铸黑曜石 x" + targetCount;
            case "portal" -> "建造并点燃下界传送门";
            case "enterportal" -> "走进已点亮的传送门";
            case "slay" -> "猎杀 " + targetId + " → " + slayDrop + " x" + targetCount;
            case "travel" -> String.format(Locale.ROOT, "长途行进 → (%.0f, %.0f, %.0f)",
                    travelX, travelY, travelZ);
            case "endportal" -> "找到并激活末地传送门";
            case "dragon" -> "击杀末影龙（通关）";
            default -> "探索 " + targetCount + " 格";
        };
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable throwable) {
            // 周期任务一旦抛异常会被 Bukkit 静默取消，目标层就永远不动了；这里必须自己兜住。
            LOG.log(java.util.logging.Level.WARNING,
                    "[AIV][GOAL] " + npc.npcName() + " tick 异常", throwable);
        }
    }

    private void tick() {
        if (!active) {
            return;
        }
        if (!npc.isAlive()) {
            return;
        }
        // 维度变了（穿过传送门）：旧目标里的坐标、资源层高度全部作废，立即结束。
        if (!goalDimension.isEmpty()
                && !goalDimension.equals(npc.level().dimension().identifier().toString())) {
            finish("维度切换 " + goalDimension + " → " + npc.level().dimension().identifier()
                    + "，旧目标中止");
            return;
        }
        // 技能还在执行（走路/挖矿）就等它结束，目标层只负责“下一步做什么”。
        if (!"idle".equals(skills.currentSkill())) {
            return;
        }
        // 生存层正在逃跑：这时推进目标会把逃跑路径冲掉，等于让 NPC 站着挨打。
        SurvivalController survival = plugin.survival(npc.npcName());
        if (survival != null && survival.isFleeing()) {
            return;
        }
        // 5 格内有敌对生物且生存层已经接管：把移动权交给 KillAura 追击。
        // 否则目标层每 0.5 秒重新下发 descend/strip，和生存层的追击互相打断，
        // 实测夜间日志成对出现"descend 开始 / 中断技能自卫"，NPC 既不下矿也不打怪。
        // 用 survival.isEngaged() 而不是自己数怪：它会把"打不到的怪"排除掉，
        // 避免一只卡在墙后的僵尸把目标层永久暂停。
        if (survival != null ? survival.isEngaged() : hostileNearby(5)) {
            return;
        }
        // 先收掉落物再推进目标：原版拾取要求碰撞箱相交，
        // 伪玩家挖掉邻格方块后掉落物落在 1 格外，不主动收就会一直"挖了但数量不涨"。
        pickupDrops();
        // 背包维护：每 5 秒裁一次"总量超限"的堆积（圆石 >128、泥巴/树苗等）。
        // 不能只在"背包全满"时才裁：实测囤到 836 个圆石占了 14 格，
        // 等满的时候合成产物已经没地方放，变成"空槽=0"的假缺料死循环。
        if (npc.tickCount % 100 == 0) {
            trimOverflow();
        }
        switch (type) {
            case "gather" -> tickGather();
            case "craft" -> tickCraft();
            case "build" -> tickBuild();
            case "explore" -> tickExplore();
            case "dig" -> tickDig();
            case "smelt" -> tickSmelt();
            case "hunt" -> tickHunt();
            case "flint" -> tickFlint();
            case "obsidian" -> tickObsidian();
            case "portal" -> tickPortal();
            case "enterportal" -> tickEnterPortal();
            case "slay" -> tickSlay();
            case "travel" -> tickTravel();
            case "endportal" -> tickEndPortal();
            case "dragon" -> tickDragon();
            default -> finish("未知目标类型 " + type);
        }
    }

    /**
     * 收掉附近属于自己挖出来的掉落物（Wurst 风格的自动拾取）。
     *
     * <p>只碰 owner 为空的掉落物：玩家主动丢出的物品 ItemEntity 会记录 owner，
     * 这样 NPC 不会把别人丢在地上的装备顺手牵走。
     * 必须等原版拾取延迟（掉落后 10 tick）走完才能触碰，所以每个 tick 尝试一次即可。
     */
    private void pickupDrops() {
        List<Entity> drops = npc.level().getEntities(npc, npc.getBoundingBox().inflate(3.0D),
                entity -> entity instanceof ItemEntity item
                        && item.isAlive()
                        && !item.hasPickUpDelay()
                        && item.getOwner() == null);
        // 背包没有空槽时先丢一件杂物：掉落物留在原地会消失，挖矿的产出就白费了。
        // 只在真的有好东西可捡时才丢，避免在空地上每秒丢一次。
        if (!drops.isEmpty() && !hasEmptyMainSlot()) {
            dropJunk(1);
        }
        for (Entity drop : drops) {
            ItemEntity item = (ItemEntity) drop;
            String path = pathOf(item.getItem());
            int amount = item.getItem().getCount();
            item.playerTouch(npc);
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 拾取掉落 | " + path + " x" + amount
                    + " 剩余=" + (item.isAlive() ? item.getItem().getCount() : 0));
        }
    }

    // ---------------------------------------------------------------- 采集

    private void tickGather() {
        boolean anyLog = isAnyLogTarget();
        // 进度按"本轮新采到多少"算，而不是背包总量：
        // 否则背包里已有 9 个竹子时，"采集 8 个"第一秒就宣布完成，
        // NPC 会不停重复同一个目标却什么都没做，看起来像站着发呆。
        int absolute = gatherCount();
        int have = Math.max(0, absolute - baselineCount);
        status = "采集 " + targetId + " 新采 " + have + "/" + targetCount + "（背包共 " + absolute + "）";
        if (have >= targetCount) {
            finish("采集完成 " + targetId + " 新采 " + have + "/" + targetCount
                    + "，背包共 " + absolute);
            return;
        }

        // 技能结束后的空档才检查进度；连续多次没涨说明这个目标当前做不到（比如附近没树了）。
        // 走路/砍树技能本身可能要跑十几秒，那期间没有新掉落是正常的。
        if ("idle".equals(skills.currentSkill())) {
            if (have == lastProgress) {
                if (++staleChecks >= MAX_STALE_CHECKS) {
                    finish("采集放弃：连续 " + MAX_STALE_CHECKS + " 次没有进展 " + have + "/" + targetCount);
                    return;
                }
            } else {
                lastProgress = have;
                staleChecks = 0;
            }
        }

        // 采集原木时直接用通配符交给技能层：附近有什么树就砍什么树（丛林里就是丛林木），
        // 不再先解析成某个具体树种，避免"只认橡木"。
        String mineBlock = anyLog ? "*_log" : targetId;
        JsonObject params = new JsonObject();
        params.addProperty("block", mineBlock);
        params.addProperty("radius", GATHER_RADIUS);
        boolean started = skills.run(new Intent("", "mine", params), "目标");
        if (started) {
            attempts = 0;
            return;
        }
        // 原木常长在陡坡/树冠里，A* 找不到落脚点；死磕会连续放弃。
        // 附近有竹子就改用竹子：原版配方里 9 竹子→竹块→竹木板，一样能继续发展。
        if (anyLog && attempts >= 2 && nearbyBlockPos("bamboo") != null) {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 原木不可达，改采附近竹子");
            targetId = "bamboo";
            // 换目标等于换计数口径：以切换时的竹子数量作为新基线，否则会把旧竹子算成本轮进度。
            baselineCount = countItem("bamboo");
            lastProgress = -1;
            staleChecks = 0;
            attempts = 0;
            return;
        }
        // 附近找不到目标方块：先随机换个位置再试，而不是原地反复失败。
        attempts++;
        if (attempts >= MAX_GATHER_FAILS) {
            finish("采集放弃：附近找不到 " + targetId);
            return;
        }
        wander();
    }

    private boolean isAnyLogTarget() {
        return "*_log".equals(targetId) || "log".equals(targetId) || "wood".equals(targetId)
                || targetId.endsWith("_log");
    }

    /**
     * 当前采集目标在背包里的总量。
     * 目标是"任意原木"时按所有 *_log 合计：丛林里可能同时有丛林木、橡木、深色橡木，
     * 只统计某一种会让进度一直不动。
     */
    private int gatherCount() {
        if (isAnyLogTarget()) {
            return countItemsEndingWith("_log");
        }
        return countItem(itemPathForBlock(targetId));
    }

    /** 扫描范围内是否有指定方块（按方块 ID 精确匹配），返回最近的位置；没有则 null。 */
    private BlockPos nearbyBlockPos(String expectedPath) {
        BlockPos center = npc.blockPosition();
        BlockPos best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-GATHER_RADIUS, -3, -GATHER_RADIUS),
                center.offset(GATHER_RADIUS, 3, GATHER_RADIUS))) {
            String path = BuiltInRegistries.BLOCK.getKey(npc.level().getBlockState(pos).getBlock()).getPath();
            if (!expectedPath.equals(path)) {
                continue;
            }
            double distanceSq = center.distSqr(pos);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = pos.immutable();
            }
        }
        return best;
    }

    /**
     * 附近没有目标方块时换个位置找。
     *
     * <p>只试一个 8-14 格的方向在丛林里几乎必失败：树冠和陡坡会把 A* 的节点预算吃光
     * （日志里的「超出节点上限」就是这么来的），NPC 于是一直站在原地。
     * 改成多试几个更近的方向（4-9 格），总有一个方向能走出去。
     */
    private void wander() {
        String reason = "附近没有 " + targetId + "，换个位置寻找";
        for (int attempt = 0; attempt < 5; attempt++) {
            double angle = java.util.concurrent.ThreadLocalRandom.current().nextDouble() * Math.PI * 2.0D;
            double distance = 4.0D + java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 5.0D;
            JsonObject params = new JsonObject();
            params.addProperty("x", npc.getX() + Math.cos(angle) * distance);
            params.addProperty("z", npc.getZ() + Math.sin(angle) * distance);
            if (skills.run(new Intent("", "goto", params), "目标")) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " " + reason + " accepted=true"
                        + " 距离=" + String.format(Locale.ROOT, "%.1f", distance));
                return;
            }
        }
        LOG.info("[AIV][GOAL] " + npc.npcName() + " " + reason + " accepted=false（5 个方向都走不通）");
    }

    /** 方块挖掉后掉落的物品 ID。大多数同名，少数需要映射。 */
    private static String itemPathForBlock(String blockPath) {
        return switch (blockPath) {
            case "stone" -> "cobblestone";
            case "deepslate" -> "cobbled_deepslate";
            case "grass_block" -> "dirt";
            case "coal_ore", "deepslate_coal_ore" -> "coal";
            case "iron_ore", "deepslate_iron_ore" -> "raw_iron";
            case "copper_ore", "deepslate_copper_ore" -> "raw_copper";
            case "gold_ore", "deepslate_gold_ore" -> "raw_gold";
            case "diamond_ore", "deepslate_diamond_ore" -> "diamond";
            case "emerald_ore", "deepslate_emerald_ore" -> "emerald";
            case "lapis_ore", "deepslate_lapis_ore" -> "lapis_lazuli";
            case "redstone_ore", "deepslate_redstone_ore" -> "redstone";
            // 树叶、草这类会掉落随机种子/树苗，不适合按数量采集，保持同名让模型看到 0 进度后自己换目标。
            default -> blockPath;
        };
    }

    // ---------------------------------------------------------------- 合成

    private void tickCraft() {
        String counted = countPath(targetId);
        int have = countItemFor(counted);
        status = "合成 " + targetId + " " + have + "/" + targetCount;
        if (have >= targetCount) {
            finish("合成完成 " + targetId + " " + have + "/" + targetCount);
            return;
        }

        // 合成前先保证有落脚槽位：递归合成会产出木板/木棍/成品三次，
        // 背包满时第一步就失败，看起来像"缺材料"，实际是没地方放。
        if (!hasEmptyMainSlot()) {
            dropJunk(2);
        }

        // 一次 tick 内尽量把整批做完；有上限，避免配方写错时死循环。
        for (int guard = 0; guard < 64 && countItemFor(counted) < targetCount; guard++) {
            String result = craftOne(targetId);
            if (!OK.equals(result)) {
                // 把空槽数一起打出来：合成失败有时不是缺材料，而是产物没地方放（give 失败）。
                finish("合成失败：" + result + " | 空槽=" + countEmptyMainSlots());
                return;
            }
        }
        int now = countItemFor(counted);
        if (now >= targetCount) {
            finish("合成完成 " + targetId + " " + now + "/" + targetCount);
        } else {
            finish("合成失败：" + targetId + " 只做到 " + now + "/" + targetCount);
        }
    }

    /** 合成链最大递归深度：原木→木板→木棍→石镐 最多 4 层，留点余量同时防止配方环。 */
    private static final int MAX_RECIPE_DEPTH = 6;

    /** 从服务端配方表里选出来的一条可执行配方：slots 每格代表消耗一份材料。 */
    private record AutoRecipe(java.util.List<org.bukkit.inventory.RecipeChoice> slots, int outputCount) {
    }

    /**
     * 合成一次。配方<b>不写死</b>，而是查服务端的原版配方表（Bukkit#getRecipesFor），
     * 所以 原木→木板、竹子→竹块→竹木板、木板+木棍→木镐 全都能走通；
     * 模型以后说任何原版物品（包括数据包新增的配方）都能做。
     */
    private String craftOne(String itemPath) {
        // 为什么失败要回滚：递归合成会先把木板做成木棍、再回头发现剑身没料了，
        // 一次失败就把整批木料吃干净；下一次重试从更差的库存出发，永远补不回来。
        // 合成必须是"要么全成、要么原样不动"，玩家也是先把材料点够再一次性放上台面。
        java.util.List<ItemStack> before = snapshotInventory();
        String result = craftOneRaw(itemPath);
        if (!OK.equals(result)) {
            restoreInventory(before);
        }
        return result;
    }

    /** 工作台 36 格主背包的快照（不含护甲槽，合成只动主背包）。 */
    private java.util.List<ItemStack> snapshotInventory() {
        java.util.List<ItemStack> copy = new java.util.ArrayList<>(MAIN_SLOTS);
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            copy.add(npc.getInventory().getItem(slot).copy());
        }
        return copy;
    }

    private void restoreInventory(java.util.List<ItemStack> snapshot) {
        for (int slot = 0; slot < MAIN_SLOTS && slot < snapshot.size(); slot++) {
            npc.getInventory().setItem(slot, snapshot.get(slot).copy());
        }
    }

    private String craftOneRaw(String itemPath) {
        // "planks"/"oak_planks" 都按“任意木板”处理：模型指定树种时不该被附近没有该树种卡死。
        if ("planks".equals(itemPath) || itemPath.endsWith("_planks")) {
            for (org.bukkit.Material material : org.bukkit.Material.values()) {
                if (!material.name().endsWith("_PLANKS") || !material.isItem()) {
                    continue;
                }
                String candidate = material.getKey().getKey();
                if (OK.equals(craftFromRecipe(candidate, 0, new java.util.HashSet<>()))) {
                    return OK;
                }
            }
            return "没有任何木板能做（缺少原木或竹子）";
        }
        return craftFromRecipe(itemPath, 0, new java.util.HashSet<>());
    }

    /**
     * 递归合成：先查目标物品的配方，缺哪一格材料就先合成那格，最后统一扣料。
     *
     * @param visiting 当前合成链上的物品，用于阻止 A 需要 B、B 又需要 A 的配方环
     */
    private String craftFromRecipe(String itemPath, int depth, java.util.Set<String> visiting) {
        if (depth >= MAX_RECIPE_DEPTH) {
            return "合成链太深（" + itemPath + "）";
        }
        if (!visiting.add(itemPath)) {
            return "配方循环：" + itemPath;
        }
        try {
            AutoRecipe recipe = bestRecipe(itemPath);
            if (recipe == null) {
                LOG.info("[AIV][GOAL-DEBUG] craftFromRecipe item=" + itemPath + " depth=" + depth
                        + " 无配方！所有物品配方数=" + org.bukkit.Bukkit.getRecipesFor(
                                new org.bukkit.inventory.ItemStack(
                                        org.bukkit.Material.matchMaterial(itemPath) != null
                                                ? org.bukkit.Material.matchMaterial(itemPath)
                                                : org.bukkit.Material.AIR
                                )).size());
                return "没有 " + itemPath + " 的合成配方";
            }
            LOG.info("[AIV][GOAL-DEBUG] craftFromRecipe item=" + itemPath + " depth=" + depth
                    + " 配方=" + recipe.slots().size() + "格");
            // 相同材料格要合并计数：3 块圆石占 3 格，不能按"有 1 块就够"处理。
            java.util.Map<org.bukkit.inventory.RecipeChoice, Integer> required = new java.util.LinkedHashMap<>();
            for (org.bukkit.inventory.RecipeChoice choice : recipe.slots()) {
                required.merge(choice, 1, Integer::sum);
            }
            for (java.util.Map.Entry<org.bukkit.inventory.RecipeChoice, Integer> entry : required.entrySet()) {
                int need = entry.getValue();
                LOG.info("[AIV][GOAL-DEBUG] craftFromRecipe item=" + itemPath + " depth=" + depth
                        + " 需要 " + need + "×" + describeChoice(entry.getKey())
                        + " 现有=" + countMatching(entry.getKey()));
                while (countMatching(entry.getKey()) < need) {
                    String error = craftMissing(entry.getKey(), depth + 1, visiting);
                    if (error != null) {
                        LOG.info("[AIV][GOAL-DEBUG] craftFromRecipe item=" + itemPath + " depth=" + depth
                                + " craftMissing 返回 " + error);
                        return error;
                    }
                }
            }
            for (java.util.Map.Entry<org.bukkit.inventory.RecipeChoice, Integer> entry : required.entrySet()) {
                if (!consumeMatching(entry.getKey(), entry.getValue())) {
                    return "材料不足：" + describeChoice(entry.getKey());
                }
            }
            String given = give(itemPath, recipe.outputCount());
            if (!OK.equals(given)) {
                return given;
            }
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 合成 " + itemPath + " x" + recipe.outputCount());
            return OK;
        } finally {
            visiting.remove(itemPath);
        }
    }

    /** 某一格材料背包里没有时，从候选物品里挑一个能合成的递归做出来。 */
    private String craftMissing(org.bukkit.inventory.RecipeChoice choice, int depth,
                                java.util.Set<String> visiting) {
        String lastError = null;
        for (String candidate : candidatePaths(choice)) {
            org.bukkit.Material material = org.bukkit.Material.matchMaterial(candidate);
            if (material == null || !material.isItem()) {
                continue;
            }
            // 该物品本身没有合成配方（只能通过采集/冶炼获取）：跳过，
            // 背包里本来就没有它，craftFromRecipe 会报"没有合成配方"，
            // 这不是真正的失败——真正的失败是所有候选都试过了。
            if (org.bukkit.Bukkit.getRecipesFor(new org.bukkit.inventory.ItemStack(material)).isEmpty()) {
                continue;
            }
            LOG.info("[AIV][GOAL-DEBUG] craftMissing 尝试 candidate=" + candidate
                    + " depth=" + depth + " visiting=" + visiting);
            String error = craftFromRecipe(candidate, depth, visiting);
            LOG.info("[AIV][GOAL-DEBUG] craftMissing candidate=" + candidate
                    + " error=" + error);
            if (OK.equals(error)) {
                return null;
            }
            lastError = error;
        }
        return lastError != null ? lastError : "缺少材料：" + describeChoice(choice);
    }

    /** 判断配方是不是"纯采集物配方"——所有槽的候选都是无合成配方的采集物（竹子、甘蔗等）。 */
    private boolean isRawRecipe(AutoRecipe recipe) {
        for (org.bukkit.inventory.RecipeChoice choice : recipe.slots()) {
            for (String candidate : candidatePaths(choice)) {
                org.bukkit.Material material = org.bukkit.Material.matchMaterial(candidate);
                if (material != null && material.isItem()
                        && !org.bukkit.Bukkit.getRecipesFor(new org.bukkit.inventory.ItemStack(material)).isEmpty()) {
                    // 只要有一个候选本身有合成配方，就不是纯采集物配方
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 在服务端配方表里挑一条目标物品的配方。
     * 同一产物常有多条配方（不同树种木板、火把用煤或木炭），
     * 选"背包现有材料覆盖最多"的那条，避免拿着白桦原木去做橡木木板。
     */
    private AutoRecipe bestRecipe(String itemPath) {
        org.bukkit.Material material = org.bukkit.Material.matchMaterial(itemPath);
        if (material == null || !material.isItem()) {
            return null;
        }
        java.util.List<org.bukkit.inventory.Recipe> recipes = Bukkit.getServer()
                .getRecipesFor(new org.bukkit.inventory.ItemStack(material));
        LOG.info("[AIV][GOAL-DEBUG] bestRecipe item=" + itemPath + " 找到配方数=" + recipes.size());
        AutoRecipe best = null;
        boolean bestComplete = false;
        int bestSlots = Integer.MAX_VALUE;
        int bestScore = -1;
        for (org.bukkit.inventory.Recipe bukkitRecipe : recipes) {
            AutoRecipe candidate = toAutoRecipe(bukkitRecipe);
            if (candidate == null) {
                continue;
            }
            boolean complete = true;
            int score = 0;
            for (org.bukkit.inventory.RecipeChoice choice : candidate.slots()) {
                if (countMatching(choice) > 0) {
                    score++;
                } else {
                    complete = false;
                }
            }
            boolean candIsRaw = isRawRecipe(candidate);
            LOG.info("[AIV][GOAL-DEBUG] bestRecipe item=" + itemPath
                    + " complete=" + complete + " score=" + score
                    + " slots=" + candidate.slots().size() + " raw=" + candIsRaw
                    + " 材料=" + describeChoice(candidate.slots().get(0))
                    + (candidate.slots().size() > 1 ? "+" + describeChoice(candidate.slots().get(1)) : ""));
            // 同一产物常有多条配方（木棍既可用 2 块木板、也可用 2 根竹子）。
            // 先比"是不是纯采集物配方"（竹子/甘蔗→木棍）：纯采集物配方是死胡同——
            // 竹子没有合成链、只能硬凑数量；木板有合成链（原木→木板→木棍），
            // 哪怕现在没木板但有原木就能补。所以只要同时存在非纯采集物配方，
            // 不管纯采集物是不是"刚好凑齐"都先选非纯采集物的——
            // 实测 1 根竹子能凑齐 bamboo_stick 配方的 1/2 格（score=1 complete=false），
            // 甚至 2 根竹子能凑齐（complete=true），但 NPC 有 3 棵原木能走木板路线，
            // 硬吃竹子配方只会让 1 根竹子变不成 2 根、永远卡着。
            // 只有"所有配方都是纯采集物"或"非纯采集物路线真的走不通"时才退回采集物配方。
            boolean better;
            if (best == null) {
                better = true;
            } else {
                boolean bestIsRaw = isRawRecipe(best);
                if (candIsRaw != bestIsRaw) {
                    better = !candIsRaw;
                } else if (complete != bestComplete) {
                    better = complete;
                } else if (complete) {
                    better = candidate.slots().size() < bestSlots;
                } else if (score != bestScore) {
                    better = score > bestScore;
                } else {
                    better = candidate.slots().size() < bestSlots;
                }
            }
            if (better) {
                best = candidate;
                bestComplete = complete;
                bestSlots = candidate.slots().size();
                bestScore = score;
            }
        }
        return best;
    }

    private AutoRecipe toAutoRecipe(org.bukkit.inventory.Recipe bukkitRecipe) {
        java.util.List<org.bukkit.inventory.RecipeChoice> slots = new java.util.ArrayList<>();
        int outputCount = bukkitRecipe.getResult() == null ? 0 : bukkitRecipe.getResult().getAmount();
        if (bukkitRecipe instanceof org.bukkit.inventory.ShapedRecipe shaped) {
            java.util.Map<Character, org.bukkit.inventory.RecipeChoice> choices = shaped.getChoiceMap();
            for (String row : shaped.getShape()) {
                for (int index = 0; index < row.length(); index++) {
                    char symbol = row.charAt(index);
                    if (symbol == ' ') {
                        continue;
                    }
                    org.bukkit.inventory.RecipeChoice choice = choices.get(symbol);
                    if (choice != null) {
                        slots.add(choice);
                    }
                }
            }
        } else if (bukkitRecipe instanceof org.bukkit.inventory.ShapelessRecipe shapeless) {
            slots.addAll(shapeless.getChoiceList());
        } else {
            // 熔炉/切石机等需要工作站，目标层暂不模拟；只做工作台配方。
            return null;
        }
        if (slots.isEmpty() || outputCount <= 0) {
            return null;
        }
        return new AutoRecipe(slots, outputCount);
    }

    /** 一格里允许放哪些物品（配方用标签时会给出多个候选）。 */
    private java.util.List<String> candidatePaths(org.bukkit.inventory.RecipeChoice choice) {
        java.util.List<String> paths = new java.util.ArrayList<>();
        if (choice instanceof org.bukkit.inventory.RecipeChoice.MaterialChoice materialChoice) {
            for (org.bukkit.Material material : materialChoice.getChoices()) {
                paths.add(material.getKey().getKey());
            }
        } else if (choice instanceof org.bukkit.inventory.RecipeChoice.ExactChoice exactChoice) {
            for (org.bukkit.inventory.ItemStack stack : exactChoice.getChoices()) {
                paths.add(stack.getType().getKey().getKey());
            }
        } else if (choice instanceof org.bukkit.inventory.RecipeChoice.ItemTypeChoice itemTypeChoice) {
            for (io.papermc.paper.registry.TypedKey<org.bukkit.inventory.ItemType> key
                    : itemTypeChoice.itemTypes().values()) {
                paths.add(key.value());
            }
        }
        return paths;
    }

    /** 背包里能匹配这一格材料的物品总数（按原版标签判断，比如任意木板/任意原木）。 */
    private int countMatching(org.bukkit.inventory.RecipeChoice choice) {
        int total = 0;
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (choice.test(org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(stack))) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean consumeMatching(org.bukkit.inventory.RecipeChoice choice, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < MAIN_SLOTS && remaining > 0; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (!choice.test(org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(stack))) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
            if (stack.isEmpty()) {
                npc.getInventory().setItem(slot, ItemStack.EMPTY);
            }
        }
        return remaining == 0;
    }

    private String describeChoice(org.bukkit.inventory.RecipeChoice choice) {
        java.util.List<String> paths = candidatePaths(choice);
        return paths.isEmpty() ? "未知材料"
                : String.join("|", paths.subList(0, Math.min(4, paths.size())));
    }

    // ---------------------------------------------------------------- 挖矿

    /**
     * 挖矿目标：先找附近暴露的目标方块，找不到就阶梯下挖到资源层，再水平掘进找矿。
     *
     * <p>这是"从地表发展成玩家"的关键一步：木头→石镐→石头→煤→铁→钻石，
     * 全部靠玩家式的挖掘方式获取，不凭空生成物品。
     */
    private void tickDig() {
        if (oreTarget == null) {
            finish("挖矿失败：缺少资源定义");
            return;
        }
        int absolute = countItem(oreTarget.drop());
        int have = Math.max(0, absolute - baselineCount);
        status = "挖矿 " + targetId + " 新采 " + have + "/" + targetCount
                + "（背包共 " + absolute + "，目标Y≈" + oreTarget.y() + "）";
        // 没有可用的镐：继续下挖/掘进每个技能都会立刻失败，
        // 直接结束目标，让规划器先去补工具（它会先做木镐/石镐）。
        if (!hasUsablePickaxe()) {
            finish("挖矿放弃：没有可用的镐，先补工具");
            return;
        }
        // 上一条矿道如果挖空了，下一轮掘进前要先换位置（见下方步骤 3 前的处理）。
        noteStripOutcome();
        if (have >= targetCount) {
            finish("挖矿完成 " + targetId + " 新采 " + have + "/" + targetCount
                    + "，背包共 " + absolute);
            return;
        }
        // 技能正在跑的时候不计"没进展"：从地表阶梯下挖到 Y=48 要 20-30 秒，
        // 中途本来就捡不到煤。旧逻辑每 0.5 秒计一次，NPC 刚挖到一半就放弃了煤/铁目标。
        // 附近有敌对生物时同样不计：生存层会为自卫反复打断 descend/strip，
        // 目标因此不断重新下发技能，表象是原地不动，实际是在打怪。
        // 用 isEngaged() 而不是固定的 16 格扫描：打不到的怪会被生存层拉黑，
        // 否则这条捷径会让"没进展"永远不计数，目标卡死不放弃。
        SurvivalController survival = plugin.survival(npc.npcName());
        boolean defending = survival != null ? survival.isEngaged() : hostileNearby(5);
        if (defending) {
            staleChecks = 0;
        } else if ("idle".equals(skills.currentSkill())) {
            if (have == lastProgress) {
                // 阈值 12 次（约 6 秒）：descend 每一步都要重新下发技能，
                // 两段技能之间必然有几次 idle，5 次会在正常下矿途中误判成放弃。
                if (++staleChecks >= 12) {
                    finish("挖矿放弃：连续 12 次没有新进展 " + have + "/" + targetCount);
                    return;
                }
            } else {
                lastProgress = have;
                staleChecks = 0;
            }
        }

        // 1) 附近（±8 格）有暴露的目标方块就先直接挖：地表石头、洞穴煤矿都能这样拿到。
        BlockPos exposed = nearbyBlockPosAny(oreTarget.block(), 8);
        if (exposed != null) {
            JsonObject params = new JsonObject();
            params.addProperty("block", oreTarget.block());
            params.addProperty("radius", 8);
            if (skills.run(new Intent("", "mine", params), "目标")) {
                return;
            }
        }

        // 2a) 泡在水里（或头还埋在水面下）时先上岸：矿道一旦挖进水域就被灌满，
        // 水下水平掘进四面都是流体，strip 只能原地转圈。玩家的做法是先游出去再从干地挖。
        // 但 dryland 如果连续 3 次失败（目标不可达或水太深游不动），就跳过，
        // 让 descend/strip 继续跑——它们会绕过障碍挖新通道，比无限重试 dryland 强。
        // 关键：dryland 正在跑时别 stop+restart 它（skills.run() 会先 stop），
        // 否则每 0.5 秒掐掉正在走的 dryland、重新开始，永远到不了终点。
        BlockPos wetFeet = npc.blockPosition();
        if ((isFluidAt(wetFeet) || isFluidAt(wetFeet.above())) && drylandFailures < 3) {
            if ("dryland".equals(skills.currentSkill())) {
                // 已经在跑了，等它结束再决定下一步
                attempts = 0;
                return;
            }
            // 上一轮 dryland 结束（不管成功失败）才会走到这里，检查结果
            String last = skills.lastOutcome();
            if (last != null && last.startsWith("dryland ") && !last.startsWith("dryland 已到干燥地面")) {
                drylandFailures++;
                if (drylandFailures >= 3) {
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " dryland 连续失败 " + drylandFailures
                            + " 次，跳过上岸直接下矿 | 最后结果=" + last);
                }
            } else if (last != null && last.startsWith("dryland 已到干燥地面")) {
                drylandFailures = 0;
            }
            if (drylandFailures < 3) {
                JsonObject params = new JsonObject();
                params.addProperty("radius", 16);
                if (skills.run(new Intent("", "dryland", params), "目标")) {
                    attempts = 0;
                    return;
                }
                drylandFailures++;
            }
        } else if (!isFluidAt(wetFeet) && !isFluidAt(wetFeet.above())) {
            // 已经不在流体里了，重置计数
            drylandFailures = 0;
        }

        // 2) 深度不够：阶梯下挖到资源层。挖矿途中会顺手捡到路上暴露的煤/铁。
        if (npc.blockPosition().getY() > oreTarget.y() + 3) {
            JsonObject params = new JsonObject();
            params.addProperty("y", oreTarget.y());
            if (skills.run(new Intent("", "descend", params), "目标")) {
                attempts = 0;
                return;
            }
        }

        // 2b) 资源层在头顶（例如刚在 Y=16 挖完铁，现在要去 Y=48 挖煤）：
        // 旧逻辑只会往下挖，会一直在 Y=16 水平掘进找煤，一整个目标周期什么都挖不到。
        if (npc.blockPosition().getY() < oreTarget.y() - 3) {
            JsonObject params = new JsonObject();
            params.addProperty("y", oreTarget.y() + 1);
            if (skills.run(new Intent("", "ascend", params), "目标")) {
                attempts = 0;
                return;
            }
        }

        // 3) 已经在资源层：水平掘进，矿道壁上暴露的矿会顺手挖掉。
        //    上一轮挖空了就先挪 4-9 格再开新矿道：原地再下发 strip 只会把
        //    同一条空矿道再走一遍。goto 发不出去（封闭矿道很常见）就照常掘进。
        if (relocateBeforeStrip) {
            relocateBeforeStrip = false;
            wander();
            if (!"idle".equals(skills.currentSkill())) {
                return;
            }
        }
        JsonObject params = new JsonObject();
        params.addProperty("block", oreTarget.block());
        params.addProperty("length", 24);
        // 只挖还差的数量：石头工具耐久只有 131，多挖的每一块都在烧掉下一把镐子的寿命。
        params.addProperty("limit", Math.max(1, targetCount - have));
        if (skills.run(new Intent("", "strip", params), "目标")) {
            attempts = 0;
            return;
        }
        attempts++;
        if (attempts >= 4) {
            finish("挖矿放弃：无法开始掘进");
            return;
        }
        wander();
    }

    /**
     * 处理上一条矿道的结果：挖空了就标记"下一轮先换位置"。
     *
     * <p>只处理新出现的结果：目标层每 0.5 秒 tick 一次，
     * 同一个结果字符串如果每 tick 都判断，会反复触发 goto 而永远开不了新矿道。
     */
    private void noteStripOutcome() {
        String outcome = skills.lastOutcome();
        if (outcome.equals(handledStripOutcome)) {
            return;
        }
        handledStripOutcome = outcome;
        // "未发现 + 已挖=0"是正常挖到头没矿；"前方无路可掘"是撞上悬空/流体/基岩。
        // 两种都说明这片区域已经挖不出东西了。
        if (outcome.startsWith("strip ")
                && ((outcome.contains("未发现") && outcome.contains("已挖=0"))
                || outcome.contains("前方无路可掘"))) {
            relocateBeforeStrip = true;
        }
    }

    /** 扫描附近同名方块（矿石自动带深板岩变种），只返回至少一面暴露的。 */
    private BlockPos nearbyBlockPosAny(String expectedPath, int radius) {
        BlockPos center = npc.blockPosition();
        Set<String> variants = new java.util.HashSet<>();
        variants.add(expectedPath);
        if (expectedPath.endsWith("_ore") && !expectedPath.startsWith("deepslate_")) {
            variants.add("deepslate_" + expectedPath);
        }
        BlockPos best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -6, -radius),
                center.offset(radius, 6, radius))) {
            String path = BuiltInRegistries.BLOCK.getKey(npc.level().getBlockState(pos).getBlock()).getPath();
            if (!variants.contains(path)) {
                continue;
            }
            // 被完全包住的方块挖掉后掉落物卡在固体里，伪玩家够不到，先跳过。
            if (!isExposed(pos)) {
                continue;
            }
            double distanceSq = center.distSqr(pos);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** 该格是否含流体（水/岩浆）。挖矿前用它判断"是不是还在水里"。 */
    private boolean isFluidAt(BlockPos pos) {
        return !npc.level().getBlockState(pos).getFluidState().isEmpty();
    }

    /** 半径内有没有活着的敌对生物。用来区分"技能卡住"和"正在被怪打断自卫"。 */
    private boolean hostileNearby(int radius) {
        // Enemy 是接口、不继承 Entity，getEntitiesOfClass 的泛型上限要求 Entity，
        // 所以先按 Mob 取，再用 instanceof Enemy 过滤（和生存层 findThreat 的做法一致）。
        return !npc.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                npc.getBoundingBox().inflate(radius),
                mob -> mob instanceof net.minecraft.world.entity.monster.Enemy && mob.isAlive()).isEmpty();
    }

    private boolean isExposed(BlockPos pos) {
        return npc.level().getBlockState(pos.above()).isAir()
                || npc.level().getBlockState(pos.below()).isAir()
                || npc.level().getBlockState(pos.north()).isAir()
                || npc.level().getBlockState(pos.south()).isAir()
                || npc.level().getBlockState(pos.east()).isAir()
                || npc.level().getBlockState(pos.west()).isAir();
    }

    /** 找附近已经放下的熔炉（取最近的一台）；没有则返回 null。 */
    private BlockPos nearbyFurnaceBlock() {
        BlockPos center = npc.blockPosition();
        BlockPos best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-8, -4, -8), center.offset(8, 4, 8))) {
            if (!npc.level().getBlockState(pos).is(Blocks.FURNACE)) {
                continue;
            }
            double distanceSq = center.distSqr(pos);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = pos.immutable();
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- 熔炼

    /**
     * 熔炼目标：放下熔炉、塞入原料和燃料、等原版烧炼，再把产物取回背包。
     *
     * <p>为什么直接操作 BlockEntity：伪玩家没有客户端 GUI，但熔炉的烧炼逻辑全在服务端
     * BlockEntity 里，直接写输入/燃料槽等价于玩家打开 GUI 放东西，产物也按原版时间生成。
     */
    private void tickSmelt() {
        int done = Math.max(0, countItem(targetId) - smeltBaseline);
        status = "熔炼 " + targetId + " " + done + "/" + targetCount;
        if (done >= targetCount) {
            // 收炉必须在 finish 之前：finish 之后目标层清空 furnacePos，
            // 剩下没烧的原料和燃料就永远留在方块里了。
            drainFurnace();
            finish("熔炼完成 " + targetId + " " + done + "/" + targetCount);
            return;
        }
        smeltTicks++;
        if (smeltTicks > 2400) {
            drainFurnace();
            finish("熔炼超时：120 秒内只完成 " + done + "/" + targetCount);
            return;
        }
        if (furnacePos == null) {
            if (!placeFurnace()) {
                return;
            }
        }
        // 每个 tick 都把产物取回背包：伪玩家不会打开 GUI，只能主动搬。
        takeFurnaceOutput();
        int now = Math.max(0, countItem(targetId) - smeltBaseline);
        if (now >= targetCount) {
            // 这是最常见的完成路径（刚取完最后一个产物），收炉同样必须在 finish 之前。
            drainFurnace();
            finish("熔炼完成 " + targetId + " " + now + "/" + targetCount);
            return;
        }
        fillFurnace();
    }

    /** 在脚边找一个空位放下熔炉；失败会直接结束目标并写清原因。 */
    private boolean placeFurnace() {
        String input = smeltInputFor(targetId);
        if (input == null) {
            finish("熔炼失败：没有 " + targetId + " 的熔炼配方");
            return false;
        }
        if (countItem(input) <= 0) {
            finish("熔炼失败：缺少原料 " + input);
            return false;
        }
        // 优先复用自己之前放下的熔炉：熔炉放下后物品栏就空了，
        // 如果只认"背包里有熔炉"，第二次冶炼会永远失败，规划器又会一直重下冶炼目标。
        BlockPos existing = nearbyFurnaceBlock();
        if (existing != null) {
            furnacePos = existing.immutable();
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 复用附近熔炉 | pos=" + furnacePos.toShortString());
            fillFurnace();
            return true;
        }
        if (countItem("furnace") <= 0) {
            finish("熔炼失败：没有熔炉（8 个圆石合成）");
            return false;
        }
        BlockPos base = npc.blockPosition();
        // 由近到远扫三层壳：只认四邻格时，1 格宽的矿道四面全是墙，
        // 实测连续三轮冶炼都直接失败在"脚边没有空位放熔炉"。
        // 玩家在矿道里会把熔炉放在身后两三格的空处，这里同样放宽搜索范围。
        BlockPos spot = null;
        for (int radius = 1; radius <= 3 && spot == null; radius++) {
            for (int dx = -radius; dx <= radius && spot == null; dx++) {
                for (int dz = -radius; dz <= radius && spot == null; dz++) {
                    for (int dy = -1; dy <= 1 && spot == null; dy++) {
                        // 只扫当前半径这一层壳，保证由近到远；重复扫内层没有意义。
                        if (Math.abs(dx) != radius && Math.abs(dz) != radius && Math.abs(dy) != radius) {
                            continue;
                        }
                        BlockPos candidate = base.offset(dx, dy, dz);
                        if (!npc.level().getBlockState(candidate).canBeReplaced()) {
                            continue;
                        }
                        // 熔炉下面必须有支撑，否则放下就掉成掉落物。
                        if (npc.level().getBlockState(candidate.below()).getCollisionShape(
                                npc.level(), candidate.below()).isEmpty()) {
                            continue;
                        }
                        spot = candidate.immutable();
                    }
                }
            }
        }
        if (spot == null) {
            // 四面都是墙（竖井/窄矿道）：像玩家一样凿壁一格，再在那个洞里放熔炉。
            for (BlockPos direction : List.of(base.east(), base.west(), base.north(), base.south())) {
                if (npc.mineWithBestTool(direction)) {
                    spot = direction.immutable();
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " 凿壁放熔炉 | pos=" + spot.toShortString());
                    break;
                }
            }
        }
        if (spot == null) {
            finish("熔炼失败：脚边没有空位放熔炉");
            return false;
        }
        if (!consume("furnace", 1)) {
            finish("熔炼失败：熔炉物品扣除失败");
            return false;
        }
        npc.level().setBlockAndUpdate(spot, Blocks.FURNACE.defaultBlockState());
        furnacePos = spot;
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 放置熔炉 | pos=" + spot.toShortString());
        fillFurnace();
        return true;
    }

    /** 原料槽空了就补一批，燃料槽空了就补燃料。 */
    private void fillFurnace() {
        if (furnacePos == null) {
            return;
        }
        if (!(npc.level().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity furnace)) {
            finish("熔炼失败：熔炉方块实体丢失 pos=" + furnacePos.toShortString());
            return;
        }
        String input = smeltInputFor(targetId);
        if (input == null) {
            return;
        }
        int need = targetCount - Math.max(0, countItem(targetId) - smeltBaseline);
        // 输入槽一次最多放 16 个：放太多的话目标提前完成后拿不回来，会浪费材料。
        if (furnace.getItem(0).isEmpty() && need > 0) {
            int available = countItem(input);
            if (available > 0) {
                int batch = Math.min(need, Math.min(16, available));
                if (consume(input, batch)) {
                    furnace.setItem(0, new ItemStack(item(input), batch));
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " 熔炉放入原料 | " + input + " x" + batch);
                }
            }
        }
        if (furnace.getItem(1).isEmpty()) {
            String fuel = pickFuel();
            if (fuel != null) {
                int put = Math.min(8, countItem(fuel));
                if (put > 0 && consume(fuel, put)) {
                    furnace.setItem(1, new ItemStack(item(fuel), put));
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " 熔炉放入燃料 | " + fuel + " x" + put);
                }
            }
        }
    }

    /** 取走熔炉产出槽；背包满时留在熔炉里等下一次 tick。 */
    private void takeFurnaceOutput() {
        if (furnacePos == null || !(npc.level().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity furnace)) {
            return;
        }
        ItemStack output = furnace.getItem(2);
        if (output.isEmpty()) {
            return;
        }
        String path = pathOf(output);
        int count = output.getCount();
        String given = give(path, count);
        if (OK.equals(given)) {
            furnace.setItem(2, ItemStack.EMPTY);
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 取出熔炼产物 | " + path + " x" + count
                    + " 背包共=" + countItem(path));
        } else {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 熔炼产物暂存 | " + given);
        }
    }

    /**
     * 目标结束时把熔炉里所有槽位（原料/燃料/产物）收回背包。
     *
     * <p>为什么必须收：fillFurnace 一次塞 8 个燃料，但目标可能只烧 1~2 个就完成了，
     * 剩下的煤还在燃料槽里；NPC 一转身走开，这些煤就等于丢进了虚空，
     * 规划器下一秒又数到"煤不足"重新下矿——实测的「挖煤→熔炼→再挖煤」死循环就是这么来的。
     * 玩家关炉子前一定会把东西掏干净，这里做同样的事。
     */
    private void drainFurnace() {
        if (furnacePos == null) {
            return;
        }
        if (!(npc.level().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity furnace)) {
            return;
        }
        int moved = 0;
        for (int slot = 0; slot < 3; slot++) {
            ItemStack stack = furnace.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String path = pathOf(stack);
            int count = stack.getCount();
            // give 内部已处理"背包满→丢杂物→重试"；仍放不下就留在炉子里，
            // 下一次冶炼复用这个熔炉时还能接着烧，不会凭空消失。
            if (OK.equals(give(path, count))) {
                furnace.setItem(slot, ItemStack.EMPTY);
                moved += count;
                LOG.info("[AIV][GOAL] " + npc.npcName() + " 熔炉收炉 | " + path + " x" + count
                        + " 槽=" + slot);
            } else {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " 熔炉收炉失败（留在炉内）| " + path + " x" + count);
            }
        }
        if (moved > 0) {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 熔炉收炉完成 | 共收回=" + moved
                    + " pos=" + furnacePos.toShortString());
        }
    }

    /**
     * 回收附近熔炉（含高炉/烟熏炉）里遗留的物品，返回回收的槽数。
     *
     * <p>为什么需要：冶炼中途停服/切目标时，原料和燃料已经进了熔炉方块，
     * 重启后新目标只会去背包里找生铁，看不到熔炉里那 5 个，于是重新下矿。
     * 玩家会先回去把炉子里的东西掏出来，这里做同样的事。
     */
    public int recoverNearbyFurnaces(int radius) {
        int recovered = 0;
        BlockPos center = npc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -6, -radius),
                center.offset(radius, 6, radius))) {
            if (!(npc.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
                continue;
            }
            boolean any = false;
            for (int slot = 0; slot < 3; slot++) {
                ItemStack stack = furnace.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                String given = give(pathOf(stack), stack.getCount());
                if (OK.equals(given)) {
                    furnace.setItem(slot, ItemStack.EMPTY);
                    recovered++;
                    any = true;
                }
            }
            if (any) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " 回收遗留熔炉 | pos=" + pos.toShortString());
            }
        }
        return recovered;
    }

    /** 燃料优先级：煤/木炭最省空间，其次木板、原木，最后木棍。 */
    private String pickFuel() {
        for (String candidate : List.of("coal", "charcoal")) {
            if (countItem(candidate) > 0) {
                return candidate;
            }
        }
        if (countPlanks() > 0) {
            for (int slot = 0; slot < MAIN_SLOTS; slot++) {
                ItemStack stack = npc.getInventory().getItem(slot);
                if (!stack.isEmpty() && pathOf(stack).endsWith("_planks")) {
                    return pathOf(stack);
                }
            }
        }
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (!stack.isEmpty() && pathOf(stack).endsWith("_log")) {
                return pathOf(stack);
            }
        }
        if (countItem("stick") > 0) {
            return "stick";
        }
        return null;
    }

    /** 熔炼产物 → 原料。只列当前科技树会用到的配方，其余交给完整配方表处理。 */
    private static String smeltInputFor(String output) {
        return switch (output) {
            case "iron_ingot" -> "raw_iron";
            case "gold_ingot" -> "raw_gold";
            case "copper_ingot" -> "raw_copper";
            case "cooked_beef" -> "raw_beef";
            case "cooked_porkchop" -> "raw_porkchop";
            case "cooked_chicken" -> "raw_chicken";
            case "cooked_mutton" -> "raw_mutton";
            case "cooked_rabbit" -> "raw_rabbit";
            case "glass" -> "sand";
            case "stone" -> "cobblestone";
            default -> null;
        };
    }

    // ---------------------------------------------------------------- 狩猎

    private void tickHunt() {
        int absolute = countHuntLoot();
        int have = Math.max(0, absolute - huntBaseline);
        status = "狩猎 " + have + "/" + targetCount + "（肉类共 " + absolute + "）";
        if (have >= targetCount) {
            finish("狩猎完成 新得 " + have + "/" + targetCount + "，肉类共 " + absolute);
            return;
        }
        if (huntAttempts >= 8) {
            finish("狩猎放弃：连续 8 次附近没有可猎动物");
            return;
        }
        JsonObject params = new JsonObject();
        params.addProperty("radius", 12);
        params.addProperty("hits", 10);
        huntAttempts++;
        if (skills.run(new Intent("", "hunt", params), "目标")) {
            return;
        }
        wander();
    }

    /** 背包里的肉类总数（生熟都算）：狩猎进度按"本轮新打到多少"统计。 */
    private int countHuntLoot() {
        int total = 0;
        for (String meat : List.of("raw_beef", "raw_porkchop", "raw_chicken", "raw_mutton",
                "raw_rabbit", "cooked_beef", "cooked_porkchop", "cooked_chicken",
                "cooked_mutton", "cooked_rabbit")) {
            total += countItem(meat);
        }
        return total;
    }

    // ---------------------------------------------------------------- 下界传送门（通关路线）

    /** 10 格传送门框架局部坐标（四角省略，同 SkillRunner）。 */
    private static final int[][] PORTAL_FRAME = {
            {0, 1}, {0, 2}, {0, 3},
            {3, 1}, {3, 2}, {3, 3},
            {1, 0}, {2, 0},
            {1, 4}, {2, 4}
    };

    /** 传送门选址：origin 是框架左下角，alongX 决定门面横向沿 X 还是 Z。 */
    private record PortalSite(BlockPos origin, boolean alongX) {
    }

    /**
     * 找燧石。
     *
     * <p>原版沙砾只有 10% 掉燧石，满世界找沙砾滩很慢；玩家最常用的办法是
     * "把已有的沙砾放下再挖掉"，反复刷到燧石为止。这里优先用这个办法，
     * 背包里没有沙砾时才去附近找天然沙砾。
     */
    private void tickFlint() {
        int have = countItem("flint");
        status = "找燧石 " + have + "/" + targetCount;
        if (have >= targetCount) {
            finish("燧石够了 " + have + "/" + targetCount);
            return;
        }
        if (countItem("gravel") > 0 && placeGravelForFlint()) {
            attempts = 0;
            return;
        }
        BlockPos gravel = nearbyBlockPos("gravel");
        if (gravel != null) {
            JsonObject params = params("block", "gravel", "radius", GATHER_RADIUS);
            if (skills.run(new Intent("", "mine", params), "目标")) {
                attempts = 0;
                return;
            }
        }
        attempts++;
        if (attempts >= 12) {
            finish("找燧石放弃：附近没有沙砾");
            return;
        }
        wander();
    }

    /** 把背包里的 1 个沙砾放在脚边再挖掉，反复触发 10% 的燧石掉落。 */
    private boolean placeGravelForFlint() {
        BlockPos base = npc.blockPosition();
        for (BlockPos candidate : List.of(base.east(), base.west(), base.north(), base.south(),
                base.east().above(), base.west().above())) {
            BlockState state = npc.level().getBlockState(candidate);
            if (!state.isAir() && !state.canBeReplaced()) {
                continue;
            }
            BlockPos below = candidate.below();
            if (npc.level().getBlockState(below).getCollisionShape(npc.level(), below).isEmpty()) {
                continue;
            }
            if (!consume("gravel", 1)) {
                return false;
            }
            npc.level().setBlockAndUpdate(candidate, Blocks.GRAVEL.defaultBlockState());
            boolean mined = npc.mine(candidate);
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 刷燧石 | 放挖沙砾 success=" + mined
                    + " 燧石=" + countItem("flint"));
            return true;
        }
        return false;
    }

    /**
     * 浇铸黑曜石：水桶浇岩浆源 → 钻石镐采集 → 收水回桶。
     * 附近没有岩浆时先阶梯下挖到 Y≈-52 的岩浆层。
     */
    private void tickObsidian() {
        int have = countItem("obsidian");
        status = "浇铸黑曜石 " + have + "/" + targetCount
                + (countItem("water_bucket") > 0 ? "（水桶就绪）" : "（需要装水）");
        if (have >= targetCount) {
            finish("黑曜石完成 " + have + "/" + targetCount);
            return;
        }
        if (!hasUsableDiamondPickaxe()) {
            finish("采集黑曜石需要钻石镐");
            return;
        }
        if (countItem("bucket") <= 0 && countItem("water_bucket") <= 0) {
            finish("缺少铁桶（3 个铁锭合成）");
            return;
        }
        // 先到地下层：地表附近常常找不到水（草原/沙漠/平原），
        // 但 Y=-50 附近既有岩浆湖（浇铸材料）也有地下水（装水），
        // 一次 descend 解决两个前置条件。
        if (npc.blockPosition().getY() > -45) {
            JsonObject params = params("y", -52);
            if (skills.run(new Intent("", "descend", params), "目标")) {
                return;
            }
        }
        if (countItem("water_bucket") <= 0) {
            skills.run(new Intent("", "fillbucket", new JsonObject()), "目标");
            return;
        }
        // 技能空闲时的无进展判定：浇铸可能因为岩浆被方块挡住而反复失败。
        if (have == lastProgress) {
            if (++staleChecks >= 12) {
                finish("浇铸黑曜石连续没有进展 " + have + "/" + targetCount);
                return;
            }
        } else {
            lastProgress = have;
            staleChecks = 0;
        }

        BlockPos lava = nearbyLavaSource(24, 8);
        if (lava != null) {
            JsonObject params = params("x", lava.getX(), "y", lava.getY(), "z", lava.getZ());
            if (skills.run(new Intent("", "castobsidian", params), "目标")) {
                attempts = 0;
                return;
            }
        }
        attempts++;
        if (attempts >= 10) {
            finish("找不到岩浆源，黑曜石失败 " + have + "/" + targetCount);
            return;
        }
        wander();
    }

    /**
     * 建造并点燃下界传送门，然后走进去。
     *
     * <p>分阶段而不是一口气做完：建门是 10 格方块 + 点火的动画过程，
     * 中间要重新读世界确认门真的亮了；进门后由基类的维度切换检查结束目标。
     */
    private void tickPortal() {
        switch (portalPhase) {
            case 0 -> {
                if (countItem("obsidian") < 10) {
                    finish("黑曜石不足 10，无法建门");
                    return;
                }
                if (countItem("flint_and_steel") <= 0) {
                    finish("缺少打火石，无法点燃传送门");
                    return;
                }
                PortalSite site = findPortalSite();
                if (site == null) {
                    attempts++;
                    if (attempts >= 8) {
                        finish("附近找不到 4x5 的空地建门");
                        return;
                    }
                    wander();
                    return;
                }
                portalOrigin = site.origin();
                portalAlongX = site.alongX();
                JsonObject params = params("x", portalOrigin.getX(), "y", portalOrigin.getY(),
                        "z", portalOrigin.getZ(), "axis", portalAlongX ? "x" : "z");
                if (skills.run(new Intent("", "buildportal", params), "目标")) {
                    portalPhase = 1;
                    status = "建造传送门 origin=" + portalOrigin.toShortString()
                            + " axis=" + (portalAlongX ? "x" : "z");
                }
            }
            case 1 -> {
                // 能进到这里说明建造技能已经结束；用世界状态确认门真的点亮了。
                if (portalOrigin == null) {
                    finish("传送门选址丢失");
                    return;
                }
                BlockPos inside = interiorPosition(portalOrigin, portalAlongX, 1, 1);
                if (npc.level().getBlockState(inside).is(Blocks.NETHER_PORTAL)) {
                    portalPhase = 2;
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " 传送门已点亮，准备进入");
                } else {
                    finish("建门失败：" + skills.lastOutcome());
                }
            }
            case 2 -> {
                if (portalOrigin == null) {
                    finish("传送门选址丢失");
                    return;
                }
                double x = portalAlongX ? portalOrigin.getX() + 2.0D : portalOrigin.getX() + 0.5D;
                double z = portalAlongX ? portalOrigin.getZ() + 0.5D : portalOrigin.getZ() + 2.0D;
                double y = portalOrigin.getY() + 1.0D;
                JsonObject params = params("x", x, "y", y, "z", z);
                if (skills.run(new Intent("", "enterportal", params), "目标")) {
                    portalPhase = 3;
                    status = "走进传送门";
                }
            }
            case 3 -> {
                // 穿过门后基类会先因维度变化结束目标；能走到这里说明这次没传送成功。
                enterAttempts++;
                if (enterAttempts >= 4) {
                    finish("多次走进传送门仍未传送");
                    return;
                }
                portalPhase = 2;
            }
            default -> finish("portal 阶段异常 " + portalPhase);
        }
    }

    /**
     * 走进附近已经点亮的传送门。
     *
     * <p>为什么单独做一个目标：门可能是上一轮建好的、也可能是走路时偶然发现的；
     * 玩家看到门的第一反应就是走进去，而不是再造一座。真正搬运身体的工作交给
     * {@code enterportal} 技能（持续贴门 + 维度变化检测），这里只负责重试和判定。
     */
    private void tickEnterPortal() {
        BlockPos portal = nearbyPortal();
        if (portal == null) {
            finish("附近找不到已点亮的传送门");
            return;
        }
        attempts++;
        if (attempts > 3) {
            finish("多次走进传送门仍未传送");
            return;
        }
        JsonObject params = params("x", portal.getX() + 0.5D, "y", portal.getY(),
                "z", portal.getZ() + 0.5D);
        if (!skills.run(new Intent("", "enterportal", params), "目标")) {
            finish("传送技能启动失败");
            return;
        }
        status = "走进传送门 " + portal.toShortString();
    }

    // ---------------------------------------------------------------- 通关路线

    /**
     * 定点猎杀：按"对应掉落物"统计进度。
     *
     * <p>为什么不按击杀数：烈焰人/末影人掉落 0~1 个，击杀数达标但掉落不足时
     * 会误判完成，规划器随后合成眼睛就会因为缺材料反复失败。
     */
    private void tickSlay() {
        int gained = countItem(slayDrop) - slayBaseline;
        status = "猎杀 " + slayEntity + " | " + slayDrop + " " + gained + "/" + targetCount;
        if (gained >= targetCount) {
            finish("已取得 " + slayDrop + " x" + gained);
            return;
        }
        slayAttempts++;
        if (slayAttempts > 30) {
            finish("猎杀放弃：附近始终找不到 " + slayEntity);
            return;
        }
        JsonObject params = params("entity", slayEntity, "radius", 16.0D, "attempts", 240);
        if (!skills.run(new Intent("", "slay", params), "目标")) {
            // 附近没有目标：先四处走走扩大搜索范围。玩家找怪也是这么做的。
            wander();
        }
    }

    /** 长途行进：水平先靠近，再竖向对齐（要塞/传送门房间都在地下或高台）。 */
    private void tickTravel() {
        double dx = travelX - npc.getX();
        double dz = travelZ - npc.getZ();
        double horizontalSq = dx * dx + dz * dz;
        status = "长途行进 剩余 " + Math.round(Math.sqrt(horizontalSq))
                + " 格 高度差 " + Math.round(travelY - npc.getY());
        if (horizontalSq <= 16.0D && Math.abs(travelY - npc.getY()) <= 3.0D) {
            finish("到达目的地");
            return;
        }
        attempts++;
        // 要塞/下界要塞可能在一两千格之外：每次技能只推进几十格，
        // 上限太小会在半路放弃，然后在"到达目的地"和重新下单之间来回刷。
        if (attempts > 240) {
            finish("长途行进放弃：多次推进仍未到达");
            return;
        }
        if (horizontalSq <= 16.0D) {
            // 水平到位但高度不对：先竖直挖楼梯下去/爬上来，再由下一轮继续水平推进。
            String action = npc.getY() > travelY ? "descend" : "ascend";
            JsonObject params = params("y", travelY, "max", 160);
            if (!skills.run(new Intent("", action, params), "目标")) {
                finish("竖直对齐失败 action=" + action);
            }
            return;
        }
        // 水平推进时 Y 用当前高度：A* 只能在地形里找路，追一个"空中的目标 Y"必然失败。
        JsonObject params = params("x", travelX, "y", npc.getY(), "z", travelZ,
                "tolerance", 4.0D);
        if (!skills.run(new Intent("", "travel", params), "目标")) {
            finish("行进技能启动失败");
        }
    }

    /** 末地传送门：到达房间 -> 镶满 12 只眼睛 -> 走进传送门。 */
    private void tickEndPortal() {
        if (!inEndDimension()) {
            if (countItem("ender_eye") < 12) {
                finish("末影之眼不足 12 个（现有 " + countItem("ender_eye") + "）");
                return;
            }
        }
        switch (endPhase) {
            case 0 -> {
                double dx = travelX - npc.getX();
                double dz = travelZ - npc.getZ();
                double horizontalSq = dx * dx + dz * dz;
                if (horizontalSq > 16.0D || Math.abs(travelY - npc.getY()) > 3.0D) {
                    attempts++;
                    if (attempts > 100) {
                        finish("无法抵达末地传送门房间");
                        return;
                    }
                    // 复用长途行进的推进逻辑：水平到位后它会自动竖直对齐。
                    JsonObject params = params("x", travelX, "y", travelY, "z", travelZ,
                            "tolerance", 4.0D);
                    if (!skills.run(new Intent("", "travel", params), "目标")) {
                        finish("前往传送门房间失败");
                    }
                    return;
                }
                endPhase = 1;
            }
            case 1 -> {
                status = "寻找末地传送门框架";
                List<BlockPos> frames = findEndPortalFrames(16, 10);
                if (frames.isEmpty()) {
                    frames = findEndPortalFrames(32, 16);
                }
                if (frames.isEmpty()) {
                    attempts++;
                    if (attempts > 120) {
                        finish("附近找不到末地传送门框架");
                        return;
                    }
                    wander();
                    return;
                }
                fillEndPortal(frames);
            }
            case 2 -> {
                status = "走进末地传送门";
                attempts++;
                if (attempts > 40) {
                    finish("多次尝试仍未进入末地");
                    return;
                }
                JsonObject params = params("x", travelX, "y", travelY, "z", travelZ,
                        "tolerance", 1.5D);
                if (!skills.run(new Intent("", "travel", params), "目标")) {
                    finish("走入传送门失败");
                }
            }
            default -> finish("末地传送门阶段异常 " + endPhase);
        }
    }

    /**
     * 把找齐的末地传送门框架全部镶上眼睛，并直接写 3x3 的 END_PORTAL。
     *
     * <p>为什么手动写传送门方块：原版在放入最后一只眼睛时才会触发激活，
     * 而伪玩家是通过 setBlockAndUpdate 改状态，不一定经过那套激活判定。
     * 直接把 3x3 内部填成 END_PORTAL，效果与激活完全一致，还能避免"眼睛放完但门没开"。
     */
    private void fillEndPortal(List<BlockPos> frames) {
        int eyeCount = countItem("ender_eye");
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos frame : frames) {
            minX = Math.min(minX, frame.getX());
            maxX = Math.max(maxX, frame.getX());
            minY = Math.min(minY, frame.getY());
            minZ = Math.min(minZ, frame.getZ());
            maxZ = Math.max(maxZ, frame.getZ());
        }
        int filled = 0;
        for (BlockPos frame : frames) {
            BlockState state = npc.level().getBlockState(frame);
            if (!state.is(Blocks.END_PORTAL_FRAME)) {
                continue;
            }
            if (state.getValue(EndPortalFrameBlock.HAS_EYE)) {
                filled++;
                continue;
            }
            if (eyeCount <= 0) {
                finish("末影之眼不足，无法镶满 12 个（已镶 " + filled + "）");
                return;
            }
            consume("ender_eye", 1);
            eyeCount--;
            npc.level().setBlockAndUpdate(frame, state.setValue(EndPortalFrameBlock.HAS_EYE, true));
            filled++;
        }
        int centerX = (minX + maxX) / 2;
        int centerZ = (minZ + maxZ) / 2;
        // 框架是 5x5 的环，内部一定是 3x3。
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                npc.level().setBlockAndUpdate(new BlockPos(centerX + dx, minY, centerZ + dz),
                        Blocks.END_PORTAL.defaultBlockState());
            }
        }
        travelX = centerX + 0.5D;
        travelY = minY;
        travelZ = centerZ + 0.5D;
        endPhase = 2;
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 末地传送门已激活 | 框架=" + frames.size()
                + " 带眼=" + filled + " 内部中心=" + centerX + "," + minY + "," + centerZ);
        status = "末地传送门已激活";
    }

    /**
     * 末地屠龙：先拆水晶，再打龙。
     *
     * <p>和玩家一致的打法：末影水晶会持续治疗龙，必须先清掉；
     * 龙大部分时间在空中，用弓箭消耗；停靠时贴上去用剑。
     */
    private void tickDragon() {
        if (!inEndDimension()) {
            finish("不在末地，屠龙目标中止");
            return;
        }
        List<Entity> dragons = npc.level().getEntities(npc, npc.getBoundingBox().inflate(256.0D),
                entity -> entity instanceof EnderDragon && entity.isAlive());
        if (dragons.isEmpty()) {
            finish("末影龙已被击败，通关");
            return;
        }
        LivingEntity dragon = (LivingEntity) dragons.get(0);
        List<Entity> crystals = npc.level().getEntities(npc, npc.getBoundingBox().inflate(160.0D),
                entity -> entity instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal
                        && entity.isAlive());
        if (!crystals.isEmpty()) {
            dragonPhase = 0;
            status = "拆除末影水晶 剩余 " + crystals.size() + " | 龙 HP="
                    + String.format(Locale.ROOT, "%.0f", dragon.getHealth());
            dragonAttempts++;
            if (dragonAttempts > 120) {
                finish("拆水晶失败或超时");
                return;
            }
            if (countItem("arrow") > 0) {
                // 一支箭就能打碎水晶；每次只射最近的水晶，打完再找下一颗。
                if (skills.run(new Intent("", "shoot",
                        params("entity", "end_crystal", "shots", 4)), "目标")) {
                    return;
                }
            }
            // 没有箭：靠近最近的水晶近战（会被爆炸波及，但总比站桩好）。
            Entity nearest = crystals.stream()
                    .min((left, right) -> Double.compare(npc.distanceToSqr(left),
                            npc.distanceToSqr(right))).orElseThrow();
            if (skills.run(new Intent("", "travel", params("x", nearest.getX(),
                    "y", nearest.getY(), "z", nearest.getZ(), "tolerance", 3.0D)), "目标")) {
                return;
            }
            wander();
            return;
        }
        if (dragonPhase == 0) {
            dragonPhase = 1;
            dragonAttempts = 0;
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 末影水晶已清空，开始攻击末影龙");
        }
        status = "攻击末影龙 HP=" + String.format(Locale.ROOT, "%.0f", dragon.getHealth());
        dragonAttempts++;
        if (dragonAttempts > 900) {
            finish("攻击末影龙超时");
            return;
        }
        double distanceSq = npc.distanceToSqr(dragon);
        // 龙在高空时先射箭；降到停靠高度后交给近战追击。
        if (distanceSq > 36.0D && countItem("arrow") > 0) {
            if (skills.run(new Intent("", "shoot",
                    params("entity", "ender_dragon", "shots", 6)), "目标")) {
                return;
            }
        }
        if (skills.run(new Intent("", "slay",
                params("entity", "ender_dragon", "radius", 64.0D, "attempts", 300)), "目标")) {
            return;
        }
        wander();
    }

    /** 是否在末地维度。 */
    private boolean inEndDimension() {
        return npc.level().dimension().identifier().toString().contains("the_end");
    }

    /** 扫描附近的末地传送门框架。 */
    private List<BlockPos> findEndPortalFrames(int horizontal, int vertical) {
        List<BlockPos> frames = new java.util.ArrayList<>();
        BlockPos center = npc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-horizontal, -vertical, -horizontal),
                center.offset(horizontal, vertical, horizontal))) {
            if (npc.level().getBlockState(pos).is(Blocks.END_PORTAL_FRAME)) {
                frames.add(pos.immutable());
            }
        }
        return frames;
    }

    /**
     * 通过结构数据找到要塞的末地传送门房间中心。
     *
     * <p>为什么不用大范围方块扫描：要塞有上百格长，逐格扫描会强制加载几十个区块，
     * 在主线程上能卡住服务器好几秒。结构 piece 自带包围盒，直接给出房间中心，
     * 代价是一次区块加载。
     */
    public BlockPos strongholdPortalRoom(BlockPos anchor) {
        try {
            // 要塞只存在于主世界：在下界调用这个方法时必须切回主世界读结构数据，
            // 否则拿下界的 Level 去查 stronghold 永远是 null。
            ServerLevel level = npc.level().getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
            if (level == null) {
                return null;
            }
            Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            Structure stronghold = registry.getValue(ResourceKey.create(Registries.STRUCTURE,
                    Identifier.withDefaultNamespace("stronghold")));
            if (stronghold == null) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " 找不到 stronghold 结构注册项");
                return null;
            }
            // 结构 start 只存在于已生成的区块里；锚点区块通常已由 /locate 加载，
            // 这里再显式取一次，避免偶发的 null。
            level.getChunk(anchor.getX() >> 4, anchor.getZ() >> 4);
            StructureStart start = level.structureManager().getStructureAt(anchor, stronghold);
            if (start == null) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " 要塞结构未生成于锚点 "
                        + anchor.toShortString());
                return null;
            }
            for (StructurePiece piece : start.getPieces()) {
                if (piece.getClass().getSimpleName().contains("PortalRoom")) {
                    BoundingBox box = piece.getBoundingBox();
                    BlockPos center = new BlockPos((box.minX() + box.maxX()) / 2,
                            box.minY() + 1, (box.minZ() + box.maxZ()) / 2);
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " 找到末地传送门房间 "
                            + center.toShortString());
                    return center;
                }
            }
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 要塞里没有 PortalRoom piece");
        } catch (Throwable throwable) {
            LOG.log(java.util.logging.Level.WARNING,
                    "[AIV][GOAL] " + npc.npcName() + " 解析要塞结构失败", throwable);
        }
        return null;
    }

    /** 生物 ID → 对应掉落物（slay 的进度统计用）。 */
    private static String dropOfEntity(String entity) {
        return switch (normalizeEntity(entity)) {
            case "spider", "cave_spider" -> "string";
            case "chicken" -> "feather";
            case "blaze" -> "blaze_rod";
            case "enderman" -> "ender_pearl";
            default -> "unknown_drop_" + normalizeEntity(entity);
        };
    }

    private static String normalizeEntity(String raw) {
        String path = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (path.startsWith("minecraft:")) {
            path = path.substring("minecraft:".length());
        }
        return path;
    }

    /** 找附近已经点亮的传送门方块（取最低、最近的一格作为走入目标）。 */
    public BlockPos nearbyPortal() {
        BlockPos center = npc.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-24, -8, -24), center.offset(24, 8, 24))) {
            if (!npc.level().getBlockState(pos).is(Blocks.NETHER_PORTAL)) {
                continue;
            }
            // 越低越接近玩家站的那一层；同层用距离决胜。
            double score = center.distSqr(pos) + (pos.getY() - center.getY()) * 4.0D;
            if (score < bestScore) {
                bestScore = score;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** 扫描半径内最近的岩浆源方块。 */
    private BlockPos nearbyLavaSource(int radius, int vertical) {
        BlockPos center = npc.blockPosition();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -vertical, -radius),
                center.offset(radius, vertical, radius))) {
            BlockState state = npc.level().getBlockState(pos);
            if (!state.is(Blocks.LAVA) || !state.getFluidState().isSource()) {
                continue;
            }
            double distanceSq = center.distSqr(pos);
            if (distanceSq < bestSq) {
                bestSq = distanceSq;
                best = pos.immutable();
            }
        }
        return best;
    }

    private boolean hasUsableDiamondPickaxe() {
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String path = pathOf(stack);
            if (!"diamond_pickaxe".equals(path) && !"netherite_pickaxe".equals(path)) {
                continue;
            }
            if (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** 在脚边由近到远找一个能放 4x5 传送门框架的空地。 */
    private PortalSite findPortalSite() {
        BlockPos base = npc.blockPosition();
        for (int radius = 2; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos origin = base.offset(dx, dy, dz);
                        if (validPortalSite(origin, true)) {
                            return new PortalSite(origin, true);
                        }
                        if (validPortalSite(origin, false)) {
                            return new PortalSite(origin, false);
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 框架 10 格 + 门洞 6 格全空、底部有支撑、门外能站人。 */
    private boolean validPortalSite(BlockPos origin, boolean alongX) {
        for (int[] local : PORTAL_FRAME) {
            BlockPos pos = alongX ? origin.offset(local[0], local[1], 0)
                    : origin.offset(0, local[1], local[0]);
            BlockState state = npc.level().getBlockState(pos);
            if (!state.isAir() && !state.canBeReplaced()) {
                return false;
            }
        }
        for (int dx = 1; dx <= 2; dx++) {
            for (int dy = 1; dy <= 3; dy++) {
                BlockPos pos = interiorPosition(origin, alongX, dx, dy);
                BlockState state = npc.level().getBlockState(pos);
                if (!state.isAir() && !state.canBeReplaced()) {
                    return false;
                }
            }
        }
        BlockPos bottomA = alongX ? origin.offset(1, 0, 0) : origin.offset(0, 0, 1);
        BlockPos bottomB = alongX ? origin.offset(2, 0, 0) : origin.offset(0, 0, 2);
        if (!hasSupport(bottomA) || !hasSupport(bottomB)) {
            return false;
        }
        BlockPos outside = origin.offset(1, 1, 1);
        return PathFinder.isStandable(npc.level(), outside);
    }

    private boolean hasSupport(BlockPos pos) {
        BlockPos below = pos.below();
        return !npc.level().getBlockState(below).getCollisionShape(npc.level(), below).isEmpty();
    }

    private static BlockPos interiorPosition(BlockPos origin, boolean alongX, int dx, int dy) {
        return alongX ? origin.offset(dx, dy, 0) : origin.offset(0, dy, dx);
    }

    /** JsonObject 构造助手（目标层自己用，避免每处都手写 addProperty）。 */
    private static JsonObject params(Object... pairs) {
        JsonObject json = new JsonObject();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            Object value = pairs[index + 1];
            if (value instanceof Number number) {
                json.addProperty(String.valueOf(pairs[index]), number);
            } else {
                json.addProperty(String.valueOf(pairs[index]), String.valueOf(value));
            }
        }
        return json;
    }

    // ---------------------------------------------------------------- 建造

    private void tickBuild() {
        status = "建造 " + targetId;
        // 选一种背包里够 25 个的方块；优先圆石（抗爆），其次泥土，再木板/原木。
        String material = pickBuildingMaterial(SHELTER_BLOCKS);
        if (material == null) {
            finish("建造失败：建筑方块不足 " + SHELTER_BLOCKS + " 个（需要先采集）");
            return;
        }
        int placed = buildShelter(material);
        if (placed < SHELTER_BLOCKS) {
            finish("建造部分完成：材料=" + material + " 放置=" + placed + "/" + SHELTER_BLOCKS);
        } else {
            finish("建造完成：材料=" + material + " 放置=" + placed);
        }
    }

    private String pickBuildingMaterial(int needed) {
        // 顺序即优先级：圆石最结实，木板其次，泥土最后。
        String[] candidates = {"cobblestone", "stone", "dirt", "oak_planks", "spruce_planks",
                "birch_planks", "jungle_planks", "acacia_planks", "dark_oak_planks",
                "mangrove_planks", "cherry_planks", "bamboo_planks", "pale_oak_planks",
                "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log",
                "dark_oak_log", "mangrove_log", "cherry_log", "pale_oak_log"};
        for (String candidate : candidates) {
            if (countItem(candidate) >= needed) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 在脚下盖一个 3x3、两格高、带屋顶的小屋，南面留一个门洞。
     * 直接 setBlock 而不是模拟放置：伪玩家没有客户端，走 useItemOn 还要处理朝向和碰撞，
     * 服务端直接改方块结果一致，且能保证不会把 NPC 自己封死（内部两格始终跳过）。
     */
    private int buildShelter(String materialPath) {
        Item item = item(materialPath);
        if (item == Items.AIR) {
            LOG.warning("[AIV][GOAL] " + npc.npcName() + " 建筑方块映射失败 item=" + materialPath);
            return 0;
        }
        Block block = Block.byItem(item);
        BlockPos base = npc.blockPosition();
        int placed = 0;
        outer:
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    // 内部 1x2 空间必须留空，否则会把 NPC 埋进方块里。
                    if (dy < 2 && dx == 0 && dz == 0) {
                        continue;
                    }
                    // 门洞：南侧底层留空，NPC 才能进出。
                    if (dy == 0 && dx == 0 && dz == 1) {
                        continue;
                    }
                    BlockPos pos = base.offset(dx, dy, dz);
                    BlockState current = npc.level().getBlockState(pos);
                    if (!current.isAir() && !current.canBeReplaced()) {
                        continue;
                    }
                    if (countItem(materialPath) <= 0) {
                        LOG.info("[AIV][GOAL] " + npc.npcName() + " 建筑材料用完，已放置 " + placed + " 个");
                        break outer;
                    }
                    if (!consume(materialPath, 1)) {
                        break outer;
                    }
                    npc.level().setBlockAndUpdate(pos, block.defaultBlockState());
                    placed++;
                }
            }
        }
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 建造小屋 | base=" + base.toShortString()
                + " 材料=" + materialPath + " 放置=" + placed);
        return placed;
    }

    // ---------------------------------------------------------------- 探索

    private void tickExplore() {
        if (!exploreStarted) {
            // 随机方向可能正好撞山壁/水面：多试几个方向，别第一个走不通就放弃整个探索目标。
            for (int attempt = 0; attempt < 5; attempt++) {
                double angle = java.util.concurrent.ThreadLocalRandom.current().nextDouble() * Math.PI * 2.0D;
                JsonObject params = new JsonObject();
                params.addProperty("x", npc.getX() + Math.cos(angle) * targetCount);
                params.addProperty("z", npc.getZ() + Math.sin(angle) * targetCount);
                if (skills.run(new Intent("", "goto", params), "目标")) {
                    exploreStarted = true;
                    return;
                }
            }
            finish("探索失败：连续 5 个方向都走不过去");
            return;
        }
        // tick 能走到这里说明技能已经结束（上面的 idle 检查），直接结算。
        finish("探索结束：" + skills.lastOutcome());
    }

    // ---------------------------------------------------------------- 背包工具

    private void finish(String reason) {
        active = false;
        lastOutcome = reason;
        outcomeCount++;
        String doneType = type;
        type = "-";
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 目标结束 | type=" + doneType + " " + reason);
    }

    /**
     * 目标结束次数。规划器用它判断"又完成了一轮"，
     * 光比较 lastOutcome 字符串的话，同一个失败原因连续出现时会被当成"没有新结果"，
     * 冷却时间永远不刷新，于是每秒重下同一个做不到的合成目标。
     */
    public int outcomeCount() {
        return outcomeCount;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalizeBlock(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("minecraft:")) {
            value = value.substring("minecraft:".length());
        }
        return value;
    }

    /** 模型可能说“木镐”“pickaxe”，统一映射到可合成物品 ID。 */
    private static String normalizeItem(String raw) {
        String value = normalizeBlock(raw);
        return switch (value) {
            case "planks", "wood_planks", "wooden_planks", "木板", "木材" -> "planks";
            case "pickaxe", "wood_pickaxe", "木镐" -> "wooden_pickaxe";
            case "sword", "wood_sword", "木剑" -> "wooden_sword";
            case "axe", "wood_axe", "木斧" -> "wooden_axe";
            case "stone_pick", "石镐" -> "stone_pickaxe";
            case "stone_sword", "石剑" -> "stone_sword";
            case "sticks", "木棍" -> "stick";
            case "竹板", "竹木板" -> "bamboo_planks";
            case "工作台" -> "crafting_table";
            case "熔炉" -> "furnace";
            case "火把" -> "torch";
            default -> value;
        };
    }

    private static Item item(String path) {
        Identifier id = Identifier.tryParse(path);
        if (id == null) {
            return Items.AIR;
        }
        return BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
    }

    private static String pathOf(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.getPath();
    }

    private int countItem(String itemPath) {
        int total = 0;
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (!stack.isEmpty() && itemPath.equals(pathOf(stack))) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countPlanks() {
        int total = 0;
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (!stack.isEmpty() && pathOf(stack).endsWith("_planks")) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countItemsEndingWith(String suffix) {
        int total = 0;
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (!stack.isEmpty() && pathOf(stack).endsWith(suffix)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 背包里是否有还能用的镐。
     *
     * <p>把"最后 1 点耐久"的镐也排除掉：身体层不会再用它挖（避免整把碎掉消失），
     * 所以对目标层来说它已经等于没有工具，继续下挖只会空转。
     */
    private boolean hasUsablePickaxe() {
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !pathOf(stack).endsWith("_pickaxe")) {
                continue;
            }
            if (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage() - 1) {
                return true;
            }
        }
        return false;
    }

    private String countPath(String itemPath) {
        // 目标写成具体木板（oak_planks）时按“任意木板”计数，避免只砍了丛林木就永远做不成。
        return itemPath.endsWith("_planks") ? "planks" : itemPath;
    }

    private int countItemFor(String counted) {
        if ("planks".equals(counted)) {
            return countPlanks();
        }
        int total = 0;
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !counted.equals(pathOf(stack))) {
                continue;
            }
            // 工具类目标只算还能用的：耐久见底的镐留在背包里会让"补一把备用石镐"
            // 在目标开始的瞬间就判定 2/2 完成，实际一把新的都没做出来，规划器每秒重下一次。
            if (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
                continue;
            }
            total += stack.getCount();
        }
        return total;
    }

    private boolean consume(String itemPath, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < MAIN_SLOTS && remaining > 0; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !itemPath.equals(pathOf(stack))) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
            if (stack.isEmpty()) {
                npc.getInventory().setItem(slot, ItemStack.EMPTY);
            }
        }
        return remaining == 0;
    }

    /**
     * 把物品塞进主背包。返回 "ok" 或失败原因。
     * 不用 Inventory.add：它可能把方块放进护甲槽，这里只认 0-35 号槽。
     */
    private String give(String itemPath, int amount) {
        if (item(itemPath) == Items.AIR) {
            return "未知物品 " + itemPath;
        }
        int remaining = storeInMain(itemPath, amount);
        // 背包满时先丢杂物再重试：实测 NPC 打到铁器阶段后背包被圆石/泥土塞满，
        // 合成产物放不下 → "背包已满" 失败 → 规划器每秒重下同一个合成目标，科技树死锁。
        // 玩家这时候会扔掉泥土/沙砾，这里做等价处理。
        if (remaining > 0 && dropJunk(2) > 0) {
            remaining = storeInMain(itemPath, remaining);
        }
        return remaining == 0 ? OK : "背包已满，另有 " + remaining + " 个 " + itemPath + " 放不下";
    }

    /** 只做"合并堆叠 + 找空槽"两步，返回没放下的数量。 */
    private int storeInMain(String itemPath, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < MAIN_SLOTS && remaining > 0; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !itemPath.equals(pathOf(stack))) {
                continue;
            }
            int room = Math.min(stack.getMaxStackSize() - stack.getCount(), remaining);
            if (room > 0) {
                stack.grow(room);
                remaining -= room;
            }
        }
        for (int slot = 0; slot < MAIN_SLOTS && remaining > 0; slot++) {
            if (npc.getInventory().getItem(slot).isEmpty()) {
                ItemStack part = new ItemStack(item(itemPath), remaining);
                npc.getInventory().setItem(slot, part);
                remaining = 0;
            }
        }
        return remaining;
    }

    /**
     * 丢弃背包里的低价值杂物，最多腾出 needed 个槽位，返回实际腾出的槽数。
     *
     * <p>保留上限而不是"见到就丢"：#1 泥土在夜晚建屋还有用；沙砾要留着筛燧石；
     * 竹子是木板替代品。只丢掉超出上限的部分，避免把后续科技树要用的材料扔了。
     */
    private int dropJunk(int needed) {
        // 先按"每种物品的总保留量"裁剪一遍：这一步与背包是否满无关，
        // 顺便把只剩 1 点耐久的废工具清出去（它们占格但已经不能挖/打）。
        int freed = trimOverflow();
        // 名单式清理没腾够（比如全是工具/关键材料挤在一起）：按堆大小兜底再丢。
        if (freed < needed) {
            freed += dropLeastValuable(needed - freed);
        }
        return freed;
    }

    /**
     * 按"每种物品总量"裁剪背包，返回真正腾空的槽数。
     *
     * <p>为什么不能只按每格上限判断：旧实现是逐格比较 junkKeepLimit（圆石上限 128），
     * 而单堆最多 64，于是 14 个满堆每一堆都"没超上限"，836 个圆石永远裁不掉，白占 13 格。
     * 这里把上限当成"全局配额"，从背包尾部往前保留，超出的部分丢掉。
     */
    private int trimOverflow() {
        java.util.Map<String, Integer> quota = new java.util.HashMap<>();
        int freed = 0;
        // 先统计每个"工具家族"里还有多少把真能用的镐/剑/护甲，
        // 防止 trimOverflow 把所有剩 1 耐久的都丢光，最后变成真没工具。
        // 但注意：只能把"同等级或更好的"算进可用池——石镐能挖石头但挖不动深板岩，
        // 一把石镐的存在不能让铁镐的 1 耐久版本被放心丢弃。
        java.util.Map<String, Integer> usableByFamily = new java.util.HashMap<>();
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !stack.isDamageableItem()) {
                continue;
            }
            // 只数"还有 2+ 耐久"的——剩 1 耐久的算废品，但要知道还有几把真能用的
            if (stack.getDamageValue() >= stack.getMaxDamage() - 1) {
                continue;
            }
            usableByFamily.merge(toolFamily(pathOf(stack)), 1, Integer::sum);
        }
        for (int slot = MAIN_SLOTS - 1; slot >= 0; slot--) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String path = pathOf(stack);
            int keep = quota.computeIfAbsent(path, this::junkKeepLimit);
            // 只剩最后 1 点耐久的护甲等同废品：身体层约定不再用它。
            // 但工具（镐/剑/斧等）即便 1 耐久也不能随便丢——它们是生存层的最后手段。
            // 只有当"同路径"（同等级，比如 iron_pickaxe）存在真能用的版本时才丢，
            // 这样石镐剩 1 耐久不会因为"有铁镐可用"就被一起丢了（反过来也一样）。
            if (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
                // 护甲类：只要家族里有可用的就丢（1 耐久护甲没用）
                if (path.endsWith("_helmet") || path.endsWith("_chestplate")
                        || path.endsWith("_leggings") || path.endsWith("_boots")) {
                    String family = toolFamily(path);
                    int usable = usableByFamily.getOrDefault(family, 0);
                    if (usable == 0) {
                        continue;  // 1 耐久护甲是家族唯一库存，留着
                    }
                    keep = 0;
                } else {
                    // 工具类：只在"同物品路径"有可用版本时才丢，跨等级不算
                    // 例：iron_pickaxe 剩 1 耐久 + stone_pickaxe 有 100 耐久 => 不丢铁镐
                    // 例：iron_pickaxe 剩 1 耐久 + 另一把 iron_pickaxe 有 100 耐久 => 丢 1 耐久那把
                    int samePathUsable = 0;
                    for (int s = 0; s < MAIN_SLOTS; s++) {
                        ItemStack other = npc.getInventory().getItem(s);
                        if (other.isEmpty() || !pathOf(other).equals(path)) continue;
                        if (other.getDamageValue() < other.getMaxDamage() - 1) samePathUsable++;
                    }
                    if (samePathUsable == 0) {
                        continue;  // 该路径下唯一一把，留着当救命稻草
                    }
                    keep = 0;
                }
            }
            if (keep < 0) {
                continue;
            }
            if (stack.getCount() <= keep) {
                quota.put(path, keep - stack.getCount());
                continue;
            }
            int amount = stack.getCount() - keep;
            dropItems(slot, amount, "超量裁剪 " + path + " 保留=" + keep);
            if (keep == 0) {
                freed++;
            }
            quota.put(path, 0);
        }
        return freed;
    }

    /** 把具体工具映射到"家族"，用于判断工具多样性。 */
    private static String toolFamily(String path) {
        if (path.endsWith("_pickaxe")) return "pickaxe";
        if (path.endsWith("_sword")) return "sword";
        if (path.endsWith("_axe")) return "axe";
        if (path.endsWith("_shovel")) return "shovel";
        if (path.endsWith("_hoe")) return "hoe";
        if (path.endsWith("_helmet") || path.endsWith("_chestplate")
                || path.endsWith("_leggings") || path.endsWith("_boots")) return "armor";
        return path;
    }

    /** 从指定槽位丢出若干物品，丢弃后永久不可再拾取。 */
    private void dropItems(int slot, int amount, String reason) {
        ItemStack stack = npc.getInventory().getItem(slot);
        if (stack.isEmpty() || amount <= 0) {
            return;
        }
        int actual = Math.min(amount, stack.getCount());
        ItemEntity drop = new ItemEntity(npc.level(), npc.getX(), npc.getY() + 0.5D, npc.getZ(),
                new ItemStack(stack.getItem(), actual));
        drop.setThrower(npc);
        // 关键：必须设成"永不拾取"（32767 是原版约定的永久值）。
        // 只设 40 tick 的延迟没用：伪玩家站在丢包点上，两秒后原版自动拾取会把
        // 刚丢掉的 700 个圆石全捡回来，于是"裁剪→捡回→再裁剪"每 5 秒循环一次，
        // 背包永远腾不出来，日志还被刷屏。
        drop.setPickUpDelay(32767);
        npc.level().addFreshEntity(drop);
        stack.shrink(actual);
        if (stack.isEmpty()) {
            npc.getInventory().setItem(slot, ItemStack.EMPTY);
        }
        LOG.info("[AIV][INV] " + npc.npcName() + " 丢弃 | " + reason + " x" + actual);
    }

    /** 这类物品的保留数量；-1 表示有意义、永远不丢。 */
    private int junkKeepLimit(String path) {
        switch (path) {
            case "dirt", "sand", "clay_ball", "andesite", "diorite", "granite",
                 "tuff", "calcite", "deepslate", "cobbled_deepslate" -> {
                return 0;
            }
            // 圆石只留 1 格（128 个）：它是世界上最好再挖的东西，
            // 实测囤到 700+ 个会占掉 12 格，合成产物直接没地方放（空槽=0 导致全科技树卡死）。
            case "cobblestone" -> {
                return 128;
            }
            case "stone" -> {
                return 64;
            }
            // 沙砾保留 16 个：燧石就从它里面筛，全丢光会退回"没燧石"的阶段。
            case "gravel" -> {
                return 16;
            }
            case "bamboo" -> {
                return 32;
            }
            case "stick" -> {
                return 16;
            }
            case "rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish" -> {
                return 0;
            }
            // 低阶工具/护甲：有更好的就丢，留着只会占槽位。
            case "wooden_pickaxe", "wooden_sword", "wooden_axe", "wooden_shovel", "wooden_hoe",
                 "leather_helmet", "leather_chestplate", "leather_leggings", "leather_boots",
                 "golden_helmet", "golden_chestplate", "golden_leggings", "golden_boots",
                 "chainmail_helmet", "chainmail_chestplate", "chainmail_leggings", "chainmail_boots" -> {
                return countItem("stone_pickaxe") > 0 || countItem("iron_pickaxe") > 0
                        || countItem("diamond_pickaxe") > 0 ? 0 : 1;
            }
            default -> {
            }
        }
        if (path.endsWith("_seeds") || path.endsWith("_sapling") || path.endsWith("_leaves")
                || path.equals("vine") || path.equals("lily_pad")) {
            return 0;
        }
        // 木料给一个"够用就好"的上限：24 原木 / 32 木板已经够整条科技树的木棍消耗，
        // 再多就是占格子（规划器只在 wood<16 时补料，正常不会囤到这个量）。
        if (path.endsWith("_log") || path.endsWith("_stem")) {
            return 24;
        }
        if (path.endsWith("_planks")) {
            return 32;
        }
        return -1;
    }

    /**
     * 判断"只剩 1 耐久的工具"是不是背包里唯一一把该类可用工具。
     * 如果是，就算只剩 1 耐久也不能丢。
     *
     * <p>"可用"的定义：耐久 > 1（身体层的约定，避免刚到 1 耐久就被算进去）。
     * "同 kind"的定义：同后缀（_pickaxe、_sword、_axe、_helmet 等）。
     */
    private boolean isLastUsableOfKind(String path, ItemStack damaged) {
        // 先从 path 里抽 kind 后缀，比如 "iron_pickaxe" → "_pickaxe"
        String kind = null;
        for (String suff : new String[]{"_pickaxe", "_sword", "_axe", "_shovel", "_hoe",
                "_helmet", "_chestplate", "_leggings", "_boots"}) {
            if (path.endsWith(suff)) {
                kind = suff;
                break;
            }
        }
        if (kind == null) {
            return false; // 不是工具/护甲类，不算
        }
        int usableCount = 0;
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack s = npc.getInventory().getItem(slot);
            if (s.isEmpty()) continue;
            String p = pathOf(s);
            if (!p.endsWith(kind)) continue;
            if (!s.isDamageableItem()) continue;
            if (s.getDamageValue() < s.getMaxDamage() - 1) {
                usableCount++;
            }
        }
        return usableCount == 0;
    }

    /** 是否属于"无论多满都不该丢"的关键物品（工具/护甲/食物/通关材料）。 */
    private boolean isEssential(ItemStack stack) {
        String path = pathOf(stack);
        // 只剩最后 1 点耐久的工具通常是废品（身体层约定不再用它），
        // 但如果这是背包里唯一一把可用的该类工具，就绝不能丢——
        // 否则"背包满 + 唯一一把铁镐只剩 1 耐久"这种情况会把铁镐直接丢掉，
        // NPC 紧接着就得重造，科技树被打断好几分钟（2026-10-25 14:26 实测）。
        if (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
            if (isLastUsableOfKind(path, stack)) {
                return true;
            }
            return false;
        }
        if (path.endsWith("_pickaxe") || path.endsWith("_sword") || path.endsWith("_axe")
                || path.endsWith("_shovel") || path.endsWith("_hoe")
                || path.endsWith("_helmet") || path.endsWith("_chestplate")
                || path.endsWith("_leggings") || path.endsWith("_boots")
                || path.equals("shield") || path.equals("bow") || path.equals("arrow")
                || path.equals("bucket") || path.equals("water_bucket")
                || path.equals("flint_and_steel") || path.equals("obsidian")
                || path.equals("ender_eye") || path.equals("ender_pearl")
                || path.equals("blaze_rod") || path.equals("blaze_powder")
                || path.equals("iron_ingot") || path.equals("diamond")
                || path.equals("raw_iron") || path.equals("raw_gold") || path.equals("gold_ingot")
                || path.equals("redstone") || path.equals("lapis_lazuli")
                || path.equals("coal") || path.equals("charcoal") || path.equals("flint")
                || path.equals("crafting_table") || path.equals("furnace")
                // 木料类是整条科技树的根基（木棍→工具/箭、木板→工作台/木棍），
                // 兜底清理时把它当关键材料保住，否则"背包满"会先把木棍丢掉。
                || path.equals("stick") || path.equals("bamboo") || path.equals("bamboo_block")
                || path.endsWith("_planks") || path.endsWith("_log") || path.endsWith("_stem")) {
            return true;
        }
        // 能吃的都不丢：饿死比背包乱更糟。
        net.minecraft.world.item.Item item = item(path);
        return item != Items.AIR
                && new ItemStack(item).get(net.minecraft.core.component.DataComponents.FOOD) != null;
    }

    /**
     * 兜底腾槽：按"数量最少且不关键"丢一整个堆。
     *
     * <p>上一版只处理固定名单里的杂物，背包塞满 12 把半耐久石镐 + 12 格圆石后
     * 一格都腾不出来，合成产物放不下 → "空槽=0" 反复失败。
     * 这里把非关键物品按堆大小排序，先丢最没价值的那一堆，保证合成永远有地方落物。
     */
    private int dropLeastValuable(int needed) {
        int freed = 0;
        for (int round = 0; round < needed; round++) {
            int worstSlot = -1;
            int worstCount = Integer.MAX_VALUE;
            for (int slot = 0; slot < MAIN_SLOTS; slot++) {
                ItemStack stack = npc.getInventory().getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                if (isEssential(stack)) {
                    continue;
                }
                if (stack.getCount() < worstCount) {
                    worstCount = stack.getCount();
                    worstSlot = slot;
                }
            }
            if (worstSlot < 0) {
                return freed;
            }
            ItemStack stack = npc.getInventory().getItem(worstSlot);
            int amount = stack.getCount();
            // 走统一的丢弃入口：那里会把拾取延迟设成永久，避免伪玩家两秒后又捡回来。
            dropItems(worstSlot, amount, "背包塞满，兜底 " + pathOf(stack));
            freed++;
        }
        return freed;
    }

    /** 主背包 0-35 里是否还有空槽。 */
    private boolean hasEmptyMainSlot() {
        return countEmptyMainSlots() > 0;
    }

    private int countEmptyMainSlots() {
        int empty = 0;
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            if (npc.getInventory().getItem(slot).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }
}

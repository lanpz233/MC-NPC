package com.example.aivillager.goals;

import com.example.aivillager.AiVillagerPlugin;
import com.example.aivillager.intent.Intent;
import com.example.aivillager.npc.FakePlayerBody;
import com.example.aivillager.pathfinding.PathFinder;
import com.example.aivillager.skills.SkillRunner;
import com.example.aivillager.snapshot.InventorySnapshot;
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
    /**
     * "数家底"要扫的槽位总数：0-35 主背包 + 36-39 装备槽 + 40 副手。
     * 只用于计数/判定（countItem/countPlanks/countItemsEndingWith/countItemFor）：
     * 穿在身上的甲必须算"有"，否则合成目标会把已穿的头盔再做一遍（第三轮修复 B）。
     * 真正动手存取的循环（consume/give/裁剪）仍只走 MAIN_SLOTS，绝不扣穿着的装备。
     */
    private static final int COUNT_SLOTS = 41;
    /** 采集时单次搜索半径；技能层内部还会再限制到 8 格。 */
    private static final int GATHER_RADIUS = 8;
    /** 连续多少次采集没有进度就判定这个目标做不到。 */
    private static final int MAX_STALE_CHECKS = 8;
    /** 连续多少次找不到目标方块就放弃（中间会先随机走走换位置）。 */
    private static final int MAX_GATHER_FAILS = 6;
    /** 盖一个 3x3 小屋需要的方块数：两层墙 16 + 屋顶 9。 */
    private static final int SHELTER_BLOCKS = 25;

    // ---- 目标总超时看门狗（第四轮修复 A）----
    // 第三轮长跑实测：挖铁目标被游泳层反复顶掉技能后既不完成也不放弃，
    // 规划器被冻结 31 分钟。这里给每个目标装两个上限：无进展超时（主判据）
    // 与总时长硬顶（兜底）。阈值按 tick（20/秒），进展判据见 observeGoalProgress。
    /** 无进展超时·默认：5 分钟没有任何目标相关进展就放弃。 */
    private static final int GOAL_NO_PROGRESS_TICKS_DEFAULT = 6_000;
    /** 无进展超时·挖矿：6 分钟。挖矿单步慢，但位置和产出应该持续变化。 */
    private static final int GOAL_NO_PROGRESS_TICKS_DIG = 7_200;
    /** 无进展超时·长目标：8 分钟。熔炼等待、长距离行进/屠龙用。 */
    private static final int GOAL_NO_PROGRESS_TICKS_LONG = 9_600;
    /** 总时长硬顶·默认：30 分钟。哪怕一直有零星进展也不许无限占着大脑。 */
    private static final int GOAL_HARD_CAP_TICKS_DEFAULT = 36_000;
    /** 总时长硬顶·挖矿：45 分钟。大批量挖矿（如钻石甲 24 颗料）合法耗时更长。 */
    private static final int GOAL_HARD_CAP_TICKS_DIG = 54_000;

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
    /** 【fix74】fillbucket 技能完整跑完一轮仍没装到水的次数。attempts 在派发成功时清零、
     * 只数派发被拒，数不到这种失败——farm33 实证无限重派白站 300 秒。 */
    private int fillFailedRuns;
    private boolean fillSkillActive;
    private int outcomeCount;
    private int lastProgress = -1;
    /**
     * gather 开始那一刻背包里已有的数量。
     * 进度只算"本轮新采到多少"，否则背包里本来就有 15 个竹子时，
     * 「采集 8 个」会在第一秒宣布完成，模型又下一遍同样的目标，NPC 看着就像站着不动。
     */
    private int baselineCount;
    /** 【第二十三轮】目标起止（供结束行打耗时与位移，日志标准第 2 条）。 */
    private long goalStartedMillis;
    private int goalStartX;
    private int goalStartY;
    private int goalStartZ;
    private int staleChecks;
    // 找资源扩围的方向惯性（fix18）：跨目标保留的航向/档位/锚点，故意不随
    // 目标重下而重置——旧逻辑每个新目标 attempts 归零、距离永远从 8 格起步，
    // 且每次都重挑"地表最低方向"，会在盆地里绕圈打转（长跑 #5 实测：
    // 无树平原开局 25 分钟、采集放弃 7 次、净位移个位数格，零原木）。
    // 改为：一个方向走得通就保持航向逐档走远；走不通（位移 <8 格）转 45°。
    private double gatherSearchAngle = -1;
    private int gatherSearchTier = 0;
    private BlockPos gatherSearchAnchor;
    /** 【第四十六轮】扩围扇区记账：8 个罗盘扇区各派过几跳 + 本轮盲跳计数，
     * 给 escalateSearch 的罗盘收敛用（恶地局盆地空转 40 分钟的对症药）。 */
    private final int[] gatherSectorHops = new int[8];
    private int gatherEscalateHops;
    /** dryland 连续失败次数：失败 3 次后跳过，避免无限重试泡在水里 20 秒又 20 秒。 */
    private int drylandFailures;
    private boolean exploreStarted;
    /**
     * 上一条矿道挖空了（一块矿没出，或被悬空/流体堵死）：下一轮掘进前先挪一段距离。
     * 在刚挖空的区域原地再下发 strip 只会重复同一条走廊
     * （实测 105,19,61 ↔ 112,19,61 往返 7 轮，步数凑满 24 但一块矿都没有）。
     */
    private boolean relocateBeforeStrip;
    /** 【第四十七轮】挖石头时 strip 连败计数：沼泽式地表无石时改下挖取石的触发量。 */
    private int stoneStripFails;
    /** 已经处理过的 strip 结果：同一个结果字符串不要每 tick 重复触发"换点"。 */
    private String handledStripOutcome = "-";
    /** 16 格探矿扫描的节流计数：全扫一次约 1.4 万格，只在技能空闲的决策点扫、且至少隔 4 次决策。 */
    private int oreScanCooldown;
    /** 上次探矿扫描选中的目标格：单格复核（方块还在、仍然裸露）通过就直接复用，省掉重复全扫。 */
    private BlockPos oreScanCachePos;
    private String oreScanCacheBlock = "";
    /** 上次探矿扫描发现的裸露候选矿总数（统计口径：ore-scan 日志里的 found）。 */
    private int lastOreScanFound;
    private String status = "无目标";
    private String lastOutcome = "-";
    /** 挖掘类目标的资源定义（方块 ID、掉落物、目标深度）；其他目标为 null。 */
    private OreTarget oreTarget;
    /** 【fix40】记矿回访目标坐标（revisit 目标专用）。 */
    private int revisitX;
    private int revisitY;
    private int revisitZ;
    /** 熔炼时放下的熔炉位置；null 表示还没放置。 */
    private BlockPos furnacePos;
    /**
     * 【第四十一轮·工作站回收】自己亲手放下的那台熔炉的位置（跨目标保留）。
     * 复用世界里找到的熔炉不登记——只收自己放的（Fong 04:11 规格：工作台/
     * 熔炉随身带、用完挖回背包）。冶炼收尾 drainFurnace 后按此位拆炉回收。
     */
    private BlockPos placedFurnacePos;
    private int smeltTicks;
    private int smeltBaseline;
    /** 狩猎目标开始时的肉类数量，进度只看本轮新打到的。 */
    private int huntBaseline;
    private int huntAttempts;
    /** 【第十五轮】连续整轮狩猎失败次数 + 远走找猎的固定航向：跨目标保留，
     * 让找吃的搬迁距离逐轮放大、方向不回头（治无动物区原地小步打转饿死循环）。 */
    private int huntFailStreak;
    private double huntHeading = Double.NaN;
    /** 【第二十二轮】存量驱动狩猎：need=leather/food 时按背包存量收工，不数击杀。 */
    private String huntNeed = "";
    private int huntStockTarget;
    private int lastHuntStock = -1;
    // ---- 种田（第四十九轮 fix49，D11）----
    /** 田块目标格数：一块小田 8 格，够验证循环种植、也不贪大。 */
    private static final int FARM_PLOT_CELLS = 8;
    /** 留种地板：小麦种子背包存量低于此数不许再扩种（收获补种不受此限，见 canPlantCrop）。 */
    private static final int FARM_SEED_FLOOR = 4;
    /** 胡萝卜/土豆留苗地板：背包+在田总量低于此数时不许吃、不许烤（生存层守卫同口径）。 */
    private static final int FARM_STOCK_FLOOR = 8;
    /** farm 目标模式（establish/tend/auto）与阶段机。 */
    private String farmMode = "";
    private int farmPhase;
    /** 待办动作队列：锄/种/收/骨粉逐个执行（动作本身是瞬时直改，移动靠 goto 技能）。 */
    private final java.util.List<FarmAction> farmActions = new java.util.ArrayList<>();
    /** 选址结果（土格清单）；null 表示还没选到址。 */
    private java.util.List<BlockPos> farmSiteCells;
    private int farmSiteAttempts;
    /** 同一动作连续走不到位的次数：超限丢弃该动作，防一块田卡死整个目标。 */
    private int farmActionStuck;
    /** 【fix60】本目标内被跳过的动作数：tend 全军覆没要如实报失败，不许冒充完成。 */
    private int farmSkipped;
    private String farmLastActionKey = "";
    /** 本访已收获的土格（拾取阶段要挨个走过田面收掉落物）。 */
    private final java.util.List<BlockPos> farmHarvestedSoils = new java.util.ArrayList<>();
    private int farmPickupIndex;
    /** 骨粉阶段是否已排过队（维护访内只催一轮，防骨粉烧光）。 */
    private boolean farmBonemealQueued;
    /** 本目标内动作队列是否已构建（establish 的锄种队列 / tend 的补种队列各建一次）。 */
    private boolean farmQueueBuilt;
    /** 骨粉后复查的第二轮收获是否已走过（防收获↔骨粉来回横跳）。 */
    private boolean farmSecondPass;
    /** 维护访开始时的农产存量基线，收尾按差额记账（资源流水）。 */
    private int farmBaseWheat;
    private int farmBaseSeeds;
    private int farmBaseCarrot;
    private int farmBasePotato;

    /** 一个田间动作：kind=till/plant/harvest/bonemeal；pos 语义见各动作实现（till/plant=土格，harvest/bonemeal=作物格）。 */
    private record FarmAction(String kind, BlockPos pos, String crop) {
    }
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
    /** 目标总超时看门狗状态（第四轮修复 A）：目标开始 tick、最后一次有进展的 tick。 */
    private long goalStartTick;
    private long lastProgressTick;
    /** 周期裁剪节流计数（第三十三轮）：safeTick 每 10 tick 采样一次，本计数每 10 次≈5 秒触发一轮裁剪。 */
    private int trimTickCounter;
    /** 【fix71·D5】插火把节流计数（目标 tick 基准，约每 10 次查一次）。 */
    private int torchTickCounter;
    /** 上次观察到的"目标相关产出计数"，变化即算进展（见 goalMetric）。 */
    private int progressMetric;
    /** 上次有进展时的位置锚点：离开锚点 6 格以上也算进展（长距离移动/下矿途中）。 */
    private BlockPos progressAnchor;
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
     * 铁在 Y=16 附近最密集，钻石起始层 Y=-54（fix81 起按 NPC 渐进下探至 -59，
     * 实际层位由 DigDepthMemory 在 accept 时覆盖），煤和铁同一层（Y=16），石头随处都是。
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
                || "revisit".equals(action) || "farm".equals(action) || "base".equals(action)
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
                if ("diamond_ore".equals(target.block())) {
                    // 【fix81】钻石目标层渐进下探：深度按 NPC 持久化（DigDepthMemory），
                    // 连败换区时下探、-59 封底；layerY/descend/strip 全链都读
                    // oreTarget.y，在这里换掉 y 即全链生效。revisit 走记账点
                    // 坐标、不经此覆盖。
                    DigDepthMemory.bindWorld(npc.level());
                    target = new OreTarget(target.block(), target.drop(),
                            DigDepthMemory.depth(npc.npcName()));
                }
                oreTarget = target;
                newTarget = target.block();
                newCount = clamp(intent.intParam("count", 8), 1, 64);
                if ("diamond_ore".equals(oreTarget.block())) {
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " 挖钻开工 | depth=" + oreTarget.y()
                            + " 目标=" + newCount);
                }
            }
            case "revisit" -> {
                // 【fix40】记矿回访：参数带记账点坐标与矿种，oreTarget 复用
                // dig 的矿种表（掉落计数/级别门都现成）。
                OreTarget target = oreTargetOf(normalizeBlock(intent.param("resource", "")));
                if (target == null) {
                    lastOutcome = "revisit 不支持的资源 " + intent.param("resource", "");
                    return false;
                }
                oreTarget = target;
                revisitX = intent.intParam("x", 0);
                revisitY = intent.intParam("y", 0);
                revisitZ = intent.intParam("z", 0);
                newTarget = "revisit";
                newCount = 1;
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
                // 【第二十二轮】存量驱动模式：need=leather/food + stock=目标存量。
                // 给了 need 就不再数"打死几只"，改由 tickHunt 每轮复查背包存量。
                huntNeed = intent.param("need", "");
                huntStockTarget = intent.intParam("stock",
                        "leather".equals(huntNeed) ? 4 : 24);
                lastHuntStock = -1;
            }
            case "farm" -> {
                // 【fix49·种田】mode=establish 开田 / tend 维护（收获补种）；
                // 缺省 auto：有田维护、无田开田，tickFarm 按账本现状分流。
                farmMode = intent.param("mode", "auto");
                newTarget = "farm:" + farmMode;
                newCount = 1;
            }
            case "base" -> {
                // 【fix51·D2 落脚点】建火柴盒小屋（选址→砌筑队列→自审落盘）。
                newTarget = "base";
                newCount = 1;
            }
            case "place_bed" -> {
                // 【fix56·D1 床】把包里的床安置进落脚点屋内并登记重生点。
                newTarget = "place_bed";
                newCount = 1;
            }
            case "sleep" -> {
                // 【fix56·D1 床】夜里回床真睡到天亮（原版睡眠跳夜、防幻翼）。
                newTarget = "sleep";
                newCount = 1;
            }
            case "store_loot" -> {
                // 【fix69·箱子】回落脚点把贵重物存进箱子（v1 只存不取）。
                newTarget = "store_loot";
                newCount = 1;
            }
            case "loot_chest" -> {
                // 【fix80·D12】搜刮世界箱子（规划层扫到的未搜箱位）。
                newTarget = "loot_chest";
                newCount = 1;
                lootX = intent.intParam("x", 0);
                lootY = intent.intParam("y", 0);
                lootZ = intent.intParam("z", 0);
                lootStuck = 0;
                lootStandPos = null;
            }
            case "flint" -> {
                newTarget = "flint";
                newCount = clamp(intent.intParam("count", 1), 1, 8);
            }
            case "fillbucket" -> {
                // 【fix70·D4】装满水桶（常备保命水）：技能层已有 fillbucket。
                newTarget = "fillbucket";
                newCount = 1;
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
        goalStartedMillis = System.currentTimeMillis();
        goalStartX = npc.getBlockX();
        goalStartY = npc.getBlockY();
        goalStartZ = npc.getBlockZ();
        if (!"hunt".equals(type)) {
            huntNeed = "";
        }
        targetId = newTarget;
        targetCount = newCount;
        attempts = 0;
        fillFailedRuns = 0;
        fillSkillActive = false;
        staleChecks = 0;
        lastProgress = -1;
        // 必须在 targetId 赋值之后算：gatherCount() 要看目标物品。
        baselineCount = "gather".equals(type) ? gatherCount()
                : ("dig".equals(type) || "revisit".equals(type)) ? countItem(oreTarget.drop()) : 0;
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
        // 【fix49】种田状态机复位（farmMode 已在解析段按参数设好）。
        farmPhase = 0;
        farmActions.clear();
        farmSiteCells = null;
        farmSiteAttempts = 0;
        farmActionStuck = 0;
        farmSkipped = 0;
        farmLastActionKey = "";
        farmHarvestedSoils.clear();
        farmPickupIndex = 0;
        farmBonemealQueued = false;
        farmQueueBuilt = false;
        farmSecondPass = false;
        farmBaseWheat = countItem("wheat");
        farmBaseSeeds = countItem("wheat_seeds");
        farmBaseCarrot = countItem("carrot");
        farmBasePotato = countItem("potato");
        // 【fix51】落脚点状态机复位。
        basePhase = 0;
        baseQueue.clear();
        baseCenter = null;
        baseActionStuck = 0;
        baseLastActionKey = "";
        basePlacedCount = 0;
        baseTotalCells = 0;
        baseQueueBuilt = false;
        // 【fix56】床状态机复位。
        bedPhase = 0;
        bedFootPos = null;
        bedFacing = null;
        bedItemPath = null;
        bedActionStuck = 0;
        bedLastActionKey = "";
        sleepPhase = 0;
        sleepStartMillis = 0L;
        sleepDayTime0 = -1L;
        sleepActionStuck = 0;
        sleepLastActionKey = "";
        // 【fix69】存箱状态机复位。
        storePhase = 0;
        storeChestPos = null;
        storeActionStuck = 0;
        storeLastActionKey = "";
        portalOrigin = null;
        portalPhase = 0;
        enterAttempts = 0;
        exploreStarted = false;
        active = true;
        // 目标总超时看门狗从新目标开始重新计时（第四轮修复 A）。
        goalStartTick = npc.tickCount;
        lastProgressTick = npc.tickCount;
        progressMetric = goalMetric();
        progressAnchor = npc.blockPosition();
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
            case "farm" -> "种田 " + farmMode;
            case "base" -> "建落脚点（火柴盒小屋）";
            case "place_bed" -> "安置床（落脚点）";
            case "sleep" -> "回床睡觉";
            case "store_loot" -> "回落脚点存贵重物（箱子）";
            case "loot_chest" -> "搜刮箱子（村庄/结构）";
            case "flint" -> "找燧石 x" + targetCount;
            case "fillbucket" -> "装满水桶（常备）";
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
            // 死亡等复活的几秒不算"无进展"：把进展时钟顶到当前，免得复活后被误杀。
            lastProgressTick = npc.tickCount;
            return;
        }
        // 维度变了（穿过传送门）：旧目标里的坐标、资源层高度全部作废，立即结束。
        if (!goalDimension.isEmpty()
                && !goalDimension.equals(npc.level().dimension().identifier().toString())) {
            finish("维度切换 " + goalDimension + " → " + npc.level().dimension().identifier()
                    + "，旧目标中止");
            return;
        }
        // 目标总超时看门狗（第四轮修复 A）：必须赶在下面"技能忙就等"的早退之前——
        // 旧结构里各 tick* 的放弃计数只在技能 idle 的间隙累加，技能被生存层游泳
        // 反复顶掉/重发时永远轮不到计数，目标就无限期挂死（长跑实测冻结 31 分钟）。
        // 这里以"目标相关产出变化 / 位置有效移动 / 熔炉正在烧"为进展信号，
        // 无进展超限或总时长超硬顶就停技能、放弃目标，把大脑还给规划器。
        observeGoalProgress();
        long noProgressTicks = npc.tickCount - lastProgressTick;
        long totalTicks = npc.tickCount - goalStartTick;
        if (noProgressTicks > noProgressLimitTicks() || totalTicks > hardCapTicks()) {
            String reason = "goal-timeout type=" + type + " target=" + targetId
                    + " noProgressSec=" + (noProgressTicks / 20)
                    + " totalSec=" + (totalTicks / 20);
            LOG.info("[AIV][GOAL] " + npc.npcName() + " " + reason);
            skills.stop();
            finish(reason + " 目标超时放弃，交回规划器");
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
        // 惜命：血量低（≤35%）且包里有吃的时先停目标回血——生存层会吃东西触发原版回血，
        // 血没恢复前不推进任何目标，更不会推进猎杀类目标去送死（口径与规划器一致）。
        // 没吃的时不拦：找吃的本身就是当前目标（hunt/gather），拦了反而回不了血。
        if (survival != null && survival.isLowHealth() && survival.hasEdibleFood()) {
            status = "血量过低，先吃东西回血";
            return;
        }
        // 先收掉落物再推进目标：原版拾取要求碰撞箱相交，
        // 伪玩家挖掉邻格方块后掉落物落在 1 格外，不主动收就会一直"挖了但数量不涨"。
        pickupDrops();
        // 背包维护：每 5 秒裁一次"总量超限"的堆积（圆石 >128、泥巴/树苗等）。
        // 不能只在"背包全满"时才裁：实测囤到 836 个圆石占了 14 格，
        // 等满的时候合成产物已经没地方放，变成"空槽=0"的假缺料死循环。
        // 【第三十三轮】节流改用本地计数：旧写法 npc.tickCount % 100 == 0，而 tick 由
        // Bukkit 调度每 10 tick 采样一次，采样值恒为同一模 10 余数——相位不对齐时
        // 永远碰不到 %100，长跑#10 整局周期裁剪零触发、退役镐壳堆了 4 把没人清，
        // 只有拾取腾格的 dropJunk 偶尔裁一次。本地计数与采样步长无关，钉死每 10 次 tick 一裁。
        if (++trimTickCounter % 10 == 0) {
            trimOverflow();
        }
        switch (type) {
            case "gather" -> tickGather();
            case "craft" -> tickCraft();
            case "build" -> tickBuild();
            case "explore" -> tickExplore();
            case "dig" -> tickDig();
            case "revisit" -> tickRevisit();
            case "smelt" -> tickSmelt();
            case "hunt" -> tickHunt();
            case "farm" -> tickFarm();
            case "base" -> tickBase();
            case "place_bed" -> tickPlaceBed();
            case "sleep" -> tickSleep();
            case "store_loot" -> tickStoreLoot();
            case "loot_chest" -> tickLootChest();
            case "flint" -> tickFlint();
            case "fillbucket" -> tickFillBucket();
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
            // 资源已经进探测范围：扩围航行结束，清掉航向状态，下次从头挑方向。
            gatherSearchAngle = -1;
            gatherSearchTier = 0;
            gatherSearchAnchor = null;
            gatherEscalateHops = 0;
            java.util.Arrays.fill(gatherSectorHops, 0);
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
        // 附近找不到目标方块：逐步扩大搜索并往低处走，而不是原地小范围打转。
        attempts++;
        if (attempts >= MAX_GATHER_FAILS) {
            finish("采集放弃：附近找不到 " + targetId);
            return;
        }
        // 长距离找树（第六轮修复）：沙漠/海岸这类无树地形里，朝最低处扩围只会
        // 沿海岸线/沙漠边缘打转（fix6 长跑实测 17 分钟零原木、徒步 140 格没出沙漠）。
        // 先"远眺"一圈：只读已加载区块的地表高度图，顶格是原木/树叶的列就是
        // 肉眼看得见的树，朝它跋涉（goto 单次最远 48 格，多轮接力）。
        // 这不是透视：只看地表、只限已加载区块；地下找矿的 isExposed 规矩不受影响。
        if (anyLog && trekToVisibleTree()) {
            return;
        }
        escalateSearch();
    }

    /**
     * 朝远处看得见的树跋涉一步（第六轮修复）。成功下发 goto 返回 true。
     * 地下不远眺（高度图看到的是头顶地表，跋涉无从走起），交给 ascend/扩围处理。
     */
    private boolean trekToVisibleTree() {
        BlockPos me = npc.blockPosition();
        int groundAtMe = npc.level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                me.getX(), me.getZ());
        if (me.getY() < groundAtMe - 8) {
            return false;
        }
        BlockPos tree = findVisibleTreeColumn(80);
        if (tree == null) {
            return false;
        }
        // 太近的树 mine 技能自己会处理，跋涉只管 8 格半径之外的。
        if (me.distSqr(tree) <= 100.0D) {
            return false;
        }
        // 瞄准点别选树干列本身（整列没有落脚格，goto 的终点解析必失败）：
        // 朝 NPC 方向回退 3 格，落在树旁的开阔地上。
        double dx = me.getX() - tree.getX();
        double dz = me.getZ() - tree.getZ();
        double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        double aimX = tree.getX() + 0.5D + dx / len * 3.0D;
        double aimZ = tree.getZ() + 0.5D + dz / len * 3.0D;
        JsonObject params = new JsonObject();
        params.addProperty("x", aimX);
        params.addProperty("z", aimZ);
        if (skills.run(new Intent("", "goto", params), "目标")) {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " trek-to-tree | tree="
                    + tree.toShortString() + " dist="
                    + String.format(Locale.ROOT, "%.0f", Math.sqrt(me.distSqr(tree))));
            return true;
        }
        // 【第四十六轮补2】分段推进：整跳在台地地形里必死——落点回退到 24~32 格后
        // A* 仍要跨断崖，节点/时间预算内找不到路（恶地复跑实证：树在 93 格外、
        // 高出 40 格的台顶，整跳与回退跳全灭）。改沿同一射线只走 16 格一小段：
        // 小范围 A* 在台地坡面通常有路（escalate 的 8 格跳在本局能走动为证），
        // 采集失败轮次会反复调用本步、逐轮向树收敛，进 mine 半径后技能接管。
        double hopLen = Math.min(12.0D, len - 3.0D);
        if (hopLen > 4.0D) {
            JsonObject hop = new JsonObject();
            hop.addProperty("x", me.getX() + 0.5D - dx / len * hopLen);
            hop.addProperty("z", me.getZ() + 0.5D - dz / len * hopLen);
            if (skills.run(new Intent("", "goto", hop), "目标")) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " trek-to-tree 分段推进 | tree="
                        + tree.toShortString() + " dist="
                        + String.format(Locale.ROOT, "%.0f", Math.sqrt(me.distSqr(tree)))
                        + " hop=" + String.format(Locale.ROOT, "%.0f", hopLen));
                return true;
            }
        }
        // 瞄到了树但走不过去也要留痕，否则日志上和"没找到树"分不出来
        // （恶地复跑靠 goto 侧流水才定位到落点判死）。
        LOG.info("[AIV][GOAL] " + npc.npcName() + " trek-to-tree 未能出发 | tree="
                + tree.toShortString() + " dist="
                + String.format(Locale.ROOT, "%.0f", Math.sqrt(me.distSqr(tree)))
                + " outcome=" + skills.lastOutcome());
        return false;
    }

    /**
     * 在已加载区块里找最近的"地表有树"列：高度图（WORLD_SURFACE，含树冠）
     * 顶格是原木或树叶即是；未加载区块一律跳过——绝不为了找树去加载/生成新区块。
     *
     * <p>【第四十六轮】采样步长 3→2、顶格下探放宽到 3 格：林地恶地的橡树稀疏
     * 矮小，步长 3 的采样网格大概率整棵漏过（恶地局实证：树在 45 格外、
     * trek 全程 0 命中、41 分钟卡死木头线）；顶格偶被藤蔓等盖住时，
     * 下探范围内的原木/树叶也算树列。半径 80、步长 2 约 6.5k 列采样/次，
     * 只在采集失败轮次调用，高度图读已加载区块，成本可控。
     */
    private BlockPos findVisibleTreeColumn(int maxRadius) {
        if (!(npc.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        BlockPos center = npc.blockPosition();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dx = -maxRadius; dx <= maxRadius; dx += 2) {
            for (int dz = -maxRadius; dz <= maxRadius; dz += 2) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                if (serverLevel.getChunkIfLoaded(x >> 4, z >> 4) == null) {
                    continue;
                }
                int topY = serverLevel.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                if (topY <= serverLevel.getMinY() + 4) {
                    continue;
                }
                boolean treeTop = false;
                for (int dy = 1; dy <= 3 && !treeTop; dy++) {
                    net.minecraft.world.level.block.state.BlockState st =
                            serverLevel.getBlockState(new BlockPos(x, topY - dy, z));
                    treeTop = st.is(net.minecraft.tags.BlockTags.LOGS)
                            || st.is(net.minecraft.tags.BlockTags.LEAVES);
                }
                if (!treeTop) {
                    continue;
                }
                BlockPos cand = new BlockPos(x, topY, z);
                double sq = center.distSqr(cand);
                if (sq < bestSq) {
                    bestSq = sq;
                    best = cand;
                }
            }
        }
        return best;
    }

    /**
     * 找资源失败时的扩围移动（第三轮修复 E 后半）：第 n 次失败就走得更远，
     * 并且优先挑比当前位置更低的方向——长跑实测 NPC 在没树的山顶上方圆 8 格
     * 反复打转、采集放弃 19 次；树一般长在山坡和谷地，下山找树才是玩家的直觉。
     *
     * <p>8 个方向各采样一次目标列的地表高度（MOTION_BLOCKING_NO_LEAVES，
     * 和规划器判"在不在地下"用的是同一张高度图），选最低的方向下 goto；
     * 所有方向都走不通时退回原来的小范围 wander()。
     */
    private void escalateSearch() {
        int[] distances = {8, 16, 24, 32, 48};
        BlockPos me = npc.blockPosition();
        int currentY = me.getY();
        // 未加载/未生成区块的高度图会返回世界底线值（-64）：不排除的话它永远"最低"，
        // NPC 会一路朝没生成的地形狂奔（短验实测 10 分钟跑出 100 多格还没找到树）。
        int invalidY = npc.level().getMinY() + 4;
        // 【第四十六轮】罗盘收敛（恶地局定因）：旧逻辑只有"下坡首跳 + 惯性保持/
        // 中断转 45°"，在盆地里"走得动"会被惯性判为方向可行、绕着盆地空转，
        // 40 分钟活动范围没出出生点 ±120 格（航向角一路累加到 3465°）。
        // 改：盲跳满 6 跳仍无果，强制把航向换到"累计派跳最少的扇区"（扇区记账
        // 跨目标保留、找到资源才清零）——8 个扇区轮着搜，最多 48 跳必扫完一圈，
        // 杜绝在同一片空地里重复兜圈。下坡首跳与惯性仍管前 6 跳的玩家式直觉。
        if (gatherEscalateHops >= 6) {
            int bestSector = 0;
            for (int s = 1; s < 8; s++) {
                if (gatherSectorHops[s] < gatherSectorHops[bestSector]) {
                    bestSector = s;
                }
            }
            gatherSearchAngle = bestSector * Math.PI / 4.0D;
            gatherSearchAnchor = null;
            gatherEscalateHops = 0;
            LOG.info("[AIV][GOAL] " + npc.npcName() + " search-compass | 扩围盲跳 6 次无果，"
                    + "强制换向最少搜过扇区 heading=" + (bestSector * 45) + "° sectorHops="
                    + java.util.Arrays.toString(gatherSectorHops));
        }
        // 方向惯性（fix18）：先结算上一跳的实际水平位移——走够 8 格说明这个
        // 方向可行，保持航向并升一档走更远；没走动（路径中断/目标不可站立）
        // 就把航向转 45° 换方向。玩家找树就是这么干的：认准一个方向走到底，
        // 而不是每分钟重挑一次"最低处"在盆地里兜圈。
        if (gatherSearchAnchor != null && gatherSearchAngle >= 0) {
            double movedX = me.getX() - gatherSearchAnchor.getX();
            double movedZ = me.getZ() - gatherSearchAnchor.getZ();
            if (Math.sqrt(movedX * movedX + movedZ * movedZ) >= 8) {
                gatherSearchTier = Math.min(gatherSearchTier + 1, distances.length - 1);
            } else {
                gatherSearchAngle += Math.PI / 4.0D;
            }
        }
        if (gatherSearchAngle < 0) {
            // 首次扩围没有航向：沿用旧规矩，8 个方向采样地表、挑最低的起步。
            int probe = distances[gatherSearchTier];
            int bestSurfaceY = Integer.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4.0D;
                int tx = (int) Math.floor(npc.getX() + Math.cos(angle) * probe);
                int tz = (int) Math.floor(npc.getZ() + Math.sin(angle) * probe);
                int surfaceY = npc.level().getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, tx, tz);
                if (surfaceY <= invalidY) {
                    continue;
                }
                if (surfaceY < bestSurfaceY) {
                    bestSurfaceY = surfaceY;
                    gatherSearchAngle = angle;
                }
            }
            if (gatherSearchAngle < 0) {
                wander();
                return;
            }
        }
        // 沿航向前进：正前方目标列未生成（高度图无效）时依次转 45° 找可用方向，
        // 采纳的方向成为新航向（不然下一步的位移结算会把航向搅乱）。
        int distance = distances[gatherSearchTier];
        for (int tries = 0; tries < 8; tries++) {
            double angle = gatherSearchAngle + tries * Math.PI / 4.0D;
            int tx = (int) Math.floor(npc.getX() + Math.cos(angle) * distance);
            int tz = (int) Math.floor(npc.getZ() + Math.sin(angle) * distance);
            int surfaceY = npc.level().getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, tx, tz);
            if (surfaceY <= invalidY) {
                continue;
            }
            gatherSearchAngle = angle;
            gatherSearchAnchor = me;
            JsonObject params = new JsonObject();
            params.addProperty("x", npc.getX() + Math.cos(angle) * distance);
            params.addProperty("z", npc.getZ() + Math.sin(angle) * distance);
            if (skills.run(new Intent("", "goto", params), "目标")) {
                // 【第四十六轮】扇区记账：这一跳派给了哪个罗盘扇区。
                gatherEscalateHops++;
                double norm = ((angle % (Math.PI * 2.0D)) + Math.PI * 2.0D) % (Math.PI * 2.0D);
                gatherSectorHops[(int) Math.round(norm / (Math.PI / 4.0D)) % 8]++;
                LOG.info("[AIV][GOAL] " + npc.npcName() + " 找不到 " + targetId
                        + "，扩围找资源 | 第" + attempts + "次 距离=" + distance
                        + " 航向=" + Math.round(Math.toDegrees(angle)) + "° 档位=" + gatherSearchTier
                        + " 当前Y=" + currentY + " 目标方向地表Y=" + surfaceY);
                return;
            }
            break;
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
            // 材料之间会互相消耗：补木棍要吃掉木板，补完回头看木板又不够了。
            // 只按顺序对一遍就扣料，木镐这种"3 木板+2 木棍"的配方永远差一块木板，
            // 再被 craftOne 的失败回滚还原成 1 根原木，规划器只能无限重试（实测连续失败 7+ 次）。
            // 改为整表复核：任何一次补料后都从头再对，直到一整轮都不用补，才算同时够数。
            for (int pass = 0; pass < 8; pass++) {
                boolean craftedAny = false;
                for (java.util.Map.Entry<org.bukkit.inventory.RecipeChoice, Integer> entry : required.entrySet()) {
                    int need = entry.getValue();
                    LOG.info("[AIV][GOAL-DEBUG] craftFromRecipe item=" + itemPath + " depth=" + depth
                            + " 需要 " + need + "×" + describeChoice(entry.getKey())
                            + " 现有=" + countMatching(entry.getKey()));
                    int guard = 0;
                    while (countMatching(entry.getKey()) < need) {
                        if (++guard > 16) {
                            return "补料多次仍凑不齐：" + describeChoice(entry.getKey());
                        }
                        String error = craftMissing(entry.getKey(), depth + 1, visiting);
                        if (error != null) {
                            LOG.info("[AIV][GOAL-DEBUG] craftFromRecipe item=" + itemPath + " depth=" + depth
                                    + " craftMissing 返回 " + error);
                            return error;
                        }
                        craftedAny = true;
                    }
                }
                if (!craftedAny) {
                    break;
                }
                if (pass == 7) {
                    return "材料互相消耗，凑不齐：" + itemPath;
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
    /**
     * 【fix71·D5】矿道插火把：规格"只插矿道/矿洞、间隔 8~11、省着用"。
     * 只在 dig 目标的技能空闲间隙执行（不打断掘进）：脚下真黑（块光 ≤2，
     * 有光/贴岩浆不插）且立体 8 格内没有既有火把时，在脚格插一支
     * （原版口径：敌对怪只在块光 0 生成，间距 8 已足够压住整段矿道）。
     */
    private void maybePlaceTorch() {
        if (++torchTickCounter % 10 != 0 || !"idle".equals(skills.currentSkill())) {
            return;
        }
        if (countItem("torch") <= 0) {
            return;
        }
        net.minecraft.core.BlockPos feet = npc.blockPosition();
        // 只插地下：BLOCK 光照在地表白天也是 0，单靠光照会在地表乱插；
        // 先按高度表判地下（与规划层 underground() 同口径），再看块光。
        int surface = npc.level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                feet.getX(), feet.getZ());
        if (feet.getY() >= surface - 3) {
            return;
        }
        int light = npc.level().getBrightness(
                net.minecraft.world.level.LightLayer.BLOCK, feet);
        if (light > 2) {
            return;
        }
        for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(
                feet.offset(-8, -3, -8), feet.offset(8, 3, 8))) {
            var s = npc.level().getBlockState(p);
            if (s.is(net.minecraft.world.level.block.Blocks.TORCH)
                    || s.is(net.minecraft.world.level.block.Blocks.WALL_TORCH)) {
                return;
            }
        }
        var feetState = npc.level().getBlockState(feet);
        if (!feetState.isAir() && !feetState.canBeReplaced()) {
            return;
        }
        net.minecraft.core.BlockPos below = feet.below();
        if (npc.level().getBlockState(below)
                .getCollisionShape(npc.level(), below).isEmpty()) {
            return;
        }
        if (!consume("torch", 1)) {
            return;
        }
        npc.level().setBlockAndUpdate(feet,
                net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState());
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 插火把 | pos=" + feet.toShortString()
                + " 光照=" + light + " 余量=" + countItem("torch"));
    }

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
            recordSeenOre();
            finish("挖矿放弃：没有可用的镐，先补工具");
            return;
        }
        // 【第十七轮】有镐但级别不够同样当场收场：钻石/金/红石/绿宝石矿必须
        // 铁镐以上，铁/青金/铜矿必须石镐以上——长跑#6d 实测手里只剩石镐时
        // 旧版照常下发 mine 技能，身体层对着裸露钻石矿 mine-no-tool 刷 159 次、
        // 耗约 30 秒才靠"12 次无进展"放弃，矿也凉了。这里按矿种查镐，
        // 不够格立刻结束目标交回规划器补铁镐，人还在矿区附近，补完能再扫到。
        if (!hasHarvestPickaxe(oreTarget.block())) {
            recordSeenOre();
            finish("挖矿放弃：没有能挖 " + oreTarget.block() + " 的镐（级别不够），先补工具");
            return;
        }
        // 【第十八轮】石镐主力（Fong 工具分级口径）：除"挖石头"本身（那是补圆石
        // 的手段）外，任何挖矿目标开工/续工前必须有可用石镐——掘进段的普通
        // 石头/深板岩全靠它烧。石镐断了若不收场，身体层会自动拿铁镐顶上，
        // 一条矿道就能把铁镐烧掉大半（fix24 只改了选工具顺序，库存断供时
        // 顺序无从谈起）。当场收场交回规划器：它会用背包圆石当场补做石镐。
        if (!"stone".equals(oreTarget.block()) && !hasUsableStonePick()) {
            finish("挖矿放弃：石镐耗尽，先补石镐（石镐主力，铁镐只留给必需矿）");
            return;
        }
        // 上一条矿道如果挖空了，下一轮掘进前要先换位置（见下方步骤 3 前的处理）。
        noteStripOutcome();
        // 【fix71·D5】矿道照明：技能空闲间隙顺手插火把（只在真黑、8 格内无火把时）。
        maybePlaceTorch();
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
                stoneStripFails = 0;
            }
        }

        // 1) 半径 16 格内有裸露的目标矿就先去挖（Fong 02:53 探矿规格）：
        //    探测范围 8 → 16；仍然只认裸露矿（isExposed），全埋矿不入选、不透视。
        //    够不着的矿（洞顶/墙后/低处）由 mine 技能内部的够矿求解接手，
        //    这里只负责"看见并下单"。扫描节流与缓存见 exposedOreTarget()。
        BlockPos exposed = exposedOreTarget();
        if (exposed != null) {
            JsonObject params = new JsonObject();
            params.addProperty("block", oreTarget.block());
            params.addProperty("radius", 16);
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

        // 资源层钳制（fix17，超平坦场景实测教训）：oreTarget 的层位是按正常世界
        // 地表（≈Y60）定的绝对值。石头写的是 Y=50——在地表 Y=-30 的超平坦世界里，
        // 挖石头会先 ascend 爬到 Y=51，在空中一路搭柱、最后栖在柱顶，
        // descend/strip 全部挖不动，目标层只能靠看门狗收场；铁层 Y=16 同理
        // 会爬到 Y=17。正常世界里这条也会把洞里挖石头的人拽上三十多层。
        // 修法：①石头到处都是，层位就取当前深度，永不为它赶路；
        // ②其余资源的层位钳到本地地表以下（min(层位, 地表-1)），
        // 层位高于地表时就在当前深度掘进，裸露矿由步骤 1 先行接手。
        int surfaceY = npc.level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                npc.blockPosition().getX(), npc.blockPosition().getZ());
        int layerY = "stone".equals(oreTarget.block())
                ? npc.blockPosition().getY()
                : Math.min(oreTarget.y(), surfaceY - 1);

        // 2) 深度不够：阶梯下挖到资源层。挖矿途中会顺手捡到路上暴露的煤/铁。
        if (npc.blockPosition().getY() > layerY + 3) {
            JsonObject params = new JsonObject();
            params.addProperty("y", layerY);
            if (skills.run(new Intent("", "descend", params), "目标")) {
                attempts = 0;
                return;
            }
        }

        // 2b) 资源层在头顶（例如刚在 Y=16 挖完铁，现在要去 Y=48 挖煤）：
        // 旧逻辑只会往下挖，会一直在 Y=16 水平掘进找煤，一整个目标周期什么都挖不到。
        if (npc.blockPosition().getY() < layerY - 3) {
            JsonObject params = new JsonObject();
            params.addProperty("y", layerY + 1);
            if (skills.run(new Intent("", "ascend", params), "目标")) {
                attempts = 0;
                return;
            }
        }

        // 【第四十七轮】沼泽式地表无石兜底（沼泽局实证：strip 41 次圆石恒 0）：
        // 目标是石头、strip 已连败 ≥2 次、人还在地表附近时，水平掘进永远穿不过
        // 土层——改原地向下挖 6 格穿土找石（玩家解法）。石头的层位口径取当前
        // 深度，下挖后自动跟随，strip 在石层里就能出圆石；计数靠 noteStripOutcome
        // 记账、出任何进度即清零。下挖途中遇流体 descend 自有防护，失败了下一轮
        // 还在地表附近会再触发、逐次换位下探（relocate 换区也在并行）。
        if ("stone".equals(oreTarget.block()) && stoneStripFails >= 2
                && npc.blockPosition().getY() >= surfaceY - 3) {
            JsonObject params = new JsonObject();
            params.addProperty("y", npc.blockPosition().getY() - 6);
            if (skills.run(new Intent("", "descend", params), "目标")) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " dig-down-for-stone | strip 连败 "
                        + stoneStripFails + " 次，地表无石，改为向下挖穿土层找石");
                stoneStripFails = 0;
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
            // 【第四十七轮】挖石头连败单独记账，给 tickDig 的下挖取石兜底当触发量。
            if (oreTarget != null && "stone".equals(oreTarget.block())) {
                stoneStripFails++;
            }
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

    /**
     * 探矿入口（Fong 02:53 规格）：返回半径 16 内最近的裸露目标矿，没有返回 null。
     *
     * <p>节流：全扫一次要看 33×33×13 ≈ 1.4 万格，只允许在技能空闲的决策点、
     * 且距上次全扫至少 4 次决策时执行；间隔内先用缓存——目标格做单格复核
     * （方块还是目标矿变种、仍然裸露、仍在 17 格内），两次 getBlockState 就能判。
     * 缓存失效又赶上节流期就返回 null，本轮走 descend/strip 老路，几秒后再扫，
     * 不会因为等扫描把挖矿目标拖超时（goal-timeout 口径不变）。
     */
    private BlockPos exposedOreTarget() {
        if (oreScanCachePos != null && oreTarget.block().equals(oreScanCacheBlock)) {
            String path = BuiltInRegistries.BLOCK.getKey(
                    npc.level().getBlockState(oreScanCachePos).getBlock()).getPath();
            boolean variantOk = path.equals(oreTarget.block())
                    || path.equals("deepslate_" + oreTarget.block());
            if (variantOk && isExposed(oreScanCachePos)
                    && npc.blockPosition().distSqr(oreScanCachePos) <= 17.0D * 17.0D) {
                return oreScanCachePos;
            }
            oreScanCachePos = null;
        }
        if (oreScanCooldown > 0) {
            oreScanCooldown--;
            return null;
        }
        oreScanCooldown = 4;
        BlockPos found = scanExposedOre(oreTarget.block(), 16);
        oreScanCacheBlock = oreTarget.block();
        oreScanCachePos = found;
        if (found != null) {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " ore-scan | found=" + lastOreScanFound
                    + " radius=16 best=" + found.toShortString() + " block=" + oreTarget.block());
        }
        return found;
    }

    /** 全扫半径内的裸露目标矿（含深板岩变种）：返回最近的，并把发现总数记进 lastOreScanFound。 */
    private BlockPos scanExposedOre(String expectedPath, int radius) {
        BlockPos center = npc.blockPosition();
        Set<String> variants = new java.util.HashSet<>();
        variants.add(expectedPath);
        if (expectedPath.endsWith("_ore") && !expectedPath.startsWith("deepslate_")) {
            variants.add("deepslate_" + expectedPath);
        }
        BlockPos best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        int found = 0;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -6, -radius),
                center.offset(radius, 6, radius))) {
            String path = BuiltInRegistries.BLOCK.getKey(npc.level().getBlockState(pos).getBlock()).getPath();
            if (!variants.contains(path)) {
                continue;
            }
            // 不透视口径不变：全埋矿（六面无空气）不入选，哪怕它就在墙后一格。
            if (!isExposed(pos)) {
                continue;
            }
            found++;
            double distanceSq = center.distSqr(pos);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = pos.immutable();
            }
        }
        lastOreScanFound = found;
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
        placedFurnacePos = spot;
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
        reclaimFurnaceBlock();
    }

    /**
     * 【第四十一轮·工作站回收】冶炼收尾时，把自己放下的那台熔炉挖回背包
     * （Fong 04:11：工作站随身带、用完挖回收，别再走到哪扔到哪、一局重造
     * 好几台）。只收 placedFurnacePos 登记的那台；复用世界里现成的熔炉
     * 不动。放弃条件（防丢物/防死锁）：炉内还有东西没收干净（背包满）→
     * 不拆、留着下次复用；没有可用镐子 → 不拆、记一行日志。
     */
    private void reclaimFurnaceBlock() {
        if (furnacePos == null || placedFurnacePos == null
                || !furnacePos.equals(placedFurnacePos)) {
            return;
        }
        if (!npc.level().getBlockState(furnacePos).is(Blocks.FURNACE)) {
            // 炉子已经不在了（被怪炸了/被别的流程清了）：登记作废，别再惦记。
            placedFurnacePos = null;
            return;
        }
        if (!(npc.level().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity furnace)) {
            return;
        }
        for (int slot = 0; slot < 3; slot++) {
            if (!furnace.getItem(slot).isEmpty()) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " 工作站回收跳过：炉内还有物品"
                        + " pos=" + furnacePos.toShortString());
                return;
            }
        }
        int before = countItem("furnace");
        if (!npc.mineWithBestTool(furnacePos)) {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 工作站回收失败：没有可用镐子，熔炉留在原地"
                    + " pos=" + furnacePos.toShortString());
            return;
        }
        // 掉落物同步收回：身体的自动拾取是逐 tick 的，这里直接把脚边的
        // 熔炉掉落物搬进背包（与 takeFurnaceOutput 的主动搬运同一口径）。
        int collected = 0;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(furnacePos).inflate(2.0D);
        for (net.minecraft.world.entity.item.ItemEntity entity : npc.level().getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class, box,
                e -> "furnace".equals(pathOf(e.getItem())))) {
            collected += entity.getItem().getCount();
            entity.discard();
        }
        if (collected > 0) {
            give("furnace", collected);
        }
        placedFurnacePos = null;
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 工作站回收：熔炉已挖回背包"
                + " | 背包熔炉 " + before + "→" + countItem("furnace")
                + " pos=" + furnacePos.toShortString());
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
            case "baked_potato" -> "potato";
            case "glass" -> "sand";
            case "stone" -> "cobblestone";
            default -> null;
        };
    }

    // ---------------------------------------------------------------- 种田（fix49·D11）

    /**
     * 种田目标（Fong D11）：开田（establish）与维护（tend）共用一个目标类型，
     * 按 FarmMemory 账本现状分流。玩家口径的闭环：攒种（打草 1/8 掉种子，
     * Wiki）→ 靠水选址（湿润=水水平 4 格内同层或高 1 层）→ 锄地播种 →
     * 等熟 → 收获后立刻补种；维护访 = 收获→走过田面拾取→补种/补锄→
     * （口粮紧且有骨粉时）催熟一轮再复查。锄/种/骨粉走直改方块+代码内
     * 原版规则校验（placeFurnace 先例：伪玩家无客户端不走 useItemOn），
     * 收获走 mineBareHand 真掉落；留种靠"播种不耗总存量（只入土）+
     * 消费端地板（生存层进食守卫/规划层烤土豆只烤富余）"两头守。
     */
    private void tickFarm() {
        FarmMemory.bindWorld(npc.level());
        FarmMemory.Farm farm = FarmMemory.farm(npc.npcName());
        boolean tend = farm != null && !farm.cells().isEmpty() && farmHasLiveCells(farm)
                && !"establish".equals(farmMode);
        if (tend) {
            tickFarmTend(farm);
        } else {
            tickFarmEstablish();
        }
    }

    /** 田格里还有没有能救的土（耕地或可重锄的土）：全变成水/石才算田没了、需要重选址。 */
    private boolean farmHasLiveCells(FarmMemory.Farm farm) {
        for (FarmMemory.Cell cell : farm.cells()) {
            BlockState state = npc.level().getBlockState(cell.soil());
            if (state.is(Blocks.FARMLAND) || state.is(Blocks.GRASS_BLOCK)
                    || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)) {
                return true;
            }
        }
        return false;
    }

    /** 播种材料总量（小麦种子 + 胡萝卜 + 土豆，三者都能下地）。 */
    private int plantStockTotal() {
        return countItem("wheat_seeds") + countItem("carrot") + countItem("potato");
    }

    private void tickFarmEstablish() {
        switch (farmPhase) {
            case 0 -> {
                status = "种田：开田准备";
                if (findHoeSlot() < 0) {
                    finish("开田放弃：没有可用的锄头");
                    return;
                }
                farmPhase = plantStockTotal() >= FARM_PLOT_CELLS ? 2 : 1;
            }
            case 1 -> {
                // 攒种子：打草 1/8 掉小麦种子（Wiki），攒够一田（8）再开田；
                // 草就长在地表，mine 技能半径内有草就打、没有就扩围换位。
                // 【fix52】放弃口径修正：旧版 attempts 把"附近没草"的轮次累计
                // 6 次就放弃——可种子期望要打 32~64 株草、草是一片片长的，
                // 每换一片草地就白扣一次，实证 72 株草/2 种子时被误杀放弃。
                // 改：技能成功启动（=附近有草）即清零 attempts；只有连续 12 个
                // "没草"轮次（期间扩围换位 12 次仍无草）才判这片地真没草。
                // 半径 8→24：与选址半径同量级，少走冤枉路。
                status = "种田：打草攒种子 " + plantStockTotal() + "/" + FARM_PLOT_CELLS;
                if (plantStockTotal() >= FARM_PLOT_CELLS) {
                    farmPhase = 2;
                    return;
                }
                if (!"idle".equals(skills.currentSkill())) {
                    return;
                }
                JsonObject grass = new JsonObject();
                grass.addProperty("block", "short_grass");
                grass.addProperty("radius", 24);
                if (skills.run(new Intent("", "mine", grass), "目标")) {
                    attempts = 0;
                    return;
                }
                JsonObject tall = new JsonObject();
                tall.addProperty("block", "tall_grass");
                tall.addProperty("radius", 24);
                if (skills.run(new Intent("", "mine", tall), "目标")) {
                    attempts = 0;
                    return;
                }
                attempts++;
                if (attempts >= 12) {
                    if (plantStockTotal() >= 4) {
                        LOG.info("[AIV][GOAL] " + npc.npcName()
                                + " [FARM] 种子只攒到 " + plantStockTotal() + "，开小块田");
                        farmPhase = 2;
                    } else {
                        finish("开田放弃：附近攒不到种子（打草） stock=" + plantStockTotal());
                    }
                    return;
                }
                escalateSearch();
            }
            case 2 -> {
                status = "种田：选址（靠水平地）";
                if (farmSiteCells == null) {
                    farmSiteCells = findFarmSite();
                    if (farmSiteCells == null) {
                        farmSiteAttempts++;
                        if (farmSiteAttempts >= 4) {
                            finish("开田放弃：附近找不到靠水的平地");
                            return;
                        }
                        if ("idle".equals(skills.currentSkill())) {
                            escalateSearch();
                        }
                        return;
                    }
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 选址 | cells="
                            + farmSiteCells.size() + " anchor=" + farmSiteCells.get(0).toShortString());
                }
                farmPhase = 3;
            }
            case 3 -> {
                status = "种田：锄地播种";
                if (!farmQueueBuilt) {
                    buildEstablishQueue();
                    farmQueueBuilt = true;
                }
                if (!processFarmActions()) {
                    return;
                }
                farmPhase = 4;
            }
            case 4 -> {
                // 落盘：以活方块为准登记（锄成耕地的格才入账，种上作物的记作物）。
                java.util.List<FarmMemory.Cell> cells = new java.util.ArrayList<>();
                int planted = 0;
                for (BlockPos soil : farmSiteCells) {
                    BlockState soilState = npc.level().getBlockState(soil);
                    if (!soilState.is(Blocks.FARMLAND)) {
                        continue;
                    }
                    String crop = FarmMemory.cropOf(
                            npc.level().getBlockState(soil.above()).getBlock());
                    cells.add(new FarmMemory.Cell(soil.getX(), soil.getY(), soil.getZ(),
                            crop == null ? "" : crop));
                    if (crop != null) {
                        planted++;
                    }
                }
                if (cells.isEmpty()) {
                    finish("开田失败：一格耕地都没锄成");
                    return;
                }
                BlockPos anchor = farmSiteCells.get(0);
                FarmMemory.save(npc.npcName(), new FarmMemory.Farm(FarmMemory.worldKey(),
                        anchor.getX(), anchor.getY(), anchor.getZ(),
                        System.currentTimeMillis(), cells));
                InventorySnapshot.log(npc, "farm", "establish", true);
                finish("开田完成 | 格=" + cells.size() + " 已种=" + planted
                        + " 种子余=" + plantStockTotal()
                        + " @ " + anchor.toShortString());
            }
            default -> finish("开田失败：未知阶段 " + farmPhase);
        }
    }

    private void tickFarmTend(FarmMemory.Farm farm) {
        switch (farmPhase) {
            case 0 -> {
                status = "种田：回田";
                BlockPos anchor = farm.anchor();
                double distSq = npc.blockPosition().distSqr(anchor);
                if (distSq > 100.0D && "idle".equals(skills.currentSkill())) {
                    JsonObject go = new JsonObject();
                    go.addProperty("x", anchor.getX() + 0.5D);
                    go.addProperty("z", anchor.getZ() + 0.5D);
                    if (skills.run(new Intent("", "goto", go), "目标")) {
                        return;
                    }
                }
                if (distSq > 16.0D && !"idle".equals(skills.currentSkill())) {
                    return;
                }
                // 收获队列：只收成熟的（age=7；未熟收只有 1 粒种子、没小麦，Wiki）。
                farmActions.clear();
                for (FarmMemory.Cell cell : farm.cells()) {
                    BlockState cropState = npc.level().getBlockState(cell.soil().above());
                    String crop = FarmMemory.cropOf(cropState.getBlock());
                    if (crop != null && FarmMemory.cropAge(cropState) >= 7) {
                        farmActions.add(new FarmAction("harvest", cell.soil().above(), crop));
                    }
                }
                farmPhase = farmActions.isEmpty() ? 2 : 1;
            }
            case 1 -> {
                status = "种田：收获 " + farmActions.size() + " 格";
                if (!processFarmActions()) {
                    return;
                }
                farmPickupIndex = 0;
                farmPhase = 2;
            }
            case 2 -> {
                // 拾取：挨个走过刚收获的土格收掉落（平走过田面不踩坏，
                // Wiki 口径踩坏=从 1 格以上落下；田是平地、同层步行安全）。
                status = "种田：拾取掉落 " + farmPickupIndex + "/" + farmHarvestedSoils.size();
                if (farmPickupIndex >= farmHarvestedSoils.size()) {
                    farmPhase = 3;
                    return;
                }
                if (!"idle".equals(skills.currentSkill())) {
                    return;
                }
                BlockPos cell = farmHarvestedSoils.get(farmPickupIndex);
                if (farmInReach(cell)) {
                    farmPickupIndex++;
                    farmActionStuck = 0;
                    farmLastActionKey = "";
                    return;
                }
                String key = "pickup@" + cell.toShortString();
                if (key.equals(farmLastActionKey) && ++farmActionStuck > 4) {
                    farmPickupIndex++;
                    farmActionStuck = 0;
                    farmLastActionKey = "";
                    return;
                }
                farmLastActionKey = key;
                JsonObject go = new JsonObject();
                go.addProperty("x", cell.getX() + 0.5D);
                go.addProperty("z", cell.getZ() + 0.5D);
                skills.run(new Intent("", "goto", go), "目标");
            }
            case 3 -> {
                status = "种田：补种补锄";
                if (!farmQueueBuilt) {
                    buildTendQueue(farm);
                    farmQueueBuilt = true;
                }
                farmPhase = 4;
            }
            case 4 -> {
                status = "种田：田间作业 " + farmActions.size() + " 项";
                if (!processFarmActions()) {
                    return;
                }
                if (farmBonemealQueued && !farmSecondPass) {
                    // 骨粉刚催过：回收获段复查一次（可能已有新熟的）。
                    farmSecondPass = true;
                    farmQueueBuilt = false;
                    farmPhase = 0;
                    return;
                }
                farmPhase = 5;
            }
            case 5 -> {
                // 更新账本：死格（土变成水/石等非土方块）销账，其余按活方块刷新作物。
                java.util.List<FarmMemory.Cell> cells = new java.util.ArrayList<>();
                for (FarmMemory.Cell cell : farm.cells()) {
                    BlockState soilState = npc.level().getBlockState(cell.soil());
                    if (!soilState.is(Blocks.FARMLAND) && !soilState.is(Blocks.GRASS_BLOCK)
                            && !soilState.is(Blocks.DIRT) && !soilState.is(Blocks.COARSE_DIRT)) {
                        continue;
                    }
                    String crop = FarmMemory.cropOf(
                            npc.level().getBlockState(cell.soil().above()).getBlock());
                    cells.add(new FarmMemory.Cell(cell.x(), cell.y(), cell.z(),
                            crop == null ? "" : crop));
                }
                FarmMemory.save(npc.npcName(), new FarmMemory.Farm(farm.world(),
                        farm.anchorX(), farm.anchorY(), farm.anchorZ(),
                        farm.establishedAt(), cells));
                FarmMemory.Scan scan = FarmMemory.scan(npc.level(), npc.npcName());
                InventorySnapshot.log(npc, "farm", "tend", true);
                int harvestDelta = (countItem("wheat") - farmBaseWheat)
                        + (countItem("carrot") - farmBaseCarrot)
                        + (countItem("potato") - farmBasePotato);
                // 【fix60】有动作被跳过且一点没收着 = 这趟白跑：如实报失败让规划退避，
                // 不许再用「完成」把 90 秒连派空转洗成正常（boot-farm17 实锤）。
                if (harvestDelta <= 0 && farmSkipped > 0) {
                    finish("农田维护失败：动作走不到位跳过=" + farmSkipped
                            + " | 收获Δ 小麦=" + (countItem("wheat") - farmBaseWheat)
                            + " 种子=" + (countItem("wheat_seeds") - farmBaseSeeds)
                            + " 胡萝卜=" + (countItem("carrot") - farmBaseCarrot)
                            + " 土豆=" + (countItem("potato") - farmBasePotato)
                            + " | 在田=" + scan.plantedTotal() + "（熟" + scan.mature()
                            + "/长" + scan.growing() + "） 播种存量=" + plantStockTotal());
                    return;
                }
                finish("农田维护完成 | 收获Δ 小麦=" + (countItem("wheat") - farmBaseWheat)
                        + " 种子=" + (countItem("wheat_seeds") - farmBaseSeeds)
                        + " 胡萝卜=" + (countItem("carrot") - farmBaseCarrot)
                        + " 土豆=" + (countItem("potato") - farmBasePotato)
                        + " | 在田=" + scan.plantedTotal() + "（熟" + scan.mature()
                        + "/长" + scan.growing() + "） 播种存量=" + plantStockTotal());
            }
            default -> finish("农田维护失败：未知阶段 " + farmPhase);
        }
    }

    /** 开田动作队列：每格先锄后种；种什么按当时存量模拟分配（见 chooseCropSim）。 */
    private void buildEstablishQueue() {
        farmActions.clear();
        int[] stock = {countItem("wheat_seeds"), countItem("carrot"), countItem("potato")};
        int[] planted = {0, 0, 0};
        for (BlockPos soil : farmSiteCells) {
            farmActions.add(new FarmAction("till", soil, ""));
            String crop = chooseCropSim(stock, planted);
            if (crop != null) {
                farmActions.add(new FarmAction("plant", soil, crop));
            }
        }
    }

    /** 维护动作队列：空地补种（优先原作物）、坏地补锄补种、口粮紧时骨粉催熟一轮。 */
    private void buildTendQueue(FarmMemory.Farm farm) {
        farmActions.clear();
        int[] stock = {countItem("wheat_seeds"), countItem("carrot"), countItem("potato")};
        int[] planted = {0, 0, 0};
        java.util.List<FarmAction> bonemealCandidates = new java.util.ArrayList<>();
        for (FarmMemory.Cell cell : farm.cells()) {
            BlockPos soil = cell.soil();
            BlockState soilState = npc.level().getBlockState(soil);
            BlockState cropState = npc.level().getBlockState(soil.above());
            String liveCrop = FarmMemory.cropOf(cropState.getBlock());
            if (liveCrop != null) {
                int idx = cropIndex(liveCrop);
                planted[idx]++;
                int age = FarmMemory.cropAge(cropState);
                if (age >= 0 && age < 7) {
                    bonemealCandidates.add(new FarmAction("bonemeal", soil.above(), liveCrop));
                }
                continue;
            }
            if (soilState.is(Blocks.FARMLAND)) {
                String crop = chooseCropSim(stock, planted,
                        cell.crop().isEmpty() ? null : cell.crop());
                if (crop != null) {
                    farmActions.add(new FarmAction("plant", soil, crop));
                }
            } else if (soilState.is(Blocks.GRASS_BLOCK) || soilState.is(Blocks.DIRT)
                    || soilState.is(Blocks.COARSE_DIRT)) {
                // 耕地干回泥土/被踩坏：补锄再补种（玩家常识，田不能就这么荒着）。
                farmActions.add(new FarmAction("till", soil, ""));
                String crop = chooseCropSim(stock, planted,
                        cell.crop().isEmpty() ? null : cell.crop());
                if (crop != null) {
                    farmActions.add(new FarmAction("plant", soil, crop));
                }
            }
            // 土变成水/石等：死格，不排动作，收尾时账本销账。
        }
        // 骨粉催熟（Wiki：+2~5 阶段）：口粮低于下矿线、包里有骨粉、田里有
        // 没熟的作物时才舍得用——骨粉也是打骷髅攒的，不许日常浪费。
        if (!farmBonemealQueued && foodNutrition() < 24 && countItem("bone_meal") > 0
                && !bonemealCandidates.isEmpty()) {
            bonemealCandidates.sort((a, b) -> Integer.compare(
                    FarmMemory.cropAge(npc.level().getBlockState(b.pos())),
                    FarmMemory.cropAge(npc.level().getBlockState(a.pos()))));
            int uses = Math.min(countItem("bone_meal"), Math.min(4, bonemealCandidates.size()));
            for (int i = 0; i < uses; i++) {
                farmActions.add(bonemealCandidates.get(i));
            }
            farmBonemealQueued = true;
            LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 骨粉催熟 | 目标格=" + uses
                    + " foodNutrition=" + foodNutrition());
        }
    }

    private static int cropIndex(String crop) {
        return switch (crop) {
            case "carrot" -> 1;
            case "potato" -> 2;
            default -> 0;
        };
    }

    /**
     * 按模拟存量给一格选作物（stock/planted 数组随选随扣，队列建完即弃）：
     * 稀有作物（胡萝卜/土豆，来源靠僵尸掉落/村庄）先各种 2 格繁殖起来，
     * 其余种小麦（主粮）；原作物优先（补种保持田面格局）。
     * 播种不耗总存量（种子只是入土），故这里不设地板门——地板守在
     * 消费端（吃/烤），见 SurvivalController 与规划层烤土豆步。
     */
    private String chooseCropSim(int[] stock, int[] planted) {
        return chooseCropSim(stock, planted, null);
    }

    private String chooseCropSim(int[] stock, int[] planted, String preferred) {
        if (preferred != null && stock[cropIndex(preferred)] > 0) {
            int idx = cropIndex(preferred);
            stock[idx]--;
            planted[idx]++;
            return preferred;
        }
        String[] order = {"carrot", "potato", "wheat"};
        for (String crop : order) {
            int idx = cropIndex(crop);
            if (stock[idx] <= 0) {
                continue;
            }
            if (!"wheat".equals(crop) && planted[idx] >= 2) {
                continue;
            }
            stock[idx]--;
            planted[idx]++;
            return crop;
        }
        // 稀有作物都已种满 2 格、或只剩小麦：有什么种什么。
        for (String crop : order) {
            int idx = cropIndex(crop);
            if (stock[idx] > 0) {
                stock[idx]--;
                planted[idx]++;
                return crop;
            }
        }
        return null;
    }

    /**
     * 逐个执行动作队列：技能在跑就等；到位就当场执行（动作是瞬时直改），
     * 走不到位先 goto 到田边格；同一动作反复走不到位超限就跳过它，
     * 不许一格田卡死整个目标（看门狗之外再加一道单格保险）。
     *
     * @return true = 队列已清空
     */
    private boolean processFarmActions() {
        while (!farmActions.isEmpty()) {
            FarmAction action = farmActions.get(0);
            if (!farmActionValid(action)) {
                farmActions.remove(0);
                farmActionStuck = 0;
                farmLastActionKey = "";
                continue;
            }
            if (!"idle".equals(skills.currentSkill())) {
                return false;
            }
            if (!farmInReach(actionReachPos(action))) {
                String key = action.kind() + "@" + action.pos().toShortString();
                if (key.equals(farmLastActionKey) && ++farmActionStuck > 6) {
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 动作跳过（反复走不到位） | "
                            + key);
                    farmSkipped++;
                    farmActions.remove(0);
                    farmActionStuck = 0;
                    farmLastActionKey = "";
                    continue;
                }
                farmLastActionKey = key;
                farmGotoStandFor(action);
                return false;
            }
            farmActionStuck = 0;
            farmLastActionKey = "";
            doFarmAction(action);
            farmActions.remove(0);
        }
        return true;
    }

    /** 动作的可达判定点：till/plant 看土格，harvest/bonemeal 看作物格。 */
    private BlockPos actionReachPos(FarmAction action) {
        return action.pos();
    }

    /** 动作执行前复核（方块可能在排队后被水流/怪/自己改掉，失效就丢）。 */
    private boolean farmActionValid(FarmAction action) {
        BlockState state = npc.level().getBlockState(action.pos());
        return switch (action.kind()) {
            case "till" -> state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                    || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT);
            case "plant" -> state.is(Blocks.FARMLAND)
                    && npc.level().getBlockState(action.pos().above()).isAir()
                    && countItem(FarmMemory.seedItemOf(action.crop())) > 0;
            case "harvest" -> {
                String crop = FarmMemory.cropOf(state.getBlock());
                yield crop != null && FarmMemory.cropAge(state) >= 7;
            }
            case "bonemeal" -> {
                String crop = FarmMemory.cropOf(state.getBlock());
                yield crop != null && FarmMemory.cropAge(state) < 7
                        && countItem("bone_meal") > 0;
            }
            default -> false;
        };
    }

    /** NPC 是否已在动作格的操作范围内（水平 3.2 格、落差 -1~+3）。 */
    private boolean farmInReach(BlockPos pos) {
        double dx = npc.getX() - (pos.getX() + 0.5D);
        double dz = npc.getZ() - (pos.getZ() + 0.5D);
        double dy = npc.getY() - pos.getY();
        return dx * dx + dz * dz <= 10.24D && dy >= -1.0D && dy <= 3.0D;
    }

    /**
     * 【fix65】建造够格（与种田 farmInReach 分开）：种田口径（水平 3.2 格、
     * 上够 1 格）是按作物在脚边设计的，套到砌筑上结构性够不着——屋内中心
     * 到角柱水平 dist²=12.5＞10.24、屋顶在脚上 3 层 dy=-3＜-1，火柴盒按
     * 原口径永远砌不完（boot-farm22/23 双实证：放置 0、逐格跳过）。
     * 建造口径按原版眼高 1.62＋手臂够格 ~4.5 格核算：水平 ≤3.6 格
     * （角柱 12.5≤12.96）、向上够 4 层（站屋内砌屋顶 dy=-3）、向下 2 层。
     */
    private boolean buildInReach(BlockPos pos) {
        double dx = npc.getX() - (pos.getX() + 0.5D);
        double dz = npc.getZ() - (pos.getZ() + 0.5D);
        double dy = npc.getY() - pos.getY();
        return dx * dx + dz * dz <= 12.96D && dy >= -4.0D && dy <= 2.0D;
    }

    /** 走到动作格旁边的落脚格（优先土格四邻的实心非田格；没有就直奔土格，goto 自己找落点）。 */
    private void farmGotoStandFor(FarmAction action) {
        BlockPos soil = ("harvest".equals(action.kind()) || "bonemeal".equals(action.kind()))
                ? action.pos().below() : action.pos();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dir : dirs) {
            BlockPos candidate = soil.offset(dir[0], 0, dir[1]);
            BlockState state = npc.level().getBlockState(candidate);
            if (state.is(Blocks.FARMLAND) || FarmMemory.cropOf(state.getBlock()) != null) {
                continue;
            }
            if (state.getCollisionShape(npc.level(), candidate).isEmpty()) {
                continue;
            }
            if (!npc.level().getBlockState(candidate.above()).getCollisionShape(
                    npc.level(), candidate.above()).isEmpty()) {
                continue;
            }
            double sq = npc.blockPosition().distSqr(candidate);
            if (sq < bestSq) {
                bestSq = sq;
                best = candidate;
            }
        }
        BlockPos target = best != null ? best : soil;
        // 【fix60】纯 A* goto 在贴水田边会只展开几个节点就无路（PathFinder 第六轮
        // 注释同款）：同一动作 goto 连败 ≥2 次后改派 travel（能挖/搭/爬的接近手段，
        // 回访线同款参数），别在原地把 7 格熟麦全跳过（boot-farm17 实锤 Δ=0）。
        if (farmActionStuck >= 2) {
            JsonObject tv = new JsonObject();
            tv.addProperty("x", target.getX() + 0.5D);
            tv.addProperty("y", (double) target.getY());
            tv.addProperty("z", target.getZ() + 0.5D);
            tv.addProperty("tolerance", 4.0D);
            if (skills.run(new Intent("", "travel", tv), "目标")) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] goto 连败改 travel 接近 | "
                        + target.toShortString());
                return;
            }
        }
        JsonObject go = new JsonObject();
        go.addProperty("x", target.getX() + 0.5D);
        go.addProperty("z", target.getZ() + 0.5D);
        skills.run(new Intent("", "goto", go), "目标");
    }

    /** 执行一个田间动作（瞬时；规则校验在 farmActionValid 与本方法内双保险）。 */
    private void doFarmAction(FarmAction action) {
        switch (action.kind()) {
            case "till" -> {
                // 土上方若有草，先清掉（顺手还能掉种子，1/8，Wiki）。
                BlockPos above = action.pos().above();
                Block aboveBlock = npc.level().getBlockState(above).getBlock();
                if (aboveBlock == Blocks.SHORT_GRASS || aboveBlock == Blocks.TALL_GRASS
                        || aboveBlock == Blocks.FERN || aboveBlock == Blocks.LARGE_FERN) {
                    npc.mineBareHand(above);
                }
                int moisture = farmHydrated(action.pos()) ? 7 : 0;
                npc.level().setBlockAndUpdate(action.pos(),
                        Blocks.FARMLAND.defaultBlockState().setValue(
                                net.minecraft.world.level.block.FarmBlock.MOISTURE, moisture));
                damageHoe();
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 锄地 | "
                        + action.pos().toShortString() + " moisture=" + moisture);
            }
            case "plant" -> {
                // 亮度 ≤7 原版种不下（Wiki：光照不足植株会掉落），露天白天恒满足。
                boolean lit = npc.level().canSeeSky(action.pos().above())
                        || npc.level().getMaxLocalRawBrightness(action.pos().above()) >= 8;
                if (!lit) {
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 播种跳过（光照不足） | "
                            + action.pos().toShortString());
                    return;
                }
                String seedItem = FarmMemory.seedItemOf(action.crop());
                if (!consume(seedItem, 1)) {
                    return;
                }
                Block cropBlock = FarmMemory.blockOfCrop(action.crop());
                npc.level().setBlockAndUpdate(action.pos().above(),
                        cropBlock.defaultBlockState());
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 播种 | crop=" + action.crop()
                        + " @ " + action.pos().toShortString());
            }
            case "harvest" -> {
                if (npc.mineBareHand(action.pos())) {
                    farmHarvestedSoils.add(action.pos().below());
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 收获 | crop=" + action.crop()
                            + " @ " + action.pos().toShortString());
                }
            }
            case "bonemeal" -> {
                BlockState state = npc.level().getBlockState(action.pos());
                int age = FarmMemory.cropAge(state);
                if (age < 0 || !consume("bone_meal", 1)) {
                    return;
                }
                int newAge = Math.min(7, age + 2 + npc.getRandom().nextInt(4));
                npc.level().setBlockAndUpdate(action.pos(), state.setValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_7,
                        newAge));
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 骨粉 | age " + age + "→" + newAge
                        + " @ " + action.pos().toShortString());
            }
            default -> {
            }
        }
    }

    /**
     * 选址（玩家口径）：地表草/泥土格、上方见天、水平 4 格内有水（湿润口径，
     * Wiki），成团 ≥5 格才算一块田，目标 8 格。只看已加载区块的地表，
     * 不为找田加载新区块（与找树同口径，非透视）。
     */
    private java.util.List<BlockPos> findFarmSite() {
        if (!(npc.level() instanceof ServerLevel level)) {
            return null;
        }
        BlockPos me = npc.blockPosition();
        java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
        for (int dx = -26; dx <= 26; dx++) {
            for (int dz = -26; dz <= 26; dz++) {
                int x = me.getX() + dx;
                int z = me.getZ() + dz;
                if (level.getChunkIfLoaded(x >> 4, z >> 4) == null) {
                    continue;
                }
                int topY = level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        x, z) - 1;
                BlockPos ground = new BlockPos(x, topY, z);
                BlockState state = level.getBlockState(ground);
                if (!state.is(Blocks.GRASS_BLOCK) && !state.is(Blocks.DIRT)) {
                    continue;
                }
                BlockState above = level.getBlockState(ground.above());
                if (!above.isAir() && !above.is(Blocks.SHORT_GRASS) && !above.is(Blocks.TALL_GRASS)
                        && !above.is(Blocks.FERN) && !above.is(Blocks.LARGE_FERN)
                        && !above.is(Blocks.SNOW)) {
                    continue;
                }
                if (!level.canSeeSky(ground.above())) {
                    continue;
                }
                if (!farmHydrated(ground)) {
                    continue;
                }
                candidates.add(ground);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(java.util.Comparator.comparingDouble(me::distSqr));
        BlockPos seed = candidates.get(0);
        java.util.List<BlockPos> cluster = new java.util.ArrayList<>();
        for (BlockPos candidate : candidates) {
            int chebyshev = Math.max(Math.abs(candidate.getX() - seed.getX()),
                    Math.abs(candidate.getZ() - seed.getZ()));
            if (chebyshev <= 3 && Math.abs(candidate.getY() - seed.getY()) <= 1) {
                cluster.add(candidate);
                if (cluster.size() >= FARM_PLOT_CELLS) {
                    break;
                }
            }
        }
        return cluster.size() >= 5 ? cluster : null;
    }

    /** 湿润判定（Wiki）：水平 4 格内、同层或高 1 层有水。 */
    private boolean farmHydrated(BlockPos soil) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    if (npc.level().getBlockState(soil.offset(dx, dy, dz)).is(Blocks.WATER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 背包里第一把可用锄头的槽位（剩 1 点耐久的按没有算，口径同镐）。 */
    private int findHoeSlot() {
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !pathOf(stack).endsWith("_hoe")) {
                continue;
            }
            if (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage() - 1) {
                return slot;
            }
        }
        return -1;
    }

    /** 锄一次地记 1 点耐久（原版锄地消耗），坏了按 [TOOL-BREAK] 记账。 */
    private void damageHoe() {
        int slot = findHoeSlot();
        if (slot < 0) {
            return;
        }
        ItemStack stack = npc.getInventory().getItem(slot);
        if (!stack.isDamageableItem()) {
            return;
        }
        int damage = stack.getDamageValue() + 1;
        if (damage >= stack.getMaxDamage()) {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " [TOOL-BREAK] " + pathOf(stack)
                    + " 锄地用坏 slot=" + slot);
            npc.getInventory().setItem(slot, ItemStack.EMPTY);
        } else {
            stack.setDamageValue(damage);
        }
    }

    // ---------------------------------------------------------------- 落脚点（fix51·D2）

    /** 小屋平面半径：外 5×5（dx,dz ∈ -2..2），内空 3×3，墙高 3、平顶。 */
    private static final int BASE_HALF = 2;
    /** 砌筑材料总需求上限估算：补地 ≤25 + 墙 46（留门洞 2）+ 顶 25 ≈ 96。 */
    private static final int BASE_MATERIAL_NEED = 80;

    private int basePhase;
    /** 待砌格队列（自下而上：补地→墙三层→屋顶）。 */
    private final java.util.List<BlockPos> baseQueue = new java.util.ArrayList<>();
    /** 选中的中心列（地面顶格）；null=未选址。 */
    private BlockPos baseCenter;
    private int baseActionStuck;
    private String baseLastActionKey = "";
    private int basePlacedCount;
    private int baseTotalCells;
    private boolean baseQueueBuilt;

    /**
     * 建落脚点：选址 → 砌筑队列 → 完工自审 → 落盘 BaseMemory。
     * 放置走 setBlockAndUpdate 直改 + 代码内校验（placeFurnace/种田同先例）：
     * 目标格必须是空气/可替换植被、材料从背包真扣。
     * 全部砌筑从屋内中心一个站位可达（最远角格水平 2.83、屋顶 dy=3，
     * 在 farmInReach 同口径内），不需要脚手架。
     */
    private void tickBase() {
        switch (basePhase) {
            case 0 -> {
                status = "落脚点：选址";
                if (baseCenter == null) {
                    baseCenter = findBaseSite();
                    if (baseCenter == null) {
                        finish("建屋放弃：附近没有合格地块（5×5 平整、无遮挡）");
                        return;
                    }
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [BASE] 选址 | center="
                            + baseCenter.toShortString() + "（评分：近水/近田/近树）");
                }
                if (!baseQueueBuilt) {
                    buildBaseQueue();
                    baseQueueBuilt = true;
                    baseTotalCells = baseQueue.size();
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [BASE] 砌筑队列 | 格数="
                            + baseTotalCells + " 建材存量=" + countBuildMaterial());
                }
                basePhase = 1;
            }
            case 1 -> {
                status = "落脚点：砌筑 " + basePlacedCount + "/" + baseTotalCells;
                if (processBaseQueue()) {
                    basePhase = 2;
                }
            }
            case 2 -> {
                // 完工自审：逐格复扫——墙/顶/补地格里是实心方块才算数，
                // 达标才落盘（不许"队列跑完=建成"的假成功）。
                int solid = 0;
                for (BlockPos cell : baseAuditCells()) {
                    BlockState state = npc.level().getBlockState(cell);
                    if (!state.isAir() && state.getFluidState().isEmpty()
                            && !isVegetation(state)) {
                        solid++;
                    }
                }
                int total = baseAuditCells().size();
                if (total > 0 && solid * 100 >= total * 95) {
                    BlockPos anchor = new BlockPos(baseCenter.getX(), baseCenter.getY() + 1,
                            baseCenter.getZ());
                    BaseMemory.bindWorld(npc.level());
                    BaseMemory.save(npc.npcName(), anchor);
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [BASE] 落脚点建成 | anchor="
                            + anchor.toShortString() + " 自审实心=" + solid + "/" + total
                            + " 用料=" + basePlacedCount);
                    finish("落脚点建成：火柴盒小屋 自审 " + solid + "/" + total
                            + "，锚点已落盘");
                } else {
                    finish("建屋未达标：自审实心 " + solid + "/" + total + "（要求 ≥95%）");
                }
            }
            default -> finish("建屋阶段异常 " + basePhase);
        }
    }

    /** 自审格清单：与砌筑队列同一批格（队列执行后已清空，按中心重建）。 */
    private java.util.List<BlockPos> baseAuditCells() {
        java.util.List<BlockPos> cells = new java.util.ArrayList<>();
        if (baseCenter == null) {
            return cells;
        }
        int cx = baseCenter.getX();
        int cz = baseCenter.getZ();
        int floor = baseCenter.getY();
        for (int dx = -BASE_HALF; dx <= BASE_HALF; dx++) {
            for (int dz = -BASE_HALF; dz <= BASE_HALF; dz++) {
                // 只审墙与顶（结构主体）；补地格在队列里有计数兜底，
                // 高度图会被新墙干扰、不适合复测补地。
                if (Math.abs(dx) == BASE_HALF || Math.abs(dz) == BASE_HALF) {
                    for (int level = 1; level <= 3; level++) {
                        if (dx == 0 && dz == BASE_HALF && level <= 2) {
                            continue; // 门洞（南墙中，2 高）
                        }
                        cells.add(new BlockPos(cx + dx, floor + level, cz + dz));
                    }
                }
                cells.add(new BlockPos(cx + dx, floor + 4, cz + dz)); // 屋顶
            }
        }
        return cells;
    }

    /** 生成砌筑队列：补地（原地面低 1 的列）→ 墙三层（南墙留门洞）→ 屋顶。 */
    private void buildBaseQueue() {
        baseQueue.clear();
        int cx = baseCenter.getX();
        int cz = baseCenter.getZ();
        int floor = baseCenter.getY();
        ServerLevel serverLevel = (ServerLevel) npc.level();
        for (int dx = -BASE_HALF; dx <= BASE_HALF; dx++) {
            for (int dz = -BASE_HALF; dz <= BASE_HALF; dz++) {
                int top = serverLevel.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        cx + dx, cz + dz);
                if (top == floor - 1) {
                    baseQueue.add(new BlockPos(cx + dx, floor, cz + dz));
                }
            }
        }
        for (int level = 1; level <= 3; level++) {
            for (int dx = -BASE_HALF; dx <= BASE_HALF; dx++) {
                for (int dz = -BASE_HALF; dz <= BASE_HALF; dz++) {
                    if (Math.abs(dx) != BASE_HALF && Math.abs(dz) != BASE_HALF) {
                        continue;
                    }
                    if (dx == 0 && dz == BASE_HALF && level <= 2) {
                        continue; // 门洞
                    }
                    baseQueue.add(new BlockPos(cx + dx, floor + level, cz + dz));
                }
            }
        }
        for (int dx = -BASE_HALF; dx <= BASE_HALF; dx++) {
            for (int dz = -BASE_HALF; dz <= BASE_HALF; dz++) {
                baseQueue.add(new BlockPos(cx + dx, floor + 4, cz + dz));
            }
        }
    }

    /**
     * 【fix62】站位接近（目标层编排，照 tickTravel/tickRevisit 既有口径）：
     * 竖直差 >4 先 descend/ascend 对齐——两技能自挖楼梯，屋顶能拆自家避难所下来、
     * 井下能续爬上来；裸 goto 在屋顶/楼梯井/洼地起点必死（standableGoal 垂直窗口
     * 以当前 Y 为锚、A* 开放集 15~97 节点耗尽），建屋三火 from==to 实锤。
     * 竖直对齐后水平远/连败才派 travel，且 y 传当前高度：travel 技能只管水平推进，
     * 其断崖搭桥是「放脚层再站上去」的爬升设计，目标在下方时直接派它会一路搭到
     * 天上（farm20 首火实证：屋顶 88 → 圆石柱 135 以「需要竖直对齐」收场）。
     * 近处平地仍走 goto。travel/descend/ascend 启动后技能层接管，本方法只管发令。
     */
    private void moveToStand(BlockPos stand, int stuckTicks, String tag) {
        double dxs = npc.getX() - (stand.getX() + 0.5D);
        double dzs = npc.getZ() - (stand.getZ() + 0.5D);
        double dys = npc.getY() - stand.getY();
        if (Math.abs(dys) > 4.0D) {
            String action = dys > 0 ? "descend" : "ascend";
            JsonObject p = new JsonObject();
            p.addProperty("y", (double) stand.getY());
            p.addProperty("max", 160);
            if (skills.run(new Intent("", action, p), "目标")) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " " + tag + " 站位接近先竖直对齐 | action="
                        + action + " 目标Y=" + stand.getY() + " 当前Y=" + Math.round(npc.getY()));
            }
            return;
        }
        if (dxs * dxs + dzs * dzs > 36.0D || stuckTicks >= 6) {
            JsonObject tv = new JsonObject();
            tv.addProperty("x", stand.getX() + 0.5D);
            tv.addProperty("y", npc.getY());
            tv.addProperty("z", stand.getZ() + 0.5D);
            tv.addProperty("tolerance", 2.0D);
            if (skills.run(new Intent("", "travel", tv), "目标")) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " " + tag + " goto 不通改 travel 接近站位 | "
                        + stand.toShortString());
            }
            return;
        }
        JsonObject params = new JsonObject();
        params.addProperty("x", stand.getX() + 0.5D);
        params.addProperty("z", stand.getZ() + 0.5D);
        skills.run(new Intent("", "goto", params), "目标");
    }

    /**
     * 执行砌筑队列。返回 true = 队列处理完（成功/跳过都算）。
     * 站位固定屋内中心：所有格都在够得着口径内（见 tickBase 注释）。
     */
    private boolean processBaseQueue() {
        while (!baseQueue.isEmpty()) {
            BlockPos cell = baseQueue.get(0);
            BlockState state = npc.level().getBlockState(cell);
            if (!state.isAir() && !isVegetation(state) && state.getFluidState().isEmpty()) {
                // 已是实心（地形自带或已砌）：视为满足，不耗料。
                baseQueue.remove(0);
                basePlacedCount++;
                continue;
            }
            break;
        }
        if (baseQueue.isEmpty()) {
            return true;
        }
        BlockPos cell = baseQueue.get(0);
        if (!buildInReach(cell)) {
            // 去屋内中心站位；反复到不了位则换内圈邻格，再不行放弃这一格。
            String key = "base-stand";
            if (key.equals(baseLastActionKey)) {
                baseActionStuck++;
            } else {
                baseLastActionKey = key;
                baseActionStuck = 0;
            }
            if (baseActionStuck > 90) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [BASE] 站位到不了，跳过一格 | cell="
                        + cell.toShortString());
                baseQueue.remove(0);
                baseActionStuck = 0;
                return false;
            }
            BlockPos stand = new BlockPos(baseCenter.getX(), baseCenter.getY() + 1, baseCenter.getZ());
            // 【fix64→fix65 收紧】到达判定须精确到站位列：建造够格（buildInReach）
            // 按屋内中心算，偏一列远角格就脱距（boot-farm23 实证：松口径
            // 1.44 在邻列低一格误判到达，墙体逐格够不着被跳）。改方块列
            // 一致 + 脚位差 ≤1.0（站位 Y 是室内脚位、身体低一层属正常）。
            boolean atStand = npc.blockPosition().getX() == stand.getX()
                    && npc.blockPosition().getZ() == stand.getZ()
                    && Math.abs(npc.getY() - stand.getY()) <= 1.0D;
            if (!atStand) {
                moveToStand(stand, baseActionStuck, "[BASE]");
            }
            return false;
        }
        baseLastActionKey = "";
        baseActionStuck = 0;
        String material = pickBuildMaterial();
        if (material == null) {
            finish("建屋放弃：建材不足（已砌 " + basePlacedCount + "/" + baseTotalCells + "）");
            return true;
        }
        Block block = blockOfItem(material);
        if (block == null || !consume(material, 1)) {
            baseQueue.remove(0);
            return false;
        }
        npc.level().setBlockAndUpdate(cell, block.defaultBlockState());
        baseQueue.remove(0);
        basePlacedCount++;
        return false;
    }

    // ===== 【fix56·D1】床：安置与真睡 =====
    private int bedPhase;
    private BlockPos bedFootPos;
    private net.minecraft.core.Direction bedFacing;
    private String bedItemPath;
    private int bedActionStuck;
    private String bedLastActionKey = "";
    private int sleepPhase;
    private long sleepStartMillis;
    private long sleepDayTime0 = -1L;
    private int sleepActionStuck;
    private String sleepLastActionKey = "";

    /** 包里第一张床的物品路径（任意颜色）；没有返回 null。 */
    private String firstBedItemPath() {
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (!stack.isEmpty() && pathOf(stack).endsWith("_bed")) {
                return pathOf(stack);
            }
        }
        return null;
    }

    /** 床格可放：本格空/植被无流体，且脚下是实心（屋内地面）。 */
    private boolean bedCellFree(BlockPos pos) {
        BlockState state = npc.level().getBlockState(pos);
        if (!state.isAir() && !isVegetation(state)) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        BlockState below = npc.level().getBlockState(pos.below());
        return !below.isAir() && below.getFluidState().isEmpty();
    }

    /** 在床边登记重生点（RespawnConfig 直接组装：维度+床脚格）。 */
    private boolean registerRespawnAtBed(BlockPos bedPos) {
        try {
            npc.setRespawnPosition(new net.minecraft.server.level.ServerPlayer.RespawnConfig(
                    net.minecraft.world.level.storage.LevelData.RespawnData.of(
                            npc.level().dimension(), bedPos, 0.0F, 0.0F), false), false);
            return true;
        } catch (Throwable throwable) {
            LOG.warning("[AIV][GOAL] " + npc.npcName() + " [BED] 重生点登记失败: " + throwable);
            return false;
        }
    }

    /** 【fix82/fix84】床位扫描：内室 = anchor ±1 的 3×3，脚位试两层
     * （anchor.y 与 anchor.y-1——farm43 到场诊断实证屋内地面层在 anchor
     * 下一层、站位 = anchor.y-1，原口径只试同层西列两对、层差一层即全灭），
     * 东西/南北任意相邻两格皆空且脚下实心即命中（foot 取西/北格、facing
     * 指向 head 格，与原版床 FACING 约定及 phase 2 放床一致）。命中写状态
     * 返回 true；扫描面与层位双扩面后不再依赖单一几何假设。 */
    private boolean scanBedSpot(BlockPos anchor) {
        for (int dy = 0; dy >= -1; dy--) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    BlockPos cell = anchor.offset(dx, dy, dz);
                    if (dx < 1 && bedCellFree(cell) && bedCellFree(cell.east())) {
                        bedFootPos = cell;
                        bedFacing = net.minecraft.core.Direction.EAST;
                        return true;
                    }
                    if (dz < 1 && bedCellFree(cell) && bedCellFree(cell.south())) {
                        bedFootPos = cell;
                        bedFacing = net.minecraft.core.Direction.SOUTH;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 【fix82/fix84】选位失败诊断：双层全内室逐格打 本格/脚下 物品 id，
     * 层位/占用/未加载三类原因靠此一次分辨（日志四问：出事留快照）。 */
    private void logBedScanDiagnostics(BlockPos anchor) {
        StringBuilder sb = new StringBuilder();
        for (int dy = 0; dy >= -1; dy--) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    BlockPos cell = anchor.offset(dx, dy, dz);
                    BlockState state = npc.level().getBlockState(cell);
                    BlockState below = npc.level().getBlockState(cell.below());
                    sb.append(' ').append(cell.toShortString())
                            .append('=').append(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath())
                            .append('/').append(BuiltInRegistries.BLOCK.getKey(below.getBlock()).getPath());
                }
            }
        }
        LOG.info("[AIV][GOAL] " + npc.npcName() + " [BED] 选位诊断（格=本格/脚下）:" + sb);
    }

    private void tickPlaceBed() {
        BaseMemory.bindWorld(npc.level());
        BaseMemory.Base base = BaseMemory.base(npc.npcName());
        if (base == null) {
            finish("安置床放弃：无落脚点账本");
            return;
        }
        if (base.bedPos() != null) {
            finish("床已安置（账本已有记录）");
            return;
        }
        switch (bedPhase) {
            case 0 -> {
                status = "安置床：选位";
                bedItemPath = firstBedItemPath();
                if (bedItemPath == null) {
                    finish("安置床放弃：包里没床");
                    return;
                }
                BlockPos anchor = base.anchor();
                // 【fix82】远端（>24 格、锚点区块未加载）不预扫：未加载读数会让 bedCellFree
                // 的「脚下实心」判定恒假、四组候选全灭误报无空位（farm41 三度秒败实证）。
                // 改先就位（phase 1 的 moveToStand），到站后在 phase 1 就地扫描。
                double bdx = npc.getX() - (anchor.getX() + 0.5D);
                double bdz = npc.getZ() - (anchor.getZ() + 0.5D);
                double bedDistSqr = bdx * bdx + bdz * bdz;
                if (bedDistSqr > 24.0D * 24.0D) {
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [BED] 远端派单：先就位后选位 | dist="
                            + (int) Math.sqrt(bedDistSqr));
                    bedPhase = 1;
                    return;
                }
                if (!scanBedSpot(anchor)) {
                    logBedScanDiagnostics(anchor);
                    finish("安置床放弃：屋内没有空位（两格连排都被占）");
                    return;
                }
                bedPhase = 1;
            }
            case 1 -> {
                status = "安置床：就位";
                BlockPos stand = base.anchor();
                // 【fix65】同 processBaseQueue：站位须精确到列 + 脚位差 ≤1.0。
                boolean bedAtStand = npc.blockPosition().getX() == stand.getX()
                        && npc.blockPosition().getZ() == stand.getZ()
                        && Math.abs(npc.getY() - stand.getY()) <= 1.0D;
                if (bedFootPos == null && bedAtStand) {
                    // 【fix82】远端派单到站后就地选位（此时区块已加载、读数真实）。
                    if (!scanBedSpot(stand)) {
                        logBedScanDiagnostics(stand);
                        finish("安置床放弃：到场后确认屋内没有空位（见选位诊断）");
                        return;
                    }
                }
                if (bedFootPos != null && farmInReach(bedFootPos)) {
                    bedPhase = 2;
                    return;
                }
                // 去屋内中心站位（照 base 站位口径：goto + 同 key 反复计数）。
                String key = "bed-stand";
                if (key.equals(bedLastActionKey)) {
                    bedActionStuck++;
                } else {
                    bedLastActionKey = key;
                    bedActionStuck = 0;
                }
                if (bedActionStuck > 90) {
                    finish("安置床放弃：到不了屋内站位");
                    return;
                }
                if (!bedAtStand) {
                    moveToStand(stand, bedActionStuck, "[BED]");
                }
            }
            case 2 -> {
                status = "安置床：放床";
                Block block = blockOfItem(bedItemPath);
                if (block == null) {
                    finish("安置床放弃：床物品无对应方块 " + bedItemPath);
                    return;
                }
                if (!bedCellFree(bedFootPos) || !bedCellFree(bedFootPos.relative(bedFacing))) {
                    finish("安置床放弃：选位被占（选位后环境变化）");
                    return;
                }
                if (!consume(bedItemPath, 1)) {
                    finish("安置床放弃：扣床失败");
                    return;
                }
                npc.level().setBlockAndUpdate(bedFootPos, block.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.BedBlock.PART,
                                net.minecraft.world.level.block.state.properties.BedPart.FOOT)
                        .setValue(net.minecraft.world.level.block.BedBlock.FACING, bedFacing));
                npc.level().setBlockAndUpdate(bedFootPos.relative(bedFacing), block.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.BedBlock.PART,
                                net.minecraft.world.level.block.state.properties.BedPart.HEAD)
                        .setValue(net.minecraft.world.level.block.BedBlock.FACING, bedFacing));
                BaseMemory.saveBed(npc.npcName(), bedFootPos);
                boolean respawnSet = registerRespawnAtBed(bedFootPos);
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [BED] 安置床 | foot="
                        + bedFootPos.toShortString() + " facing=" + bedFacing
                        + " item=" + bedItemPath + " 重生点登记=" + respawnSet);
                finish("床已安置：" + bedItemPath + " @ " + bedFootPos.toShortString()
                        + (respawnSet ? "，重生点已登记" : "，重生点登记失败（见日志）"));
            }
            default -> finish("安置床阶段异常 " + bedPhase);
        }
    }

    /** 门洞格里若是建材块，就是门塞：拆下回收（无状态幂等，防残留）。 */
    private void clearDoorPlugs(BlockPos anchor) {
        for (int level = 0; level <= 1; level++) {
            BlockPos cell = anchor.offset(0, level, BASE_HALF);
            BlockState state = npc.level().getBlockState(cell);
            if (state.isAir() || isVegetation(state)) {
                continue;
            }
            String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            boolean isPlug = path.equals("cobblestone") || path.equals("cobbled_deepslate")
                    || path.equals("dirt") || path.equals("andesite") || path.equals("diorite")
                    || path.equals("granite") || path.equals("tuff") || path.equals("stone")
                    || path.endsWith("_planks");
            if (!isPlug) {
                continue;
            }
            npc.level().setBlockAndUpdate(cell,
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            give(path, 1);
            LOG.info("[AIV][GOAL] " + npc.npcName() + " [BED] 门塞拆除 | cell="
                    + cell.toShortString() + " item=" + path);
        }
    }

    /** 睡前堵门洞两格（门暂无门，玩家原味：塞块、晨起拆回）。 */
    private void plugDoor(BlockPos anchor) {
        for (int level = 0; level <= 1; level++) {
            BlockPos cell = anchor.offset(0, level, BASE_HALF);
            BlockState state = npc.level().getBlockState(cell);
            if (!state.isAir() && !isVegetation(state)) {
                continue;
            }
            String material = pickBuildMaterial();
            if (material == null) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [BED] 门塞跳过：无建材");
                return;
            }
            Block block = blockOfItem(material);
            if (block == null || !consume(material, 1)) {
                return;
            }
            npc.level().setBlockAndUpdate(cell, block.defaultBlockState());
            LOG.info("[AIV][GOAL] " + npc.npcName() + " [BED] 门塞 | cell="
                    + cell.toShortString() + " material=" + material);
        }
    }

    private void tickSleep() {
        BaseMemory.bindWorld(npc.level());
        BaseMemory.Base base = BaseMemory.base(npc.npcName());
        if (base == null || base.bedPos() == null) {
            finish("睡觉放弃：无床账本");
            return;
        }
        BlockPos bed = base.bedPos();
        BlockPos anchor = base.anchor();
        switch (sleepPhase) {
            case 0 -> {
                status = "睡觉：回床边";
                clearDoorPlugs(anchor);
                if (npc.blockPosition().distSqr(anchor) <= 1.0D) {
                    plugDoor(anchor);
                    sleepPhase = 1;
                    return;
                }
                String key = "sleep-stand";
                if (key.equals(sleepLastActionKey)) {
                    sleepActionStuck++;
                } else {
                    sleepLastActionKey = key;
                    sleepActionStuck = 0;
                }
                if (sleepActionStuck > 90) {
                    finish("睡觉放弃：到不了床边");
                    return;
                }
                moveToStand(anchor, sleepActionStuck, "[BED]");
            }
            case 1 -> {
                status = "睡觉：上床";
                var result = npc.startSleepInBed(bed, false);
                if (result.left().isPresent()) {
                    clearDoorPlugs(anchor);
                    finish("睡不成：" + result.left().get() + "（原版判定）");
                    return;
                }
                sleepStartMillis = System.currentTimeMillis();
                sleepDayTime0 = npc.level().getDayTime();
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [BED] 入睡 | bed="
                        + bed.toShortString() + " dayTime=" + sleepDayTime0);
                sleepPhase = 2;
            }
            case 2 -> {
                status = "睡觉中";
                if (!npc.isSleeping()) {
                    clearDoorPlugs(anchor);
                    long dayTime = npc.level().getDayTime();
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [BED] 睡醒 | dayTime "
                            + sleepDayTime0 + " → " + dayTime);
                    finish("睡醒了（dayTime " + sleepDayTime0 + " → " + dayTime + "）");
                    return;
                }
                if (System.currentTimeMillis() - sleepStartMillis > 20_000L) {
                    npc.stopSleepInBed(false, true);
                    clearDoorPlugs(anchor);
                    LOG.info("[AIV][GOAL] " + npc.npcName()
                            + " [BED] 睡眠超时 20 秒未跳夜，强制起床（跳夜未生效，如实记账）");
                    finish("睡觉超时：跳夜未生效，强制起床");
                }
            }
            default -> finish("睡觉阶段异常 " + sleepPhase);
        }
    }

    /**
     * 选址：半径 26 内找 5×5 地块——25 列顶高差 ≤1（只许低 1 待补，不许高）、
     * 每列上方 1..4 格无实心遮挡、顶格非液体/非落沙类松散顶（沙/沙砾会塌）。
     * 评分：近水（8 格内有水 -100）、近田锚点（距离）、近树（16 格内原木列 -2/棵）、
     * 离 NPC 近（0.1/格）。洞口评分 v1 未含（无廉价可靠的洞口判定，宁缺）。
     */
    private BlockPos findBaseSite() {
        if (!(npc.level() instanceof ServerLevel level)) {
            return null;
        }
        BlockPos me = npc.blockPosition();
        FarmMemory.bindWorld(npc.level());
        FarmMemory.Farm farm = FarmMemory.farm(npc.npcName());
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int cx = me.getX() - 26; cx <= me.getX() + 26; cx += 1) {
            for (int cz = me.getZ() - 26; cz <= me.getZ() + 26; cz += 1) {
                if (level.getChunkIfLoaded(cx >> 4, cz >> 4) == null) {
                    continue;
                }
                int floor = level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
                if (floor <= level.getMinY() + 4) {
                    continue;
                }
                if (!baseFootprintOk(level, cx, cz, floor)) {
                    continue;
                }
                double score = me.distSqr(new BlockPos(cx, floor, cz)) * 0.01D;
                if (farm != null) {
                    score += farm.anchor().distSqr(new BlockPos(cx, floor, cz)) * 0.05D;
                }
                if (baseNearWater(level, cx, floor, cz, 8)) {
                    score -= 100.0D;
                }
                score -= Math.min(10, baseTreeColumns(level, cx, cz, 16)) * 2.0D;
                if (score < bestScore) {
                    bestScore = score;
                    best = new BlockPos(cx, floor, cz);
                }
            }
        }
        return best;
    }

    private boolean baseFootprintOk(ServerLevel level, int cx, int cz, int floor) {
        for (int dx = -BASE_HALF; dx <= BASE_HALF; dx++) {
            for (int dz = -BASE_HALF; dz <= BASE_HALF; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                if (level.getChunkIfLoaded(x >> 4, z >> 4) == null) {
                    return false;
                }
                int top = level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (top != floor && top != floor - 1) {
                    return false;
                }
                BlockState topState = level.getBlockState(new BlockPos(x, top, z));
                if (!topState.getFluidState().isEmpty()
                        || topState.is(Blocks.SAND) || topState.is(Blocks.RED_SAND)
                        || topState.is(Blocks.GRAVEL)) {
                    return false;
                }
                // 【fix64】高度表返回值比地表块高一层（boot-farm22 实证：水道列
                // top=63 而水在 62，上面的 topState 查到的是空气，流体排除从未
                // 生效，水边址被判合格、站位正落在水道上）。改按标称地表
                // floor 上下直接扫块：floor-2..floor 任一格有流体即否决；
                // 农田/作物在足迹内同样否决（房子不许压田，boot-farm22 选址
                // 足迹压了农田南侧一排）。
                for (int y = floor - 2; y <= floor; y++) {
                    BlockState s = level.getBlockState(new BlockPos(x, y, z));
                    if (!s.getFluidState().isEmpty()) {
                        return false;
                    }
                    if (s.is(Blocks.FARMLAND) || s.is(Blocks.WHEAT)
                            || s.is(Blocks.CARROTS) || s.is(Blocks.POTATOES)
                            || s.is(Blocks.BEETROOTS)) {
                        return false;
                    }
                }
                for (int up = 1; up <= 4; up++) {
                    BlockState above = level.getBlockState(new BlockPos(x, top + up, z));
                    if (!above.isAir() && !isVegetation(above)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean baseNearWater(ServerLevel level, int cx, int floor, int cz, int radius) {
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                for (int y = floor - 1; y <= floor + 1; y++) {
                    // 用方块判定不用 FluidState.is(Fluids.WATER)：后者在
                    // 编译/运行时映射下签名对不齐、类加载 VerifyError
                    // （fix52 首跑实证，auto-npc 生成直接失败）。
                    if (level.getBlockState(new BlockPos(x, y, z)).is(Blocks.WATER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 数半径内"地表有树"的列（口径同 findVisibleTreeColumn 的简化版）。 */
    private int baseTreeColumns(ServerLevel level, int cx, int cz, int radius) {
        int count = 0;
        for (int x = cx - radius; x <= cx + radius; x += 2) {
            for (int z = cz - radius; z <= cz + radius; z += 2) {
                if (level.getChunkIfLoaded(x >> 4, z >> 4) == null) {
                    continue;
                }
                int top = level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                BlockState state = level.getBlockState(new BlockPos(x, top - 1, z));
                if (state.is(net.minecraft.tags.BlockTags.LOGS)
                        || state.is(net.minecraft.tags.BlockTags.LEAVES)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 砌筑材料：按"最不心疼"排序挑背包里真有的方块物品。圆石是工具料，
     * 只在存量 &gt;16 时用；花岗岩/闪长岩/安山岩是 Fong 定的第一档可弃杂石，
     * 拿来砌墙正好废物利用；木板是棍料，存量 &gt;8 才用。
     */
    private String pickBuildMaterial() {
        if (countItem("cobblestone") > 16) {
            return "cobblestone";
        }
        for (String trash : new String[]{"andesite", "diorite", "granite", "cobbled_deepslate",
                "dirt", "tuff", "stone"}) {
            if (countItem(trash) > 0) {
                return trash;
            }
        }
        if (countItem("cobblestone") > 0) {
            return "cobblestone";
        }
        String planks = firstPlanksPath();
        if (planks != null && countItemsEndingWith("_planks") > 8) {
            return planks;
        }
        return null;
    }

    /** 建材总存量（与规划层的触发口径一致）。 */
    private int countBuildMaterial() {
        return countItem("cobblestone") + countItem("cobbled_deepslate") + countItem("dirt")
                + countItem("andesite") + countItem("diorite") + countItem("granite")
                + countItem("tuff") + countItem("stone") + countItemsEndingWith("_planks");
    }

    private String firstPlanksPath() {
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (!stack.isEmpty() && pathOf(stack).endsWith("_planks")) {
                return pathOf(stack);
            }
        }
        return null;
    }

    /** 物品路径 → 对应方块（只认 BlockItem）。 */
    private Block blockOfItem(String itemPath) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse("minecraft:" + itemPath));
        if (item instanceof net.minecraft.world.item.BlockItem blockItem) {
            return blockItem.getBlock();
        }
        return null;
    }

    /** 可替换的植被/薄层（与种田选址同口径的近似集合）。 */
    private boolean isVegetation(BlockState state) {
        return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.SNOW) || state.is(Blocks.DEAD_BUSH)
                || state.is(Blocks.DANDELION) || state.is(Blocks.POPPY)
                || state.is(Blocks.AZURE_BLUET) || state.is(Blocks.OXEYE_DAISY)
                || state.is(Blocks.CORNFLOWER) || state.is(Blocks.LILY_OF_THE_VALLEY)
                || state.is(Blocks.RED_TULIP) || state.is(Blocks.ORANGE_TULIP)
                || state.is(Blocks.WHITE_TULIP) || state.is(Blocks.PINK_TULIP)
                || state.is(Blocks.ALLIUM) || state.is(Blocks.BLUE_ORCHID)
                || state.is(Blocks.SUNFLOWER) || state.is(Blocks.LILAC)
                || state.is(Blocks.ROSE_BUSH) || state.is(Blocks.PEONY);
    }

    // ---------------------------------------------------------------- 狩猎

    private void tickHunt() {
        // 【第二十二轮】存量驱动（Fong 18:49 拍板）：规划器给了 need/stock 就不数
        // "打死几只"——掉落是随机的（牛皮革 0–2、1/3 空手），数击杀会出现在打完
        // 工单皮革还是 0 的假完成。每轮复查背包存量：皮革看 leather 计数、食物看
        // 全部食物的营养值合计；存量一变就打复查行，够了立刻收工，不够继续打；
        // 连续 8 次找不到目标照旧连败退避交回规划器（防原地空转）。
        if (!huntNeed.isEmpty()) {
            boolean leatherNeed = "leather".equals(huntNeed);
            int stock = leatherNeed ? countItem("leather") : foodNutrition();
            status = "狩猎（存量驱动 " + huntNeed + "） " + stock + "/" + huntStockTarget;
            if (stock != lastHuntStock) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " hunt 存量复查 | " + huntNeed + "="
                        + stock + "/" + huntStockTarget
                        + (stock >= huntStockTarget ? " 已达标" : " 不够，继续"));
                lastHuntStock = stock;
            }
            if (stock >= huntStockTarget) {
                huntFailStreak = 0;
                huntHeading = Double.NaN;
                finish("狩猎完成（存量达标）" + huntNeed + "=" + stock + "/" + huntStockTarget);
                return;
            }
            if (huntAttempts >= 8) {
                huntFailStreak++;
                finish("狩猎放弃（存量未达标 " + stock + "/" + huntStockTarget
                        + "）：连续 8 次没找到目标");
                return;
            }
            JsonObject stockParams = new JsonObject();
            // 【第四十三轮】搜猎半径 12/24 → 32/48：五局群系合证半径 12 在动物
            // 稀疏/斑块分布地形从未猎成（且技能层旧钳制把 24 也压回 16、从未生效）。
            // 按“玩家目视”口径：常态 32、久猎无获 48，配 wanderForHunt 步幅放大。
            stockParams.addProperty("radius", huntAttempts >= 4 || huntFailStreak >= 1 ? 48 : 32);
            stockParams.addProperty("hits", 10);
            if (leatherNeed) {
                stockParams.addProperty("filter", "leather");
            } else if (countItem("leather") < 4) {
                stockParams.addProperty("prefer", "cow");
            }
            huntAttempts++;
            if (skills.run(new Intent("", "hunt", stockParams), "目标")) {
                return;
            }
            wanderForHunt();
            return;
        }
        int absolute = countHuntLoot();
        int have = Math.max(0, absolute - huntBaseline);
        status = "狩猎 " + have + "/" + targetCount + "（肉类共 " + absolute + "）";
        if (have >= targetCount) {
            huntFailStreak = 0;
            huntHeading = Double.NaN;
            finish("狩猎完成 新得 " + have + "/" + targetCount + "，肉类共 " + absolute);
            return;
        }
        if (huntAttempts >= 8) {
            huntFailStreak++;
            finish("狩猎放弃：连续 8 次附近没有可猎动物");
            return;
        }
        JsonObject params = new JsonObject();
        // 【第十五轮】久猎无获时放大搜猎半径（旧版 12 格外一步之遥永远看不见）。
        // 【第四十三轮】口径再放大：常态 32、久猎无获 48（见存量分支同注）。
        params.addProperty("radius", huntAttempts >= 4 || huntFailStreak >= 1 ? 48 : 32);
        params.addProperty("hits", 10);
        // 【第十九轮】皮革不足 4 张时打猎优先牛：牛肉是顶级食物、皮革是皮靴
        // 材料，一举两得（Fong 口径）。攒够 4 张后恢复"最近优先"。
        if (countItem("leather") < 4) {
            params.addProperty("prefer", "cow");
        }
        huntAttempts++;
        if (skills.run(new Intent("", "hunt", params), "目标")) {
            return;
        }
        wanderForHunt();
    }

    /**
     * 【第十五轮】找猎专用搬迁：旧版所有"附近没有"都用通用 wander 小步随机
     * 4-9 格换位，在无动物区就是原地打转——长跑#6 实测 food=0 后连续 4 轮
     * 狩猎目标全失败、8 分钟只挪出 50 格，铁装/钻石主线被饥饿顶住饿死循环。
     * 改为逐级放大：本轮前两次仍小步；之后沿一条固定航向逐级远走
     * （航向在整轮失败期间保持，跨目标 streak 叠加升温），真正离开贫瘠区；
     * 打到猎物即清零复位。航向走不通时依次试 ±45° 与反向，都不通退回通用小步。
     * 【第四十三轮】步幅随搜猎半径（32/48）同步放大：24→48→64 格——步幅小于
     * 扫描半径时新扫区大半与旧区重叠，等于在贫瘠区里换个姿势盲走。
     */
    private void wanderForHunt() {
        int heat = huntAttempts + huntFailStreak * 2;
        if (heat <= 2) {
            wander();
            return;
        }
        double distance = heat <= 4 ? 24.0D : heat <= 6 ? 48.0D : 64.0D;
        if (Double.isNaN(huntHeading)) {
            huntHeading = java.util.concurrent.ThreadLocalRandom.current()
                    .nextDouble() * Math.PI * 2.0D;
        }
        double[] angles = {huntHeading, huntHeading + Math.PI / 4, huntHeading - Math.PI / 4,
                huntHeading + Math.PI};
        for (double angle : angles) {
            JsonObject params = new JsonObject();
            params.addProperty("x", npc.getX() + Math.cos(angle) * distance);
            params.addProperty("z", npc.getZ() + Math.sin(angle) * distance);
            if (skills.run(new Intent("", "goto", params), "目标")) {
                huntHeading = angle;
                LOG.info("[AIV][GOAL] " + npc.npcName() + " hunt-escalate | streak="
                        + huntFailStreak + " dist=" + (int) distance
                        + " heading=" + (int) Math.toDegrees(angle));
                return;
            }
        }
        wander();
    }

    /** 【第二十二轮】背包食物总营养值：按 FOOD 组件 nutrition×数量合计（下矿口粮线的口径）。
     * 【第二十四轮】腐肉每块按 1 点计（Wiki：恢复 4/饱和 0.8/80% 概率饥饿效果，
     * 满值高估），与规划层、盘点同一口径。 */
    private int foodNutrition() {
        int total = 0;
        for (int slot = 0; slot < COUNT_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            var food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
            if (food != null && food.nutrition() > 0) {
                total += ("rotten_flesh".equals(pathOf(stack)) ? 1 : food.nutrition())
                        * stack.getCount();
            }
        }
        return total;
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
    // ===== 【fix69·箱子】回落脚点存贵重物（v1 只存不取，草案见 status-round14） =====
    private int storePhase;
    private BlockPos storeChestPos;
    private int storeActionStuck;
    private String storeLastActionKey = "";

    // ===== 【fix80·D12】搜刮世界箱子（村庄/结构，只拿用得上的） =====
    private int lootX;
    private int lootY;
    private int lootZ;
    private int lootStuck;
    private BlockPos lootStandPos;

    /** 存入名单（草案定版）：死了怕丢、平时不用的贵重物 + 第二档杂物（存箱优先于扔）。
     * 【fix92·Fong 2026-10-28】红石加入：留存的一组（配额裁剪已封顶 64）
     * 回家存进箱子，不在包里占格。 */
    private static final java.util.Set<String> STORE_LOOT = java.util.Set.of(
            "diamond", "emerald", "netherite_ingot", "netherite_scrap", "ancient_debris",
            "blaze_rod", "ender_pearl", "gold_ingot", "redstone",
            "bone", "string", "spider_eye");

    /** 落脚点箱子是否已安置且在 range 格内（第二档"存箱优先"的判定口）。 */
    private boolean hasChestWithin(double range) {
        BaseMemory.bindWorld(npc.level());
        BaseMemory.Base base = BaseMemory.base(npc.npcName());
        return base != null && base.chestPos() != null
                && npc.blockPosition().distSqr(base.chestPos()) <= range * range;
    }

    private void tickStoreLoot() {
        BaseMemory.bindWorld(npc.level());
        BaseMemory.Base base = BaseMemory.base(npc.npcName());
        if (base == null) {
            finish("存箱放弃：无落脚点账本");
            return;
        }
        switch (storePhase) {
            case 0 -> {
                status = "存箱：定箱位";
                BlockPos recorded = base.chestPos();
                if (recorded != null && npc.level().getBlockState(recorded)
                        .is(net.minecraft.world.level.block.Blocks.CHEST)) {
                    storeChestPos = recorded;
                } else {
                    if (recorded != null) {
                        LOG.info("[AIV][GOAL] " + npc.npcName() + " [STORE] 账本箱位块已不在（"
                                + recorded.toShortString() + "），重新选位");
                    }
                    BlockPos anchor = base.anchor();
                    BlockPos[] candidates = {
                            anchor.offset(1, 0, 1), anchor.offset(1, 0, -1),
                            anchor.offset(-1, 0, 1), anchor.offset(1, 0, 0),
                            anchor.offset(-1, 0, 0), anchor.offset(0, 0, 1)};
                    storeChestPos = null;
                    for (BlockPos cell : candidates) {
                        // bedCellFree 要求空/植被+脚下实心：床格是实体块天然被排除，
                        // 锚点本身不在候选内（留作站位）。
                        if (bedCellFree(cell)) {
                            storeChestPos = cell;
                            break;
                        }
                    }
                    if (storeChestPos == null) {
                        finish("存箱放弃：屋内没有放箱子的空位");
                        return;
                    }
                    if (countItem("chest") == 0) {
                        finish("存箱放弃：包里没箱子");
                        return;
                    }
                }
                storePhase = 1;
            }
            case 1 -> {
                status = "存箱：就位";
                BlockPos stand = base.anchor();
                boolean atStand = npc.blockPosition().getX() == stand.getX()
                        && npc.blockPosition().getZ() == stand.getZ()
                        && Math.abs(npc.getY() - stand.getY()) <= 1.0D;
                if (atStand) {
                    storePhase = 2;
                    return;
                }
                String key = "store-stand";
                if (key.equals(storeLastActionKey)) {
                    storeActionStuck++;
                } else {
                    storeLastActionKey = key;
                    storeActionStuck = 0;
                }
                if (storeActionStuck > 90) {
                    finish("存箱放弃：到不了屋内站位");
                    return;
                }
                moveToStand(stand, storeActionStuck, "[STORE]");
            }
            case 2 -> {
                status = "存箱：存入";
                if (!npc.level().getBlockState(storeChestPos)
                        .is(net.minecraft.world.level.block.Blocks.CHEST)) {
                    if (!consume("chest", 1)) {
                        finish("存箱放弃：扣箱子失败");
                        return;
                    }
                    npc.level().setBlockAndUpdate(storeChestPos,
                            net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
                    BaseMemory.saveChest(npc.npcName(), storeChestPos);
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [STORE] 放箱 | pos="
                            + storeChestPos.toShortString());
                }
                net.minecraft.world.level.block.entity.BlockEntity blockEntity =
                        npc.level().getBlockEntity(storeChestPos);
                if (!(blockEntity instanceof net.minecraft.world.Container container)) {
                    finish("存箱放弃：箱子容器不可用");
                    return;
                }
                int ironExcess = Math.max(0, countItem("iron_ingot") - 32);
                int kinds = 0;
                int total = 0;
                for (int slot = 0; slot < MAIN_SLOTS; slot++) {
                    ItemStack stack = npc.getInventory().getItem(slot);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    String path = pathOf(stack);
                    int want;
                    if (STORE_LOOT.contains(path)) {
                        want = stack.getCount();
                    } else if (path.equals("iron_ingot")) {
                        // 铁锭只存超出常备 32 的部分（Fong 常备口径，草案定版）。
                        want = Math.min(stack.getCount(), ironExcess);
                    } else {
                        continue;
                    }
                    if (want <= 0) {
                        continue;
                    }
                    ItemStack moving = stack.copy();
                    moving.setCount(want);
                    addToContainer(container, moving);
                    int moved = want - moving.getCount();
                    if (moved <= 0) {
                        continue;
                    }
                    stack.shrink(moved);
                    if (stack.isEmpty()) {
                        npc.getInventory().setItem(slot, ItemStack.EMPTY);
                    }
                    if (path.equals("iron_ingot")) {
                        ironExcess -= moved;
                    }
                    kinds++;
                    total += moved;
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [STORE] 存入 | item="
                            + path + " x" + moved + " 箱位=" + storeChestPos.toShortString());
                }
                blockEntity.setChanged();
                if (total == 0) {
                    finish("存箱完成：无可存物品（或箱已满）");
                } else {
                    finish("存箱完成：存入 " + kinds + " 种共 " + total + " 个 @ "
                            + storeChestPos.toShortString());
                }
            }
            default -> finish("存箱阶段异常 " + storePhase);
        }
    }

    /** 把一堆物品放进容器：先合并同物品堆、再填空槽（原版箱子放入口径）。 */
    private void addToContainer(net.minecraft.world.Container container, ItemStack stack) {
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack cur = container.getItem(i);
            if (!cur.isEmpty() && ItemStack.isSameItemSameComponents(cur, stack)) {
                int room = Math.min(cur.getMaxStackSize(), container.getMaxStackSize())
                        - cur.getCount();
                if (room > 0) {
                    int move = Math.min(room, stack.getCount());
                    cur.grow(move);
                    stack.shrink(move);
                    container.setItem(i, cur);
                }
            }
        }
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            if (container.getItem(i).isEmpty()) {
                int move = Math.min(stack.getCount(),
                        Math.min(stack.getMaxStackSize(), container.getMaxStackSize()));
                ItemStack put = stack.copy();
                put.setCount(move);
                container.setItem(i, put);
                stack.shrink(move);
            }
        }
    }

    /**
     * 【fix80·D12】搜刮世界箱子：走到箱边 → 按清单拿 → 记账销账。
     * 只拿用得上的（口粮/床料/贵重物/原料），不动箱子本身、不拆任何东西、
     * 不碰床和铁傀儡（Fong 拍板边界）。搜完与到不了都进 LootMemory 账本，
     * 同一箱子不重复派单（到不了多半是永久几何，记下别再试）。
     */
    private void tickLootChest() {
        BlockPos chestPos = new BlockPos(lootX, lootY, lootZ);
        status = "搜刮箱子 " + chestPos.toShortString();
        LootMemory.bindWorld(npc.level());
        net.minecraft.world.level.block.state.BlockState chestState =
                npc.level().getBlockState(chestPos);
        if (!chestState.is(net.minecraft.world.level.block.Blocks.CHEST)
                && !chestState.is(net.minecraft.world.level.block.Blocks.TRAPPED_CHEST)) {
            LootMemory.markLooted(npc.npcName(), chestPos);
            finish("搜箱放弃：箱子已不在 " + chestPos.toShortString());
            return;
        }
        double dx = chestPos.getX() + 0.5D - npc.getX();
        double dz = chestPos.getZ() + 0.5D - npc.getZ();
        boolean atChest = Math.sqrt(dx * dx + dz * dz) <= 2.2D
                && Math.abs(npc.getY() - chestPos.getY()) <= 2.0D;
        if (!atChest) {
            if (lootStandPos == null) {
                lootStandPos = pickLootStand(chestPos);
                if (lootStandPos == null) {
                    LootMemory.markLooted(npc.npcName(), chestPos);
                    finish("搜箱放弃：箱子四周没有可站的格 " + chestPos.toShortString());
                    return;
                }
            }
            lootStuck++;
            if (lootStuck > 240) {
                LootMemory.markLooted(npc.npcName(), chestPos);
                finish("搜箱放弃：多次推进仍未到达箱子边 " + chestPos.toShortString());
                return;
            }
            moveToStand(lootStandPos, lootStuck, "[LOOT]");
            return;
        }
        if (!"idle".equals(skills.currentSkill())) {
            return;
        }
        net.minecraft.world.level.block.entity.BlockEntity blockEntity =
                npc.level().getBlockEntity(chestPos);
        if (!(blockEntity instanceof net.minecraft.world.Container container)) {
            LootMemory.markLooted(npc.npcName(), chestPos);
            finish("搜箱放弃：箱子容器不可用 " + chestPos.toShortString());
            return;
        }
        int kinds = 0;
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String path = pathOf(stack);
            if (!lootWorthTaking(path)) {
                continue;
            }
            ItemStack moving = stack.copy();
            int before = moving.getCount();
            npc.getInventory().add(moving);
            int taken = before - moving.getCount();
            if (taken <= 0) {
                continue;
            }
            if (taken >= stack.getCount()) {
                container.setItem(slot, ItemStack.EMPTY);
            } else {
                stack.shrink(taken);
                container.setItem(slot, stack);
            }
            kinds++;
            total += taken;
            LOG.info("[AIV][GOAL] " + npc.npcName() + " [LOOT] " + path + " x" + taken
                    + " | 箱=" + chestPos.toShortString());
        }
        container.setChanged();
        LootMemory.markLooted(npc.npcName(), chestPos);
        finish(total > 0
                ? "搜箱完成：拿到 " + kinds + " 种共 " + total + " 件 @ " + chestPos.toShortString()
                : "搜箱完成：箱里没有用得上的东西 @ " + chestPos.toShortString());
    }

    /** 箱边站位：四个水平邻格里挑能站的（脚格可替换、脚下有实心），离 NPC 最近优先。 */
    private BlockPos pickLootStand(BlockPos chestPos) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            BlockPos cell = chestPos.offset(d[0], 0, d[1]);
            net.minecraft.world.level.block.state.BlockState cellState =
                    npc.level().getBlockState(cell);
            net.minecraft.world.level.block.state.BlockState belowState =
                    npc.level().getBlockState(cell.below());
            if (!(cellState.isAir() || cellState.canBeReplaced())
                    || belowState.getCollisionShape(npc.level(), cell.below()).isEmpty()) {
                continue;
            }
            double sq = cell.distSqr(npc.blockPosition());
            if (sq < bestSq) {
                bestSq = sq;
                best = cell;
            }
        }
        return best;
    }

    /** 搜箱拿取清单（D12 定版）：食物（含生肉，回去烤）+ 床料 + 功能料 + 贵重物。 */
    private static final java.util.Set<String> LOOT_TAKE = java.util.Set.of(
            "bread", "apple", "golden_apple", "enchanted_golden_apple",
            "carrot", "golden_carrot", "potato", "baked_potato", "beetroot",
            "melon_slice", "sweet_berries", "glow_berries", "pumpkin_pie",
            "dried_kelp", "cookie", "honey_bottle", "wheat",
            "cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_mutton",
            "cooked_cod", "cooked_salmon", "cooked_rabbit",
            "beef", "porkchop", "chicken", "mutton", "cod", "salmon", "rabbit",
            "rabbit_stew", "mushroom_stew", "beetroot_soup",
            "string", "bone", "arrow", "bucket", "torch", "coal",
            "iron_ingot", "gold_ingot", "diamond", "emerald", "lapis_lazuli",
            "ender_pearl", "blaze_rod", "gold_nugget", "iron_nugget");

    /** 这件东西值不值得从箱子里拿（干草块另有小麦当量上限口径）。 */
    private boolean lootWorthTaking(String path) {
        if (path.endsWith("_wool") || LOOT_TAKE.contains(path)) {
            return true;
        }
        if (path.equals("hay_block")) {
            // 干草块=面包原料：一块顶 9 小麦，小麦当量满一组（64）就停手。
            return countItem("wheat") + countItem("hay_block") * 9 < 64;
        }
        return false;
    }

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
            // 【第十八轮】走 mineWithBestTool：沙砾不需要工具，它会先换空手再挖；
            // 旧版 npc.mine() 拿主手当前的镐硬挖，白磨铁镐耐久（选工具审计逮到的小漏口）。
            boolean mined = npc.mineWithBestTool(candidate);
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 刷燧石 | 放挖沙砾 success=" + mined
                    + " 燧石=" + countItem("flint"));
            return true;
        }
        return false;
    }

    /**
     * 【fix70·D4】装满水桶（常备保命水）：转派技能层 fillbucket
     * （64 格内找水源→走过去→舀水，照原版取水口径）。技能在跑时目标层
     * tick 被全局守卫挡住，这里只在技能空闲时派单/收手。
     */
    private void tickFillBucket() {
        status = "装满水桶";
        if (countItem("water_bucket") > 0) {
            finish("水桶已装满");
            return;
        }
        if (countItem("bucket") <= 0) {
            finish("没有空铁桶");
            return;
        }
        // 【fix74】技能一轮跑完（fillbucket→idle）仍没装到水：计一次失败运行，
        // 6 次诚实收手。旧版只有 attempts（派发被拒才计数、派发成功即清零），
        // 技能每 20 秒正常收场就无限重派——farm33 实证靠 300 秒看门狗才收场。
        if ("fillbucket".equals(skills.currentSkill())) {
            fillSkillActive = true;
        } else if (fillSkillActive) {
            fillSkillActive = false;
            if (++fillFailedRuns >= 6) {
                finish("装水多次接近失败（地形到不了水源）");
                return;
            }
        }
        if (skills.run(new Intent("", "fillbucket", new JsonObject()), "目标")) {
            attempts = 0;
            return;
        }
        if (++attempts >= 12) {
            finish("装水连续没有进展（附近无水源）");
        }
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
    /**
     * 【fix40】记矿：级别门/没镐收场前，16 格内真扫到裸露目标矿才记账
     *（扫不到不记、不编造坐标——Fong 口径"只记真实见到的"）。
     */
    private void recordSeenOre() {
        if (oreTarget == null) {
            return;
        }
        OreMemory.bindWorld(npc.level());
        BlockPos seen = scanExposedOre(oreTarget.block(), 16);
        if (seen != null) {
            OreMemory.record(npc.npcName(), oreTarget.block(), seen);
        }
    }

    /**
     * 【fix40】记矿回访：赶到记账点、把当时挖不了的矿挖掉。
     * 长途段镜像 tickTravel（travel 技能+竖直对齐）；进 12 格圈后先验记账点
     * 本体、再扫裸露同矿下 mine 技能。收场口径：挖到=成功销账；矿已不在=
     * 销账（不算失败）；工具又不够=中止（不计次）；其余失败由 finish() 计次，
     * 满 2 次自动销账防死锁。
     */
    private void tickRevisit() {
        OreMemory.bindWorld(npc.level());
        status = "回访记账矿 " + oreTarget.block() + " @ " + revisitX + "," + revisitY + "," + revisitZ;
        if (!hasHarvestPickaxe(oreTarget.block())) {
            finish("回访中止：又没有能挖 " + oreTarget.block() + " 的镐了");
            return;
        }
        if (countItem(oreTarget.drop()) > baselineCount) {
            OreMemory.remove(npc.npcName(), oreTarget.block(), revisitX, revisitY, revisitZ);
            finish("回访成功：挖到 " + oreTarget.drop() + "，销账");
            return;
        }
        double dx = revisitX + 0.5D - npc.getX();
        double dz = revisitZ + 0.5D - npc.getZ();
        double horizontalSq = dx * dx + dz * dz;
        if (horizontalSq > 144.0D || Math.abs(revisitY - npc.getY()) > 8.0D) {
            attempts++;
            if (attempts > 240) {
                finish("回访放弃：多次推进仍未到达记账点");
                return;
            }
            if (horizontalSq <= 16.0D) {
                String action = npc.getY() > revisitY ? "descend" : "ascend";
                JsonObject p = params("y", revisitY, "max", 160);
                if (!skills.run(new Intent("", action, p), "目标")) {
                    finish("回访竖直对齐失败 action=" + action);
                }
                return;
            }
            JsonObject p = params("x", revisitX, "y", npc.getY(), "z", revisitZ,
                    "tolerance", 4.0D);
            if (!skills.run(new Intent("", "travel", p), "目标")) {
                finish("回访行进技能启动失败");
            }
            return;
        }
        BlockPos exact = new BlockPos(revisitX, revisitY, revisitZ);
        String exactPath = BuiltInRegistries.BLOCK.getKey(
                npc.level().getBlockState(exact).getBlock()).getPath();
        boolean exactOk = (exactPath.equals(oreTarget.block())
                || exactPath.equals("deepslate_" + oreTarget.block())) && isExposed(exact);
        BlockPos found = exactOk ? exact : scanExposedOre(oreTarget.block(), 12);
        if (found == null) {
            OreMemory.remove(npc.npcName(), oreTarget.block(), revisitX, revisitY, revisitZ);
            finish("记账矿已不在（挖走/采完），销账");
            return;
        }
        if (!"idle".equals(skills.currentSkill())) {
            return;
        }
        JsonObject p = new JsonObject();
        p.addProperty("block", oreTarget.block());
        p.addProperty("radius", 16);
        if (!skills.run(new Intent("", "mine", p), "目标")) {
            if (++staleChecks >= 12) {
                finish("回访放弃：矿在附近但 mine 技能反复启动失败");
            }
        } else {
            staleChecks = 0;
        }
    }

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
        // 【fix58】补深板岩圆石/黑石：深挖时代包里最多的就是深板岩圆石，选料表不认它
        // 会在泥土耗尽后整夜瞬间失败「建筑方块不足」（boot-farm13 实锤），与 fix57 同病。
        String[] candidates = {"cobblestone", "cobbled_deepslate", "blackstone", "stone", "dirt",
                "oak_planks", "spruce_planks",
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
                    // 【fix72】自封门：与身体包围盒相交的格子永不放块。恢复点
                    // 醒来时身体常横跨两列（z=-219.89 横跨 -220/-221），墙格
                    // 放进包围盒眼高层会 inWall 窒息（farm30 实证：建屋自封
                    // 13 秒致死）。缺这一两块按「部分完成」既有口径接受。
                    if (npc.getBoundingBox().intersects(
                            new net.minecraft.world.phys.AABB(pos))) {
                        continue;
                    }
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

    // ---------------------------------------------------------------- 目标总超时看门狗（第四轮修复 A）

    /**
     * 观察一次目标进展，有进展就刷新 lastProgressTick。
     *
     * <p>进展信号（任一成立）：①目标相关产出计数变化（见 {@link #goalMetric()}，
     * 只认与目标直接相关的物品——在水洼里蹭挖到的圆石不算挖铁的进展）；
     * ②位置离开锚点 6 格以上（长距离移动、下矿/上浮途中物品还没产出时靠它续命）；
     * ③熔炼目标且熔炉正点着（等烧炼是合法等待，产物每约 10 秒一变本也会触发①，
     * 这里把"火还着"也算上，免得大批量慢烧被误判）。
     */
    private void observeGoalProgress() {
        int metric = goalMetric();
        BlockPos pos = npc.blockPosition();
        boolean moved = progressAnchor == null || pos.distSqr(progressAnchor) >= 36.0D;
        boolean smeltBurning = false;
        if ("smelt".equals(type) && furnacePos != null) {
            net.minecraft.world.level.block.state.BlockState furnaceState =
                    npc.level().getBlockState(furnacePos);
            smeltBurning = furnaceState.hasProperty(
                            net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)
                    && furnaceState.getValue(
                            net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT);
        }
        if (metric != progressMetric || moved || smeltBurning) {
            progressMetric = metric;
            progressAnchor = pos;
            lastProgressTick = npc.tickCount;
        }
    }

    /** 目标相关的产出计数：与目标无关的物品进出背包不算进展。 */
    private int goalMetric() {
        return switch (type) {
            case "gather" -> gatherCount();
            case "dig", "revisit" -> oreTarget != null ? countItem(oreTarget.drop()) : 0;
            case "smelt", "craft" -> countItem(targetId);
            case "hunt" -> countHuntLoot();
            case "farm" -> countItem("wheat") + countItem("wheat_seeds")
                    + countItem("carrot") + countItem("potato") + countItem("bread");
            case "base" -> basePlacedCount;
            case "slay" -> countItem(slayDrop);
            case "flint" -> countItem("flint");
            case "fillbucket" -> countItem("water_bucket");
            case "obsidian" -> countItem("obsidian");
            default -> 0;
        };
    }

    /** 无进展超时阈值（tick）：按目标类型放宽，但都有上限。 */
    private int noProgressLimitTicks() {
        return switch (type) {
            case "dig" -> GOAL_NO_PROGRESS_TICKS_DIG;
            // 【fix85】place_bed/sleep 与 travel/store_loot 同为「长接近+
            // 短动作」的回家型目标：接近段（下栈/越岭/崖面 descend）动辄
            // 数分钟困在小范围内刨挖，位移 <6 格不触发进展信号，按 DEFAULT
            // 300 秒必被误杀（farm44 实证：stall 满 300 秒被杀、目标死后
            // 10 秒 descend 自行打通）。归 LONG 档（480 秒）与同形目标同口径；
            // 总时长仍有 1800 秒硬顶回收，不会无限期挂死。
            case "smelt", "travel", "revisit", "endportal", "dragon", "farm", "base", "store_loot", "loot_chest", "place_bed", "sleep" -> GOAL_NO_PROGRESS_TICKS_LONG;
            default -> GOAL_NO_PROGRESS_TICKS_DEFAULT;
        };
    }

    /** 总时长硬顶（tick）：进展再零星也不许超过。 */
    private int hardCapTicks() {
        return ("dig".equals(type) || "revisit".equals(type))
                ? GOAL_HARD_CAP_TICKS_DIG : GOAL_HARD_CAP_TICKS_DEFAULT;
    }

    private void finish(String reason) {
        // 【fix40】回访失败计次统一入口：成功/销账/中止不计，其余（超时、
        // 行进失败等）计一次，满 2 次 OreMemory 自动销账防死锁。
        if ("revisit".equals(type) && oreTarget != null
                && !reason.contains("回访成功") && !reason.contains("销账")
                && !reason.contains("中止")) {
            OreMemory.noteAttempt(npc.npcName(), oreTarget.block(), revisitX, revisitY, revisitZ);
        }
        active = false;
        lastOutcome = reason;
        outcomeCount++;
        String doneType = type;
        type = "-";
        // 【第二十三轮】动作有头有尾（日志标准第 2 条）：结束行带耗时与起止位置，
        // 出问题时不用再靠两条日志的时间戳和坐标反推这一段干了多久、走了多远。
        long elapsedMs = goalStartedMillis > 0 ? System.currentTimeMillis() - goalStartedMillis : -1;
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 目标结束 | type=" + doneType + " " + reason
                + " | elapsed=" + (elapsedMs < 0 ? "-"
                        : String.format(java.util.Locale.ROOT, "%.1f", elapsedMs / 1000.0) + "s")
                + " from=" + goalStartX + "," + goalStartY + "," + goalStartZ
                + " to=" + npc.getBlockX() + "," + npc.getBlockY() + "," + npc.getBlockZ());
        // 【第二十三轮】合成/冶炼一批完成后盘点一次（家底触发点之一）。
        if (("craft".equals(doneType) || "smelt".equals(doneType)) && reason.contains("完成")) {
            InventorySnapshot.log(npc, doneType, doneType + ":" + targetId, false);
        }
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
        for (int slot = 0; slot < COUNT_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (!stack.isEmpty() && itemPath.equals(pathOf(stack))) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countPlanks() {
        int total = 0;
        for (int slot = 0; slot < COUNT_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (!stack.isEmpty() && pathOf(stack).endsWith("_planks")) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countItemsEndingWith(String suffix) {
        int total = 0;
        for (int slot = 0; slot < COUNT_SLOTS; slot++) {
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

    /** 是否有可用石镐（第十八轮，石镐主力检查；见底的按没有算，口径同 hasUsablePickaxe）。 */
    private boolean hasUsableStonePick() {
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !"stone_pickaxe".equals(pathOf(stack))) {
                continue;
            }
            if (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage() - 1) {
                return true;
            }
        }
        return false;
    }

    /** 目标矿的收获等级检查（第十七轮）：原版掉落规则——低级镐挖高级矿只碎不掉。 */
    private boolean hasHarvestPickaxe(String blockId) {
        String[] tiers = switch (blockId) {
            case "diamond_ore", "gold_ore", "redstone_ore", "emerald_ore" ->
                    new String[]{"iron_pickaxe", "diamond_pickaxe", "netherite_pickaxe"};
            case "iron_ore", "lapis_ore", "copper_ore" ->
                    new String[]{"stone_pickaxe", "iron_pickaxe", "diamond_pickaxe",
                            "netherite_pickaxe"};
            default -> null;
        };
        if (tiers == null) {
            return true;
        }
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String path = pathOf(stack);
            for (String tier : tiers) {
                if (tier.equals(path) && (!stack.isDamageableItem()
                        || stack.getDamageValue() < stack.getMaxDamage() - 1)) {
                    return true;
                }
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
        for (int slot = 0; slot < COUNT_SLOTS; slot++) {
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
        // 【fix38·背包处置，Fong 04:15/04:17 定】丢弃分两档：
        // 第一档（建筑杂石+地形杂料）必须排在配额裁剪与兜底之前——旧兜底按
        // "最小堆"挑，可能先扔掉小堆正经物资而留着整组杂石；
        // 第二档（蜘蛛眼/骨头/线，怪掉落当前用不上）在配额裁剪之后、兜底之前：
        // 有落脚点箱子时应优先存箱（线日后做弓），存箱逻辑随 P2 箱子落地接上，
        // 在此之前没箱子且背包满就照扔。腐肉是应急口粮，不入档、口径照旧。
        int freed = dropTier1Junk(needed);
        if (freed >= needed) {
            return freed;
        }
        // 再按"每种物品的总保留量"裁剪一遍：这一步与背包是否满无关，
        // 顺便把只剩 1 点耐久的废工具清出去（它们占格但已经不能挖/打）。
        freed += trimOverflow();
        if (freed < needed) {
            freed += dropTier2Junk(needed - freed);
        }
        // 名单式清理没腾够（比如全是工具/关键材料挤在一起）：按堆大小兜底再丢。
        if (freed < needed) {
            freed += dropLeastValuable(needed - freed);
        }
        return freed;
    }

    /** 第一档·建筑杂石（Fong 04:15 名单）：花岗岩/闪长岩/安山岩及其磨制变种。 */
    private static final java.util.Set<String> BUILDING_RUBBLE = java.util.Set.of(
            "granite", "polished_granite",
            "diorite", "polished_diorite",
            "andesite", "polished_andesite");

    /** 第一档·地形杂料（Fong 04:17 扩充）：沙子、沙砾，挖矿/地形途中大量混入。 */
    private static final java.util.Set<String> TERRAIN_JUNK = java.util.Set.of(
            "sand", "gravel");

    /** 第二档（Fong 04:17）：蜘蛛眼/骨头/线——怪掉落、假人当前用不上；满包压力下才扔。
     * 例外（fix68）：线在床链挂起期间是床料，dropTier2Junk 与兜底都护住。 */
    private static final java.util.Set<String> TIER2_JUNK = java.util.Set.of(
            "spider_eye", "bone", "string");

    /**
     * 第一档优先丢弃：建筑杂石 + 地形杂料，整堆丢、丢够为止，返回腾出的槽数。
     * 每堆走 dropItems 统一入口（逐笔流水日志：物品 x数量），末尾再打一行小计
     * （腾格数），验收按这两类行对账：满包时应先见第一档清仓、圆石与正经物资不动。
     * 圆石是功能材料（垫脚/小屋/脱困），不在此列——它的超量由配额裁剪按 128 上限管。
     */
    private int dropTier1Junk(int needed) {
        int freed = 0;
        for (int slot = 0; slot < MAIN_SLOTS && freed < needed; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String path = pathOf(stack);
            String label = BUILDING_RUBBLE.contains(path) ? "建筑杂石优先丢弃 "
                    : TERRAIN_JUNK.contains(path) ? "地形杂料优先丢弃 " : null;
            if (label == null) {
                continue;
            }
            dropItems(slot, stack.getCount(), label + path);
            freed++;
        }
        if (freed > 0) {
            LOG.info("[AIV][INV] " + npc.npcName() + " 第一档优先丢弃小计 | 腾格=" + freed
                    + " 需求=" + needed);
        }
        return freed;
    }

    /**
     * 第二档丢弃：蜘蛛眼/骨头/线整堆丢。只在 dropJunk 满包压力路径里调用
     * （不进周期配额裁剪——平时不扔，线还要留着日后做弓）；
     * P2 落脚点箱子落地后，本档改为"先存箱、箱满/无箱才扔"，在此挂钩。
     */
    private int dropTier2Junk(int needed) {
        // 【fix69】落脚点箱子在 48 格内时，第二档整档先存箱不扔——靠规划层
        // 存箱步（6.77）回落脚点时批量收尾；离箱子远/没箱子时照旧扔（草案
        // v1 简化口径，如实记：不是逐趟折返存，是"在家附近不扔、存箱步收"）。
        if (hasChestWithin(48.0D)) {
            return 0;
        }
        int freed = 0;
        for (int slot = 0; slot < MAIN_SLOTS && freed < needed; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !TIER2_JUNK.contains(pathOf(stack))) {
                continue;
            }
            // 【fix68】床链挂起期间线是床料，第二档也不许扔（床成后恢复）。
            if (pathOf(stack).equals("string") && bedChainPending()) {
                continue;
            }
            dropItems(slot, stack.getCount(), "第二档丢弃 " + pathOf(stack));
            freed++;
        }
        if (freed > 0) {
            LOG.info("[AIV][INV] " + npc.npcName() + " 第二档丢弃小计 | 腾格=" + freed
                    + " 需求=" + needed);
        }
        return freed;
    }

    /** 镐的等级：数值越大越高级。木/金同为 0（金镐挖得快但挖不动铁以上，不比木镐强）。 */
    private static int pickaxeTier(String path) {
        return switch (path) {
            case "netherite_pickaxe" -> 4;
            case "diamond_pickaxe" -> 3;
            case "iron_pickaxe" -> 2;
            case "stone_pickaxe" -> 1;
            default -> 0;
        };
    }

    /** 这把工具还算不算"能用"：按全系统约定，耐久只剩最后 1 点的不算能用。 */
    private static boolean isUsableStack(ItemStack stack) {
        return !stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage() - 1;
    }

    /**
     * 受保护的镐是哪一槽（第三轮修复 D 的保底，全丢弃路径共用）：
     *
     * <ul>
     *   <li>有能用的镐时：最高等级里只有一把能用的 → 就是它（扔了就掉档/断档）；</li>
     *   <li>最高等级有多把能用的 → 不用特别保护（丢一把还剩同级可用的）；</li>
     *   <li>一把能用的都没有 → 保护最高等级的那把残镐，身体层兜底还会用它挖最后一格。</li>
     * </ul>
     *
     * @return 受保护的槽位；背包里根本没有镐时返回 -1
     */
    private int protectedPickaxeSlot() {
        int bestTier = -1;
        int bestSlot = -1;
        int usableAtBest = 0;
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !pathOf(stack).endsWith("_pickaxe") || !isUsableStack(stack)) {
                continue;
            }
            int tier = pickaxeTier(pathOf(stack));
            if (tier > bestTier) {
                bestTier = tier;
                bestSlot = slot;
                usableAtBest = 1;
            } else if (tier == bestTier) {
                usableAtBest++;
            }
        }
        if (bestSlot >= 0) {
            return usableAtBest == 1 ? bestSlot : -1;
        }
        // 没有能用的镐：保护最高等级的残镐（最后稻草）。
        bestTier = -1;
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !pathOf(stack).endsWith("_pickaxe")) {
                continue;
            }
            int tier = pickaxeTier(pathOf(stack));
            if (tier > bestTier) {
                bestTier = tier;
                bestSlot = slot;
            }
        }
        return bestSlot;
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
        // 镐保底（第三轮修复 D）：先算出"受保护的镐"是哪一槽，裁剪时整槽跳过。
        // 长跑实测背包满时石镐被裁掉 8 把以上、全程重造 16 把——裁剪规则互相叠加时
        // 没有一条全局底线。这里钉死：当前能用的最好的一把镐永远不扔；
        // 一把能用的都没有时，最高等级的那把（哪怕只剩 1 耐久）也留着当最后稻草。
        int protectedPickaxe = protectedPickaxeSlot();
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
            if (slot == protectedPickaxe) {
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
    /** 本世界是否已建成落脚点（fix55 建材预留的开关；账本口径同规划层）。 */
    private boolean baseBuiltHere() {
        BaseMemory.bindWorld(npc.level());
        return BaseMemory.hasBase(npc.npcName());
    }

    /**
     * 床链是否挂起（fix68）：本世界有落脚点、但床位还没安置落盘。
     * 这段时间线是床料（线×4 合 1 白羊毛，规划步 6.76 折算），丢弃路径要
     * 把线护住；床安置落盘后自动恢复第二档原口径（存箱优先、没箱才扔）。
     */
    private boolean bedChainPending() {
        BaseMemory.bindWorld(npc.level());
        BaseMemory.Base base = BaseMemory.base(npc.npcName());
        return base != null && base.bedPos() == null;
    }

    private int junkKeepLimit(String path) {
        switch (path) {
            case "sand", "clay_ball",
                 "polished_andesite", "polished_diorite", "polished_granite",
                 "calcite", "deepslate",
                 // 【fix54】落叶层（leaf_litter）：森林地表装饰，打草/路过自动
                 // 捡满包（实证囤到 215 个、3 格半），纯地形杂料不保留。
                 "leaf_litter",
                 // 【fix38·Fong 04:17】沙砾列第一档地形杂料、不再保留：
                 // 旧版留 16 个筛燧石，但燧石有专门的"找燧石"目标按需重挖，
                 // 满包时先保格子；筛出的燧石本身在永不扔清单里，不受影响。
                 "gravel" -> {
                return 0;
            }
            // 【fix55】建屋建材预留：圆石深板岩/凝灰岩/泥土/三杂石是 fix51
            // 小屋的砌墙料（pickBuildMaterial「拿来砌墙正好废物利用」、
            // 规划层建屋触发也按它们计数），旧口径一律保留=0 等于挖一块
            // 扔一块——farm8~10 三局实证建屋触发 0 次、地表建材永远攒
            // 不够 80。只在「本世界还没落脚点」时给预留额度，建成后恢复
            // 0 照旧不囤；这只改周期配额裁剪，满包时的第一档优先丢弃
            // 顺序（Fong 04:15 规格）不动，压力兜底仍在。
            case "cobbled_deepslate" -> {
                return baseBuiltHere() ? 0 : 96;
            }
            case "tuff", "andesite", "diorite", "granite" -> {
                return baseBuiltHere() ? 0 : 64;
            }
            case "dirt" -> {
                return baseBuiltHere() ? 0 : 48;
            }
            // 圆石只留 1 格（128 个）：它是世界上最好再挖的东西，
            // 实测囤到 700+ 个会占掉 12 格，合成产物直接没地方放（空槽=0 导致全科技树卡死）。
            case "cobblestone" -> {
                return 128;
            }
            // 【fix92·Fong 2026-10-28 拍板】「红石最多就留一组，其他都扔了。
            // 回家就把这些放箱子」：保留=64，超出部分由本裁剪清掉；留存的
            // 一组回家存箱（STORE_LOOT 已同步加入 redstone）。此口径只点名
            // 红石，滴石块等其他大宗囤积不套用。
            case "redstone" -> {
                return 64;
            }
            case "stone" -> {
                return 64;
            }
            // 沙砾保留量已按 Fong 04:17 改为 0（见上方第一档分组）。
            case "bamboo" -> {
                return 32;
            }
            // 【fix54】小麦种子是田的命根子（D11），不再走下面 *_seeds 的
            // 杂物口径：整组保留。fix49 短验实证旧口径保留=0，NPC 一边打草
            // 一边把种子全扔了、攒种阶段永远攒不够（17 次拾取全被裁掉）。
            case "wheat_seeds" -> {
                return 64;
            }
            case "stick" -> {
                return 16;
            }
            // 【fix91】腐肉移出本组：Fong 定腐肉是应急口粮（平时不吃、
            // 危险线才吃，见 SurvivalController.foodScore 的 fix91 分支），
            // 盘点/规划/hunt 存量也都按食物记账——旧口径把它和蜘蛛眼等
            // 真毒物同组保留=0，farm50 实证 NPC food=0/hp=8.8 时拾到的
            // 唯一食物腐肉×2 在 9 秒后被配额裁剪整堆扔掉。移出后落
            // default -1，与其他食物同格不裁剪。
            case "spider_eye", "poisonous_potato", "pufferfish" -> {
                return 0;
            }
            // 低阶工具/护甲：有更好的就丢，留着只会占槽位。
            case "wooden_pickaxe", "wooden_sword", "wooden_axe", "wooden_shovel", "wooden_hoe",
                 "leather_helmet", "leather_chestplate", "leather_leggings", "leather_boots",
                 "golden_helmet", "golden_chestplate", "golden_leggings", "golden_boots",
                 "chainmail_helmet", "chainmail_chestplate", "chainmail_leggings", "chainmail_boots" -> {
                // 用"可用口径"（countItemFor，滤掉只剩 1 耐久的）判断有没有更好的镐：
                // 以前用 countItem，一把只剩 1 耐久的石镐也会让木镐保留量变 0，
                // 等于把唯一还能用的木镐扔了（第一轮遗留问题）。
                return countItemFor("stone_pickaxe") > 0 || countItemFor("iron_pickaxe") > 0
                        || countItemFor("diamond_pickaxe") > 0 ? 0 : 1;
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
                // 【fix75】火把是规划步 13.06 按常备 ≥8 维护的消耗品，不保护
                // 就会被满包兜底当最小堆扔掉（farm33 实证：合成 8/8 后 23 秒
                // 全数丢弃、规划又重派合成，烧煤烧棍空转）。
                || path.equals("torch")
                || path.equals("flint_and_steel") || path.equals("obsidian")
                || path.equals("ender_eye") || path.equals("ender_pearl")
                || path.equals("blaze_rod") || path.equals("blaze_powder")
                || path.equals("iron_ingot") || path.equals("diamond")
                || path.equals("raw_iron") || path.equals("raw_gold") || path.equals("gold_ingot")
                || path.equals("redstone") || path.equals("lapis_lazuli")
                || path.equals("coal") || path.equals("charcoal") || path.equals("flint")
                || path.equals("crafting_table") || path.equals("furnace")
                // 【fix54】种田物资兜底保护：小麦种子（田的命根子）、骨粉
                // （催熟料，堆小、最容易被"最小堆兜底"误杀）。
                || path.equals("wheat_seeds") || path.equals("bone_meal")
                // 【fix66】主粮与床料兜底保护：wheat 在原版没有 FOOD 组件
                // （不能直接吃）、不在名单就会被兜底当最小堆扔掉（farm22
                // 收获后 wheat×3 被扔实证）——它是面包原料、种田产出的主粮；
                // 羊毛是 D1 床料（同色 3 张靠猎羊零散攒），farm24 实证拾到
                // 即被兜底扔掉（x1/x2 两笔），不护住床链永远攒不齐。
                || path.equals("wheat") || path.endsWith("_wool")
                // 【fix83】床本体兜底保护：farm41 实证——20:57 合成的白床在
                // 当晚满包兜底里被当最小堆扔掉（21:28:52），NPC 隔天重攒羊毛
                // 再合一张。床是 D1 链的交付物本身，与床料同格不受兜底清理。
                || path.endsWith("_bed")
                // 【fix68】床链挂起期间线是床料（线×4 合白羊毛）：兜底不许扔，
                // 否则自卫杀蜘蛛攒的线被满包实时清零、床链永远齐不了（与
                // fix66 羊毛同病）。床安置后 bedChainPending 转假、自动恢复。
                || (path.equals("string") && bedChainPending())
                // 木料类是整条科技树的根基（木棍→工具/箭、木板→工作台/木棍），
                // 兜底清理时把它当关键材料保住，否则"背包满"会先把木棍丢掉。
                || path.equals("stick") || path.equals("bamboo") || path.equals("bamboo_block")
                || path.endsWith("_planks") || path.endsWith("_log") || path.endsWith("_stem")
                // 【fix89】圆石族兜底保护（圆石/深板岩圆石/黑石）：Fong 明规
                // 「圆石是功能材料（垫脚/小屋/脱困）、永不扔」，超量由
                // trimOverflow 的配额（128）管，不归兜底。兜底按最小堆挑，
                // 刚挖到的小堆圆石永远是第一受害者——farm48 实证满包兜底
                // 把刚挖的圆石 ×11 整堆扔掉（04:00:43），同一秒派发的石镐
                // 合成随即因缺圆石失败，挖石→扔石→合成败循环 ×3。
                || path.equals("cobblestone") || path.equals("cobbled_deepslate")
                || path.equals("blackstone")) {
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
        // 兜底路径同样受镐保底约束（第三轮修复 D）：再满也不能把最好的一把可用镐扔了。
        int protectedPickaxe = protectedPickaxeSlot();
        for (int round = 0; round < needed; round++) {
            int worstSlot = -1;
            int worstCount = Integer.MAX_VALUE;
            for (int slot = 0; slot < MAIN_SLOTS; slot++) {
                ItemStack stack = npc.getInventory().getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                if (slot == protectedPickaxe) {
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
            boolean lastResortUsed = false;
            if (worstSlot < 0) {
                // 【fix90】二线兜底：一线（非关键物）挑不出受害者时——farm49
                // 实证 36 格全是关键物（工具/护甲/矿石/口粮全在保护名单）、
                // 兜底返回 0、合成产物无处落物「放不下」连败死循环。二线只对
                // 大宗消耗料下手（煤/红石/燧石/种子/箭/青金石等，取最小堆），
                // 硬保：一切耐久物（工具/武器/护甲）、一切能吃的、贵重锭料与
                // 通关物（钻石/铁锭/金/珍珠/烈焰棒/黑曜石/桶/工作台/熔炉）、
                // 火把与圆石族（fix89）。玩家满包做镐时也会先扔一把煤腾格。
                for (int slot = 0; slot < MAIN_SLOTS; slot++) {
                    ItemStack stack = npc.getInventory().getItem(slot);
                    if (stack.isEmpty() || slot == protectedPickaxe) {
                        continue;
                    }
                    if (!isLastResortDroppable(stack)) {
                        continue;
                    }
                    if (stack.getCount() < worstCount) {
                        worstCount = stack.getCount();
                        worstSlot = slot;
                    }
                }
                lastResortUsed = worstSlot >= 0;
            }
            if (worstSlot < 0) {
                return freed;
            }
            ItemStack stack = npc.getInventory().getItem(worstSlot);
            int amount = stack.getCount();
            // 走统一的丢弃入口：那里会把拾取延迟设成永久，避免伪玩家两秒后又捡回来。
            dropItems(worstSlot, amount, "背包塞满，兜底 " + pathOf(stack)
                    + (lastResortUsed ? "（二线）" : ""));
            freed++;
        }
        return freed;
    }

    /** 【fix90】二线兜底可丢的大宗消耗料：非耐久、不能吃、不在硬保名单。
     * 圆石族不在此列（fix89 已钉死永不扔）；名单外的大宗囤积（滴石块等）
     * 天然落入本线——按最小堆挑，先小后大。 */
    private boolean isLastResortDroppable(ItemStack stack) {
        if (stack.isDamageableItem()) {
            return false;
        }
        String path = pathOf(stack);
        net.minecraft.world.item.Item item = item(path);
        if (item != Items.AIR
                && new ItemStack(item).get(net.minecraft.core.component.DataComponents.FOOD) != null) {
            return false;
        }
        return switch (path) {
            case "diamond", "iron_ingot", "gold_ingot", "raw_iron", "raw_gold",
                    "ender_pearl", "ender_eye", "blaze_rod", "blaze_powder",
                    "obsidian", "bucket", "water_bucket", "crafting_table",
                    "furnace", "torch", "cobblestone", "cobbled_deepslate",
                    "blackstone" -> false;
            // 【fix92·Fong 2026-10-28】红石最多留一组：总量 ≤64 时二线
            // 也不许动；超出时才可丢——二线按最小堆挑受害者，丢掉最小的
            // 红石堆后总量仍 ≥64 或收敛到一组内，不会把留存组扔穿。
            case "redstone" -> countItem("redstone") > 64;
            default -> true;
        };
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

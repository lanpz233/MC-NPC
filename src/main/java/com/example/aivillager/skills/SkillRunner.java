package com.example.aivillager.skills;

import com.example.aivillager.goals.OreMemory;
import com.example.aivillager.intent.Intent;
import com.example.aivillager.npc.FakePlayerBody;
import com.example.aivillager.pathfinding.PathFinder;
import com.example.aivillager.survival.SurvivalController;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 技能层：把"意图"翻译成身体能执行的动作，全部跑在主线程。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>同一时刻只允许一个技能，新技能会先取消旧技能，避免走路和攻击同时抢输入。</li>
 *   <li>每次执行都带一个 {@code runId}；被取消后迟到的 walk 回调直接作废，不会"幽灵行动"。</li>
 *   <li>所有参数都做上限裁剪（半径、次数、时长），因为意图来自模型，不能信。</li>
 * </ul>
 *
 * <p>技能列表：goto（走到坐标）、mine（找最近方块并挖）、attack（找最近敌对生物并打）、follow（跟随玩家）。
 */
public final class SkillRunner {

    private static final Logger LOG = Logger.getLogger("AIVillager");

    /** 搜索方块/生物的最大半径，防止模型给出离谱坐标导致全服扫描。 */
    private static final int MAX_SEARCH_RADIUS = 16;
    /** goto 的单次最大位移；再远就不追了，避免 A* 扫爆主线程。 */
    private static final double MAX_GOTO_DISTANCE = 48.0D;
    /** 单次技能的最长执行时间（tick），到点强制结束，避免卡死。带了 A* 之后放宽到 40 秒。 */
    private static final int SKILL_TIMEOUT_TICKS = 800;

    private final Plugin plugin;
    private final FakePlayerBody npc;
    private final List<BukkitTask> tasks = new ArrayList<>();

    private String current = "idle";
    /** 上一次技能的结果，喂回给大脑，避免模型反复尝试不可能的动作（比如在平地上找石头）。 */
    private String lastOutcome = "-";
    /** 每次 finish 自增，让规划器知道"有新结果了"，哪怕字符串和上次一样。 */
    private int outcomeSeq;
    private int runId;
    /**
     * 上一条 strip 矿道的掘进方向（0=东 1=南 2=西 3=北，-1 表示还没挖过）。
     * 玩家不会对着自己刚挖空的矿道继续掘进；这里记住方向，在"空挖"之后
     * 让下一条矿道垂直于旧矿道分支，把矿道挖成鱼骨状。
     */
    private int lastStripDir = -1;
    /** 上一条矿道有没有挖到目标矿石；false 表示这片区域大概率已经挖空。 */
    private boolean lastStripFoundOre;
    /** 上一条矿道结束的时间戳：只对"刚才挖的"矿道做分支判断，避免几分钟前的旧方向误导。 */
    private long lastStripEndMillis;

    public SkillRunner(Plugin plugin, FakePlayerBody npc) {
        this.plugin = plugin;
        this.npc = npc;
    }

    public String currentSkill() {
        return current;
    }

    public String lastOutcome() {
        return lastOutcome;
    }

    /** 每次 finish 自增，让规划器检测到"有新技能结束"。 */
    public int outcomeSeq() {
        return outcomeSeq;
    }

    /**
     * 执行一个意图。返回 false 表示动作无法执行（参数非法或找不到目标）。
     *
     * @param listenerName 当前和 NPC 说话的人，follow 缺省跟随他
     */
    public boolean run(Intent intent, String listenerName) {
        stop();
        int id = runId;
        String action = intent.action() == null ? "none" : intent.action();

        switch (action) {
            case "goto" -> {
                double x = intent.doubleParam("x", npc.getX());
                double z = intent.doubleParam("z", npc.getZ());

                // 模型给的坐标可能很远，先按 48 格截断，避免一次 A* 扫太大范围。
                double offsetX = x - npc.getX();
                double offsetZ = z - npc.getZ();
                double distance = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ);
                if (distance > MAX_GOTO_DISTANCE) {
                    double scale = MAX_GOTO_DISTANCE / distance;
                    x = npc.getX() + offsetX * scale;
                    z = npc.getZ() + offsetZ * scale;
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " goto 目标过远，截断到 "
                            + String.format(Locale.ROOT, "%.1f", MAX_GOTO_DISTANCE) + " 格");
                }

                // goto 只给 x/z；y 以当前高度为基准，再上下找最近的合法落脚点（洞穴和地表都能用）。
                BlockPos desired = BlockPos.containing(x, npc.getY(), z);
                BlockPos goal = PathFinder.standableGoal(npc.level(), desired, 6);
                if (goal == null && distance > 16.0D) {
                    // 【第四十六轮补3】远途落点先放宽垂直扫描：台地崖面列的地表常在
                    // 当前 Y ±6 之外（树在 +43 高的台顶、12 格外坡面已高出 10 格），
                    // ±12 扫到坡面落点后 A* 逐列爬台阶；近途仍用 ±6 防洞穴串层。
                    goal = PathFinder.standableGoal(npc.level(), desired, 12);
                }
                if (goal == null && distance > 16.0D) {
                    // 【第四十六轮补】远途落点回退：截断/全程落点列在台地陡坡上时
                    // ±6 格内常常没有落脚格，旧行为整跳判死（恶地复跑实证：trek 瞄到
                    // 55 格外树列、48 格截断落点不可站立、连续 12 次全灭、一步没挪）。
                    // 沿原射线按 32/24/16 格逐档缩短，取第一个有落脚格的落点先走一段，
                    // 上层下一轮会接着往原目标推进；全档无落点才走原判死路径。
                    double ux = offsetX / distance;
                    double uz = offsetZ / distance;
                    for (double hop : new double[]{32.0D, 24.0D, 16.0D}) {
                        if (hop >= Math.min(distance, MAX_GOTO_DISTANCE)) {
                            continue;
                        }
                        BlockPos hopPos = BlockPos.containing(
                                npc.getX() + ux * hop, npc.getY(), npc.getZ() + uz * hop);
                        BlockPos hopGoal = PathFinder.standableGoal(npc.level(), hopPos, 6);
                        if (hopGoal != null) {
                            goal = hopGoal;
                            LOG.info("[AIV][SKILL] " + npc.npcName() + " goto 落点回退 | 原目标不可站立，"
                                    + "先走 " + (int) hop + " 格到 " + hopGoal.toShortString());
                            break;
                        }
                    }
                }
                if (goal == null) {
                    // 短距离兜底（第六轮补充3）：终点列没有落脚格（水中树岛的树干列等），
                    // 且距离 ≤16 格时直接朝目标点直线走——见 walkStraightTo 的说明。
                    if (horizontalDistanceTo(desired) <= 16.0D && walkStraightTo(id, desired)) {
                        return true;
                    }
                    lastOutcome = "goto 目标附近没有可站立位置 x=" + Math.round(x) + " z=" + Math.round(z);
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " goto 目标不可站立 | x=" + x + " z=" + z);
                    return false;
                }

                // goal 经落点回退后可能被重赋值，lambda 捕获需要快照。
                final BlockPos pathGoal = goal;
                PathFinder.Result result = PathFinder.find(npc.level(), npc.blockPosition(), pathGoal, !npc.wearingLeatherBoots());
                logPath("goto", pathGoal, result);
                if (!result.found()) {
                    // 短距离兜底（第六轮补充3）：A* 把流体判不可通行，沼泽/河口地形里
                    // 出生在一小块干岛上时四周全是水，A* 只展开几个节点就无路可走，
                    // 十几格外水中的树永远够不着（fix8/fix9 长跑实测，背包全空卡死）。
                    // ≤16 格改直线走：身体会自己蹚浅水/游泳（生存层游泳脱困接管），
                    // 撞墙有 stuck-rescue 挖路，80 tick 无进展自动放弃、技能看门狗兜底，
                    // 不会无限硬走；长距离 goto 行为不变，仍只认 A*。
                    if (horizontalDistanceTo(pathGoal) <= 16.0D && walkStraightTo(id, pathGoal)) {
                        return true;
                    }
                    lastOutcome = "goto 找不到路径 " + result.detail();
                    return false;
                }

                current = "goto";
                LOG.info("[AIV][SKILL] " + npc.npcName() + " goto 开始 | goal=" + pathGoal.toShortString()
                        + " 节点=" + result.path().size());
                npc.followPath(result.path(), false,
                        () -> finish(id, "goto", "到达 " + pathGoal.toShortString()),
                        () -> finish(id, "goto", "路径中断 " + pathGoal.toShortString()));
                armWatchdog(id, "goto");
                return true;
            }
            case "mine" -> {
                return runMine(intent, id);
            }
            case "attack" -> {
                return runAttack(intent, id);
            }
            case "descend" -> {
                return runDescend(intent, id);
            }
            case "ascend" -> {
                return runAscend(intent, id);
            }
            case "dryland" -> {
                return runDryLand(intent, id);
            }
            case "strip" -> {
                return runStrip(intent, id);
            }
            case "hunt" -> {
                return runHunt(intent, id);
            }
            case "slay" -> {
                return runSlay(intent, id);
            }
            case "shoot" -> {
                return runShoot(intent, id);
            }
            case "travel" -> {
                return runTravel(intent, id);
            }
            case "follow" -> {
                return runFollow(intent.param("player", listenerName), id);
            }
            // 下界通关路线的四个专用技能（装水→浇黑曜石→建门→穿门）。
            case "fillbucket" -> {
                return runFillBucket(id);
            }
            case "castobsidian" -> {
                return runCastObsidian(intent, id);
            }
            case "buildportal" -> {
                return runBuildPortal(intent, id);
            }
            case "enterportal" -> {
                return runEnterPortal(intent, id);
            }
            case "idle", "none" -> {
                LOG.info("[AIV][SKILL] " + npc.npcName() + " idle | 无动作");
                return true;
            }
            // 注意：不再有 "respawn" 主动重生技能（Fong 定：不许故意自杀逃回出生点）。
            // 被困死局靠挖/垫/游脱困，挖不动就失败收场让规划层换活，绝不许拿死亡当传送用。
            default -> {
                LOG.warning("[AIV][SKILL] " + npc.npcName() + " 不支持的技能: " + action);
                return false;
            }
        }
    }

    /** 停止当前技能：取消所有定时任务、清空行走目标，并让旧回调失效。 */
    public void stop() {
        runId++;
        current = "idle";
        cancelTasks();
        npc.stopWalking();
    }

    /** NPC 到目标格的水平距离（格）。 */
    private double horizontalDistanceTo(BlockPos target) {
        double dx = target.getX() + 0.5D - npc.getX();
        double dz = target.getZ() + 0.5D - npc.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * 直线走兜底（第六轮补充3）：A* 无路但目标就在十几格内时（多半隔着浅水/沼泽），
     * 让身体朝目标点直线走——蹚水、游泳、撞墙挖路都由身体层/生存层现有机制接管，
     * 这里只负责下发和收尾（到达回调或技能看门狗超时）。玩家在这种地形里也是
     * 直接走过去的，不会因为"寻路算法说无路"就站在干岛上发呆。
     */
    private boolean walkStraightTo(int id, BlockPos goal) {
        current = "goto";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " goto-straight | goal=" + goal.toShortString()
                + " dist=" + String.format(Locale.ROOT, "%.1f", horizontalDistanceTo(goal)));
        npc.walkTo(new Vec3(goal.getX() + 0.5D, goal.getY(), goal.getZ() + 0.5D), false,
                () -> finish(id, "goto", "直线到达 " + goal.toShortString()));
        armWatchdog(id, "goto");
        return true;
    }

    // ---------------------------------------------------------------- 具体技能

    /**
     * 找到最近的指定方块，走到它旁边再挖。
     * 之所以要先走：ServerPlayerGameMode.destroyBlock 有交互距离校验，隔空挖会直接返回 false。
     */
    private boolean runMine(Intent intent, int id) {
        String blockName = intent.param("block", "");
        // "*_log" 是"任意原木"通配符：模型不该被树种限制。
        // 丛林里就砍丛林木、白桦林砍白桦，由技能层自己找最近且够得着的那棵树。
        boolean anyLog = "*_log".equals(blockName) || "log".equals(blockName) || "wood".equals(blockName);
        Block block = null;
        if (!anyLog) {
            Material material = Material.matchMaterial(blockName);
            if (material == null || !material.isBlock()) {
                lastOutcome = "mine 参数非法 block=" + blockName;
                LOG.warning("[AIV][SKILL] " + npc.npcName() + " mine 参数非法 | block=" + blockName);
                return false;
            }
            block = CraftMagicNumbers.getBlock(material);
        }
        int radius = Math.max(2, Math.min(16, intent.intParam("radius", 8)));
        // 是不是矿石（够矿求解对矿石启用；【第三十五轮】起原木在站立点全灭时
        // 也走同一求解作 tree-reach 兜底，见下方 chosen==null 分支）。
        boolean oreBlock = !anyLog && block != null
                && BuiltInRegistries.BLOCK.getKey(block).getPath().endsWith("_ore");
        List<BlockPos> targets = findNearestBlocks(block, anyLog, radius);
        if (oreBlock) {
            // 矿石带深板岩变种：目标层的 16 格探测把变种也算数，这里必须同口径，
            // 否则探测说"有矿"、技能却只找普通矿，对深板岩矿永远扑空。
            Block twin = deepslateTwin(block);
            if (twin != null) {
                List<BlockPos> merged = new ArrayList<>(targets);
                merged.addAll(findNearestBlocks(twin, false, radius));
                BlockPos centerPos = npc.blockPosition();
                merged.sort(Comparator.comparingDouble(centerPos::distSqr));
                targets = new ArrayList<>(merged.subList(0, Math.min(6, merged.size())));
            }
        }
        if (targets.isEmpty()) {
            lastOutcome = "mine 找不到方块 block=" + blockName + " radius=" + radius;
            LOG.info("[AIV][SKILL] " + npc.npcName() + " mine 找不到方块 | block=" + blockName + " radius=" + radius);
            return false;
        }
        // 逐个候选找落脚点：丛林树常是 2x2 粗树干，外侧被藤蔓/树叶堵死，
        // 最近的树 A* 到不了不代表附近别的树也不行——换下一棵继续试，而不是直接放弃。
        PathFinder.Result chosen = null;
        BlockPos target = null;
        BlockPos stand = null;
        int tried = 0;
        // 候选站立点有 17 个，每个都要跑一次 A*；一 tick 最多跑 8 次，
        // 否则一次采集就可能占掉主线程上百毫秒，服务器 tick 会被拖慢。
        int pathBudget = 8;
        outer:
        for (BlockPos candidateTarget : targets) {
            tried++;
            for (BlockPos candidate : standCandidates(candidateTarget)) {
                if (!PathFinder.isStandable(npc.level(), candidate)) {
                    continue;
                }
                if (pathBudget-- <= 0) {
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " mine 寻路预算用尽 | 已试 "
                            + tried + " 棵，先放弃本次");
                    break outer;
                }
                PathFinder.Result result = PathFinder.find(npc.level(), npc.blockPosition(), candidate, !npc.wearingLeatherBoots());
                logPath("mine-approach", candidate, result);
                if (result.found()) {
                    target = candidateTarget;
                    stand = candidate;
                    chosen = result;
                    break outer;
                }
            }
        }
        if (chosen == null) {
            // 够矿求解（Fong 02:53 规格）：矿石有裸露面、位置已知，但周边没有现成
            // 落脚点（洞顶矿/墙后矿/低处矿）时不直接放弃——按情形选跳搭或挖通接近。
            // 只对矿石启用，且候选必须仍是裸露的（探测口径不变，不透视）。
            if (oreBlock) {
                for (BlockPos candidateTarget : targets) {
                    if (!isExposed(candidateTarget)) {
                        continue;
                    }
                    String reachMode = oreReachMode(candidateTarget);
                    if (reachMode != null) {
                        return runOreReach(intent, id, candidateTarget, reachMode);
                    }
                }
            }
            // 【第三十五轮】原木同理走够取求解（tree-reach）：长跑#12 实证——
            // trek-to-tree 能把 NPC 送到看得见的树跟前（17 格/29 格两例），但树干
            // 长在陡坡台地上时 17 个站立候选全灭、A* 无路，mine 直接"候选全部
            // 不可达"放弃，采集连败到 7、整局三度被木头卡住（开局 26 分钟 +
            // 两次补木危机）。树干是地表可见物、不存在透视问题，玩家够不到树干
            // 时会挖台阶/垫柱上去砍，这里复用同一状态机（接近挖步/垫柱/拆柱
            // 都是玩家手段）。旧注释"别把垫柱用在树上"防的是常态滥用——现在
            // 只在站立点全灭的兜底才启用，常态砍树路径不变。
            if (anyLog) {
                for (BlockPos candidateTarget : targets) {
                    String reachMode = oreReachMode(candidateTarget);
                    if (reachMode != null) {
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " tree-reach | mode=" + reachMode
                                + " target=" + candidateTarget.toShortString());
                        return runOreReach(intent, id, candidateTarget, reachMode);
                    }
                }
            }
            lastOutcome = "mine 没有可到达的站立点 候选=" + tried;
            LOG.info("[AIV][SKILL] " + npc.npcName() + " mine 候选全部不可达 | block=" + blockName
                    + " 候选=" + tried);
            return false;
        }
        if (oreBlock) {
            LOG.info("[AIV][SKILL] " + npc.npcName() + " ore-reach | mode=direct target="
                    + target.toShortString() + " block=" + blockName);
        }

        // lambda 里要用，先落成 final 局部变量。
        final BlockPos mineTarget = target;
        final BlockPos standPos = stand;
        String actualBlock = BuiltInRegistries.BLOCK.getKey(npc.level().getBlockState(mineTarget).getBlock()).getPath();
        current = "mine";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " mine 开始 | block=" + blockName
                + " actual=" + actualBlock
                + " target=" + mineTarget.toShortString() + " stand=" + standPos.toShortString()
                + " 节点=" + chosen.path().size());

        npc.followPath(chosen.path(), false, () -> {
            if (this.runId != id) {
                return;
            }
            // 挖掉正脚下的方块会立刻掉下去，连续两次就会困在 2 格深坑里。
            // 候选点已经排除了正上方，这里再兜一层，防止路径终点刚好等于脚下。
            if (mineTarget.equals(npc.blockPosition().below())) {
                finish(id, "mine", "拒绝自埋 目标在正下方 pos=" + mineTarget.toShortString());
                return;
            }
            // AutoTool：石头/矿石必须手持正确镐子才有掉落，空手挖等于白干。
            boolean success = npc.mineWithBestTool(mineTarget);
            finish(id, "mine", "success=" + success + " pos=" + mineTarget.toShortString());
        }, () -> finish(id, "mine", "无法到达 " + mineTarget.toShortString()));
        armWatchdog(id, "mine");
        return true;
    }

    // ---------------------------------------------------------------- 够矿求解

    /** 垫脚/搭桥可用的方块全集（与 pickBuildingBlock 同口径，含木板；垫柱预检时单独排除木板）。 */
    private static final java.util.Set<String> BUILDING_BLOCK_PATHS = java.util.Set.of(
            "cobblestone", "netherrack", "blackstone", "basalt", "cobbled_deepslate",
            "stone", "andesite", "diorite", "granite",
            "oak_planks", "spruce_planks", "birch_planks", "jungle_planks",
            "acacia_planks", "dark_oak_planks", "mangrove_planks", "cherry_planks",
            "bamboo_planks", "pale_oak_planks");

    /** 矿石的深板岩互变种（普通 ↔ 深板岩）；没有对应变种返回 null。 */
    private Block deepslateTwin(Block block) {
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        String twinPath = path.startsWith("deepslate_")
                ? path.substring("deepslate_".length()) : "deepslate_" + path;
        Material material = Material.matchMaterial(twinPath);
        return material != null && material.isBlock() ? CraftMagicNumbers.getBlock(material) : null;
    }

    /**
     * 给一块够不着的裸露矿分类够法（Fong 02:53 规格）：
     * 头顶 +3~+6 → 跳搭垫柱（pillar；水平再远也先由接近阶段走到柱位）；
     * 16 格内、高差 -3~+2 → 挖通接近（dig）；同列/邻列的低处矿（高差 -6~-1）→
     * dig 里的挖脚下逐格落（digStepDown）。都不沾边返回 null（老实放弃，
     * 交给目标层走 descend/strip 老路）。更低的矿（< -6）不硬够：目标层会
     * descend 到矿层再重新探到它，那时高差归零、直接挖——组合手段，不死磕。
     * 跳搭有材料预检：石系建筑块（不含木板，护住木料经济）不够垫到高度就别起跳。
     */
    private String oreReachMode(BlockPos target) {
        BlockPos feet = npc.blockPosition();
        int dy = target.getY() - feet.getY();
        double horiz = horizontalDistanceTo(target);
        if (dy >= 3 && dy <= 6 && horiz <= 16.0D) {
            return countBuildingBlocks() >= dy - 1 ? "pillar" : null;
        }
        if (horiz <= 16.0D && dy >= -3 && dy <= 2) {
            return "dig";
        }
        if (horiz <= 1.5D && dy >= -6 && dy < 0) {
            return "dig";
        }
        return null;
    }

    /** 石系建筑块总数（pickBuildingBlock 优先级表的前段，不含木板）。 */
    private int countBuildingBlocks() {
        int total = 0;
        for (String candidate : List.of("cobblestone", "netherrack", "blackstone", "basalt",
                "cobbled_deepslate", "stone", "andesite", "diorite", "granite")) {
            total += countItem(candidate);
        }
        return total;
    }

    /** 眼睛到方块中心 ≤4.5 格：原版生存挖掘的够取范围，超出时 mineWithBestTool 也会失败。 */
    private boolean withinMineReach(BlockPos target) {
        return npc.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) <= 4.5D * 4.5D;
    }

    private boolean collisionEmpty(BlockPos pos) {
        return npc.level().getBlockState(pos).getCollisionShape(npc.level(), pos).isEmpty();
    }

    private boolean fluidAt(BlockPos pos) {
        return !npc.level().getBlockState(pos).getFluidState().isEmpty();
    }

    /** 目标格六邻里有岩浆：挖开它等于把岩浆放进来，惜命口径下不挖。 */
    private boolean touchesLava(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (npc.level().getBlockState(pos.relative(dir)).getFluidState().is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBuildingBlock(BlockPos pos) {
        String path = BuiltInRegistries.BLOCK.getKey(npc.level().getBlockState(pos).getBlock()).getPath();
        return BUILDING_BLOCK_PATHS.contains(path);
    }

    /**
     * 挖通接近一步：朝目标方向掘进一格（挖开脚位/头位两格通道，必要时落下 ≤3 格到固体干地）。
     * 方向只由"已探到的裸露矿"位置决定（朝着洞壁上看得见的矿挖过去是正常采矿），
     * 不存在透视问题；中途遇流体格、岩浆邻接就换方向，绝不挖穿液体层。
     *
     * @return 是否产生了有效动作（挖了方块或开始移动）
     */
    private boolean digStepToward(BlockPos target) {
        BlockPos feet = npc.blockPosition();
        int dx = target.getX() - feet.getX();
        int dz = target.getZ() - feet.getZ();
        if (dx == 0 && dz == 0) {
            // 同一列只处理矿在正下方（逐格挖脚下落）；正上方是跳搭的活，这里不接。
            return target.getY() < feet.getY() && digStepDown(feet);
        }
        int sx = Integer.signum(dx);
        int sz = Integer.signum(dz);
        int[][] dirs = Math.abs(dx) >= Math.abs(dz)
                ? new int[][]{{sx, 0}, {0, sz}} : new int[][]{{0, sz}, {sx, 0}};
        for (int[] d : dirs) {
            if (d[0] == 0 && d[1] == 0) {
                continue;
            }
            if (tryDigStep(feet, d[0], d[1])) {
                return true;
            }
        }
        return false;
    }

    private boolean tryDigStep(BlockPos feet, int sx, int sz) {
        BlockPos front = feet.offset(sx, 0, sz);
        if (fluidAt(front) || fluidAt(front.above())) {
            return false;
        }
        // 矿道标准断面：先清出脚位与头位两格。
        for (BlockPos cell : List.of(front, front.above())) {
            if (!collisionEmpty(cell)) {
                if (touchesLava(cell)) {
                    return false;
                }
                if (!npc.mineWithBestTool(cell) || !collisionEmpty(cell)) {
                    return false;
                }
            }
        }
        // 落脚点：从 front 起向下最多探 3 格，第一个有碰撞的格子顶面就是落点。
        // 中间每一格都查流体： shaft 里有水/岩浆就不往下跳（惜命）。
        for (int i = 0; i <= 3; i++) {
            BlockPos floor = front.below(i);
            if (fluidAt(floor)) {
                return false;
            }
            if (!collisionEmpty(floor)) {
                // i=0/1 是平步或落 1 格；i 越大落差越大，封顶 3 格（原版 3 格内无摔伤）。
                npc.walkTo(new Vec3(front.getX() + 0.5D, floor.getY() + 1, front.getZ() + 0.5D),
                        false, null);
                return true;
            }
        }
        return false;
    }

    /** 同列下挖一格：挖掉脚下，落到 ≤3 格内的固体干地；落点不明或有流体就不挖（惜命）。 */
    private boolean digStepDown(BlockPos feet) {
        BlockPos below = feet.below();
        if (fluidAt(below) || touchesLava(below) || collisionEmpty(below)) {
            return false;
        }
        boolean landingOk = false;
        for (int i = 1; i <= 3; i++) {
            BlockPos c = below.below(i);
            if (fluidAt(c)) {
                return false;
            }
            if (!collisionEmpty(c)) {
                landingOk = true;
                break;
            }
        }
        if (!landingOk) {
            return false;
        }
        return npc.mineWithBestTool(below);
    }

    /**
     * 够矿求解主状态机（Fong 02:53 规格）：目标矿已探到（裸露、位置确定）但没有
     * 现成落脚点。pillar = 跳搭垫柱够洞顶矿，挖完逐块拆柱安全下来；
     * dig = 挖通接近（隔墙掘进/台阶向下）。全程走身体层原版物理：跳、走、挖、放块，
     * 零传送；总轮数与单步等待都有死线，卡住放弃回报目标层（goal-timeout 再兜底）。
     */
    private boolean runOreReach(Intent intent, int id, BlockPos targetPos, String mode) {
        final BlockPos target = targetPos.immutable();
        final String orePath = BuiltInRegistries.BLOCK.getKey(
                npc.level().getBlockState(target).getBlock()).getPath();
        current = "mine";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " ore-reach 开始 | mode=" + mode
                + " target=" + target.toShortString() + " block=" + orePath
                + " pos=" + npc.blockPosition().toShortString());
        final int maxRounds = "pillar".equals(mode) ? 900 : 1500;
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int rounds;
            private int waitRounds;
            private int stepFails;
            private int minedCount;
            /**
             * 落定窗口（第二十轮）：waitingBody 严格判定（!isWalking && onGround）
             * 等不到时，用位置窗口兜底——连续约 6 秒（60 轮×2 tick）内 Y 活动
             * 幅度 ≤0.1 格且水平格不变，按实际已站稳放行。锚格一变（真在走）
             * 或 Y 幅度超限（真下落）立刻重置窗口，不会误放。
             */
            private int settleRounds;
            private double settleMinY = Double.NaN;
            private double settleMaxY = Double.NaN;
            private int settleCellX = Integer.MIN_VALUE;
            private int settleCellZ = Integer.MIN_VALUE;
            private List<BlockPos> bonus = List.of();
            private int bonusIndex;
            /** 阶段：0=接近（dig 全程在此；pillar 先接近柱位） 1=垫柱上升 2=挖主矿/顺手矿 3=拆柱下降。 */
            private int phase;
            /** pillar 出发点（拆柱下降的目标高度与位置）。 */
            private BlockPos startPos;
            private int startFeetY = Integer.MIN_VALUE;
            private final List<BlockPos> placed = new ArrayList<>();
            /** 上升子状态：0=起跳 1=等腾空放块 2=等落地。 */
            private int sub;
            private BlockPos levelCell;
            private double jumpBaseY;
            private int subWait;

            @Override
            public void run() {
                if (runId != id) {
                    return;
                }
                if (++rounds > maxRounds) {
                    failReach("总轮数超限");
                    return;
                }
                String nowPath = BuiltInRegistries.BLOCK.getKey(
                        npc.level().getBlockState(target).getBlock()).getPath();
                if (!nowPath.equals(orePath) && minedCount == 0 && phase < 2) {
                    failReach("目标已消失（被顺手挖掉或方块变化）");
                    return;
                }
                // 惜命：够矿途中落水/落岩浆立刻停手，生存层会先带它脱困，不为一块矿冒险。
                if (npc.isInWater() || npc.isInLava()) {
                    failReach("落入流体，中断够矿");
                    return;
                }
                try {
                    switch (phase) {
                        case 0 -> phaseApproach();
                        case 1 -> phasePillarUp();
                        case 2 -> phaseMineBonus();
                        case 3 -> phasePillarDown();
                        default -> failReach("未知阶段 " + phase);
                    }
                } catch (Throwable throwable) {
                    // 兜底（fix15 场景实测教训）：状态机里一个未预料的空指针会让
                    // Bukkit 任务每轮抛异常空转、把技能拖到总轮数上限才收场，
                    // 中间 90 秒全是异常刷屏。抓到就立刻按失败收尾并留痕。
                    LOG.log(java.util.logging.Level.WARNING,
                            "[AIV][SKILL] " + npc.npcName() + " ore-reach 内部异常", throwable);
                    failReach("内部异常 " + throwable.getClass().getSimpleName());
                }
            }

            /** 等身体落定（在走或在空中）。等待超 120 轮（约 12 秒）按一步失败处理，不干等。 */
            private boolean waitingBody() {
                if (!npc.isWalking() && npc.onGround) {
                    waitRounds = 0;
                    settleRounds = 0;
                    settleMinY = Double.NaN;
                    settleMaxY = Double.NaN;
                    settleCellX = Integer.MIN_VALUE;
                    return false;
                }
                // 悬空/走位状态不落定但身体实际已站稳（与 descend 的 fix13/fix20
                // 同病）：站在自挖矿道台阶边缘等怪碰撞上时 onGround 假报、走位
                // 标志不解除，严格判定永远等不到落定——长跑#8B 实测 pillar 够
                // 头顶煤时连环超时 6 次整次放弃。改用窗口判定：连续约 6 秒
                // Y 活动幅度 ≤0.1 格且水平格不变，按已站稳放行并清走位状态；
                // 真下落时 Y 幅度数格、真行走时锚格每轮在变，窗口都会被重置，
                // 不会误放（与 descend 放行同一安全论证）。
                BlockPos cellNow = npc.blockPosition();
                double yNow = npc.getY();
                if (settleCellX == Integer.MIN_VALUE
                        || cellNow.getX() != settleCellX || cellNow.getZ() != settleCellZ
                        || Double.isNaN(settleMinY)) {
                    settleCellX = cellNow.getX();
                    settleCellZ = cellNow.getZ();
                    settleMinY = yNow;
                    settleMaxY = yNow;
                    settleRounds = 0;
                } else {
                    settleMinY = Math.min(settleMinY, yNow);
                    settleMaxY = Math.max(settleMaxY, yNow);
                    if (settleMaxY - settleMinY > 0.1D) {
                        settleMinY = yNow;
                        settleMaxY = yNow;
                        settleRounds = 0;
                    } else if (++settleRounds >= 60) {
                        settleRounds = 0;
                        settleMinY = Double.NaN;
                        settleMaxY = Double.NaN;
                        settleCellX = Integer.MIN_VALUE;
                        waitRounds = 0;
                        npc.stopWalking();
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " ore-reach 等待落定：位置窗口稳定，按已落定放行 | mode=" + mode
                                + " y=" + String.format(java.util.Locale.ROOT, "%.2f", yNow)
                                + " pos=" + cellNow.toShortString());
                        return false;
                    }
                }
                if (++waitRounds > 120) {
                    waitRounds = 0;
                    npc.stopWalking();
                    stepFails++;
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " ore-reach 等待落定超时 | mode=" + mode
                            + " 连续失败=" + stepFails);
                    if (stepFails >= 6) {
                        failReach("多次等待落定超时");
                    }
                }
                return true;
            }

            private void phaseApproach() {
                if (waitingBody()) {
                    return;
                }
                if (withinMineReach(target) && mineTargetNow()) {
                    phase = 2;
                    return;
                }
                if ("pillar".equals(mode) && horizontalDistanceTo(target) <= 2.2D) {
                    startPos = npc.blockPosition();
                    startFeetY = startPos.getY();
                    phase = 1;
                    sub = 0;
                    subWait = 0;
                    return;
                }
                if (!digStepToward(target)) {
                    stepFails++;
                    if (stepFails >= 6) {
                        failReach("挖通接近连续无路");
                    }
                } else {
                    stepFails = 0;
                }
            }

            private void phasePillarUp() {
                BlockPos feet = npc.blockPosition();
                if (sub == 0) {
                    if (withinMineReach(target)) {
                        // 已经够高了（矿比预估低，或垫一两块就够）：直接挖，转入挖矿阶段；
                        // 够得着却挖不动（工具问题）也别再垫了，下柱收尾。
                        phase = mineTargetNow() ? 2 : 3;
                        if (phase == 3 && minedCount == 0) {
                            startPos = startPos == null ? feet : startPos;
                            startFeetY = startFeetY == Integer.MIN_VALUE ? feet.getY() : startFeetY;
                        }
                        return;
                    }
                    if (feet.getY() >= target.getY()) {
                        failReach("垫柱高度异常（已高于矿）");
                        return;
                    }
                    if (!npc.onGround) {
                        if (++subWait > 40) {
                            failReach("上升中长时间未落地");
                        }
                        return;
                    }
                    subWait = 0;
                    levelCell = feet;
                    jumpBaseY = npc.getY();
                    npc.jumpNow();
                    sub = 1;
                    return;
                }
                if (sub == 1) {
                    // 腾空到 0.55 格以上就往脚下放块：放早了会和身体碰撞箱重叠失败，
                    // 放晚了身体已经开始下落、块会垫不到正下方。
                    if (npc.getY() - jumpBaseY >= 0.55D) {
                        if (!placeBuildingBlockAt(levelCell)) {
                            failReach("垫脚方块不足");
                            return;
                        }
                        placed.add(levelCell);
                        sub = 2;
                        subWait = 0;
                        return;
                    }
                    if (++subWait > 20) {
                        sub = 0;
                        stepFails++;
                        if (stepFails >= 4) {
                            failReach("起跳失败多次（头顶遮挡？）");
                        }
                    }
                    return;
                }
                // sub == 2：等落到刚垫的块上。
                if (npc.onGround) {
                    sub = 0;
                    subWait = 0;
                    return;
                }
                if (++subWait > 40) {
                    failReach("垫柱后未落地");
                }
            }

            private void phaseMineBonus() {
                if (waitingBody()) {
                    return;
                }
                if (mineBonusNow()) {
                    return;
                }
                // 只有真的垫过柱（startPos 已记、且人还在柱顶上方）才需要拆柱下降；
                // 接近阶段顺手就挖到的（没垫柱）直接收尾——fix15 实测：startPos 为 null
                // 时进下柱阶段会对 null 调 digStepToward，每轮 NPE 空转到总轮数超限。
                if ("pillar".equals(mode) && startPos != null
                        && npc.blockPosition().getY() > startFeetY) {
                    phase = 3;
                } else {
                    finishReach("已挖 " + minedCount + " 块");
                }
            }

            private void phasePillarDown() {
                if (waitingBody()) {
                    return;
                }
                if (startPos == null) {
                    // 没垫过柱就进了下柱阶段（状态异常）：按已挖数量收尾，别对 null 寻路。
                    if (minedCount > 0) {
                        finishReach("已挖 " + minedCount + " 块");
                    } else {
                        failReach("下柱阶段缺少出发点记录");
                    }
                    return;
                }
                BlockPos feet = npc.blockPosition();
                if (feet.getY() <= startFeetY) {
                    if (minedCount > 0) {
                        finishReach("已挖 " + minedCount + " 块并安全下柱");
                    } else {
                        failReach("回到地面但一块矿都没挖到");
                    }
                    return;
                }
                BlockPos below = feet.below();
                if (placed.contains(below) || isBuildingBlock(below)) {
                    // 逐块拆脚下垫柱：每拆一块只落 1 格，全程无摔伤（惜命口径）。
                    if (npc.mineWithBestTool(below)) {
                        return;
                    }
                    failReach("下柱时垫块挖不动");
                    return;
                }
                // 站位漂移（被怪推开/滑落）：朝出发点横向挪回去再继续拆。
                if (!digStepToward(startPos)) {
                    stepFails++;
                    if (stepFails >= 4) {
                        if (minedCount > 0) {
                            finishReach("已挖 " + minedCount + " 块（柱未完全回收，先离柱）");
                        } else {
                            failReach("下柱受阻且未挖到矿");
                        }
                    }
                }
            }

            /** 挖主矿；成功后把相邻的同种裸露矿列入顺手清单（一窝矿别只挖一块）。 */
            private boolean mineTargetNow() {
                if (!withinMineReach(target)) {
                    return false;
                }
                // 【fix37 岩浆邻接防护】矿的六邻有岩浆就不出手：另一侧/下方是岩浆时
                // 掉落物会落进岩浆烧毁，上方是岩浆时挖开会灌到自己头上。
                // 跳过并记账，交回状态机按失败处理（与挖不到同路，不死等）。
                if (touchesLava(target)) {
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " ore-reach 跳过岩浆邻接矿 | "
                            + orePath + " " + target.toShortString());
                    return false;
                }
                if (!npc.mineWithBestTool(target)) {
                    return false;
                }
                minedCount++;
                List<BlockPos> extra = new ArrayList<>();
                for (Direction dir : Direction.values()) {
                    BlockPos p = target.relative(dir);
                    String pp = BuiltInRegistries.BLOCK.getKey(
                            npc.level().getBlockState(p).getBlock()).getPath();
                    if (pp.equals(orePath) && isExposed(p)) {
                        extra.add(p.immutable());
                    }
                }
                bonus = extra;
                bonusIndex = 0;
                return true;
            }

            /** 顺手清单里挖一块（够不着/已消失的逐个跳过）；挖到返回 true，清单耗尽返回 false。 */
            private boolean mineBonusNow() {
                while (bonusIndex < bonus.size()) {
                    BlockPos p = bonus.get(bonusIndex++);
                    String pp = BuiltInRegistries.BLOCK.getKey(
                            npc.level().getBlockState(p).getBlock()).getPath();
                    if (!pp.equals(orePath)) {
                        continue;
                    }
                    // 【fix37】顺手矿同样查岩浆邻接，命中就跳过这一块（清单继续往下走）。
                    if (touchesLava(p)) {
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " ore-reach 跳过岩浆邻接顺手矿 | "
                                + pp + " " + p.toShortString());
                        continue;
                    }
                    if (withinMineReach(p) && npc.mineWithBestTool(p)) {
                        minedCount++;
                        return true;
                    }
                }
                return false;
            }

            private void failReach(String why) {
                LOG.info("[AIV][SKILL] " + npc.npcName() + " ore-reach | mode=" + mode
                        + " success=false target=" + target.toShortString() + " " + why);
                finish(id, "mine", "ore-reach 放弃 mode=" + mode + " success=false " + why
                        + " target=" + target.toShortString());
            }

            private void finishReach(String note) {
                LOG.info("[AIV][SKILL] " + npc.npcName() + " ore-reach | mode=" + mode
                        + " success=true target=" + target.toShortString() + " " + note);
                finish(id, "mine", "ore-reach mode=" + mode + " success=true " + note);
            }
        }, 1L, 2L));
        armWatchdog(id, "mine", 3600);
        return true;
    }

    /**
     * 惜命接战把关（Fong 定：生命第一）：主动打敌对生物的技能入口先过
     * {@link SurvivalController#canEngage} 同一口径——低血不打、有武器/装备可打、
     * 空手无装备只有血量健康才打。不满足就拒绝并写明原因，让规划层改派别的活；
     * 战斗中血掉下去则由生存层 defend() 中断技能带它逃跑，两道闸口径一致。
     *
     * @return 拒绝原因；允许接战返回 null
     */
    private String combatRefusal(String action) {
        float health = npc.getHealth();
        boolean equipped = npc.getArmorValue() > 0 || isWeaponItem(npc.getMainHandItem());
        if (SurvivalController.canEngage(health, equipped)) {
            return null;
        }
        return action + " 惜命拒绝：hp=" + String.format(Locale.ROOT, "%.1f", health)
                + " 有武器/装备=" + equipped + "（低血或空手无装备且不够健康，先保命不接战）";
    }

    /** 主手是不是武器类物品（剑/斧/弓弩/三叉戟/重锤）；生存层平时会把最好的武器换到主手。 */
    private boolean isWeaponItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return path.endsWith("_sword") || path.endsWith("_axe")
                || path.equals("bow") || path.equals("crossbow")
                || path.equals("trident") || path.equals("mace");
    }

    /** 最近的敌对生物（排除玩家和 NPC 自己），走到 2 格内后按 1 秒一下连续攻击。 */
    private boolean runAttack(Intent intent, int id) {
        String refusal = combatRefusal("attack");
        if (refusal != null) {
            lastOutcome = refusal;
            LOG.info("[AIV][SKILL] " + npc.npcName() + " " + refusal);
            return false;
        }
        double radius = Math.max(2.0D, Math.min(MAX_SEARCH_RADIUS, intent.doubleParam("radius", 10.0D)));
        int maxHits = Math.max(1, Math.min(5, intent.intParam("hits", 3)));

        // 只打 Enemy（僵尸/骷髅/苦力怕等）：LivingEntity 会把鸡、牛、村民也算进来，
        // 那样 NPC 会莫名其妙屠杀被动生物。1.21.11 的 getEntities 返回 List<Entity>，
        // 泛型不会跟着谓词收窄，只能拿到列表后自己转。
        // 注意 Enemy 只是标记接口、不继承 LivingEntity，所以要先判 LivingEntity 再判 Enemy。
        List<Entity> candidates = npc.level().getEntities(npc, npc.getBoundingBox().inflate(radius),
                entity -> entity instanceof LivingEntity living
                        && living instanceof Enemy
                        && living != npc
                        && living.isAlive());
        if (candidates.isEmpty()) {
            lastOutcome = "attack 附近没有敌对目标 radius=" + radius;
            LOG.info("[AIV][SKILL] " + npc.npcName() + " attack 附近没有敌对目标 | radius=" + radius);
            return false;
        }
        // getEntities 不保证顺序，按距离取最近的，否则会绕过近处的僵尸去打远处的。
        LivingEntity target = (LivingEntity) candidates.stream()
                .min((left, right) -> Double.compare(npc.distanceToSqr(left), npc.distanceToSqr(right)))
                .orElseThrow();

        // 先走到目标旁边 2 格内。直线走遇到墙就卡死，所以这里也走 A*。
        BlockPos approach = PathFinder.standableGoal(npc.level(), target.blockPosition(), 3);
        if (approach == null) {
            lastOutcome = "attack 目标附近没有可站立位置 target=" + target.getType();
            LOG.info("[AIV][SKILL] " + npc.npcName() + " attack 目标附近没有可站立位置 | target=" + target.getType());
            return false;
        }
        PathFinder.Result approachPath = PathFinder.find(npc.level(), npc.blockPosition(), approach, !npc.wearingLeatherBoots());
        logPath("attack-approach", approach, approachPath);
        if (!approachPath.found()) {
            lastOutcome = "attack 找不到接近路径 " + approachPath.detail();
            return false;
        }

        current = "attack";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " attack 开始 | target=" + target.getType()
                + " hits=" + maxHits + " distance=" + String.format(Locale.ROOT, "%.1f", npc.distanceTo(target)));

        npc.followPath(approachPath.path(), true, () -> {
            if (this.runId != id) {
                return;
            }
            tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                private int hits;
                private int attempts;

                @Override
                public void run() {
                    if (runId != id) {
                        return;
                    }
                    attempts++;
                    if (target.isRemoved() || !target.isAlive()) {
                        finish(id, "attack", "目标已倒下 hits=" + hits);
                        return;
                    }
                    if (attempts > 120) {
                        finish(id, "attack", "超时 hits=" + hits);
                        return;
                    }
                    double distanceSq = npc.distanceToSqr(target);
                    if (distanceSq > 16.0D) {
                        // 目标跑远了：重新算追击路径，正在走就不要每一秒重建一次。
                        if (!npc.isFollowingPath()) {
                            BlockPos chaseGoal = PathFinder.standableGoal(npc.level(), target.blockPosition(), 3);
                            if (chaseGoal != null) {
                                PathFinder.Result chase = PathFinder.find(npc.level(), npc.blockPosition(), chaseGoal, !npc.wearingLeatherBoots());
                                if (chase.found()) {
                                    npc.followPath(chase.path(), true, null, null);
                                }
                            }
                        }
                        return;
                    }
                    float damage = npc.attackTarget(target);
                    hits++;
                    if (damage <= 0.0F) {
                        finish(id, "attack", "攻击无效，目标可能已免疫 hits=" + hits);
                    } else if (hits >= maxHits) {
                        finish(id, "attack", "完成 hits=" + hits);
                    }
                }
            }, 20L, 20L));
        }, () -> finish(id, "attack", "接近路径中断"));
        armWatchdog(id, "attack");
        return true;
    }

    /**
     * 定点猎杀：只打指定类型的生物，用来收集特定掉落（线/羽毛/烈焰棒/末影珍珠）。
     *
     * <p>为什么不复用 attack/hunt：
     * <ul>
     *   <li>attack 的谓词是 Enemy，在下界会随机打僵尸猪灵、拿不到烈焰棒；</li>
     *   <li>hunt 只认 Animal，打不了末影人。</li>
     * </ul>
     * 目标会对玩家造成伤害，所以这里只负责"贴上去 + 挥剑"，
     * 真正的保命（逃跑、进食、换甲）仍然交给独立运行的生存层。
     */
    private boolean runSlay(Intent intent, int id) {
        String wanted = normalizeEntity(intent.param("entity", ""));
        if (wanted.isEmpty()) {
            lastOutcome = "slay 缺少 entity 参数";
            LOG.info("[AIV][SKILL] " + npc.npcName() + " slay 缺少 entity 参数");
            return false;
        }
        // 定点猎杀也是主动接战敌对生物（烈焰人/末影人等），同样过惜命把关：
        // 低血时不许为了掉落去送死，先回血再来。
        String refusal = combatRefusal("slay");
        if (refusal != null) {
            lastOutcome = refusal;
            LOG.info("[AIV][SKILL] " + npc.npcName() + " " + refusal);
            return false;
        }
        double radius = Math.max(6.0D, Math.min(MAX_SEARCH_RADIUS, intent.doubleParam("radius", 12.0D)));
        int maxAttempts = Math.max(40, Math.min(600, intent.intParam("attempts", 240)));

        List<Entity> candidates = npc.level().getEntities(npc, npc.getBoundingBox().inflate(radius),
                entity -> entity instanceof LivingEntity living
                        && living.isAlive()
                        && !(living instanceof Animal animal && animal.isBaby())
                        && wanted.equals(entityPath(living)));
        if (candidates.isEmpty()) {
            lastOutcome = "slay 附近没有 " + wanted + " radius=" + radius;
            LOG.info("[AIV][SKILL] " + npc.npcName() + " slay 附近没有目标 | entity=" + wanted
                    + " radius=" + radius);
            return false;
        }
        LivingEntity target = (LivingEntity) candidates.stream()
                .min((left, right) -> Double.compare(npc.distanceToSqr(left), npc.distanceToSqr(right)))
                .orElseThrow();
        BlockPos approach = PathFinder.standableGoal(npc.level(), target.blockPosition(), 2);
        // 目标可能悬空（烈焰人、末影龙）：找不到落脚点或路径时不能直接失败，
        // 否则猎杀目标会在"目标在天上"这种情况下被永久跳过。
        PathFinder.Result approachPath = approach == null ? null
                : PathFinder.find(npc.level(), npc.blockPosition(), approach, !npc.wearingLeatherBoots());
        if (approachPath != null) {
            logPath("slay-approach", approach, approachPath);
        }

        current = "slay";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " slay 开始 | target=" + wanted
                + " distance=" + String.format(Locale.ROOT, "%.1f", npc.distanceTo(target)));
        java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable startCombat = () -> {
            if (runId != id || !started.compareAndSet(false, true)) {
                return;
            }
            tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                private int attempts;

                @Override
                public void run() {
                    if (runId != id) {
                        return;
                    }
                    attempts++;
                    if (target.isRemoved() || !target.isAlive()) {
                        finish(id, "slay", "目标已倒下 attempts=" + attempts);
                        return;
                    }
                    if (attempts > maxAttempts) {
                        finish(id, "slay", "超时 目标仍在=" + target.getType());
                        return;
                    }
                    double distanceSq = npc.distanceToSqr(target);
                    // 烈焰人/恶魂悬空时近战永远够不到：有弓就射，没弓就走到它正下方等它下来。
                    if (distanceSq > 9.0D && "blaze".equals(wanted) && hasBowAndArrow()) {
                        shootArrowAt(target);
                        return;
                    }
                    if (distanceSq > 25.0D) {
                        if (!npc.isFollowingPath()) {
                            BlockPos chaseGoal = PathFinder.standableGoal(npc.level(),
                                    target.blockPosition(), 2);
                            if (chaseGoal != null) {
                                PathFinder.Result chase = PathFinder.find(npc.level(),
                                        npc.blockPosition(), chaseGoal, !npc.wearingLeatherBoots());
                                if (chase.found()) {
                                    npc.followPath(chase.path(), true, null, null);
                                    return;
                                }
                            }
                            // 目标在空中：直接朝它的水平位置贴过去，等它下来/停靠。
                            if (!npc.isWalking()) {
                                npc.walkTo(new Vec3(target.getX(), npc.getY(), target.getZ()),
                                        true, null);
                            }
                        }
                        return;
                    }
                    if (distanceSq > 4.0D) {
                        // 还在两格外：直线贴近，比每 0.5 秒重建 A* 便宜得多。
                        if (!npc.isWalking()) {
                            npc.walkTo(target.position(), true, null);
                        }
                        return;
                    }
                    npc.attackTarget(target);
                }
            }, 20L, 20L));
        };
        if (approachPath != null && approachPath.found()) {
            npc.followPath(approachPath.path(), true, startCombat, startCombat);
        } else {
            startCombat.run();
        }
        armWatchdog(id, "slay", 1600);
        return true;
    }

    /**
     * 远程射击：对最近的目标生物射一支箭。
     *
     * <p>箭是真实的 {@link net.minecraft.world.entity.projectile.arrow.Arrow} 实体，
     * 会受重力、会被方块挡住，打末影水晶和末影龙时和玩家射箭的判定完全一致。
     * 只额外做了一点重力补偿：距离 d 格时抬高约 d²/360 格，这是玩家瞄远处时的本能。
     */
    private boolean runShoot(Intent intent, int id) {
        String wanted = normalizeEntity(intent.param("entity", ""));
        int shots = Math.max(1, Math.min(64, intent.intParam("shots", 16)));
        current = "shoot";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " shoot 开始 | entity=" + wanted + " shots=" + shots);

        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int fired;
            private int attempts;

            @Override
            public void run() {
                if (runId != id) {
                    return;
                }
                attempts++;
                if (fired >= shots) {
                    finish(id, "shoot", "射完 " + fired + " 支箭");
                    return;
                }
                if (attempts > 240) {
                    finish(id, "shoot", "超时 已射 " + fired);
                    return;
                }
                if (!hasBowAndArrow()) {
                    finish(id, "shoot", "没有弓或箭 已射 " + fired);
                    return;
                }
                List<Entity> candidates = npc.level().getEntities(npc,
                        npc.getBoundingBox().inflate(96.0D),
                        entity -> entity instanceof LivingEntity living
                                && living.isAlive()
                                && (wanted.isEmpty() || wanted.equals(entityPath(living))));
                if (candidates.isEmpty()) {
                    finish(id, "shoot", "附近没有目标 已射 " + fired);
                    return;
                }
                LivingEntity target = (LivingEntity) candidates.stream()
                        .min((left, right) -> Double.compare(npc.distanceToSqr(left),
                                npc.distanceToSqr(right)))
                        .orElseThrow();
                if (shootArrowAt(target)) {
                    fired++;
                }
            }
        }, 10L, 15L));
        armWatchdog(id, "shoot", 800);
        return true;
    }

    /**
     * 长途行进：优先用 A* 找路，找不到就朝目标方向挖隧道/搭桥。
     *
     * <p>这段是通关路线上最脏的一段：下界要塞经常在上百格外，中间隔着岩浆海、
     * 玄武岩柱和断崖。玩家会一边挖一边搭桥，这里做等价的事：
     * <ul>
     *   <li>有可站立路径就跟路径走；</li>
     *   <li>没路径就沿目标方向的主轴挖 1x2 通道；</li>
     *   <li>下一格下方是空气/岩浆时先放一块垫脚石，站到它上面再继续。</li>
     * </ul>
     */
    private boolean runTravel(Intent intent, int id) {
        final double targetX = intent.doubleParam("x", npc.getX());
        final double targetY = intent.doubleParam("y", npc.getY());
        final double targetZ = intent.doubleParam("z", npc.getZ());
        final double tolerance = Math.max(2.0D, Math.min(16.0D, intent.doubleParam("tolerance", 4.0D)));
        current = "travel";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " travel 开始 | 目标="
                + String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", targetX, targetY, targetZ));

        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int noPathRounds;
            private int lastLogTick;

            @Override
            public void run() {
                if (runId != id) {
                    return;
                }
                double distanceSq = npc.distanceToSqr(targetX, targetY, targetZ);
                if (distanceSq <= tolerance * tolerance) {
                    npc.stopWalking();
                    finish(id, "travel", "到达目标");
                    return;
                }
                // 水平已经到了但高度差还很大（要塞房间在地底）：本技能只会水平推进/搭桥，
                // 竖向交给目标层调用 descend/ascend。不在这里收手的话会一直挖到超时。
                double dx = targetX - npc.getX();
                double dz = targetZ - npc.getZ();
                if (dx * dx + dz * dz <= tolerance * tolerance
                        && Math.abs(targetY - npc.getY()) > 2.5D) {
                    npc.stopWalking();
                    finish(id, "travel", "水平到位，需要竖直对齐");
                    return;
                }
                // 正在走就先让它走完这一小段，每 tick 重算路径会把身体锁死在原地。
                if (npc.isFollowingPath() || npc.isWalking()) {
                    return;
                }
                BlockPos goal = new BlockPos((int) Math.floor(targetX),
                        (int) Math.floor(targetY), (int) Math.floor(targetZ));
                PathFinder.Result result = PathFinder.find(npc.level(), npc.blockPosition(),
                        goal, 24000, !npc.wearingLeatherBoots());
                if (result.found()) {
                    noPathRounds = 0;
                    npc.followPath(result.path(), true, null, null);
                    if (npc.level().getServer().getTickCount() - lastLogTick > 200) {
                        lastLogTick = npc.level().getServer().getTickCount();
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " travel 找到路径 | 剩余="
                                + Math.round(Math.sqrt(distanceSq)) + " 格 节点=" + result.visited());
                    }
                    return;
                }
                // 没路径：朝目标方向自己挖/搭桥一步。
                if (travelStepToward(targetX, targetZ)) {
                    noPathRounds = 0;
                    return;
                }
                noPathRounds++;
                if (noPathRounds >= 12) {
                    finish(id, "travel", "长途行进受阻 | " + result.detail());
                }
            }
        }, 1L, 10L));
        armWatchdog(id, "travel", 12000);
        return true;
    }

    /**
     * 朝目标水平方向推进一格：必要时先搭桥再挖开通道。
     *
     * @return 是否做出了有效动作（挖了/放了/开始走）
     */
    private boolean travelStepToward(double targetX, double targetZ) {
        BlockPos feet = npc.blockPosition();
        int dx = (int) Math.floor(targetX) - feet.getX();
        int dz = (int) Math.floor(targetZ) - feet.getZ();
        // 主轴推进：两轴都走会让每一步都贴着墙角，实际速度反而更慢。
        boolean alongX = Math.abs(dx) >= Math.abs(dz);
        int stepX = alongX ? Integer.signum(dx) : 0;
        int stepZ = alongX ? 0 : Integer.signum(dz);
        if (stepX == 0 && stepZ == 0) {
            return false;
        }

        BlockPos ahead = feet.offset(stepX, 0, stepZ);
        BlockPos floor = ahead.below();
        BlockState floorState = npc.level().getBlockState(floor);
        BlockState aheadState = npc.level().getBlockState(ahead);
        boolean floorSolid = !floorState.getCollisionShape(npc.level(), floor).isEmpty()
                && floorState.getFluidState().isEmpty();

        if (!floorSolid) {
            // 断崖/岩浆：先把 ahead 填成实心当桥面，然后站到它上面（整体抬高 1 格）。
            // 这样脚永远踩在方块上，不会走进岩浆。
            if (!placeBuildingBlockAt(ahead)) {
                return false;
            }
            ahead = ahead.above();
        }
        // 清出站位（脚）与头顶两格；已经通的地方 mineWithBestTool 会直接跳过。
        npc.mineWithBestTool(ahead);
        if (!npc.level().getBlockState(ahead).getCollisionShape(npc.level(), ahead).isEmpty()) {
            // 挖不动（缺少合适工具/基岩）：换方向由上层重试，别在这里硬顶。
            return false;
        }
        npc.mineWithBestTool(ahead.above());
        npc.walkTo(new Vec3(ahead.getX() + 0.5D, ahead.getY(), ahead.getZ() + 0.5D), false, null);
        return true;
    }

    /** 【fix57】上次放块的格子与时刻：断「放块→身体卡入→被生存层挖掉→再放」紧循环。 */
    private BlockPos lastBridgePos;
    private long lastBridgeTick = Long.MIN_VALUE;

    /** 【fix61】自放方块登记表（pos→放置 tick）：生存层实心自救靠它豁免刚放下的台阶/桥块。 */
    private static final java.util.Map<BlockPos, Long> SELF_PLACED = new java.util.HashMap<>();

    /** 【fix61】该格是否在 windowTicks 内由本插件放块（搭桥/台阶/垫柱统一口 placeBuildingBlockAt）。 */
    public static boolean wasSelfPlacedRecently(BlockPos pos, long nowTick, long windowTicks) {
        Long placed = SELF_PLACED.get(pos);
        return placed != null && nowTick - placed < windowTicks;
    }

    /** 放一块垫脚方块（优先圆石/下界岩这类廉价方块）。 */
    private boolean placeBuildingBlockAt(BlockPos pos) {
        // 【fix57】落地时禁止往身体脚格/头格放块：ascend 台阶与 travel 搭桥曾在同一格
        // 互相打架——块放进身体所在格，生存层「卡在方块里」当场挖掉再放再挖，
        // 108 秒烧掉 194 块、整组圆石清零（boot-farm12）。垫柱是腾空 0.55 格后放脚下，
        // 放块时 onGround=false，不受此闸影响。
        if (npc.onGround) {
            BlockPos feet = npc.blockPosition();
            if (pos.equals(feet) || pos.equals(feet.above())) {
                return false;
            }
        }
        // 【fix57】同格 60 tick 内不重复放块：正常搭桥/垫柱都是逐格推进、不回头同格。
        long nowTick = npc.level().getGameTime();
        if (pos.equals(lastBridgePos) && nowTick - lastBridgeTick < 60L) {
            return false;
        }
        String path = pickBuildingBlock();
        if (path == null) {
            return false;
        }
        Item item = itemOf(path);
        Block block = blockOf(path);
        if (item == null || block == null || countItem(path) <= 0) {
            return false;
        }
        consumeItem(path, 1);
        npc.level().setBlockAndUpdate(pos, block.defaultBlockState());
        lastBridgePos = pos;
        lastBridgeTick = nowTick;
        // 【fix61】登记自放块供生存层豁免（40 tick 窗口在生存层侧）；顺手清 400 tick 前的旧登记防表膨胀。
        SELF_PLACED.put(pos, nowTick);
        SELF_PLACED.values().removeIf(t -> nowTick - t > 400L);
        LOG.info("[AIV][SKILL] " + npc.npcName() + " travel 搭桥 | block=" + path
                + " pos=" + pos.toShortString());
        return true;
    }

    /** 背包里是否有弓，且箭至少 1 支。 */
    private boolean hasBowAndArrow() {
        return countItem("bow") > 0 && countItem("arrow") > 0;
    }

    /**
     * 朝目标射一支箭（真实箭实体，带重力补偿）。
     *
     * @return 是否真的射出去了（弓/箭不足时返回 false）
     */
    private boolean shootArrowAt(LivingEntity target) {
        if (!hasBowAndArrow() || countItem("arrow") <= 0) {
            return false;
        }
        var from = npc.getEyePosition();
        var to = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        // 箭初速约 3 格/tick、重力 0.05 格/tick²，飞行时间 t=d/3，
        // 下坠量 = 0.5*0.05*t² ≈ d²/360。抬高同样的高度就能落在准心上。
        double drop = horizontal * horizontal / 360.0D;
        var arrow = new net.minecraft.world.entity.projectile.arrow.Arrow(
                npc.level(), npc, new ItemStack(Items.BOW), new ItemStack(Items.ARROW));
        arrow.setPos(from.x, from.y - 0.1D, from.z);
        arrow.shoot(dx, dy + drop, dz, 3.0F, 0.6F);
        arrow.setBaseDamage(6.0D);
        npc.level().addFreshEntity(arrow);
        consumeItem("arrow", 1);
        LOG.info("[AIV][SKILL] " + npc.npcName() + " shoot 射箭 | target=" + entityPath(target)
                + " distance=" + String.format(Locale.ROOT, "%.1f", horizontal)
                + " 抬高=" + String.format(Locale.ROOT, "%.2f", drop)
                + " 剩余箭=" + countItem("arrow"));
        return true;
    }

    /** 物品/实体 ID 规范化：去掉命名空间前缀，统一小写。 */
    private static String normalizeEntity(String raw) {
        String path = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (path.startsWith("minecraft:")) {
            path = path.substring("minecraft:".length());
        }
        return path;
    }

    private static String entityPath(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
    }

    /**
     * 阶梯式下挖：沿一个水平方向挖 1x2 通道逐层下降，直到接近目标 Y。
     *
     * <p>为什么不做垂直挖坑：垂直坑需要垫脚才能上来，伪玩家不擅长放方块自救；
     * 阶梯通道每步只降 1 格，随时能走回去，也是玩家最常用的下矿方式。
     * 每一步先检查前方/下方是不是流体或基岩，遇到就转 90 度，避免挖进岩浆。
     */
    private boolean runDescend(Intent intent, int id) {
        int targetY = intent.intParam("y", 16);
        int maxSteps = Math.max(10, Math.min(300, intent.intParam("steps", 260)));
        current = "descend";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " descend 开始 | 目标Y=" + targetY
                + " 当前Y=" + npc.blockPosition().getY() + " 上限步数=" + maxSteps);

        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int steps;
            /** 4 个水平方向轮换；遇到流体/基岩就转向，不在原地反复撞。 */
            private int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
            private int dirIndex;
            private BlockPos lastTarget;
            /** 挖这一级之前的脚部 Y：只要身体真的降下去了就算完成一步。 */
            private int stepStartY = Integer.MIN_VALUE;
            /** 连续几级挖不动/走不动。用来在"镐子全碎了"时及时收手，而不是无限转向。 */
            private int failedSteps;
            /**
             * 连续"没有安全台阶"的转向次数。
             * 在雨林树冠上，四个方向的下一格脚下全是空气，原本每 3 tick 转一次方向、
             * 每转一次刷一条日志，NPC 却永远不落地。这里累计到整圈（4 次）后改成垂直下挖。
             */
            private int unsafeTurns;
            /** 总进度看门狗：步数不变的轮数（每轮 3 tick）。见 run() 开头说明。 */
            private int lastStepsSeen;
            private int roundsWithoutProgress;
            /** 【fix73】同层横移次数（自上次真步数推进以来）：防帽盖上两列间乒乓。 */
            private int lateralMoves;
            /** 诊断心跳轮数：每 200 轮（约 30 秒）打一行完整状态，定位静默卡死卡在哪条分支。 */
            private int diagRounds;
            /**
             * 单级等待轮数：等身体降到上一级目标的轮数（每轮 3 tick）。
             * 长跑实测：身体贴着坑壁磨蹭时 walkTo 的卡住判定被微小位移反复重置，
             * isWalking 永远为真，descend 就在这一级干等满 3 分钟总看门狗——
             * 一格死等 30 秒已经绰绰有余，到点按"走位中断"处理（转向重挖）。
             */
            private int stepWaitRounds;
            /**
             * 落地等待轮数：!onGround 且不在走时的连续轮数。
             * fix13 心跳实测抓到的第三种卡法：身体卡在台阶边缘的小数高度
             * （y=28.77），不走、不落地、lastTarget 就是自己脚下——在走死线和
             * 挖不动计数都管不到，只有 3 分钟步数看门狗能收尾。同样给 30 秒死线。
             */
            private int landWaitRounds;
            /**
             * 悬空放行窗（第十三轮）：!onGround 且无待结算步时，统计 Y 的
             * 活动窗口（min/max）。身体在怪碰撞上会缓慢蠕动下沉，单点基准
             * 永远等不到"稳定"，必须用窗口幅度判定。见 run() 的放行分支。
             */
            private int airGateRounds;
            private double airGateMinY = Double.NaN;
            private double airGateMaxY = Double.NaN;
            /**
             * 悬空总时长死线（第四十一轮）：无待结算步的悬空累计轮数，不随窗口
             * 重置清零，只在落地或走出放行分支时清零。长跑#13 三度实证：窗口
             * 幅度判定在雪层/边缘站姿的微幅蠕动下永远等不到放行（整局 0 条
             * 放行日志），悬空满 3 分钟步数看门狗、期间被骷髅当靶子。累计
             * 200 轮（约 30 秒）一到按已站稳强制放行，窗口判定只作快路。
             */
            private int airTotalRounds;

            @Override
            public void run() {
                if (runId != id) {
                    return;
                }
                // 总进度看门狗：failedSteps 只在少数分支累加，四方向轮换/垂直下挖之间
                // 存在步数永远不变、失败计数也永远不涨的空转组合（只能等 10 分钟技能看门狗）。
                // 这里按"步数多久没变"一刀切：约 3 分钟（1200 轮×3 tick）无一步进展就收手，
                // 让目标层的无进展处理接管。正常下挖每级约 2 秒，3 分钟足够宽容。
                if (steps != lastStepsSeen) {
                    lastStepsSeen = steps;
                    roundsWithoutProgress = 0;
                    lateralMoves = 0;
                } else if (++roundsWithoutProgress > 1200) {
                    finish(id, "descend", "长时间无进展（步数约3分钟未变） Y="
                            + Math.round(npc.getY()) + " 步数=" + steps);
                    return;
                }
                // 诊断心跳（第八轮补充）：descend 曾出现"步数看门狗按时收手、但中间
                // 3 分钟零日志零挖掘"的静默卡死，所有动作分支都会打日志，唯独等待类
                // 分支全静默。这里每约 30 秒把完整状态打出来，卡在哪条分支一眼可见。
                if (++diagRounds >= 200) {
                    diagRounds = 0;
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " descend-diag | steps=" + steps
                            + " y=" + String.format(java.util.Locale.ROOT, "%.2f", npc.getY())
                            + " blockPos=" + npc.blockPosition().toShortString()
                            + " onGround=" + npc.onGround
                            + " walking=" + npc.isWalking()
                            + " lastTarget=" + (lastTarget == null ? "null" : lastTarget.toShortString())
                            + " stepStartY=" + stepStartY
                            + " dir=" + dirIndex + " failed=" + failedSteps
                            + " inWater=" + npc.isInWater() + " inLava=" + npc.isInLava());
                }
                // 下落/走位过程中不重复挖：等身体真正站到上一格目标再挖下一级。
                if (!npc.onGround) {
                    if (lastTarget != null) {
                        airTotalRounds = 0;
                        // 【第四十二轮】先按高度结算：雪层/边缘的小数高度站姿下
                        // onGround 恒 false，旧逻辑在悬空分支里 return、永远走不到
                        // 下面的到达结算——长跑#13b 实测 lastTarget 已到达（方块 Y
                        // 已低于起始线）仍等满 30 秒落地死线判失败，steps 恒 0、
                        // 身体靠转向一点点蹭下、挖铁目标连吃 360 秒超时。方块 Y
                        // 已越线就按到达结算，与下方 onGround 分支同口径。
                        if (npc.blockPosition().getY() < stepStartY) {
                            lastTarget = null;
                            landWaitRounds = 0;
                            stepWaitRounds = 0;
                            steps++;
                            failedSteps = 0;
                        } else if (!npc.isWalking() && ++landWaitRounds > 200) {
                            // 落地等待死线（第十轮）：卡在边缘小数高度时不在走、不落地，
                            // stepWait 的"在走"分支管不到——30 秒落不了地按一级失败处理
                            // （清目标+转向+计数），换方向挖通常能把它带出边缘状态。
                            landWaitRounds = 0;
                            lastTarget = null;
                            dirIndex++;
                            failedSteps++;
                            LOG.info("[AIV][SKILL] " + npc.npcName() + " descend 落地等待超时，转向"
                                    + " 连续失败=" + failedSteps + " Y=" + Math.round(npc.getY()));
                            if (failedSteps >= 6) {
                                finish(id, "descend", "连续 " + failedSteps + " 级落地等待超时 Y="
                                        + Math.round(npc.getY()));
                            }
                        }
                        return;
                    }
                    // 无待结算步却报悬空（第十三轮，A2 竹林实测）：站在竹竿顶/方块
                    // 边缘等怪碰撞上时身体被物理支撑、但 onGround 永远 false，
                    // 旧逻辑在这里无声 return 到 3 分钟步数看门狗收场（步数恒 0，
                    // 三连 stall 把挖煤目标全拖超时）。这种站姿下身体还会缓慢蠕动
                    // 下沉（实测约 1 格/2.5 分钟），单点 Y 基准永远等不到"稳定"，
                    // 所以用窗口判定：约 6 秒（40 轮）内 Y 活动幅度 ≤0.1 格就判定
                    // "实际已站稳"，放行去挖第一级；真下落时 6 秒幅度是数格，
                    // 不会误放。挖掉支撑块后重力接管，状态自然恢复正常。
                    double yNow = npc.getY();
                    if (Double.isNaN(airGateMinY)) {
                        airGateMinY = yNow;
                        airGateMaxY = yNow;
                    }
                    airGateMinY = Math.min(airGateMinY, yNow);
                    airGateMaxY = Math.max(airGateMaxY, yNow);
                    // 【第四十一轮】窗口阈值 0.1→0.25：雪层/边缘站姿的微幅蠕动
                    // 幅度常在 0.1~0.2 之间，旧阈值下窗口被反复重置、整局零放行。
                    if (airGateMaxY - airGateMinY > 0.25D) {
                        airGateMinY = yNow;
                        airGateMaxY = yNow;
                        airGateRounds = 0;
                        airTotalRounds++;
                        return;
                    }
                    airGateRounds++;
                    airTotalRounds++;
                    if (airGateRounds == 41) {
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " descend 悬空但 Y 窗口稳定，按已站稳放行 | y="
                                + String.format(java.util.Locale.ROOT, "%.2f", npc.getY())
                                + " pos=" + npc.blockPosition().toShortString());
                    }
                    // 【第四十一轮】悬空总死线：窗口快路走不通时，累计悬空约
                    // 30 秒（200 轮）强制放行，不再满 3 分钟步数看门狗当活靶子。
                    if (airTotalRounds == 201) {
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " descend 悬空超 30 秒死线，按已站稳强制放行 | y="
                                + String.format(java.util.Locale.ROOT, "%.2f", npc.getY())
                                + " pos=" + npc.blockPosition().toShortString());
                    }
                    if (airGateRounds <= 40 && airTotalRounds <= 200) {
                        return;
                    }
                } else {
                    airGateRounds = 0;
                    airTotalRounds = 0;
                    airGateMinY = Double.NaN;
                    airGateMaxY = Double.NaN;
                }
                landWaitRounds = 0;
                if (lastTarget != null) {
                    // 不能用 blockPosition().equals(lastTarget)：walkTo 允许在 0.6 格内停下，
                    // 常常停在原列而不是目标列的格心上，导致永远判不"到达"，每级都要等卡住超时。
                    if (npc.blockPosition().getY() < stepStartY) {
                        lastTarget = null;
                        stepWaitRounds = 0;
                        steps++;
                        failedSteps = 0;
                    } else if (!npc.isWalking()) {
                        // 走位被身体层判超时放弃（掉坑、被方块卡住）：换方向重挖，
                        // 否则这里会一直等一个永远到不了的目标。
                        lastTarget = null;
                        stepWaitRounds = 0;
                        dirIndex++;
                        failedSteps++;
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " descend 走位中断，转向"
                                + " 连续失败=" + failedSteps);
                        if (failedSteps >= 6) {
                            // 镐子碎光或前方全是基岩时，这里会一直转向空转。
                            // 结束技能让目标层的"无进展"计数生效，最终交给规划器补工具。
                            finish(id, "descend", "连续 " + failedSteps + " 级挖不动/走不动 Y="
                                    + Math.round(npc.getY()));
                        }
                        return;
                    } else {
                        // 身体还自称在走：给单级等待设死线（200 轮×3 tick ≈ 30 秒）。
                        // 贴墙磨蹭的微小位移会反复重置 walkTo 自己的卡住计时，
                        // 不设死线这一级能干等满 3 分钟总看门狗（fix10 长跑实测两连 stall）。
                        if (++stepWaitRounds > 200) {
                            stepWaitRounds = 0;
                            lastTarget = null;
                            npc.stopWalking();
                            dirIndex++;
                            failedSteps++;
                            LOG.info("[AIV][SKILL] " + npc.npcName() + " descend 单级等待超时，强制转向"
                                    + " 连续失败=" + failedSteps + " Y=" + Math.round(npc.getY()));
                            if (failedSteps >= 6) {
                                finish(id, "descend", "连续 " + failedSteps + " 级等待超时 Y="
                                        + Math.round(npc.getY()));
                            }
                        }
                        return;
                    }
                }
                if (npc.getY() <= targetY) {
                    finish(id, "descend", "到达 Y=" + Math.round(npc.getY()) + " 步数=" + steps);
                    return;
                }
                if (steps >= maxSteps) {
                    finish(id, "descend", "步数用尽 Y=" + Math.round(npc.getY()) + " 步数=" + steps);
                    return;
                }

                BlockPos feet = npc.blockPosition();
                int[] dir = dirs[dirIndex % dirs.length];
                BlockPos ahead = feet.offset(dir[0], 0, dir[1]);
                BlockPos aheadHead = ahead.above();
                BlockPos nextFeet = ahead.below();
                BlockState aheadState = npc.level().getBlockState(ahead);
                BlockState aheadHeadState = npc.level().getBlockState(aheadHead);
                BlockState nextFeetState = npc.level().getBlockState(nextFeet);
                // 流体 / 基岩 / 悬空：这一级不安全，转向。悬空会直接掉进洞穴或岩浆湖。
                if (nextFeetState.is(Blocks.BEDROCK) || aheadState.is(Blocks.BEDROCK)
                        || aheadHeadState.is(Blocks.BEDROCK)
                        || isFluid(nextFeetState) || isFluid(aheadState) || isFluid(aheadHeadState)
                        || npc.level().getBlockState(nextFeet.below()).getCollisionShape(
                                npc.level(), nextFeet.below()).isEmpty()) {
                    // 【fix73】同层横移步：本方向被判不安全时先看台面——ahead
                    // 与 aheadHead 都空、nextFeet 本身是实心可站块（非流体），
                    // 说明正前方就有一块同层台面/屋顶块。真人站屋顶会先横走
                    // 到屋檐再找路，而不是原地转圈。横走落差 ≤1、必落在
                    // nextFeet 顶面；不挂 lastTarget（无降步可结算）、不计步，
                    // 从新列重评四向。farm31 实证缺口：叠层帽盖顶四向全灭+
                    // 脚下走一格被帽块托住假到达，目标原地重派死循环。
                    if (lateralMoves < 6 && !npc.isWalking()
                            && (aheadState.isAir() || aheadState.canBeReplaced())
                            && (aheadHeadState.isAir() || aheadHeadState.canBeReplaced())
                            && !isFluid(nextFeetState)
                            && !nextFeetState.getCollisionShape(
                                    npc.level(), nextFeet).isEmpty()) {
                        lateralMoves++;
                        npc.walkTo(new Vec3(ahead.getX() + 0.5D, feet.getY(),
                                ahead.getZ() + 0.5D), false, null);
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " descend 同层横移 | to="
                                + ahead.toShortString() + " 横移次数=" + lateralMoves
                                + " Y=" + feet.getY());
                        return;
                    }
                    dirIndex++;
                    unsafeTurns++;
                    // 只在转过一整圈后记一条：雨林树冠上原本每 3 tick 刷一行日志，
                    // 玩家看起来像刷屏，实际信息量是零。
                    if (unsafeTurns < dirs.length) {
                        return;
                    }
                    unsafeTurns = 0;
                    // 四个方向都没有可站的下一级：多半站在树冠或悬崖边缘，脚下全是空气。
                    // 玩家的做法是直接向下挖穿树叶落地，这里做同样的事；
                    // 一次只挖脚下 1 格，重力会把人带下去，落地高度永远只有 1 格，不会摔伤。
                    BlockPos belowFeet = feet.below();
                    BlockState belowState = npc.level().getBlockState(belowFeet);
                    if (isFluid(belowState) || belowState.is(Blocks.BEDROCK)) {
                        failedSteps++;
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " descend 无法落脚 | 脚下="
                                + belowState.getBlock() + " 连续失败=" + failedSteps
                                + " Y=" + feet.getY());
                        if (failedSteps >= 6) {
                            finish(id, "descend", "四周与脚下都不安全 Y=" + feet.getY());
                        }
                        return;
                    }
                    // 脚下本来就是空气却还站着（实测站在树叶/台阶边缘、矿洞边缘会出现）：
                    // 没有东西可挖，mineWithBestTool 会直接返回 false，之后每 tick
                    // 都在"垂直下挖 / 走位中断"之间空转（实测连续 6 级全失败）。
                    // 玩家的做法是往下一格走过去，让重力把人接住。
                    if (belowState.isAir()) {
                        stepStartY = feet.getY();
                        lastTarget = belowFeet;
                        npc.walkTo(new Vec3(belowFeet.getX() + 0.5D, belowFeet.getY(),
                                belowFeet.getZ() + 0.5D), false, null);
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " descend 脚下悬空，向下走一格 | Y="
                                + feet.getY());
                        return;
                    }
                    stepStartY = feet.getY();
                    npc.mineWithBestTool(belowFeet);
                    lastTarget = belowFeet;
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " descend 垂直下挖 | 脚下="
                            + belowState.getBlock() + " Y=" + feet.getY());
                    return;
                }
                unsafeTurns = 0;

                // 一次挖掉三格：新站位脚部（y-1）、新站位头部（y）、以及当前头部高度的 y+1。
                // 少挖 y+1 的话，身体还在一层高的位置时脑袋会撞上左侧的方块，
                // walkTo 会一直卡住等 4 秒超时，下矿速度只有每分钟十几格。
                stepStartY = feet.getY();
                npc.mineWithBestTool(ahead);
                npc.mineWithBestTool(aheadHead);
                npc.mineWithBestTool(nextFeet);
                // 挖完立刻检查：下一格的脚部如果还有碰撞，说明镐子碎了或方块挖不动。
                // 这时候再 walkTo 只会白等 4 秒并被判卡住，直接转向更省时间。
                if (!npc.level().getBlockState(nextFeet).getCollisionShape(npc.level(), nextFeet).isEmpty()) {
                    dirIndex++;
                    failedSteps++;
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " descend 挖不动 | 下一格="
                            + npc.level().getBlockState(nextFeet).getBlock() + " 连续失败=" + failedSteps);
                    if (failedSteps >= 6) {
                        finish(id, "descend", "连续 " + failedSteps + " 格挖不动，可能缺少镐 Y="
                                + Math.round(npc.getY()));
                    }
                    return;
                }
                // 走到"洞中心再往前 0.4 格"：walkTo 的到达半径是 0.5，
                // 只给格心的话站在洞沿（离格心正好 0.5）就会被判到达，人却没掉下去，
                // 于是每级都白等一次超时、还乱转方向。多给 0.4 格强制它真正走进洞里。
                npc.walkTo(new Vec3(nextFeet.getX() + 0.5D + dir[0] * 0.4D, nextFeet.getY(),
                        nextFeet.getZ() + 0.5D + dir[1] * 0.4D), false, null);
                lastTarget = nextFeet;
                if (steps % 8 == 0) {
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " descend 进度 | 步数=" + steps
                            + " Y=" + npc.blockPosition().getY() + " " + ahead.toShortString());
                }
            }
        }, 1L, 3L));
        // 从地表（Y=86）到钻石层（Y=-54）要下 140 级，每级 ~2 秒，总共 ~4.5 分钟。
        // 3×SKILL_TIMEOUT_TICKS = 120 秒根本不够，实测到 Y=-24 就被 watchdog 掐断了（2026-10-25 14:31）。
        // 放宽到 15× = 10 分钟，阶梯下挖总步数上限才 200，真挖不动了自然会触发 failedSteps 收手。
        armWatchdog(id, "descend", SKILL_TIMEOUT_TICKS * 15);
        return true;
    }

    /**
     * 阶梯式上挖：镐子碎光、被困在矿道里时的自救，沿水平方向逐层向上挖回地表。
     *
     * <p>为什么必须做这个：木镐只有 59 点耐久，下挖到石头层经常中途碎掉。
     * 没有镐就挖不动石头（采不到圆石），地下也没有树可以砍，
     * 规划器补工具的所有路线都会失败——NPC 会在矿道里无限空转。
     * 玩家遇到这种情况会用手慢慢把头顶的方块挖开爬回地面，这里做等价行为。
     *
     * <p>每步挖三格：目标站位脚/头、以及当前头顶上方一格（起身时要过脑袋）。
     * 目标格下方必须是实心（作为台阶），遇到流体/基岩/悬空就转向。
     * 没有工具时素手挖（无掉落，只为脱困）；连续 6 次挖不动（比如被基岩层封死）
     * 就失败收场让规划层换方向——不许传送回地表（Fong 定）。
     */
    private boolean runAscend(Intent intent, int id) {
        current = "ascend";
        // 可选目标 Y：只爬到这一层就停（例如在 Y=16 挖完铁后要去 Y=48 挖煤）。
        // 缺省（未给 y）时一路回到地表，用于"被困在矿道里自救"。
        final int goalY = intent.intParam("y", Integer.MIN_VALUE);
        LOG.info("[AIV][SKILL] " + npc.npcName() + " ascend 开始 | 当前Y="
                + npc.blockPosition().getY() + " 目标Y=" + (goalY == Integer.MIN_VALUE ? "地表" : goalY)
                + " 地表Y=" + surfaceHeightAt(npc.blockPosition().getX(),
                npc.blockPosition().getZ()));

        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int steps;
            /** 4 个水平方向轮换；撞到基岩/流体就换方向。 */
            private final int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
            private int dirIndex;
            private BlockPos lastTarget;
            /** 挖这一级之前的脚部 Y：身体真的升上去了才算完成一步。 */
            private int stepStartY = Integer.MIN_VALUE;
            private int failedSteps;
            /**
             * 连续"目标格是流体/基岩"的转向次数。
             * 从水底往上爬时每一级的头顶都是水，旧逻辑每 3 tick 转一次、刷一行日志、
             * 永远不累计失败，只能等 120 秒 watchdog。转满一圈记一次，两圈就收手。
             */
            private int unsafeTurns;
            /** 总进度看门狗：步数不变的轮数（每轮 3 tick）。见 run() 开头说明。 */
            private int lastStepsSeen;
            private int roundsWithoutProgress;
            /** 悬空放行窗（第十三轮，与 descend 同修）：见 run() 里的悬空放行分支。 */
            private int airGateRounds;
            private double airGateMinY = Double.NaN;
            private double airGateMaxY = Double.NaN;
            /** 悬空总时长死线（第四十一轮，与 descend 同修）：见 descend 字段注释。 */
            private int airTotalRounds;

            @Override
            public void run() {
                if (runId != id) {
                    return;
                }
                // 总进度看门狗（与 descend 同理）：ascend 的失败收场只挂在"挖不动"一条
                // 分支后面，其余空转组合（转向/等走位/垫脚失败交替）永远走不到那里，
                // 步数不变约 3 分钟（1200 轮×3 tick）就直接收手，交给目标层处理。
                if (steps != lastStepsSeen) {
                    lastStepsSeen = steps;
                    roundsWithoutProgress = 0;
                } else if (++roundsWithoutProgress > 1200) {
                    finish(id, "ascend", "长时间无进展（步数约3分钟未变） Y="
                            + Math.round(npc.getY()) + " 步数=" + steps);
                    return;
                }
                // 空中（起跳/下落）时不挖下一格，等站稳再说。
                if (!npc.onGround) {
                    if (lastTarget != null) {
                        airTotalRounds = 0;
                        // 【第四十二轮】与 descend 同修：先按高度结算——方块 Y
                        // 已高于起始线即算到达。雪层站姿 onGround 恒 false 时旧
                        // 逻辑直接 return、步数永不结算，只能等步数看门狗收场。
                        if (npc.blockPosition().getY() > stepStartY) {
                            lastTarget = null;
                            steps++;
                            failedSteps = 0;
                            unsafeTurns = 0;
                        }
                        return;
                    }
                    // 与 descend 同病（第十三轮）：无待结算步却报悬空
                    // （站在竹竿顶/方块边缘等怪碰撞上），旧逻辑无声空等到步数看门狗。
                    // 同样用窗口判定：约 6 秒内 Y 活动幅度 ≤0.1 格按已站稳放行。
                    double yNow = npc.getY();
                    if (Double.isNaN(airGateMinY)) {
                        airGateMinY = yNow;
                        airGateMaxY = yNow;
                    }
                    airGateMinY = Math.min(airGateMinY, yNow);
                    airGateMaxY = Math.max(airGateMaxY, yNow);
                    // 【第四十一轮】阈值 0.1→0.25 + 30 秒总死线，与 descend 同口径。
                    if (airGateMaxY - airGateMinY > 0.25D) {
                        airGateMinY = yNow;
                        airGateMaxY = yNow;
                        airGateRounds = 0;
                        airTotalRounds++;
                        return;
                    }
                    airGateRounds++;
                    airTotalRounds++;
                    if (airGateRounds == 41) {
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " ascend 悬空但 Y 窗口稳定，按已站稳放行 | y="
                                + String.format(java.util.Locale.ROOT, "%.2f", npc.getY()));
                    }
                    if (airTotalRounds == 201) {
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " ascend 悬空超 30 秒死线，按已站稳强制放行 | y="
                                + String.format(java.util.Locale.ROOT, "%.2f", npc.getY()));
                    }
                    if (airGateRounds <= 40 && airTotalRounds <= 200) {
                        return;
                    }
                } else {
                    airGateRounds = 0;
                    airTotalRounds = 0;
                    airGateMinY = Double.NaN;
                    airGateMaxY = Double.NaN;
                }
                if (lastTarget != null) {
                    if (npc.blockPosition().getY() > stepStartY) {
                        lastTarget = null;
                        steps++;
                        failedSteps = 0;
                        unsafeTurns = 0;
                    } else if (!npc.isWalking()) {
                        lastTarget = null;
                        dirIndex++;
                        failedSteps++;
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " ascend 走位中断，转向 | 连续失败="
                                + failedSteps);
                        if (failedSteps >= 6) {
                            finish(id, "ascend", "连续 " + failedSteps + " 级上不去 Y="
                                    + Math.round(npc.getY()));
                        }
                        return;
                    } else {
                        return;
                    }
                }

                BlockPos feet = npc.blockPosition();
                int surfaceY = surfaceHeightAt(feet.getX(), feet.getZ());
                // 脱困判据不能只看高度图：NPC 掉进自己挖的 1×1 竖井时，
                // 它头顶那列全是空气，高度图给出的"地表"就是井底自己站的那层，
                // 于是 ascend 一到井底就宣布"已回到地表"停下，而四面都是墙，A* 永远走不出去。
                // 真正的判据是"脚下这层至少有一个能站/能下的邻格"，也就是真的接回了地面路网。
                if (goalY != Integer.MIN_VALUE) {
                    // 高度到了但落点在水里：直接结束，交给目标层去 dryland 上岸。
                    // 站在池塘水面里继续往上挖是不可能的（头顶全是流体），硬爬只会转到超时。
                    if (feet.getY() >= goalY && isFluid(npc.level().getBlockState(feet))) {
                        finish(id, "ascend", "到达目标层但落在水里 Y=" + feet.getY() + "，需要上岸");
                        return;
                    }
                    if (feet.getY() >= goalY && hasOpenNeighbor(feet)) {
                        finish(id, "ascend", "已到目标层 Y=" + feet.getY() + " 步数=" + steps);
                        return;
                    }
                } else if (feet.getY() >= surfaceY - 1 && hasOpenNeighbor(feet)
                        && !isFluid(npc.level().getBlockState(feet))) {
                    finish(id, "ascend", "已回到地表 Y=" + feet.getY() + " 步数=" + steps);
                    return;
                }
                if (steps >= 240) {
                    finish(id, "ascend", "步数用尽 Y=" + feet.getY() + " 步数=" + steps);
                    return;
                }

                int[] dir = dirs[dirIndex % dirs.length];
                BlockPos targetFeet = feet.offset(dir[0], 1, dir[1]);
                BlockPos targetHead = targetFeet.above();
                BlockPos currentCeiling = feet.above(2);
                BlockState targetFeetState = npc.level().getBlockState(targetFeet);
                BlockState targetHeadState = npc.level().getBlockState(targetHead);
                BlockState stepState = npc.level().getBlockState(targetFeet.below());
                // 流体/基岩：这一级不安全（会掉进去或灌水），转向。
                if (targetFeetState.is(Blocks.BEDROCK) || targetHeadState.is(Blocks.BEDROCK)
                        || isFluid(targetFeetState) || isFluid(targetHeadState) || isFluid(stepState)) {
                    dirIndex++;
                    unsafeTurns++;
                    // 转满两圈都上不去才记一次失败：多半整条竖井被水灌满。
                    // 记满两次就结束，让目标层调 dryland 上岸，而不是在这里转到 watchdog。
                    if (unsafeTurns < dirs.length * 2) {
                        return;
                    }
                    unsafeTurns = 0;
                    failedSteps++;
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " ascend 四周不可上挖 | target="
                            + targetFeetState.getBlock() + " step=" + stepState.getBlock()
                            + " 连续失败=" + failedSteps + " Y=" + feet.getY());
                    if (failedSteps >= 2) {
                        finish(id, "ascend", "四周被流体/基岩包围，无法上挖 Y=" + feet.getY());
                    }
                    return;
                }
                // 台阶下方悬空（洞穴边缘、竖井口）：先垫一块再上，像玩家垒台阶那样。
                // 旧逻辑直接转向，可四个方向全是"下方空气"时会永远原地转圈，
                // 实测 NPC 就卡在这种洞穴峭壁上，A* 只能展开 53 个节点。
                if (stepState.getCollisionShape(npc.level(), targetFeet.below()).isEmpty()
                        && !placeBuildingBlockAt(targetFeet.below())) {
                    dirIndex++;
                    unsafeTurns++;
                    if (unsafeTurns < dirs.length * 2) {
                        return;
                    }
                    unsafeTurns = 0;
                    failedSteps++;
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " ascend 四周无落脚点 | target="
                            + targetFeetState.getBlock() + " 连续失败=" + failedSteps);
                    if (failedSteps >= 2) {
                        finish(id, "ascend", "四周悬空且没有垫脚方块 Y=" + feet.getY());
                    }
                    return;
                }
                unsafeTurns = 0;

                stepStartY = feet.getY();
                // 【第三十三轮】ascend 是"工具耗尽素手脱困"的路径：选工具不许动退役壳
                // （allowLastResort=false），没工具就走下面的真素手挖——长跑#10 实测
                // 兜底+主手挖在这里 7 秒烧光石剑和 4 把退役镐，最后还是卡死。
                npc.mineWithBestTool(currentCeiling, false);
                npc.mineWithBestTool(targetFeet, false);
                npc.mineWithBestTool(targetHead, false);
                // 没有合适工具时素手挖：石头/泥土用手也能挖开（只是没有掉落），
                // 这是"工具耗尽被困地下"时唯一的合法脱困办法（Fong 定：不许重生/传送逃逸）。
                // 基岩这类素手也挖不动的，下面的碰撞验证会照常判失败，不会假成功。
                // 素手挖必须走 mineBareHand：mine() 挖的是主手物品，生存层把武器切回
                // 主手时就会拿剑挖石头烧耐久（长跑#10 实证）。
                if (!npc.level().getBlockState(targetFeet).getCollisionShape(npc.level(), targetFeet).isEmpty()) {
                    npc.mineBareHand(targetFeet);
                }
                if (!npc.level().getBlockState(targetHead).getCollisionShape(npc.level(), targetHead).isEmpty()) {
                    npc.mineBareHand(targetHead);
                }
                if (!npc.level().getBlockState(currentCeiling).getCollisionShape(npc.level(), currentCeiling).isEmpty()) {
                    npc.mineBareHand(currentCeiling);
                }
                // 挖完立刻验证：目标脚部还有碰撞说明挖不动（基岩/保护区域），换方向。
                if (!npc.level().getBlockState(targetFeet).getCollisionShape(npc.level(), targetFeet).isEmpty()) {
                    dirIndex++;
                    failedSteps++;
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " ascend 挖不动 | 目标="
                            + npc.level().getBlockState(targetFeet).getBlock() + " 连续失败=" + failedSteps);
                    if (failedSteps >= 6) {
                        // 被基岩/保护区域彻底封死：老实失败收场，交给规划层换方向/换目标。
                        // 旧做法是传送到最近的地表自救——已按 Fong 的决定拆除：
                        // 假人只能靠自己，挖不动就是挖不动，宁可待命打日志也不许瞬移回地表。
                        finish(id, "ascend", "挖不动（基岩/保护区封死），失败收场 Y=" + feet.getY());
                    }
                    return;
                }
                // 同 descend：目标点给到格心再往前 0.4 格，避免站在台阶边缘就被判“已到达”。
                npc.walkTo(new Vec3(targetFeet.getX() + 0.5D + dir[0] * 0.4D, targetFeet.getY(),
                        targetFeet.getZ() + 0.5D + dir[1] * 0.4D), false, null);
                lastTarget = targetFeet;
                if (steps % 8 == 0) {
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " ascend 进度 | 步数=" + steps
                            + " Y=" + feet.getY() + " " + targetFeet.toShortString());
                }
            }
        }, 1L, 3L));
        armWatchdog(id, "ascend", SKILL_TIMEOUT_TICKS * 3);
        return true;
    }

    /**
     * 上岸：找一个最近的干燥落脚点走过去，途中泡在水里就先上浮。
     *
     * <p>为什么需要：矿道一旦挖进水域就会被灌满，水平掘进在水下四面都是流体，
     * strip 只能原地转向；玩家这时会先游出水面、走到岸边，再从干地重新下挖。
     * 判据用"脚和头都不是流体"，因为只有空气格才算真正离开了水。
     */
    private boolean runDryLand(Intent intent, int id) {
        final int radius = Math.max(4, Math.min(24, intent.intParam("radius", 16)));
        current = "dryland";
        BlockPos from = npc.blockPosition();
        // 人在水里时，落脚点还必须"能从水面爬上去"（第四轮修复 B）：只判 isStandable
        // 会把崖顶格选成目标，NPC 贴着崖壁走/游都到不了，白白耗到超时
        // （第三轮长跑实测）。判据与生存层找岸共用 PathFinder.isShoreCell。
        boolean inFluidNow = isFluid(npc.level().getBlockState(from))
                || isFluid(npc.level().getBlockState(from.above()));
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        // 先看上层再看下层：从水里出来一般要往上走，落在岸边而不是水底。
        // 只接受 isStandable（脚下实心、脚/头可通过且非流体）的格子；
        // 在水里时再叠一层 isShoreCell（贴水面、最多高 1 格）。
        for (int dy = 3; dy >= -3; dy--) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = from.offset(dx, dy, dz);
                    if (inFluidNow ? !PathFinder.isShoreCell(npc.level(), pos)
                            : !PathFinder.isStandable(npc.level(), pos)) {
                        continue;
                    }
                    double distanceSq = from.distSqr(pos);
                    if (distanceSq < bestSq) {
                        bestSq = distanceSq;
                        best = pos.immutable();
                    }
                }
            }
        }
        if (best == null) {
            lastOutcome = "dryland 附近没有干燥落脚点";
            LOG.info("[AIV][SKILL] " + npc.npcName() + " dryland 放弃 | 半径=" + radius
                    + " 内没有可站立且不含流体的格子");
            return false;
        }
        final BlockPos target = best;
        LOG.info("[AIV][SKILL] " + npc.npcName() + " dryland 开始 | 目标=" + target.toShortString()
                + " 距离=" + String.format(Locale.ROOT, "%.1f", Math.sqrt(bestSq)));

        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int ticks;

            @Override
            public void run() {
                if (runId != id) {
                    return;
                }
                BlockPos feet = npc.blockPosition();
                // 判定"已经干"用脚和头两格：只离开水面半格时头还泡在水里，挖洞照样灌水。
                if (!isFluid(npc.level().getBlockState(feet))
                        && !isFluid(npc.level().getBlockState(feet.above()))) {
                    npc.stopWalking();
                    finish(id, "dryland", "已到干燥地面 " + feet.toShortString()
                            + " 耗时=" + ticks);
                    return;
                }
                if (++ticks > 240) {
                    finish(id, "dryland", "上不了岸 Y=" + feet.getY() + " pos=" + feet.toShortString());
                    return;
                }
                // 整个人泡在水里时持续给一点向上速度：原版玩家按住跳跃上浮就是这个效果，
                // 只有水平输入的话会一直贴着水底走，永远出不了深水。
                if (npc.isInWater()) {
                    Vec3 movement = npc.getDeltaMovement();
                    npc.setDeltaMovement(movement.x, Math.max(movement.y, 0.04D), movement.z);
                }
                // 正在走就等它走完；每 10 tick 才重算一次路径，避免每 tick 重规划把身体锁死。
                if (npc.isFollowingPath() || npc.isWalking()) {
                    return;
                }
                if (ticks % 10 != 1 && ticks > 1) {
                    return;
                }
                PathFinder.Result result = PathFinder.find(npc.level(), feet, target, 4000, !npc.wearingLeatherBoots());
                if (result.found()) {
                    npc.followPath(result.path(), false, null, null);
                } else {
                    // 水里起点常常被判"不可站立"，A* 直接失败；退化成直线走，
                    // 隔着水潭时至少能朝岸边靠近。
                    npc.walkTo(new Vec3(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D),
                            false, null);
                }
            }
        }, 1L, 2L));
        armWatchdog(id, "dryland", 400);
        return true;
    }

    /** 高度图给出的站立 Y（该列最高的可站立方块上方一格）。 */
    private int surfaceHeightAt(int x, int z) {
        return npc.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    /**
     * 四个水平方向里是否至少有一格能站/能下一阶。
     * 用于判断"脱困是否真的接回了地面路网"，而不是掉进自己挖的竖井里出不来。
     */
    private boolean hasOpenNeighbor(BlockPos feet) {
        for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockPos side = feet.offset(dir[0], 0, dir[1]);
            if (PathFinder.isStandable(npc.level(), side)
                    || PathFinder.isStandable(npc.level(), side.below())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 水平掘进找矿：像玩家一样挖一条 1x2 的矿道，边走边看两侧墙里有没有目标矿石。
     *
     * <p>只在相邻格找矿，不隔着墙"透视"：矿石一旦暴露在矿道壁上就顺手挖掉，
     * 挖不到就继续往前推进。遇到流体/基岩/悬空同样转向，避免挖穿岩浆池。
     *
     * @param block 目标矿石方块 ID（技能层自动匹配深板岩变种）
     */
    private boolean runStrip(Intent intent, int id) {
        String blockName = intent.param("block", "iron_ore");
        Set<String> variants = oreVariants(blockName);
        int length = Math.max(4, Math.min(64, intent.intParam("length", 24)));
        // 目标层传入"还差几个"：挖够了立刻收手，避免把整条镐子的耐久都烧在多余的矿石上。
        int limit = Math.max(0, intent.intParam("limit", 0));
        current = "strip";
        // 起始方向不能固定从正东开始：之前 dirIndex 恒为 0，目标层每次重新下发 strip
        // 都朝东重挖同一条 24 格矿道（实测 Y=16 连续 5 次「步数=24 已挖=0」原地打转）。
        // 玩家的做法是换方向掏分支矿道，这里按上一条矿道的结果决定：
        //  · 上一条矿道刚挖空（60 秒内、一个矿都没出）→ 垂直于旧矿道分支，避开空区域；
        //  · 其余情况 → 随机方向，避免每次都贴着同一条线走。
        boolean branch = lastStripDir >= 0 && !lastStripFoundOre
                && System.currentTimeMillis() - lastStripEndMillis < 60_000L;
        // 两个垂直方向二选一；不选反方向，否则等于沿旧矿道原路挖回去。
        final int startDir = branch
                ? (lastStripDir + (java.util.concurrent.ThreadLocalRandom.current().nextBoolean() ? 1 : 3)) % 4
                : java.util.concurrent.ThreadLocalRandom.current().nextInt(4);
        String[] dirNames = {"东", "南", "西", "北"};
        LOG.info("[AIV][SKILL] " + npc.npcName() + " strip 开始 | 目标=" + variants
                + " 长度=" + length + " 需要=" + limit + " Y=" + npc.blockPosition().getY()
                + " 方向=" + dirNames[startDir] + (branch ? "（分支）" : ""));

        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int steps;
            private int oreFound;
            private int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
            private int dirIndex = startDir;
            /** 当前这一步朝哪个方向走（dirs 下标）；到达后写进 lastDir。 */
            private int walkingDir = startDir;
            /** 本次技能最后一次成功掘进的方向；结束时交给下一条矿道做分支判断。 */
            private int lastDir = -1;
            private BlockPos lastTarget;
            /** 连续几次挖不动/走不动：用来在镐子碎光时结束技能，别无限转向。 */
            private int failedSteps;

            /**
             * 结束技能，并记下本条矿道的方向与是否挖到矿。
             * 下一条 strip 据此做分支：挖空的矿道不再沿原方向重复掘进。
             * 如果一步都没走成（lastDir=-1），就用当前朝向当作旧方向，
             * 让后续分支至少不会又贴着这条死路继续。
             */
            private void end(String detail) {
                lastStripDir = lastDir >= 0 ? lastDir : (dirIndex % dirs.length);
                lastStripFoundOre = oreFound > 0;
                lastStripEndMillis = System.currentTimeMillis();
                finish(id, "strip", detail);
            }

            @Override
            public void run() {
                if (runId != id) {
                    return;
                }
                if (!npc.onGround) {
                    return;
                }
                if (lastTarget != null) {
                    // 走位结束（到达或超时放弃）才结算这一步：用格坐标判断是否真的进了目标列，
                    // walkTo 有 0.6 格容差，停在原列时不能算作掘进成功。
                    if (npc.isWalking()) {
                        return;
                    }
                    if (npc.blockPosition().equals(lastTarget)) {
                        steps++;
                        failedSteps = 0;
                        // 只有真正走进了目标格才算掘进成功，方向也只在此时更新。
                        lastDir = walkingDir;
                    } else {
                        dirIndex++;
                        failedSteps++;
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " strip 走位未到位，转向 连续失败="
                                + failedSteps);
                        if (failedSteps >= 6) {
                            end("连续 " + failedSteps + " 步走不动 Y="
                                    + Math.round(npc.getY()));
                            return;
                        }
                    }
                    lastTarget = null;
                }
                if (steps >= length) {
                    end("掘进结束 未发现 " + variants + " 步数=" + steps
                            + " 已挖=" + oreFound);
                    return;
                }

                BlockPos feet = npc.blockPosition();
                // 先看矿道两侧 2 格内有没有暴露的目标矿石：有就直接挖，效率优先。
                BlockPos ore = findVariantNear(feet, variants, 2);
                if (ore != null && touchesLava(ore)) {
                    // 【fix37】岩浆邻接的矿不挖：掉落易落岩浆、上方岩浆会灌顶。
                    // 不 return，落到下面的掘进逻辑继续推进，别被这块矿钉在原地。
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " strip 跳过岩浆邻接矿 | "
                            + variantPath(ore) + " " + ore.toShortString());
                    ore = null;
                }
                if (ore != null) {
                    // 必须在挖掉之前取方块名：挖完再读坐标只会拿到 air，
                    // 日志里全变成「挖到矿石 | air」，没法判断真正挖到的是哪种矿。
                    String oreName = variantPath(ore);
                    if (npc.mineWithBestTool(ore)) {
                        oreFound++;
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " strip 挖到矿石 | "
                                + oreName + " " + ore.toShortString() + " 第" + oreFound + "个");
                        if (limit > 0 && oreFound >= limit) {
                            end("挖够目标数量 " + oreFound + "/" + limit);
                            return;
                        }
                    }
                    return;
                }
                // 【fix38·Fong 04:14】煤顺手采集：目标矿之外，两侧有裸露煤且背包
                // 煤+木炭存量未到上限向导（64）就顺手挖一颗，到了上限跳过省镐
                // 耐久。煤是顺路型储备，规划层已拆除常驻挖煤目标（#11 病根），
                // 存量靠的就是这种掘进路过时的一颗颗积累。只认 findVariantNear
                // 的裸露判定（不透视）；岩浆邻接同样不挖（fix37 口径）。
                // 本技能目标本身就是煤时（燃料告急）不走这里——上面 ore 路径已处理。
                if (!variants.contains("coal_ore")) {
                    BlockPos coalOre = countItemStock("coal", "charcoal") < COAL_OPPORTUNISTIC_CAP
                            ? findVariantNear(feet, oreVariants("coal_ore"), 2) : null;
                    if (coalOre != null && !touchesLava(coalOre)) {
                        if (npc.mineWithBestTool(coalOre)) {
                            LOG.info("[AIV][SKILL] " + npc.npcName() + " strip 顺手挖煤 | "
                                    + variantPath(coalOre) + " " + coalOre.toShortString()
                                    + " 煤存量=" + countItemStock("coal", "charcoal"));
                        }
                        return;
                    }
                }
                // 【fix40】记矿：没有铁镐时顺手挖不了的金矿/钻石矿，记坐标
                // 回访（只记 2 格内真扫到的裸露矿、不占本 tick 动作、不
                // return）。有铁镐时不记——顺手挖与主线会处理。
                if (!hasPickTier(2)) {
                    OreMemory.bindWorld(npc.level());
                    BlockPos seenGold = findVariantNear(feet, oreVariants("gold_ore"), 2);
                    if (seenGold != null && !touchesLava(seenGold)) {
                        OreMemory.record(npc.npcName(), variantPath(seenGold), seenGold);
                    } else {
                        BlockPos seenDiamond = findVariantNear(feet, oreVariants("diamond_ore"), 2);
                        if (seenDiamond != null && !touchesLava(seenDiamond)) {
                            OreMemory.record(npc.npcName(), variantPath(seenDiamond), seenDiamond);
                        }
                    }
                }
                // 【fix38·D7，Fong 04:25】副矿顺手挖：金矿遇见就挖、不限量；
                // 青金石可挖但存量上限 2 组（128）、满了跳过；红石照旧不理。
                // 与煤同口径：只认裸露、岩浆邻接不挖、一 tick 一块不抢主目标节奏。
                if (!variants.contains("gold_ore") && hasPickTier(2)) {
                    BlockPos goldOre = findVariantNear(feet, oreVariants("gold_ore"), 2);
                    if (goldOre != null && !touchesLava(goldOre)) {
                        if (npc.mineWithBestTool(goldOre)) {
                            LOG.info("[AIV][SKILL] " + npc.npcName() + " strip 顺手挖金 | "
                                    + variantPath(goldOre) + " " + goldOre.toShortString());
                        }
                        return;
                    }
                }
                if (!variants.contains("lapis_ore") && hasPickTier(1)
                        && countItemStock("lapis_lazuli") < LAPIS_OPPORTUNISTIC_CAP) {
                    BlockPos lapisOre = findVariantNear(feet, oreVariants("lapis_ore"), 2);
                    if (lapisOre != null && !touchesLava(lapisOre)) {
                        if (npc.mineWithBestTool(lapisOre)) {
                            LOG.info("[AIV][SKILL] " + npc.npcName() + " strip 顺手挖青金 | "
                                    + variantPath(lapisOre) + " " + lapisOre.toShortString()
                                    + " 青金存量=" + countItemStock("lapis_lazuli"));
                        }
                        return;
                    }
                }

                // 选择一个真正能掘进的方向：从 dirIndex 开始依次尝试，并且跳过"反方向"。
                // 反方向等于回头重走已挖空的矿道：旧逻辑在矿道尽头遇到悬空/流体时
                // 连续转向掉头，结果在同一条 8 格矿道里来回跑，10 秒凑满 24 步
                // 却一块矿都挖不到（实测 105,19,61 ↔ 112,19,61 往返了 7 轮）。
                int reverseDir = (dirIndex + 2) % dirs.length;
                int chosenDir = -1;
                BlockPos ahead = null;
                for (int offset = 0; offset < dirs.length; offset++) {
                    int idx = (dirIndex + offset) % dirs.length;
                    if (idx == reverseDir) {
                        continue;
                    }
                    BlockPos candidate = feet.offset(dirs[idx][0], 0, dirs[idx][1]);
                    BlockState candidateFeet = npc.level().getBlockState(candidate);
                    BlockState candidateHead = npc.level().getBlockState(candidate.above());
                    BlockState candidateFloor = npc.level().getBlockState(candidate.below());
                    // 【fix37】岩浆邻接的方向按"无路"处理：候选格或其头位格六邻有岩浆
                    // 就不挖——挖穿岩浆袖珍层，岩浆会灌进矿道而 NPC 正站在掘进面前面。
                    // 与 tryDigStep/digStepDown 的邻接标准对齐（此前 strip 是唯一没查的挖掘路径）。
                    if (touchesLava(candidate) || touchesLava(candidate.above())) {
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " strip 跳过方向 | 岩浆邻接 "
                                + candidate.toShortString());
                        continue;
                    }
                    if (candidateFeet.is(Blocks.BEDROCK) || candidateHead.is(Blocks.BEDROCK)
                            || isFluid(candidateFeet) || isFluid(candidateHead)
                            || candidateFloor.getCollisionShape(npc.level(), candidate.below()).isEmpty()) {
                        continue;
                    }
                    chosenDir = idx;
                    ahead = candidate;
                    break;
                }
                if (chosenDir < 0) {
                    // 除回头路外一条都挖不了：死胡同（悬空/岩浆/基岩）。
                    // 掉头只会把已挖空的矿道再走一遍并虚增步数，直接结束，
                    // 让目标层按上一条矿道的方向做垂直分支或换位置。
                    end("前方无路可掘 步数=" + steps + " Y=" + feet.getY());
                    return;
                }
                dirIndex = chosenDir;
                // 【fix37】下镐前再查一次岩浆邻接（与候选过滤同口径双保险）：
                // 命中按"挖不动"转向，别把岩浆放进矿道。
                if (touchesLava(ahead) || touchesLava(ahead.above())) {
                    dirIndex++;
                    failedSteps++;
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " strip 挖前岩浆邻接，转向 | 前方="
                            + ahead.toShortString() + " 连续失败=" + failedSteps);
                    if (failedSteps >= 6) {
                        end("连续 " + failedSteps + " 步被岩浆邻接挡住 Y="
                                + Math.round(npc.getY()));
                    }
                    return;
                }
                npc.mineWithBestTool(ahead);
                npc.mineWithBestTool(ahead.above());
                // 挖完还挡着：镐子碎了或这块挖不动，继续 walkTo 只是空等卡住。
                if (!npc.level().getBlockState(ahead).getCollisionShape(npc.level(), ahead).isEmpty()) {
                    dirIndex++;
                    failedSteps++;
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " strip 挖不动 | 前方="
                            + npc.level().getBlockState(ahead).getBlock() + " 连续失败=" + failedSteps);
                    if (failedSteps >= 6) {
                        end("连续 " + failedSteps + " 格挖不动，可能缺少镐 Y="
                                + Math.round(npc.getY()));
                    }
                    return;
                }
                // 记录这一步实际朝哪走：走位成功后才算这条矿道的方向。
                walkingDir = chosenDir;
                npc.walkTo(new Vec3(ahead.getX() + 0.5D, ahead.getY(), ahead.getZ() + 0.5D), false, null);
                lastTarget = ahead;
                if (steps % 8 == 0) {
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " strip 进度 | 步数=" + steps
                            + " 已挖=" + oreFound + " " + ahead.toShortString());
                }
            }
        }, 1L, 4L));
        armWatchdog(id, "strip", SKILL_TIMEOUT_TICKS);
        return true;
    }

    /**
     * 狩猎：找最近的动物（牛/猪/羊/鸡/兔）并打死，补食物。
     * 只认 {@link Animal}，不会误伤村民、已驯服的狼或玩家。
     */
    private boolean runHunt(Intent intent, int id) {
        // 【第四十三轮】hunt 专用半径上限 48，不再走 MAX_SEARCH_RADIUS=16 的通用钳制：
        // 五局群系合证旧钳制把上层传的 24 也压回 16，“放大搜猎半径”从未真正生效，
        // 动物间距 30–80 格的稀疏地形里等于盲走（针叶林放弃 20 次、沙漠饿死）。
        double radius = Math.max(6.0D, Math.min(48.0D, intent.doubleParam("radius", 12.0D)));
        int maxHits = Math.max(1, Math.min(12, intent.intParam("hits", 10)));
        List<Entity> candidates = npc.level().getEntities(npc, npc.getBoundingBox().inflate(radius),
                entity -> entity instanceof Animal animal && animal.isAlive() && !animal.isBaby());
        // 【第二十二轮】皮革目标来源过滤（Fong 18:48 纠正：猪不掉皮革）：filter=leather 时
        // 只认 Wiki 皮革词条列的掉皮动物（牛/哞菇/马/驴/骡/羊驼/行商羊驼，无抢夺 0–2、
        // 平均 1.0、1/3 掉 0）。兔子明确排除：兔子掉兔子皮（0–1、平均 0.5），4 张才合
        // 1 张皮革，一双靴子要 16 张兔子皮≈32 只兔子，不走这条坑路（Fong 二选一取排除）。
        // 附近没掉皮动物就如实报"没有皮革来源"，让上层换区，不许追猪/羊/兔凑数。
        if ("leather".equals(intent.param("filter", ""))) {
            java.util.Set<String> leatherSources = java.util.Set.of(
                    "cow", "mooshroom", "horse", "donkey", "mule", "llama", "trader_llama");
            List<Entity> filtered = candidates.stream()
                    .filter(e -> leatherSources.contains(
                            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                                    .getKey(e.getType()).getPath()))
                    .toList();
            if (filtered.isEmpty()) {
                lastOutcome = "hunt 附近没有皮革来源（只认牛/哞菇/马/驴/骡/羊驼）radius=" + radius
                        + " 附近动物=" + candidates.size();
                LOG.info("[AIV][SKILL] " + npc.npcName() + " hunt 没有皮革来源 | radius=" + radius
                        + " nearbyAnimals=" + candidates.size());
                return false;
            }
            candidates = filtered;
        }
        if (candidates.isEmpty()) {
            lastOutcome = "hunt 附近没有动物 radius=" + radius;
            LOG.info("[AIV][SKILL] " + npc.npcName() + " hunt 附近没有动物 | radius=" + radius);
            return false;
        }
        // 【第十九轮】皮革未攒够 4 张时优先打牛（Fong：找食物打猎时牛兼顾皮革
        // 来源，攒皮做皮靴）。上层（GoalExecutor.tickHunt）按皮革存量传 prefer=cow；
        // 附近有牛先打牛，没有牛照旧打最近的，不为皮革饿着。
        if ("cow".equals(intent.param("prefer", ""))) {
            List<Entity> cows = candidates.stream()
                    .filter(e -> "cow".equals(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                            .getKey(e.getType()).getPath()))
                    .toList();
            if (!cows.isEmpty()) {
                candidates = cows;
            }
        }
        // 【第四十三轮】半径放大后远处候选常隔河/隔崖不可达：按距离依次试接近
        // 路径，前 4 个候选都走不通才报失败——免得最近一只不可达就整轮放弃、
        // 更远处明明能打的动物被白白放过。
        List<Entity> sorted = candidates.stream()
                .sorted((left, right) -> Double.compare(npc.distanceToSqr(left), npc.distanceToSqr(right)))
                .toList();
        LivingEntity target = null;
        PathFinder.Result approachPath = null;
        int tried = 0;
        for (Entity cand : sorted) {
            if (tried >= 4) {
                break;
            }
            tried++;
            BlockPos approach = PathFinder.standableGoal(npc.level(), cand.blockPosition(), 3);
            if (approach == null) {
                continue;
            }
            PathFinder.Result path = PathFinder.find(npc.level(), npc.blockPosition(), approach,
                    !npc.wearingLeatherBoots());
            logPath("hunt-approach", approach, path);
            if (path.found()) {
                target = (LivingEntity) cand;
                approachPath = path;
                break;
            }
        }
        if (target == null) {
            lastOutcome = "hunt 候选均不可达 tried=" + tried + " candidates=" + sorted.size()
                    + " radius=" + radius;
            LOG.info("[AIV][SKILL] " + npc.npcName() + " hunt 候选均不可达 | tried=" + tried
                    + " candidates=" + sorted.size() + " radius=" + radius);
            return false;
        }
        final LivingEntity huntTarget = target;
        final PathFinder.Result huntPath = approachPath;
        current = "hunt";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " hunt 开始 | target=" + huntTarget.getType()
                + " hits=" + maxHits + " distance=" + String.format(Locale.ROOT, "%.1f", npc.distanceTo(huntTarget)));
        npc.followPath(huntPath.path(), true, () -> {
            if (runId != id) {
                return;
            }
            tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                private int hits;
                private int attempts;

                @Override
                public void run() {
                    if (runId != id) {
                        return;
                    }
                    attempts++;
                    if (huntTarget.isRemoved() || !huntTarget.isAlive()) {
                        finish(id, "hunt", "猎物已倒下 hits=" + hits);
                        return;
                    }
                    if (attempts > 160) {
                        finish(id, "hunt", "超时 hits=" + hits);
                        return;
                    }
                    double distanceSq = npc.distanceToSqr(huntTarget);
                    if (distanceSq > 16.0D) {
                        if (!npc.isFollowingPath()) {
                            BlockPos chaseGoal = PathFinder.standableGoal(npc.level(), huntTarget.blockPosition(), 3);
                            if (chaseGoal != null) {
                                PathFinder.Result chase = PathFinder.find(npc.level(), npc.blockPosition(), chaseGoal, !npc.wearingLeatherBoots());
                                if (chase.found()) {
                                    npc.followPath(chase.path(), true, null, null);
                                }
                            }
                        }
                        return;
                    }
                    npc.attackTarget(huntTarget);
                    hits++;
                    if (hits >= maxHits) {
                        finish(id, "hunt", "达到攻击次数 hits=" + hits + " 存活=" + huntTarget.isAlive());
                    }
                }
            }, 20L, 20L));
        }, () -> finish(id, "hunt", "接近路径中断"));
        armWatchdog(id, "hunt");
        return true;
    }

    /** 目标矿石的方块 ID 集合；自动带上深板岩变种（同一矿石在地下会换宿主方块）。 */
    private static Set<String> oreVariants(String blockName) {
        String base = blockName == null ? "" : blockName.trim().toLowerCase(Locale.ROOT);
        if (base.startsWith("minecraft:")) {
            base = base.substring("minecraft:".length());
        }
        Set<String> variants = new HashSet<>();
        variants.add(base);
        if (base.endsWith("_ore") && !base.startsWith("deepslate_")) {
            variants.add("deepslate_" + base);
        }
        return variants;
    }

    /** 半径内的目标矿石（只找已经暴露的，避免隔墙透视）。 */
    /** 煤顺手采集上限向导（fix38·Fong 04:14）：与规划层 COAL_OPPORTUNISTIC_CAP 同值。 */
    private static final int COAL_OPPORTUNISTIC_CAP = 64;

    /** 青金石顺手采集存量上限（fix38·D7，Fong 04:25）：2 组 = 128，满了跳过。 */
    private static final int LAPIS_OPPORTUNISTIC_CAP = 128;

    /**
     * 背包里有没有达到指定等级的可用镐（顺手挖副矿的级别门，防重蹈 #6d
     * "级别不够还硬挖"）：金矿要铁镐（tier 2）、青金要石镐（tier 1）；
     * 等级不够时整块矿留着不碰，等有够格的镐路过再说。可用口径与全系统
     * 一致：剩余耐久必须大于最后 1 点。
     */
    private boolean hasPickTier(int minTier) {
        var inv = npc.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            int tier = switch (path) {
                case "netherite_pickaxe", "diamond_pickaxe" -> 3;
                case "iron_pickaxe" -> 2;
                case "stone_pickaxe" -> 1;
                default -> 0;
            };
            if (tier >= minTier && stack.getDamageValue() < stack.getMaxDamage() - 1) {
                return true;
            }
        }
        return false;
    }

    /** 背包里指定物品（按 path，多个合计）的总数：fix38 顺手采集的存量口径。 */
    private int countItemStock(String... paths) {
        java.util.Set<String> wanted = java.util.Set.of(paths);
        int total = 0;
        var inv = npc.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (wanted.contains(path)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private BlockPos findVariantNear(BlockPos center, Set<String> variants, int radius) {
        BlockPos best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            String path = BuiltInRegistries.BLOCK.getKey(npc.level().getBlockState(pos).getBlock()).getPath();
            if (!variants.contains(path) || !isExposed(pos)) {
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

    private String variantPath(BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(npc.level().getBlockState(pos).getBlock()).getPath();
    }

    private static boolean isFluid(BlockState state) {
        return !state.getFluidState().isEmpty() || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE);
    }

    /** 跟随玩家：每 0.5 秒看一次距离，远于 3 格就追。距离超过 32 格放弃。 */
    private boolean runFollow(String playerName, int id) {
        org.bukkit.entity.Player target = Bukkit.getPlayerExact(playerName);
        if (target == null || !target.isOnline()) {
            lastOutcome = "follow 目标不在线 player=" + playerName;
            LOG.info("[AIV][SKILL] " + npc.npcName() + " follow 目标玩家不在线 | player=" + playerName);
            return false;
        }
        current = "follow";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " follow 开始 | player=" + playerName);

        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (runId != id) {
                return;
            }
            if (!target.isOnline() || target.isDead()) {
                finish(id, "follow", "目标下线");
                return;
            }
            Location npcLocation = npc.bukkitPlayer().getLocation();
            Location playerLocation = target.getLocation();
            if (!playerLocation.getWorld().equals(npcLocation.getWorld())) {
                finish(id, "follow", "目标换维度");
                return;
            }
            double distanceSq = playerLocation.distanceSquared(npcLocation);
            if (distanceSq > 32.0D * 32.0D) {
                finish(id, "follow", "距离过远");
                return;
            }
            if (distanceSq > 9.0D) {
                // 只在没在走的时候重新规划，避免每 0.5 秒都跑一次 A*。
                if (!npc.isFollowingPath()) {
                    BlockPos goal = PathFinder.standableGoal(npc.level(), BlockPos.containing(
                            playerLocation.getX(), playerLocation.getY(), playerLocation.getZ()), 3);
                    if (goal != null) {
                        PathFinder.Result result = PathFinder.find(npc.level(), npc.blockPosition(), goal, !npc.wearingLeatherBoots());
                        logPath("follow", goal, result);
                        if (result.found()) {
                            npc.followPath(result.path(), true, null, null);
                        }
                    }
                }
            } else if (npc.isFollowingPath()) {
                // 已经贴近玩家，丢掉剩余路径，免得从玩家身边冲过去。
                npc.stopWalking();
            }
        }, 10L, 10L));
        return true;
    }

    // ---------------------------------------------------------------- 传送门路线技能

    /** 完整下界传送门框架的 10 格（四角省略，原版允许）：以左下角为原点的局部坐标。 */
    private static final int[][] PORTAL_FRAME = {
            {0, 1}, {0, 2}, {0, 3},
            {3, 1}, {3, 2}, {3, 3},
            {1, 0}, {2, 0},
            {1, 4}, {2, 4}
    };

    /**
     * 用空桶装水。
     *
     * <p>原版里"水桶点水源"会把那一格水源取走并换成水桶；这里先 A* 走到水边再执行，
     * 因为服务端的交互/挖掘都有距离校验，隔空取水在玩家视角里也不成立。
     */
    private boolean runFillBucket(int id) {
        if (countItem("water_bucket") > 0) {
            finish(id, "fillbucket", "已经有水桶");
            return true;
        }
        if (countItem("bucket") <= 0) {
            finish(id, "fillbucket", "没有空铁桶");
            return true;
        }
        BlockPos water = nearestWaterSource(64, 16);
        if (water == null) {
            finish(id, "fillbucket", "附近没有水源");
            return true;
        }
        BlockPos stand = nearestStandableAround(water, 4);
        if (stand == null) {
            finish(id, "fillbucket", "水源附近没有可站立位置");
            return true;
        }
        PathFinder.Result path = PathFinder.find(npc.level(), npc.blockPosition(), stand, !npc.wearingLeatherBoots());
        logPath("fillbucket", stand, path);
        if (!path.found()) {
            // A* 找不到路径（地下洞穴被墙堵死）：启动挖路模式，
            // 像 descend 挖竖井一样朝 stand 方向直线挖过去。
            return startFillBucketMining(id, water, stand);
        }
        current = "fillbucket";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " fillbucket 开始 | water=" + water.toShortString()
                + " stand=" + stand.toShortString());
        npc.followPath(path.path(), false, () -> {
            if (runId != id) {
                return;
            }
            BlockState state = npc.level().getBlockState(water);
            if (!state.is(Blocks.WATER) || !state.getFluidState().isSource()) {
                finish(id, "fillbucket", "水源已消失 " + water.toShortString());
                return;
            }
            npc.level().setBlockAndUpdate(water, Blocks.AIR.defaultBlockState());
            replaceOneItem("bucket", "water_bucket");
            finish(id, "fillbucket", "装满水桶 " + water.toShortString());
        }, () -> finish(id, "fillbucket", "接近水源失败"));
        armWatchdog(id, "fillbucket");
        return true;
    }

    /**
     * A* 找不到路径时的兜底：朝 stand 方向直线挖过去。
     * 和 descend 一样：每 tick 挖掉前方/头顶/脚下的阻挡，走一步。
     */
    private boolean startFillBucketMining(int id, BlockPos water, BlockPos stand) {
        current = "fillbucket";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " fillbucket 挖路 | water="
                + water.toShortString() + " stand=" + stand.toShortString());
        final int[] stepsDone = {0};
        armWatchdog(id, "fillbucket");
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (runId != id || !"fillbucket".equals(current)) {
                return;
            }
            BlockPos feet = npc.blockPosition();
            if (feet.equals(stand) || feet.equals(water)) {
                // 到达！装水
                BlockState state = npc.level().getBlockState(water);
                if (state.is(Blocks.WATER) && state.getFluidState().isSource()) {
                    npc.level().setBlockAndUpdate(water, Blocks.AIR.defaultBlockState());
                    replaceOneItem("bucket", "water_bucket");
                    finish(id, "fillbucket", "装满水桶 " + water.toShortString());
                } else {
                    finish(id, "fillbucket", "水源已消失 " + water.toShortString());
                }
                return;
            }
            // 朝 stand 方向移动：优先 X/Z 差最大的方向，然后 Y。
            int dx = stand.getX() - feet.getX();
            int dy = stand.getY() - feet.getY();
            int dz = stand.getZ() - feet.getZ();
            int mx = 0, my = 0, mz = 0;
            if (Math.abs(dx) >= Math.abs(dz) && Math.abs(dx) >= Math.abs(dy) && dx != 0) {
                mx = (int) Math.signum(dx);
            } else if (Math.abs(dz) >= Math.abs(dy) && dz != 0) {
                mz = (int) Math.signum(dz);
            } else if (dy != 0) {
                my = (int) Math.signum(dy);
            } else if (dx != 0) {
                mx = (int) Math.signum(dx);
            } else if (dz != 0) {
                mz = (int) Math.signum(dz);
            }
            if (mx == 0 && my == 0 && mz == 0) {
                // 到了附近，找相邻格
                for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}}) {
                    BlockPos near = feet.offset(d[0], d[1], d[2]);
                    if (near.equals(stand) || near.equals(water)
                            || npc.level().getBlockState(near).getCollisionShape(npc.level(), near).isEmpty()) {
                        mx = d[0]; my = d[1]; mz = d[2];
                        break;
                    }
                }
            }
            if (mx == 0 && my == 0 && mz == 0) {
                finish(id, "fillbucket", "挖路找不到方向");
                return;
            }
            BlockPos targetFeet = feet.offset(mx, my, mz);
            BlockPos targetHead = targetFeet.above();
            BlockPos currentHead = feet.above();
            // 挖掉阻挡
            npc.mineWithBestTool(currentHead);
            npc.mineWithBestTool(targetFeet);
            npc.mineWithBestTool(targetHead);
            // 走过去
            npc.walkTo(new Vec3(targetFeet.getX() + 0.5D, targetFeet.getY(), targetFeet.getZ() + 0.5D),
                    false, null);
            stepsDone[0]++;
            if (stepsDone[0] > 200) {
                finish(id, "fillbucket", "挖路步数用尽");
            }
        }, 1L, 2L));
        return true;
    }

    /**
     * 浇铸一个黑曜石：水浇岩浆源 → 用钻石镐挖掉 → 把水收回桶里。
     *
     * <p>为什么直接 setBlock 转黑曜石：原版水与岩浆的相遇要等流体刻，
     * 还会生成圆石/石头；这里只取"水源浇岩浆源=黑曜石"这一确定结果，
     * 结果与玩家操作一致，但不会因为流体延迟导致目标层空转。
     */
    private boolean runCastObsidian(Intent intent, int id) {
        if (countItem("water_bucket") <= 0) {
            finish(id, "castobsidian", "没有水桶");
            return true;
        }
        BlockPos lava = new BlockPos(intent.intParam("x", 0), intent.intParam("y", 0),
                intent.intParam("z", 0));
        BlockState lavaState = npc.level().getBlockState(lava);
        if (!lavaState.is(Blocks.LAVA) || !lavaState.getFluidState().isSource()) {
            finish(id, "castobsidian", "目标不是岩浆源 " + lava.toShortString());
            return true;
        }
        // 水优先倒在岩浆的侧面：这样挖出来的黑曜石上方是空气，掉落物不会被水流推走。
        BlockPos waterPos = null;
        for (BlockPos candidate : List.of(lava.north(), lava.south(), lava.east(),
                lava.west(), lava.above())) {
            BlockState state = npc.level().getBlockState(candidate);
            if (state.isAir() || state.canBeReplaced()) {
                waterPos = candidate;
                break;
            }
        }
        if (waterPos == null) {
            finish(id, "castobsidian", "岩浆四周没有可倒水的位置");
            return true;
        }
        // 落脚点：离自己最近、且不是倒水那一格的干燥站立格。
        BlockPos stand = null;
        double bestSq = Double.MAX_VALUE;
        BlockPos center = npc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(lava.offset(-3, -2, -3), lava.offset(3, 2, 3))) {
            if (pos.equals(waterPos) || pos.equals(lava)) {
                continue;
            }
            if (!PathFinder.isStandable(npc.level(), pos)) {
                continue;
            }
            double distanceSq = center.distSqr(pos);
            if (distanceSq < bestSq) {
                bestSq = distanceSq;
                stand = pos.immutable();
            }
        }
        if (stand == null) {
            finish(id, "castobsidian", "岩浆旁没有落脚点");
            return true;
        }
        PathFinder.Result path = PathFinder.find(npc.level(), npc.blockPosition(), stand, !npc.wearingLeatherBoots());
        logPath("castobsidian", stand, path);
        if (!path.found()) {
            finish(id, "castobsidian", "无法接近岩浆 " + path.detail());
            return true;
        }
        final BlockPos waterAt = waterPos;
        current = "castobsidian";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " castobsidian 开始 | lava=" + lava.toShortString()
                + " water=" + waterAt.toShortString() + " stand=" + stand.toShortString());
        npc.followPath(path.path(), false, () -> {
            if (runId != id) {
                return;
            }
            npc.level().setBlockAndUpdate(waterAt, Blocks.WATER.defaultBlockState());
            replaceOneItem("water_bucket", "bucket");
            npc.level().setBlockAndUpdate(lava, Blocks.OBSIDIAN.defaultBlockState());
            boolean mined = npc.mineWithBestTool(lava);
            // 收回倒出去的水：空桶→水桶，水面清掉，形成"一次装水可反复浇铸"的循环。
            if (npc.level().getBlockState(waterAt).is(Blocks.WATER)) {
                npc.level().setBlockAndUpdate(waterAt, Blocks.AIR.defaultBlockState());
            }
            replaceOneItem("bucket", "water_bucket");
            finish(id, "castobsidian", "浇铸黑曜石 success=" + mined + " pos=" + lava.toShortString());
        }, () -> finish(id, "castobsidian", "接近岩浆失败"));
        armWatchdog(id, "castobsidian");
        return true;
    }

    /**
     * 按 10 格黑曜石 → 6 格传送门方块搭出下界传送门，并用打火石点燃。
     * 每 2 tick 放一格，既不占满主线程，也保留"一格一格搭"的玩家观感。
     */
    private boolean runBuildPortal(Intent intent, int id) {
        BlockPos origin = new BlockPos(intent.intParam("x", 0), intent.intParam("y", 0),
                intent.intParam("z", 0));
        boolean alongX = !"z".equalsIgnoreCase(intent.param("axis", "x"));
        if (countItem("obsidian") < 10) {
            finish(id, "buildportal", "黑曜石不足 10");
            return true;
        }
        if (countItem("flint_and_steel") <= 0) {
            finish(id, "buildportal", "缺少打火石");
            return true;
        }
        current = "buildportal";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " buildportal 开始 | origin="
                + origin.toShortString() + " axis=" + (alongX ? "x" : "z"));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int step;

            @Override
            public void run() {
                if (runId != id) {
                    return;
                }
                if (step < PORTAL_FRAME.length) {
                    BlockPos pos = framePosition(origin, alongX, step);
                    step++;
                    if (npc.level().getBlockState(pos).is(Blocks.OBSIDIAN)) {
                        return;
                    }
                    if (!consumeItem("obsidian", 1)) {
                        finish(id, "buildportal", "黑曜石中途不足");
                        return;
                    }
                    npc.level().setBlockAndUpdate(pos, Blocks.OBSIDIAN.defaultBlockState());
                    return;
                }
                if (step == PORTAL_FRAME.length) {
                    step++;
                    if (!damageFlintAndSteel()) {
                        finish(id, "buildportal", "打火石不可用");
                        return;
                    }
                    BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState()
                            .setValue(NetherPortalBlock.AXIS,
                                    alongX ? Direction.Axis.X : Direction.Axis.Z);
                    for (int dx = 1; dx <= 2; dx++) {
                        for (int dy = 1; dy <= 3; dy++) {
                            npc.level().setBlockAndUpdate(interiorPosition(origin, alongX, dx, dy), portal);
                        }
                    }
                    finish(id, "buildportal", "传送门已建好并点燃 origin=" + origin.toShortString()
                            + " axis=" + (alongX ? "x" : "z"));
                }
            }
        }, 1L, 2L));
        armWatchdog(id, "buildportal", 200);
        return true;
    }

    /**
     * 走进传送门并等待原版传送。
     *
     * <p>服务端玩家站着不动也会被 {@code Entity.handlePortal} 累计传送时间
     * （普通玩家默认 80 tick ≈ 4 秒），所以不需要额外发包。
     * 到新维度后立刻铺 5x5 落脚平台：下界落点常常悬在岩浆海上方的窄崖边。
     */
    private boolean runEnterPortal(Intent intent, int id) {
        final String startDimension = npc.level().dimension().identifier().toString();
        final double x = intent.doubleParam("x", npc.getX());
        final double y = intent.doubleParam("y", npc.getY());
        final double z = intent.doubleParam("z", npc.getZ());
        current = "enterportal";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " enterportal 开始 | 目标="
                + String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", x, y, z)
                + " 维度=" + startDimension);
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int ticks;

            @Override
            public void run() {
                if (runId != id) {
                    return;
                }
                String now = npc.level().dimension().identifier().toString();
                if (!now.equals(startDimension)) {
                    secureArrival(npc.blockPosition());
                    finish(id, "enterportal", "已通过传送门进入 " + now
                            + " pos=" + npc.blockPosition().toShortString());
                    return;
                }
                if (++ticks > 600) {
                    finish(id, "enterportal", "超时未传送");
                    return;
                }
                double dx = x - npc.getX();
                double dz = z - npc.getZ();
                if (ticks % 10 == 0 && !npc.isWalking() && dx * dx + dz * dz > 0.8D) {
                    npc.walkTo(new Vec3(x, y, z), false, null);
                }
            }
        }, 1L, 1L));
        armWatchdog(id, "enterportal", 700);
        return true;
    }

    /** 传送门框架局部坐标 → 世界坐标；alongX 时门面朝 Z 轴、横向沿 X。 */
    private static BlockPos framePosition(BlockPos origin, boolean alongX, int index) {
        int[] local = PORTAL_FRAME[index];
        return alongX ? origin.offset(local[0], local[1], 0)
                : origin.offset(0, local[1], local[0]);
    }

    /** 门洞内部（2 宽 × 3 高）的世界坐标。 */
    private static BlockPos interiorPosition(BlockPos origin, boolean alongX, int dx, int dy) {
        return alongX ? origin.offset(dx, dy, 0) : origin.offset(0, dy, dx);
    }

    /** 最近的水源方块（只要源方块，流动水装不进桶）。 */
    private BlockPos nearestWaterSource(int radius, int vertical) {
        BlockPos center = npc.blockPosition();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -vertical, -radius),
                center.offset(radius, vertical, radius))) {
            BlockState state = npc.level().getBlockState(pos);
            if (!state.is(Blocks.WATER) || !state.getFluidState().isSource()) {
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

    /** 目标附近最近的合法站立格（不含目标本身）。 */
    private BlockPos nearestStandableAround(BlockPos target, int radius) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                target.offset(-radius, -radius, -radius),
                target.offset(radius, radius, radius))) {
            if (pos.equals(target)) {
                continue;
            }
            if (!PathFinder.isStandable(npc.level(), pos)) {
                continue;
            }
            double distanceSq = target.distSqr(pos);
            if (distanceSq < bestSq) {
                bestSq = distanceSq;
                best = pos.immutable();
            }
        }
        return best;
    }

    /**
     * 抵达新维度后的落脚平台：把脚下 5x5 范围里没有碰撞的格子换成随身建筑方块。
     * 只补地板不动头顶，避免把 NPC 自己封进方块里。
     */
    private void secureArrival(BlockPos center) {
        int placed = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos floor = center.offset(dx, -1, dz);
                if (!npc.level().getBlockState(floor)
                        .getCollisionShape(npc.level(), floor).isEmpty()) {
                    continue;
                }
                String blockPath = pickBuildingBlock();
                if (blockPath == null) {
                    break;
                }
                Block block = blockOf(blockPath);
                if (block == null || !consumeItem(blockPath, 1)) {
                    break;
                }
                npc.level().setBlockAndUpdate(floor, block.defaultBlockState());
                placed++;
            }
        }
        LOG.info("[AIV][SKILL] " + npc.npcName() + " 落脚平台 | 放置=" + placed
                + " pos=" + center.toShortString());
    }

    /** 随身可用的建筑方块；优先级从结实到临时。 */
    private String pickBuildingBlock() {
        for (String candidate : List.of("cobblestone", "netherrack", "blackstone", "basalt",
                "cobbled_deepslate", "stone", "andesite", "diorite", "granite",
                "oak_planks", "spruce_planks", "birch_planks", "jungle_planks",
                "acacia_planks", "dark_oak_planks", "mangrove_planks", "cherry_planks",
                "bamboo_planks", "pale_oak_planks")) {
            if (countItem(candidate) > 0) {
                return candidate;
            }
        }
        return null;
    }

    /** 消耗一次打火石耐久；留最后 1 点不烧穿（和镐子的约定一致）。 */
    private boolean damageFlintAndSteel() {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !"flint_and_steel".equals(itemPathOf(stack))) {
                continue;
            }
            if (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
                continue;
            }
            if (stack.isDamageableItem()) {
                stack.setDamageValue(stack.getDamageValue() + 1);
            }
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 背包小工具（技能层自用）

    private static String itemPathOf(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.getPath();
    }

    private static Item itemOf(String path) {
        Identifier id = Identifier.tryParse(path);
        if (id == null) {
            return Items.AIR;
        }
        return BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
    }

    private static Block blockOf(String path) {
        Identifier id = Identifier.tryParse(path);
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }

    private int countItem(String path) {
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (!stack.isEmpty() && path.equals(itemPathOf(stack))) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean consumeItem(String path, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < 36 && remaining > 0; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !path.equals(itemPathOf(stack))) {
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

    /** 原地把 1 个 from 物品换成 to（铁桶↔水桶）。 */
    private boolean replaceOneItem(String fromPath, String toPath) {
        Item to = itemOf(toPath);
        if (to == Items.AIR) {
            return false;
        }
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !fromPath.equals(itemPathOf(stack))) {
                continue;
            }
            stack.shrink(1);
            if (stack.isEmpty()) {
                npc.getInventory().setItem(slot, new ItemStack(to, 1));
                return true;
            }
            return addOneItem(to);
        }
        return false;
    }

    private boolean addOneItem(Item item) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                npc.getInventory().setItem(slot, new ItemStack(item, 1));
                return true;
            }
            if (stack.getItem() == item && stack.getCount() < stack.getMaxStackSize()) {
                stack.grow(1);
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 内部工具

    /**
     * 列出附近最近的若干目标方块（按距离排序）。
     *
     * <p>垂直范围放宽到 ±6：丛林巨树的树干很高，只扫 ±3 会漏掉大半；
     * 返回多个候选是为了在"最近的树被地形挡住"时还能试下一棵。
     */
    private List<BlockPos> findNearestBlocks(Block block, boolean anyLog, int radius) {
        BlockPos center = npc.blockPosition();
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -6, -radius),
                center.offset(radius, 6, radius))) {
            BlockState state = npc.level().getBlockState(pos);
            boolean match = anyLog ? state.is(BlockTags.LOGS) : state.is(block);
            if (!match) {
                continue;
            }
            // 被完全包住的方块挖掉后掉落物会卡在固体里，伪玩家够不到，直接跳过。
            if (!isExposed(pos)) {
                continue;
            }
            found.add(pos.immutable());
        }
        found.sort(Comparator.comparingDouble(center::distSqr));
        // 只试最近的几棵：每个候选都要跑一次 A*，太多会让主线程卡顿。
        int limit = Math.min(6, found.size());
        return new ArrayList<>(found.subList(0, limit));
    }

    /** 六个面里任意一个贴着空气就算暴露，挖它有地方掉物品、也有地方站。 */
    private boolean isExposed(BlockPos pos) {
        return npc.level().getBlockState(pos.above()).isAir()
                || npc.level().getBlockState(pos.below()).isAir()
                || npc.level().getBlockState(pos.north()).isAir()
                || npc.level().getBlockState(pos.south()).isAir()
                || npc.level().getBlockState(pos.east()).isAir()
                || npc.level().getBlockState(pos.west()).isAir();
    }

    /**
     * 挖掘的候选站立点。
     *
     * <ul>
     *   <li>target.above(2)：挖"地表下一层"时站在地表上，伸手往下挖（不会掉）。</li>
     *   <li>四邻同层：挖墙里/柱上的方块时站在旁边的地面。</li>
     *   <li>四邻上层：挖地表方块时站在旁边那块地的地表上，而不是站在目标块顶上。</li>
     * </ul>
     *
     * <p>刻意不包含 target.above()：那等于站在目标块顶上挖脚下，必然掉 1 格；
     * 连续这样挖两次就会落在 2 格深的坑里，而 A* 只允许上 1 格，爬不出来。
     */
    private List<BlockPos> standCandidates(BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>(List.of(
                target.above(2),
                target.east(), target.west(), target.north(), target.south(),
                target.east().above(), target.west().above(),
                target.north().above(), target.south().above(),
                // 斜对角：丛林巨树是 2x2 树干，四个正邻格常被另一根树干占住，
                // 只有斜角位置能站人、又能伸手挖到（玩家砍 2x2 树也是站斜角）。
                target.east().north(), target.east().south(),
                target.west().north(), target.west().south(),
                target.east().north().above(), target.east().south().above(),
                target.west().north().above(), target.west().south().above()));
        candidates.sort(Comparator.comparingDouble(npc.blockPosition()::distSqr));
        return candidates;
    }

    /** 统一打印 A* 结果：节点数、耗时、路径长度，便于观察主线程开销。 */
    private void logPath(String label, BlockPos goal, PathFinder.Result result) {
        LOG.info("[AIV][PATH] " + npc.npcName() + " " + label + " | goal=" + goal.toShortString()
                + " found=" + result.found() + " 展开=" + result.visited()
                + " 路径=" + result.path().size() + " 耗时=" + result.millis() + "ms "
                + result.detail());
    }

    /** 兜底：技能超时后强制结束，避免"正在挖矿"永远挂在状态里。 */
    private void armWatchdog(int id, String action) {
        armWatchdog(id, action, SKILL_TIMEOUT_TICKS);
    }

    /** 长耗时技能（阶梯下挖）需要单独放宽超时，否则挖到半路会被 watchdog 掐断。 */
    private void armWatchdog(int id, String action, int ticks) {
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (runId == id && action.equals(current)) {
                LOG.warning("[AIV][SKILL] " + npc.npcName() + " " + action + " 超时未完成，强制结束");
                finish(id, action, "timeout");
            }
        }, ticks));
    }

    private void finish(int id, String action, String detail) {
        if (runId != id) {
            return;
        }
        lastOutcome = action + " " + detail;
        outcomeSeq++;
        LOG.info("[AIV][SKILL] " + npc.npcName() + " " + action + " 结束 | " + detail
                + " pos=" + npc.blockPosition().toShortString());
        current = "idle";
        cancelTasks();
    }

    private void cancelTasks() {
        for (BukkitTask task : tasks) {
            if (!task.isCancelled()) {
                task.cancel();
            }
        }
        tasks.clear();
    }
}

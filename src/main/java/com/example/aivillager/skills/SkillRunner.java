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
import net.minecraft.world.level.block.PointedDripstoneBlock;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    /** 【fix245】goto 熔断账：上次瞬时失败的目标格/身体位置/时刻与连环数。 */
    private BlockPos gotoFuseGoal;
    private BlockPos gotoFusePos;
    private long gotoFuseTick;
    private int gotoFuseStrikes;

    private int lastStripDir = -1;
    /** 上一条矿道有没有挖到目标矿石；false 表示这片区域大概率已经挖空。 */
    private boolean lastStripFoundOre;
    /** 上一条矿道结束的时间戳：只对"刚才挖的"矿道做分支判断，避免几分钟前的旧方向误导。 */
    private long lastStripEndMillis;

    /**
     * 【fix343】够矿失败目标记忆：目标格→失败毫秒时间戳。runMine 每次重扫
     * 都是最近优先、失败目标无记忆，同一棵够不着的树会被反复选中（正式
     * 250 棒：birch_log (222,73,-39) 24 次重派）。10 分钟内失败过的目标
     * 不再入选（点名目标 pinned 除外），过期自动解禁。
     */
    private final Map<BlockPos, Long> oreReachFailedAt = new HashMap<>();

    /**
     * 【fix352】travel 腿目标失败记忆：腿目标格→失败毫秒时间戳。高差 abort 后
     * 技能层若原样重喂同一腿目标，A* 必回同一条路、同一航点 abort（264 第五簇
     * 675 次实证）。10 分钟内失败过的腿目标不再入选，改走横向重取（fix186 口径）。
     */
    private final Map<BlockPos, Long> travelLegFailedAt = new HashMap<>();

    /** 【fix352】腿目标是否在 10 分钟内高差 abort 失败过（fix343 同口径）。 */
    private boolean travelLegRecentlyFailed(BlockPos pos) {
        Long at = travelLegFailedAt.get(pos);
        if (at == null) {
            return false;
        }
        if (System.currentTimeMillis() - at > 600_000L) {
            travelLegFailedAt.remove(pos);
            return false;
        }
        return true;
    }

    /** 【fix352】腿目标是否不可用：不可站立，或 10 分钟内高差 abort 失败过。 */
    private boolean isLegGoalBlocked(BlockPos goal) {
        return !PathFinder.isStandable(npc.level(), goal) || travelLegRecentlyFailed(goal);
    }

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
                // 【fix245】goto 熔断：同目标格、同位置、100 tick 内瞬时失败
                // 已连环 ≥2 次——再派只会 0ms 重演同一出（86 棒新图 BASE
                // 站位 goto↔abort 同秒往返 246 次实证）。直接拒绝派发，
                // 逼目标层走 ascend/travel 等换法；身体挪窝 ≥1 格、换目标
                // 或超时窗后自动解熔（记账口径见 noteGotoFailure）。
                if (pathGoal.equals(gotoFuseGoal)
                        && npc.blockPosition().equals(gotoFusePos)
                        && npc.level().getGameTime() - gotoFuseTick <= 100L
                        && gotoFuseStrikes >= 2) {
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " goto 熔断（fix245） | goal="
                            + pathGoal.toShortString() + " 同点同目标瞬时失败连环=" + gotoFuseStrikes
                            + "，拒绝重派待目标层换法");
                    lastOutcome = "goto 熔断：同点同目标连续瞬时失败";
                    return false;
                }
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
                    noteGotoFailure(pathGoal);
                    lastOutcome = "goto 找不到路径 " + result.detail();
                    return false;
                }

                current = "goto";
                LOG.info("[AIV][SKILL] " + npc.npcName() + " goto 开始 | goal=" + pathGoal.toShortString()
                        + " 节点=" + result.path().size());
                npc.followPath(result.path(), false,
                        () -> {
                            noteGotoSuccess();
                            finish(id, "goto", "到达 " + pathGoal.toShortString());
                        },
                        () -> {
                            noteGotoFailure(pathGoal);
                            finish(id, "goto", "路径中断 " + pathGoal.toShortString());
                        });
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
            case "pillarclimb" -> {
                return runPillarClimb(intent, id);
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
            case "mineobsidian" -> {
                return runMineObsidian(intent, id);
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
        // 【fix237】walk-stuck 自救攒满时身体层会跑 onAbandon（见 FakePlayerBody
        // walk-stuck 分支）：旧代码只有到达回调，放弃信号被静默丢掉，69 棒实证
        // travel 腿在石墙前打转 6 分钟、规划层补镐保障步一次 decide 都拿不到。
        // 放弃即技能失败收场，交 GoalExecutor 重 decide（石镐断供时保障步当场补造）。
        npc.walkTo(new Vec3(goal.getX() + 0.5D, goal.getY(), goal.getZ() + 0.5D), false,
                () -> {
                    noteGotoSuccess();
                    finish(id, "goto", "直线到达 " + goal.toShortString());
                },
                () -> {
                    noteGotoFailure(goal);
                    finish(id, "goto", "直线行走放弃（自救挖不开） " + goal.toShortString());
                });
        armWatchdog(id, "goto");
        return true;
    }

    // ---------------------------------------------------------------- 具体技能

    /**
     * 找到最近的指定方块，走到它旁边再挖。
     * 之所以要先走：ServerPlayerGameMode.destroyBlock 有交互距离校验，隔空挖会直接返回 false。
     */
    /**
     * 【fix343】目标是否在 10 分钟内够矿失败过。过期条目顺手清掉，记忆表不膨胀。
     */
    private boolean oreReachRecentlyFailed(BlockPos pos) {
        Long at = oreReachFailedAt.get(pos);
        if (at == null) {
            return false;
        }
        if (System.currentTimeMillis() - at > 600_000L) {
            oreReachFailedAt.remove(pos);
            return false;
        }
        return true;
    }

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
        // 【fix302】目标层点名单点试挖：Intent 带 x/y/z 时只试这一格（扫描 verdict 精确归属）。
        // 该格已不在或已非目标块时回落到最近 6 格老路，不让点名失败拖垮整次 mine。
        BlockPos pinned = null;
        if (intent.params().has("x") && intent.params().has("y") && intent.params().has("z")) {
            BlockPos named = new BlockPos(intent.intParam("x", 0),
                    intent.intParam("y", 0), intent.intParam("z", 0));
            BlockState pinnedState = npc.level().getBlockState(named);
            boolean pinnedMatch = anyLog ? pinnedState.is(BlockTags.LOGS)
                    : block != null && pinnedState.is(block);
            if (!pinnedMatch && oreBlock) {
                Block twin = deepslateTwin(block);
                pinnedMatch = twin != null && pinnedState.is(twin);
            }
            if (pinnedMatch && isExposed(named)) {
                pinned = named;
                targets = List.of(pinned);
            }
        }
        if (oreBlock && pinned == null) {
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
                    // 【fix343】跳过 10 分钟内够矿失败过的目标（点名目标除外）。
                    if (!candidateTarget.equals(pinned) && oreReachRecentlyFailed(candidateTarget)) {
                        continue;
                    }
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
                    // 【fix343】跳过 10 分钟内够矿失败过的目标（点名目标除外）。
                    if (!candidateTarget.equals(pinned) && oreReachRecentlyFailed(candidateTarget)) {
                        continue;
                    }
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
            // 【第七十八轮】钟乳石防护扩面（fix77 只护了 ascend/strip 上挖，
            // farm37 实证 descend 同样挖断链砸死）：目标块是悬挂链的支撑/链身
            // 就不挖，挖断了正砸在站位上（检查与 ascend/strip 同用 stalactiteHazard）。
            BlockPos mineDrip = stalactiteHazard(mineTarget);
            if (mineDrip != null) {
                LOG.info("[AIV][SKILL] " + npc.npcName() + " mine 钟乳石回避 | target="
                        + mineTarget.toShortString() + " 钟乳石=" + mineDrip.toShortString());
                finish(id, "mine", "目标块连着钟乳石，不挖 pos=" + mineTarget.toShortString());
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

    /**
     * 【第七十七轮】钟乳石断链检查：挖掉 pos 会不会让尖滴石锥坠落砸人。
     * 原版机制（Wiki 核实）：钟乳石的支撑方块、或链身任一格被破坏，其下未受
     * 支撑的滴石锥全部坠落，对正下方实体按落距造成伤害（2 格后每格 6 点、
     * 封顶 40 心）——farm35 升井挖掉滴石块后被坠落钟乳石满血砸死（第 5 死）。
     * 两查任一命中返回危险的滴石锥格、无危险返回 null：
     * ①pos 正下一格是朝下的滴石锥——pos 就是悬挂链的支撑格或链身上段，
     * 挖掉整链（或链的下段）立即坠落；
     * ②pos 同列向上扫 10 格，最低一格滴石锥若在 pos+1/+2 且朝下（链下端与
     * 待挖格相连或相隔一格），按定案一并回避；在 +3 以上或朝上与本格无关。
     * 石笋（朝上）被毁下层只碎成物品、无坠落伤害，不在防护面。
     */
    private BlockPos stalactiteHazard(BlockPos pos) {
        if (npc.level().getBlockState(pos).isAir()) {
            return null; // 本格没东西可挖，不存在断链
        }
        BlockPos below = pos.below();
        BlockState belowState = npc.level().getBlockState(below);
        if (belowState.is(Blocks.POINTED_DRIPSTONE)
                && belowState.getValue(PointedDripstoneBlock.TIP_DIRECTION) == Direction.DOWN) {
            return below;
        }
        for (int dy = 1; dy <= 10; dy++) {
            BlockPos p = pos.above(dy);
            BlockState s = npc.level().getBlockState(p);
            if (s.is(Blocks.POINTED_DRIPSTONE)) {
                if (dy <= 2
                        && s.getValue(PointedDripstoneBlock.TIP_DIRECTION) == Direction.DOWN) {
                    return p;
                }
                return null; // 同列最低的滴石锥格与本格无关（太远或朝上）
            }
            if (!s.isAir()) {
                return null; // 中间隔着实心块，上面的锥另有支撑
            }
        }
        return null;
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
            /**
             * 【fix343】pillar 接近走路腿状态：只下发一次 walkTo（每次调用会
             * 重置身体层 no-progress 追踪，每轮重发等于永远不触发卡住判定），
             * 按轮次验水平距进展；60 轮无进展记一步失败。
             */
            private boolean walkLegIssued;
            private double walkLegBestHoriz = Double.MAX_VALUE;
            private int walkLegNoProgressRounds;
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
                // 【fix209】窗口参数对齐 descend 第四十一轮实证修正值：幅度
                // 0.1→0.25（台阶边缘微幅蠕动常在 0.1~0.2，旧阈值整局零放行——
                // 本窗口放行行自上线以来各长跑 boot 零触发、farm179 实证同点
                // 超时 19 次连环）；格锚重置放宽到移位 ≥2 格才重置（站在格
                // 边界上 blockPosition 双格振荡是台阶楔住的常态，±1 格抖动
                // 不算移动；真行走 6 秒必移 ≥2 格、仍会重置，不误放）。
                // 【fix343】幅度 0.25→1.05：NPC 方块 Y 在格边 71↔72 间振荡
                // （250 棒生产行 677399–677436、实验室行 750492 起 pos 在
                // 63↔64 间振荡）时每次都超 0.25 而重置窗口，settleRounds
                // 永不到 60、6 次超时整次放弃；X/Z 格锚已有 ±1 格容忍，
                // Y 须对称。真下落 6 秒内 Y 变化数格，1.05 不会误放。
                BlockPos cellNow = npc.blockPosition();
                double yNow = npc.getY();
                if (settleCellX == Integer.MIN_VALUE
                        || Math.abs(cellNow.getX() - settleCellX) >= 2
                        || Math.abs(cellNow.getZ() - settleCellZ) >= 2
                        || Double.isNaN(settleMinY)) {
                    settleCellX = cellNow.getX();
                    settleCellZ = cellNow.getZ();
                    settleMinY = yNow;
                    settleMaxY = yNow;
                    settleRounds = 0;
                } else {
                    settleMinY = Math.min(settleMinY, yNow);
                    settleMaxY = Math.max(settleMaxY, yNow);
                    if (settleMaxY - settleMinY > 1.05D) {
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
                            + " 连续失败=" + stepFails
                            + " 窗口幅=" + (Double.isNaN(settleMinY) ? "?"
                                    : String.format(java.util.Locale.ROOT, "%.2f", settleMaxY - settleMinY))
                            + " 格=" + npc.blockPosition().toShortString());
                    if (stepFails >= 6) {
                        failReach("多次等待落定超时");
                    }
                }
                return true;
            }

            private void phaseApproach() {
                // 【fix343】pillar 接近腿：水平距 >2.2 时先走路向目标 XZ，
                // 再谈垫柱。设计意图（oreReachMode 注释）本就是「水平再远也
                // 先由接近阶段走到柱位」，但实现里接近阶段从不走路、唯一
                // 手段是 digStepToward——悬崖边前方无落脚点时必然 false，
                // 6 次即放弃，pillar 阶段永不可达。走路中途不走 waitingBody
                // （走路本身就是进展，落定门只拦挖矿动作）。
                if ("pillar".equals(mode) && horizontalDistanceTo(target) > 2.2D) {
                    double horizNow = horizontalDistanceTo(target);
                    if (!walkLegIssued) {
                        npc.walkTo(new Vec3(target.getX() + 0.5D, npc.getY(),
                                        target.getZ() + 0.5D),
                                false, null, null);
                        walkLegIssued = true;
                        walkLegBestHoriz = horizNow;
                        walkLegNoProgressRounds = 0;
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " ore-reach 接近走路 | mode=pillar target="
                                + target.toShortString()
                                + " 水平距=" + String.format(java.util.Locale.ROOT, "%.1f", horizNow)
                                + " pos=" + npc.blockPosition().toShortString());
                        return;
                    }
                    if (horizNow < walkLegBestHoriz - 0.1D) {
                        walkLegBestHoriz = horizNow;
                        walkLegNoProgressRounds = 0;
                    } else if (++walkLegNoProgressRounds >= 60) {
                        walkLegNoProgressRounds = 0;
                        stepFails++;
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " ore-reach 接近走路无进展 | mode=pillar target="
                                + target.toShortString() + " 连续失败=" + stepFails
                                + " pos=" + npc.blockPosition().toShortString());
                        if (stepFails >= 6) {
                            failReach("接近走路无路");
                            return;
                        }
                        // 再给一次机会：重发走路（身体层卡住追踪随调用重置）。
                        npc.walkTo(new Vec3(target.getX() + 0.5D, npc.getY(),
                                        target.getZ() + 0.5D),
                                false, null, null);
                    }
                    return;
                }
                // 走到柱位（水平 ≤2.2）后停走、清腿状态，失败计数清零，
                // 落入原有接近逻辑（落定→够取→垫柱）。
                if (walkLegIssued) {
                    npc.stopWalking();
                    walkLegIssued = false;
                    stepFails = 0;
                }
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
                // 【fix343】登记失败目标（10 分钟记忆）：同一目标不再被 runMine
                // 反复选中。点名目标也登记，过滤时由 runMine 循环豁免 pinned。
                oreReachFailedAt.put(target, System.currentTimeMillis());
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
        // 【fix331】末影人猎杀半径放宽到 48（219 棒 G0 探针实证，见 GoalExecutor.tickSlay）。
        // MAX_SEARCH_RADIUS（16）是 attack 等近战技能的口径，不动它、只在这里按 entity 放宽；
        // 烈焰人仍 16（刷怪笼锚点已定位，生成在笼旁 4 格内，16 已够）。
        double radiusCap = "enderman".equals(wanted) ? 48.0D : MAX_SEARCH_RADIUS;
        double radius = Math.max(6.0D, Math.min(radiusCap, intent.doubleParam("radius", 12.0D)));
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
        // 【fix373】按距离逐个试候选：最近一只不可达不再全盘放弃，跳过它试下一只；
        // 只有全部候选都不可达时才 fail-fast（即现行行为）。fix371 的 noRoute
        // 判定原样复用；烈焰人悬空无路是常态，保持原行为（首只即用，不跳过）。
        List<LivingEntity> ordered = candidates.stream()
                .map(e -> (LivingEntity) e)
                .sorted((left, right) -> Double.compare(npc.distanceToSqr(left), npc.distanceToSqr(right)))
                .toList();
        LivingEntity target = null;
        BlockPos approach = null;
        PathFinder.Result approachPath = null;
        BlockPos nearestApproach = null;
        for (LivingEntity cand : ordered) {
            BlockPos candApproach = PathFinder.standableGoal(npc.level(), cand.blockPosition(), 2);
            if (nearestApproach == null) {
                nearestApproach = candApproach;
            }
            // 目标可能悬空（烈焰人、末影龙）：找不到落脚点或路径时不能直接失败，
            // 否则猎杀目标会在"目标在天上"这种情况下被永久跳过。
            PathFinder.Result candPath = candApproach == null ? null
                    : PathFinder.find(npc.level(), npc.blockPosition(), candApproach, !npc.wearingLeatherBoots());
            if (candPath != null) {
                logPath("slay-approach", candApproach, candPath);
            }
            // 【fix371】地面怪目标无路可达时不盲走：寻路已证实开放集耗尽
            // （非节点/时间预算失败）或目标根本没有可站立落脚点，且目标是末影人
            // 这类地面怪时，直接失败交上层重 decide——否则技能层盲 walkTo 撞岩
            // 浆口袋、walk-stuck 自救挖空气、5 秒一轮无限循环（318 棒实验室实证：
            // walk-stuck 227 行、slay 目标 120 秒超时重派 29 次）。烈焰人悬空时
            // 无路是常态（有弓就射、没弓等它下来），保持原行为。
            boolean candNoRoute = candApproach == null || (candPath != null && !candPath.found()
                    && candPath.detail().contains("开放集耗尽"));
            if ("enderman".equals(wanted) && candNoRoute) {
                continue;
            }
            target = cand;
            approach = candApproach;
            approachPath = candPath;
            break;
        }
        if (target == null) {
            // 全部候选都不可达（fix371 原行为）：直接失败交上层重 decide。
            // approach 记最近一只的落脚点（与 fix371 原日志口径一致）。
            lastOutcome = "slay 目标不可达";
            LOG.info("[AIV][SKILL] " + npc.npcName() + " slay 目标不可达 | entity=" + wanted
                    + " approach=" + nearestApproach + " tried=" + ordered.size());
            return false;
        }

        // 内层战斗闭包引用目标须为 final：循环里赋值的 target 不是 effectively final，
        // 拷一份 final 别名给闭包用。
        final LivingEntity chosen = target;

        current = "slay";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " slay 开始 | target=" + wanted
                + " distance=" + String.format(Locale.ROOT, "%.1f", npc.distanceTo(chosen)));
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
                    if (chosen.isRemoved() || !chosen.isAlive()) {
                        finish(id, "slay", "目标已倒下 attempts=" + attempts);
                        return;
                    }
                    if (attempts > maxAttempts) {
                        finish(id, "slay", "超时 目标仍在=" + chosen.getType());
                        return;
                    }
                    double distanceSq = npc.distanceToSqr(chosen);
                    // 烈焰人/恶魂悬空时近战永远够不到：有弓就射，没弓就走到它正下方等它下来。
                    if (distanceSq > 9.0D && "blaze".equals(wanted) && hasBowAndArrow()) {
                        shootArrowAt(chosen);
                        return;
                    }
                    if (distanceSq > 25.0D) {
                        if (!npc.isFollowingPath()) {
                            BlockPos chaseGoal = PathFinder.standableGoal(npc.level(),
                                    chosen.blockPosition(), 2);
                            if (chaseGoal != null) {
                                PathFinder.Result chase = PathFinder.find(npc.level(),
                                        npc.blockPosition(), chaseGoal, !npc.wearingLeatherBoots());
                                if (chase.found()) {
                                    npc.followPath(chase.path(), true, null, null);
                                    return;
                                }
                            }
                            // 目标在空中：直接朝它的水平位置贴过去，等它下来/停靠。
                            // 【fix371】直线走不通时不静默重发：walk-stuck 自救攒满
                            // （onAbandon）即收场“目标不可达”，交上层重 decide——
                            // 否则 5 秒一轮 walk-stuck 无限循环（318 棒实验室实证）。
                            if (!npc.isWalking()) {
                                npc.walkTo(new Vec3(chosen.getX(), npc.getY(), chosen.getZ()),
                                        true, null,
                                        () -> finish(id, "slay", "目标不可达"));
                            }
                        }
                        return;
                    }
                    if (distanceSq > 4.0D) {
                        // 还在两格外：直线贴近，比每 0.5 秒重建 A* 便宜得多。
                        // 【fix371】同上：走不通即收场，不静默重发。
                        if (!npc.isWalking()) {
                            npc.walkTo(chosen.position(), true, null,
                                    () -> finish(id, "slay", "目标不可达"));
                        }
                        return;
                    }
                    npc.attackTarget(chosen);
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
        // 【fix114】寻路终点先沿终点列解析到可站立格（到达判定仍用原始
        // targetY，见 resolveTravelGoal 注释）。
        final BlockPos pathGoal = resolveTravelGoal(new BlockPos(
                (int) Math.floor(targetX), (int) Math.floor(targetY), (int) Math.floor(targetZ)));

        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int noPathRounds;
            private int lastLogTick;
            // 【fix202】无菌重规划计数：issuedPos 记上次 followPath 的发路位置；
            // 路径走完/被身体 abort 后水平位移 <1 格 = 原地打转（台阶高差
            // abort 热循环），连续 2 次后本轮改走 travelStepToward 挖/搭兜底。
            private BlockPos issuedPos;
            /** 【fix352】上次发路的服务端 tick：结算时凭此判定高差 abort 是否属于本段。 */
            private int issuedTick;
            private int sterileReplans;
            // 【fix251】守卫无菌计数：无菌段内见过分级守卫拒挖 → 本段是被
            // 「石镐可合、拒用高级镐」堵死的（fresh100 回箱存物实证：A* 恒
            // 能找到穿墙路径、守卫恒拒挖、无菌计数被零星动作反复清零，
            // 单 travel 钉死 480 秒被目标看门狗处决）。连续 2 段守卫无菌
            // → 礼让收场交规划层补造石镐（与 descend/strip 的 fix196 礼让
            // 同族）。guardSeen 闩锁本段内任一次拒挖（宽窗 200 tick 回看，
            // 覆盖 followPath 整段时长）。
            private int guardStuckRounds;
            private boolean guardSeen;
            // 【fix138】中间点解析缓存：原始中间点格位移 <4 格时复用上次解析，
            // 防原地失败时每 0.5 秒重跑 resolveTravelGoal 的深扫（260 格）+刷日志。
            private BlockPos chunkCell;
            private BlockPos chunkGoal;

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
                // 【fix156】宽带早退：水平 ≤8 格 ∧ 高差 ≥6 时同样交目标层竖直
                // 对齐，不等 A* 磨到无路（与 GoalExecutor.tickTravel 收尾带对齐）。
                // 【fix176】带宽 8→12 格：farm141 旧田唇口实测水平Sq≈97–110
                // （约 10.5 格）落在 8 格带外冻死 577 秒；12 格覆盖实测+余量，
                // 更宽会在可走缓坡上也抢派 descend，故止于 12。
                if ((dx * dx + dz * dz <= tolerance * tolerance
                        && Math.abs(targetY - npc.getY()) > 2.5D)
                        || (dx * dx + dz * dz <= 144.0D
                        && Math.abs(targetY - npc.getY()) >= 6.0D)) {
                    npc.stopWalking();
                    finish(id, "travel", "水平到位，需要竖直对齐");
                    return;
                }
                // 【fix251】守卫拒挖闩锁：行走中也要采样——拒挖发生在
                // followPath 挖墙的当下，等走完再查早过窗口。
                if (npc.mineRefusedByTierGuardWithin(200)) {
                    guardSeen = true;
                }
                // 正在走就先让它走完这一小段，每 tick 重算路径会把身体锁死在原地。
                if (npc.isFollowingPath() || npc.isWalking()) {
                    return;
                }
                // 【fix202】结算上一条路的产量：人没挪窝（<1 格）就是无菌
                // 重规划（身体高差 abort 后技能层无状态重喂同一条路，farm168
                // (327,-69) 同点热循环约 20 分钟实证）。连续 2 次无菌，本轮
                // 先走挖/搭一步的玩家式兜底；兜底也失败则清零照常 A*，诚实
                // 失败仍有 noPathRounds 与目标看门狗收尾，不另设死路。
                if (issuedPos != null) {
                    double movedDx = npc.getX() - (issuedPos.getX() + 0.5D);
                    double movedDz = npc.getZ() - (issuedPos.getZ() + 0.5D);
                    if (movedDx * movedDx + movedDz * movedDz < 1.0D) {
                        sterileReplans++;
                        // 【fix251】本段无位移∧段内见过拒挖 = 守卫无菌段。
                        if (guardSeen) {
                            guardStuckRounds++;
                        }
                        // 【fix352】本段死于高差 abort（身体刚记了一笔，且在本段发路之后）：
                        // 把本腿目标记入失败记忆、失效分段缓存，下一轮重解析时横向重取，
                        // 不再原样重喂同一条路（264 第五簇：fix202 朝最终目标挖/搭一步
                        // 与爬坡方向正交，永不破局）。
                        // 【fix353】短途直达亦记失败：fix138 分段只在水平 >24 格时设
                        // chunkGoal，水平 ≤24 格的直达趟 chunkGoal 恒 null，(B) 永不点火
                        // （265 第六簇：水平 ≈23.4 格，(B) 全程 0 次）。直达时记终点
                        // pathGoal 为失败腿目标：travelLegRecentlyFailed 只被 fix178／
                        // fix186／横移候选检查，直达分支不查——记了不毒化直达终点，
                        // 只防以后长途腿候选重取同点＋留诊断行。
                        BlockPos failedLeg = chunkGoal != null ? chunkGoal : pathGoal;
                        if (npc.lastHeightAbortTick() >= issuedTick) {
                            travelLegFailedAt.put(failedLeg, System.currentTimeMillis());
                            LOG.info("[AIV][SKILL] " + npc.npcName()
                                    + " travel 腿目标高差 abort 记失败（fix352） | 腿目标="
                                    + failedLeg.toShortString() + " 位置="
                                    + npc.blockPosition().toShortString());
                            chunkCell = null;
                            chunkGoal = null;
                        }
                    } else {
                        sterileReplans = 0;
                        guardStuckRounds = 0;
                    }
                    issuedPos = null;
                    guardSeen = false;
                }
                // 【fix251】守卫礼让收场：连续 2 段被分级守卫堵死、无位移，
                // 再喂 A* 也是同一面墙——立即收场交规划层补造石镐，不许
                // 钉死到目标看门狗 480 秒（fresh100 回箱存物 480 秒实证）。
                if (guardStuckRounds >= 2) {
                    LOG.info("[AIV][SKILL] " + npc.npcName()
                            + " travel 守卫无菌礼让（fix251） | 连续无菌段=2 且均见分级守卫拒挖"
                            + " 位置=" + npc.blockPosition().toShortString());
                    npc.stopWalking();
                    finish(id, "travel",
                            "礼让收场：分级守卫拒用高级镐（石镐可合），交规划层补造石镐（fix251）");
                    return;
                }
                if (sterileReplans >= 2) {
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " travel 无菌重规划改走挖/搭一步（fix202）"
                            + " | 位置=" + npc.blockPosition().toShortString());
                    sterileReplans = 0;
                    if (travelStepToward(targetX, targetZ)) {
                        return;
                    }
                    // 【fix251】挖/搭一步同步失败∧当场拒挖新鲜（同 descend 的
                    // fix196 同步查格口径）：一步也迈不出且正是守卫堵的，
                    // 同记一轮守卫无菌（闩锁轮由 issuedPos 结算，本轮无发路、
                    // 直接在这里累加并判收场）。
                    if (npc.mineRefusedByTierGuardRecently()) {
                        guardStuckRounds++;
                        if (guardStuckRounds >= 2) {
                            LOG.info("[AIV][SKILL] " + npc.npcName()
                                    + " travel 守卫无菌礼让（fix251·一步兜底面） | 连续无菌=2"
                                    + " 位置=" + npc.blockPosition().toShortString());
                            npc.stopWalking();
                            finish(id, "travel",
                                    "礼让收场：分级守卫拒用高级镐（石镐可合），交规划层补造石镐（fix251）");
                            return;
                        }
                    }
                }
                BlockPos goal = pathGoal;
                // 【fix138】长途分段寻路：水平 >24 格时本轮只搜朝终点方向 24 格的
                // 中间点。旧版每次重算都直搜最终目标——贵地形（树冠/洞穴碰撞查询
                // 约 700µs/节点）100ms 预算只够约 140–700 节点，65–600 格全程 A*
                // 恒超时/开放集耗尽，travelStepToward 兜底 12 轮判败后上层同点
                // 重派、无限空转（farm106 回程补给 7 分钟 62 次同型收尾，fix103/
                // fix136 到达环与 PORTAL_PREP 回锚点全堵于此）。玩家走长途也是
                // 朝大方向走一段看一段。中间点 Y 走既有 resolveTravelGoal 口径；
                // 到达/竖直对齐判定仍按真实终点，本段只喂寻路。预算不放宽
                // （主线程冻服红线）。
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                if (horizontal > 24.0D) {
                    double scale = 24.0D / horizontal;
                    int wx = (int) Math.floor(npc.getX() + dx * scale);
                    int wz = (int) Math.floor(npc.getZ() + dz * scale);
                    BlockPos cell = new BlockPos(wx, (int) Math.floor(targetY), wz);
                    if (chunkCell == null
                            || Math.max(Math.abs(cell.getX() - chunkCell.getX()),
                                    Math.abs(cell.getZ() - chunkCell.getZ())) >= 4) {
                        chunkCell = cell;
                        // 【fix165】中间点走地表口径解析（resolveSurfaceGoal）：
                        // 原向下扫会被洞穿列劫持进洞网（fix136 存箱腿实证）。
                        chunkGoal = resolveSurfaceGoal(cell);
                        // 【fix178】解析后验真：两级地表口径皆空时回落的
                        // resolveTravelGoal 可能返回不可站立的原格（埋藏格/
                        // 水面列——水体列下扫的脚格恒含流体、无一可站），
                        // PathFinder 恒判「终点不可站立」、目标层约 6 秒
                        // 热循环至 480 秒看门狗（farm142 回程腿中间点
                        // (521,73,-143) 实证）。玩家落点站不住会就近找个
                        // 能站的点再朝大方向走：沿同方向缩短段长 24→16
                        // →12 逐级重解析+验真，首个可站立者采用；全灭
                        // 维持原值（诚实失败+travelStepToward+看门狗兜底）。
                        if (isLegGoalBlocked(chunkGoal)) {
                            for (int segLen : new int[]{16, 12}) {
                                double fbScale = segLen / horizontal;
                                BlockPos fbCell = new BlockPos(
                                        (int) Math.floor(npc.getX() + dx * fbScale),
                                        (int) Math.floor(targetY),
                                        (int) Math.floor(npc.getZ() + dz * fbScale));
                                BlockPos fbGoal = resolveSurfaceGoal(fbCell);
                                if (PathFinder.isStandable(npc.level(), fbGoal)
                                        && !travelLegRecentlyFailed(fbGoal)) {
                                    LOG.info("[AIV][SKILL] " + npc.npcName()
                                            + " 段中间点回退取点（fix178） | 原点="
                                            + cell.toShortString() + " 解析不可站立，缩短段长="
                                            + segLen + " 改取=" + fbGoal.toShortString());
                                    chunkCell = fbCell;
                                    chunkGoal = fbGoal;
                                    break;
                                }
                            }
                        }
                        // 【fix186】缩短全灭（宽河型：24/16/12 落点全在河面、
                        // 整列无可站格，farm152 河岸 (509,65,-201) 实证）→
                        // 玩家找过河口的两手：先加长段长瞄对岸（A* 以对岸
                        // 为目标会在节点预算内沿岸找出路），再横移沿岸取点
                        // （垂直于行进方向 ±16/±32）。均同口径验真、首个
                        // 可站立者采用；全灭维持原值诚实失败，目标层另有
                        // 平地停滞收场（fix186-B）兜底、不再 6 秒热循环。
                        if (isLegGoalBlocked(chunkGoal)) {
                            for (int segLen : new int[]{32, 48}) {
                                if (segLen >= horizontal) {
                                    continue;
                                }
                                double fbScale = segLen / horizontal;
                                BlockPos fbCell = new BlockPos(
                                        (int) Math.floor(npc.getX() + dx * fbScale),
                                        (int) Math.floor(targetY),
                                        (int) Math.floor(npc.getZ() + dz * fbScale));
                                BlockPos fbGoal = resolveSurfaceGoal(fbCell);
                                if (PathFinder.isStandable(npc.level(), fbGoal)
                                        && !travelLegRecentlyFailed(fbGoal)) {
                                    LOG.info("[AIV][SKILL] " + npc.npcName()
                                            + " 段目标加长取点（fix186） | 原点="
                                            + cell.toShortString() + " 缩短回退全灭，加长段长="
                                            + segLen + " 改取=" + fbGoal.toShortString());
                                    chunkCell = fbCell;
                                    chunkGoal = fbGoal;
                                    break;
                                }
                            }
                        }
                        if (isLegGoalBlocked(chunkGoal)) {
                            double ux = dx / horizontal;
                            double uz = dz / horizontal;
                            for (int lat : new int[]{16, -16, 32, -32}) {
                                BlockPos latCell = new BlockPos(
                                        (int) Math.floor(cell.getX() + (-uz) * lat),
                                        (int) Math.floor(targetY),
                                        (int) Math.floor(cell.getZ() + ux * lat));
                                BlockPos latGoal = resolveSurfaceGoal(latCell);
                                if (PathFinder.isStandable(npc.level(), latGoal)
                                        && !travelLegRecentlyFailed(latGoal)) {
                                    LOG.info("[AIV][SKILL] " + npc.npcName()
                                            + " 段目标横移取点（fix186） | 原点="
                                            + cell.toShortString() + " 直线落点全灭，横移="
                                            + lat + " 改取=" + latGoal.toShortString());
                                    chunkCell = latCell;
                                    chunkGoal = latGoal;
                                    break;
                                }
                            }
                        }
                    }
                    goal = chunkGoal;
                }
                PathFinder.Result result = PathFinder.find(npc.level(), npc.blockPosition(),
                        goal, 24000, !npc.wearingLeatherBoots());
                if (result.found() && result.path().size() >= 2) {
                    noPathRounds = 0;
                    npc.followPath(result.path(), true, null, null);
                    issuedPos = npc.blockPosition();
                    issuedTick = npc.level().getServer().getTickCount();
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
                // 【fix251】无路∧一步兜底同步失败∧拒挖新鲜：同记守卫无菌
                // 一轮（与发路面共用计数，2 轮礼让收场，先于 noPathRounds
                // 的 12 轮长途受阻——守卫面要快收场交补镐，不磨长途口径）。
                if (npc.mineRefusedByTierGuardRecently()) {
                    guardStuckRounds++;
                    if (guardStuckRounds >= 2) {
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " travel 守卫无菌礼让（fix251·无路面） | 连续无菌=2"
                                + " 位置=" + npc.blockPosition().toShortString());
                        npc.stopWalking();
                        finish(id, "travel",
                                "礼让收场：分级守卫拒用高级镐（石镐可合），交规划层补造石镐（fix251）");
                        return;
                    }
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
     * 【fix114】travel 寻路终点 Y 解析：目标层传的 y 是派单瞬间的身体高度
     * （寻路平面口径——A* 只能在地形里找路，见 GoalExecutor.tickTravel
     * 注释），不是目的地地表。NPC 半路被搭桥棘轮带上高空后目标层重派，
     * y 悬在目的地列半空（farm78 死后回程实证：柱顶 Y=198 重派 y=197、
     * 目的地表 64），PathFinder 对终点只做 ±2 可站修正、够不着真地面，
     * 直接判「终点不可站立」，技能约 5 秒一轮秒败、直到目标看门狗
     * 480 秒处决。这里在技能内沿终点列向下解析第一个可站立格作寻路
     * 终点：传入格 ±2 内可站（常态）则原样返回、行为不变；向下扫上限
     * 260 格，扫不到保持原值让 PathFinder 如实失败（兜底不缺位）。
     * 到达/竖直对齐判定仍用原始 targetY（归目标层），本解析只喂寻路。
     */
    private BlockPos resolveTravelGoal(BlockPos goal) {
        for (int dy = 0; dy <= 2; dy++) {
            if (PathFinder.isStandable(npc.level(), goal.offset(0, dy, 0))
                    || PathFinder.isStandable(npc.level(), goal.offset(0, -dy, 0))) {
                return goal;
            }
        }
        for (int dy = 3; dy <= 260; dy++) {
            BlockPos cand = goal.offset(0, -dy, 0);
            if (PathFinder.isStandable(npc.level(), cand)) {
                // 【fix177】下探拦截：传入 Y 埋在山体里时下扫会穿岩命中洞穴层
                // 台地（farm141 实证解析到地表下 80 格的 Y=-5），那不是玩家
                // 会去的「地面」。候选低于无叶地表 −8 即拒绝：改返地表口径
                // 可站立格，无则原样返回走诚实失败。合法深扫（fix114 高空
                // 传入扫回地表本尊、洼地目标地表即洼底）候选都在地表 −8 内，
                // 按构造不触发。
                int groundY = npc.level().getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types
                                .MOTION_BLOCKING_NO_LEAVES,
                        goal.getX(), goal.getZ());
                if (cand.getY() < groundY - 8) {
                    BlockPos bySurface = surfaceStandable(goal.getX(), goal.getZ(),
                            net.minecraft.world.level.levelgen.Heightmap.Types
                                    .MOTION_BLOCKING_NO_LEAVES);
                    if (bySurface == null) {
                        bySurface = surfaceStandable(goal.getX(), goal.getZ(),
                                net.minecraft.world.level.levelgen.Heightmap.Types
                                        .MOTION_BLOCKING);
                    }
                    LOG.info("[AIV][SKILL] " + npc.npcName()
                            + " travel 终点 Y 解析下探拦截（fix177） | 传入="
                            + goal.toShortString() + " 拦截候选=" + cand.toShortString()
                            + " 无叶地表=" + groundY + " 改返="
                            + (bySurface == null ? "原值" : bySurface.toShortString()));
                    return bySurface == null ? goal : bySurface;
                }
                LOG.info("[AIV][SKILL] " + npc.npcName() + " travel 终点 Y 解析 | 传入="
                        + goal.toShortString() + " 解析=" + cand.toShortString()
                        + "（fix114：传入 Y 悬空，沿终点列向下找可站立格）");
                return cand;
            }
        }
        return goal;
    }

    /**
     * 【fix165】长途中间点的地表口径解析：先认该列高度图（MOTION_BLOCKING）表面格，
     * ±3 内有可站立格就用它；没有（水面/树冠/未加载等）才落回
     * {@link #resolveTravelGoal} 向下扫兜底，行为不劣于旧版。
     *
     * <p>病灶：中间点原走向下扫，列被 NPC 自家竖井/洞网洞穿时第一个命中的是
     * 洞层台地——fix136 存箱腿中间点被解析到 (317,40,-215)，其后每段都在洞网里
     * 接续、Y=13 磨 20+ 分钟不回地表。玩家长途赶路是先回地表再水平推进：
     * 中间点恒在地表，A* 自然先爬出洞再朝终点走，「回地表」从选点口径涌现，
     * 不另设模式开关。终点 pathGoal 的 fix114 口径不在此列（目的地洼地/
     * 竖向收尾自有 fix156 宽带接管）。
     */
    private BlockPos resolveSurfaceGoal(BlockPos cell) {
        BlockPos bySurface = surfaceStandable(cell.getX(), cell.getZ(),
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING);
        if (bySurface != null) {
            return avoidIsolatedTop(cell, bySurface);
        }
        // 【fix177】树冠列：MOTION_BLOCKING 表面是树冠顶，isStandable 拒树叶
        // 作支撑恒落空，旧版直接回落向下扫被洞穴层劫持（farm141 中间点
        // (384,64,-186) 解析到 Y=-5、A* 恒超时 6 秒热循环）。先认无叶地表
        // （树冠下的地面）再回落。
        bySurface = surfaceStandable(cell.getX(), cell.getZ(),
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        if (bySurface != null) {
            return avoidIsolatedTop(cell, bySurface);
        }
        return resolveTravelGoal(cell);
    }

    /**
     * 【fix286】中间点孤柱顶改取地面点（134 棒定谳）：高度图地表口径分不清
     * 「地形表面」与「孤柱顶」——柱列（自家恢复栈/桥墩/孤树冠）的表面就是
     * 柱顶，中间点落在柱顶后 A* 沿柱顶群推进、把人 deliver 到高处，末段
     * 再甩给 descend 慢对齐（farm309 段 2 终点 (206,73,-39) 实证）。玩家
     * 赶路的航点只会定在贴地处：解析格 C 的四邻列地表全部 ≤ C.y−4（孤
     * 柱顶）时，在 C 周边半径 ≤6 内按环由近及远找可站格（y ≤ C.y−4、
     * 同环内优先贴近行进平面 min(当前 Y, 目标 Y)），首个有命中的环内
     * 取最优者作中间点；全灭维持 C（兜底不缺位）。非孤柱顶（任一邻列
     * 地表在 C.y−4 以上：缓坡/台地/连续地形）行为与旧版完全一致；终点
     * 解析（resolveTravelGoal）不在此列——目的地可以是柱顶。
     */
    private BlockPos avoidIsolatedTop(BlockPos cell, BlockPos top) {
        for (int[] off : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockPos nb = surfaceStandable(top.getX() + off[0], top.getZ() + off[1],
                    net.minecraft.world.level.levelgen.Heightmap.Types
                            .MOTION_BLOCKING_NO_LEAVES);
            if (nb == null || nb.getY() > top.getY() - 4) {
                return top;
            }
        }
        double planeY = Math.min(npc.getY(), cell.getY());
        for (int r = 1; r <= 6; r++) {
            BlockPos best = null;
            double bestScore = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    BlockPos cand = surfaceStandable(top.getX() + dx, top.getZ() + dz,
                            net.minecraft.world.level.levelgen.Heightmap.Types
                                    .MOTION_BLOCKING_NO_LEAVES);
                    if (cand == null || cand.getY() > top.getY() - 4) {
                        continue;
                    }
                    double score = Math.abs(cand.getY() - planeY) * 100.0D
                            + Math.sqrt(dx * dx + dz * dz);
                    if (score < bestScore) {
                        bestScore = score;
                        best = cand;
                    }
                }
            }
            if (best != null) {
                LOG.info("[AIV][SKILL] " + npc.npcName()
                        + " travel 段中间点孤柱顶改取地面点（fix286） | 柱顶="
                        + top.toShortString() + " 改取=" + best.toShortString());
                return best;
            }
        }
        return top;
    }

    /** 某高度图表面 ±3 内第一个可站立格；没有返回 null。 */
    private BlockPos surfaceStandable(int x, int z,
            net.minecraft.world.level.levelgen.Heightmap.Types type) {
        int surfaceY = npc.level().getHeight(type, x, z);
        for (int dy = 0; dy <= 3; dy++) {
            BlockPos cand = new BlockPos(x, surfaceY + dy, z);
            if (PathFinder.isStandable(npc.level(), cand)) {
                return cand;
            }
            cand = new BlockPos(x, surfaceY - dy, z);
            if (PathFinder.isStandable(npc.level(), cand)) {
                return cand;
            }
        }
        return null;
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
        if (tryStepInto(ahead)) {
            return true;
        }
        // 【fix239】主轴没迈出去且挡路的是存储块：先避让——副轴朝目标
        // 也有分量时改走副轴一步（玩家会侧身绕开自家箱子，不直着挖穿）；
        // 副轴无路或同样被存储块挡住，才问身体层围死兜底（全围死且只剩
        // 存储块可挖时挖最快一块脱困）。81 棒实验场实证：旧版此路径无
        // 存储守卫，travel 一开始就挖穿工作台/熔炉两格。
        // 【fix322】主轴被岩浆记忆守卫拦时同样改走副轴（玩家在岩浆湖边
        // 被拦住会侧身绕开一格，不会每秒往同一格岩浆里迈）：tryStepInto
        // 遇守卫直接返回 false（不下发必被取消的 walkTo），这里据此放行
        // 副轴；两轴都被拦才返回 false，让 noPathRounds 累积、travel
        // 12 轮后以「长途行进受阻」诚实收场（规划层 fix306 停滞记账接住）。
        boolean storageBlocked = FakePlayerBody.isStorageBlock(npc.level().getBlockState(ahead))
                || FakePlayerBody.isStorageBlock(npc.level().getBlockState(ahead.above()));
        boolean guardBlocksAhead = npc.lavaGuardBlocks(ahead.getX() + 0.5D, ahead.getZ() + 0.5D);
        if (!storageBlocked && !guardBlocksAhead) {
            return false;
        }
        int altX = alongX ? 0 : Integer.signum(dx);
        int altZ = alongX ? Integer.signum(dz) : 0;
        if (altX != 0 || altZ != 0) {
            BlockPos alt = feet.offset(altX, 0, altZ);
            if (tryStepInto(alt)) {
                LOG.info("[AIV][SKILL] " + npc.npcName() + " travel 副轴避让（"
                        + (guardBlocksAhead ? "fix322：主轴被岩浆记忆守卫拦" : "fix239：主轴被存储块挡住")
                        + "） | 主轴 " + ahead.toShortString() + "，改走副轴 " + alt.toShortString());
                return true;
            }
        }
        npc.digStorageEscapeFallback();
        return false;
    }

    /**
     * 【fix332】无可用工具时清路兜底：tryStepInto 行走清路口。
     * 选镐全败（只剩 1 点耐久钻镐被三处守卫排除、无石镐/铁镐、无料可合）且方块属石镐可采时，
     * 素手清掉挡路方块（无掉落、零耐久），不许原地永冻（220 棒生产实证：钻镐 1/1561，
     * 下界岩/玄武岩头格 mine-no-tool＋fix330 循环 21 分钟零位移）。
     * 只在 tryStepInto 行走清路口径内生效；挖矿目标（strip/3 参 mineWithBestTool 直调）不受影响，
     * 矿石掉落不因此丢失。石镐可采口径天然排除黑曜石/钻石矿/远古残骸等须铁镐以上者。
     * 石镐可合时不走素手兜底：让 fix251 礼让收场、规划层补造石镐（正常链路优先）；
     * 只有合不出石镐（无料）时才素手清路——此时礼让亦落空，不兜底即永冻。
     * 农田守卫/存储守卫/基岩仍由身体层 mine() 拦住，本兜底不绕守卫。
     */
    private boolean clearByBareHandIfNoTool(BlockPos pos) {
        BlockState state = npc.level().getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        if (!new ItemStack(Items.STONE_PICKAXE).isCorrectToolForDrops(state)) {
            return false;
        }
        if (npc.stonePickCraftableNow(new StringBuilder())) {
            return false;
        }
        boolean cleared = npc.mineBareHand(pos);
        if (cleared) {
            LOG.info("[AIV][SKILL] " + npc.npcName() + " 素手清路（fix332） | 方块=" + state.getBlock()
                    + " " + pos.toShortString() + " 无可用工具、不烧钻镐");
        }
        return cleared;
    }

    /** 朝 ahead 格迈一步：断崖先搭桥抬高 1 格，再清出脚/头两格并 walkTo 过去。 */
    private boolean tryStepInto(BlockPos ahead) {
        // 【fix322】先问身体层：这格会不会被岩浆记忆守卫拦截。会拦就
        // 不下发 walkTo——旧版下发了也会被身体层静默取消（无回调），
        // travel 计时器 0.5 秒一轮重发同一目标，2 Hz 热循环、noPathRounds
        // 永不清零（正式服 200 棒实证：walk-start 664 行/5.5 分钟）。
        // 返回 false 让上层走副轴或累积 noPathRounds 诚实收场。
        if (npc.lavaGuardBlocks(ahead.getX() + 0.5D, ahead.getZ() + 0.5D)) {
            long nowTick = npc.level().getGameTime();
            if (!ahead.equals(lastLavaGuardStepCell) || nowTick - lastLavaGuardStepLogTick >= 200L) {
                lastLavaGuardStepCell = ahead;
                lastLavaGuardStepLogTick = nowTick;
                LOG.info("[AIV][SKILL] " + npc.npcName() + " tryStepInto 被岩浆记忆守卫拦（fix322） | 目标格="
                        + ahead.toShortString() + " 不下发 walkTo，改走副轴或累积无路轮次");
            }
            return false;
        }
        BlockPos floor = ahead.below();
        BlockState floorState = npc.level().getBlockState(floor);
        boolean floorSolid = !floorState.getCollisionShape(npc.level(), floor).isEmpty()
                && floorState.getFluidState().isEmpty();

        if (!floorSolid) {
            // 【fix320】平铺搭桥：脚下无实心支撑时把块放在 floor（脚下一格）而非
            // ahead——桥面与脚层同高，水平过洼/过水不爬升。旧版放 ahead 再站
            // ahead.above()，每步抬高 1 格的棘轮（187 棒 G1：桥搭了但 NPC 被带
            // 上高空、水平零位移甚至东漂）。floor 为流体（水/岩浆）时块沉入流
            // 体、顶面与脚层齐平，脚仍踩实心块、不进流体。floor 实心时不走本支。
            if (!placeBuildingBlockAt(floor)) {
                return false;
            }
        }
        // 清出站位（脚）与头顶两格；已经通的地方 mineWithBestTool 会直接跳过。
        // 【fix239】存储块在此被身体层守卫拒挖、清不掉就返回 false，
        // 由 travelStepToward 的避让/兜底接手，不再无差别挖穿。
        // 【fix327】(A) 自家桥不挖：ahead 若是本插件 400 tick 内刚放下的桥/台阶
        // （SELF_PLACED 登记，保留期正好 400 tick），不许清格步把它挖掉——
        // 203 生产实证：travel 刚搭的桥 2 秒后被本步 mine 无声挖掉，78 桥/78 挖
        // 1:1、6 秒一循环（fix323 (A) 只守住了救援侧挖，没守住行走步）。
        // 桥顶可站（ahead.above() 可站立）时直接踩上去；桥顶被封（上方是耕地
        // 等实心）时不挖也不走、诚实返回 false（travel 12 轮后收场、由农场
        // 轮换/跳过接手），桥留作地形、不再互拆。注意生产几何里桥上格是耕地
        // （实心）故本支不点火、由 (B) 跨上台阶接手；fix61 的 40 tick 窗口
        // 只在生存层侧，行走步此前从不查自放登记。
        long nowTick327 = npc.level().getGameTime();
        if (wasSelfPlacedRecently(ahead, nowTick327, 400L)) {
            if (PathFinder.isStandable(npc.level(), ahead.above())) {
                npc.walkTo(new Vec3(ahead.above().getX() + 0.5D, ahead.above().getY(), ahead.above().getZ() + 0.5D), false, null);
                LOG.info("[AIV][SKILL] " + npc.npcName() + " travel 踩自家桥（fix327） | 桥格=" + ahead.toShortString());
                return true;
            }
            LOG.info("[AIV][SKILL] " + npc.npcName() + " travel 自家桥顶被封（fix327） | 桥格=" + ahead.toShortString() + " 不挖、等上层轮换");
            return false;
        }
        npc.mineWithBestTool(ahead);
        if (!npc.level().getBlockState(ahead).getCollisionShape(npc.level(), ahead).isEmpty()
                && !clearByBareHandIfNoTool(ahead)) {
            // 【fix327】(B) 挖不动的台阶跨上去：ahead 仍有碰撞（农田守卫/基岩/硬度），
            // 且其上格可站立（脚/头可通行、脚下即 ahead 有支撑）时不再硬顶、直接
            // 走上台阶顶（生产几何：桥上耕地 (-11,62,-114)，脚 62 跨上顶 63）。
            // 只在挖已失败时触发，平时挖路口径不动；2 格高墙（上格亦实心）不触发。
            if (PathFinder.isStandable(npc.level(), ahead.above())) {
                npc.walkTo(new Vec3(ahead.above().getX() + 0.5D, ahead.above().getY(), ahead.above().getZ() + 0.5D), false, null);
                LOG.info("[AIV][SKILL] " + npc.npcName() + " travel 跨上挖不动的台阶（fix327） | 台阶=" + ahead.toShortString());
                return true;
            }
            // 挖不动（缺少合适工具/基岩/存储守卫）：换方向由上层重试，别在这里硬顶。
            return false;
        }
        npc.mineWithBestTool(ahead.above());
        // 【fix330】头格挖后验碰撞：头格挖失败（分级守卫拒挖/基岩/存储守卫）时不发 walkTo——
        // 否则身体撞上未清的头格，walk-stuck 自救也挖不开（同守卫），travel 计时器又被 isWalking
        // 锁死、fix202/fix251/noPathRounds 全够不着，只剩 10 分钟看门狗（219 棒 G0 实证：石镐
        // 烧断后 (250,94,394) 头格 netherrack 拒挖，walk-stuck 循环 8 分钟、换机先到）。
        // 返回 false 让 noPathRounds／fix251 礼让收场接住，规划层补造石镐再续行。
        if (!npc.level().getBlockState(ahead.above()).getCollisionShape(npc.level(), ahead.above()).isEmpty()
                && !clearByBareHandIfNoTool(ahead.above())) {
            LOG.info("[AIV][SKILL] " + npc.npcName() + " travel 头格挖不开（fix330） | 头格="
                    + ahead.above().toShortString() + " 不发 walkTo，等上层轮换");
            return false;
        }
        npc.walkTo(new Vec3(ahead.getX() + 0.5D, ahead.getY(), ahead.getZ() + 0.5D), false, null);
        return true;
    }

    /** 【fix57】上次放块的格子与时刻：断「放块→身体卡入→被生存层挖掉→再放」紧循环。 */
    private BlockPos lastBridgePos;
    private long lastBridgeTick = Long.MIN_VALUE;

    /** 【fix322】tryStepInto 被岩浆记忆守卫拦的节流日志：同格 200 tick 一行，防 0.5 秒一轮刷屏。 */
    private BlockPos lastLavaGuardStepCell;
    private long lastLavaGuardStepLogTick = -100000L;

    /** 【fix228】床边禁铺缓存：床两格的四邻站位格及其上格（复活落点），100 tick 刷新一次。 */
    // 【fix257】初值不许用 Long.MIN_VALUE：刷新判定是 nowTick - 本值 > 100，
    // 与 MIN_VALUE 作差在 long 算术下溢出成负数、判定恒假——fix228 落码
    // 起保护集从未建立过（106 棒实验场诊断版实证：放块进床脚格无拦截、
    // 无刷新日志）。nowTick（世界 gameTime）恒 ≥0，初值 -1000 保证首次
    // 调用必刷新。
    private long bedGuardCacheTick = -1000L;
    private final java.util.Set<BlockPos> bedGuardCells = new java.util.HashSet<>();

    /** 【fix61】自放方块登记表（pos→放置 tick）：生存层实心自救靠它豁免刚放下的台阶/桥块。 */
    private static final java.util.Map<BlockPos, Long> SELF_PLACED = new java.util.HashMap<>();

    /** 【fix61】该格是否在 windowTicks 内由本插件放块（搭桥/台阶/垫柱统一口 placeBuildingBlockAt）。 */
    public static boolean wasSelfPlacedRecently(BlockPos pos, long nowTick, long windowTicks) {
        Long placed = SELF_PLACED.get(pos);
        return placed != null && nowTick - placed < windowTicks;
    }

    /** 放一块垫脚方块（优先圆石/下界岩这类廉价方块）。 */
    private boolean placeBuildingBlockAt(BlockPos pos) {
        return placeBlockAt(pos, this::pickBuildingBlock) != null;
    }

    /**
     * 【fix321】(A) ascend 垫脚放块：与 placeBuildingBlockAt 同款守卫（床边禁铺 fix228／
     * 同格节流 fix57／自放登记 fix61），取块口径为垫脚专用（石系／木板优先、羊毛兜底）。
     * 只供 ascend 台阶调用；travel 搭桥等共用入口仍走 placeBuildingBlockAt（口径不动）。
     * 返回实际放下的方块路径，失败返回 null（调用方可据此判断是否用了羊毛）。
     */
    private String placeAscendPadBlockAt(BlockPos pos) {
        return placeBlockAt(pos, this::pickAscendPadBlock);
    }

    /** 放块共用体：守卫与登记与 placeBuildingBlockAt 同款，取块口径由 picker 决定。 */
    private String placeBlockAt(BlockPos pos, java.util.function.Supplier<String> picker) {
        // 【fix228】床边禁铺：床两格的四邻站位格及其上格是复活落点，
        // travel 搭桥往这里铺圆石会把复活路逐格封死（66 棒实证：#58
        // 落床格 (730,64,-132) 就是被 04:15 一块搭桥圆石压顶封掉、
        // #59/#60 连续回落世界出生点）。床账本在且床本体在时禁铺
        // 这组格；被拒后上层换向重试（搭桥调用点已有换向口径），
        // 不是硬失败。床本体不在/区块未加载时保护集为空、安全放行。
        if (isBedGuardCell(pos)) {
            LOG.info("[AIV][SKILL] " + npc.npcName() + " 搭桥避让床边（fix228） | pos=" + pos.toShortString());
            return null;
        }
        // 【fix57】落地时禁止往身体脚格/头格放块：ascend 台阶与 travel 搭桥曾在同一格
        // 互相打架——块放进身体所在格，生存层「卡在方块里」当场挖掉再放再挖，
        // 108 秒烧掉 194 块、整组圆石清零（boot-farm12）。垫柱是腾空 0.55 格后放脚下，
        // 放块时 onGround=false，不受此闸影响。
        if (npc.onGround) {
            BlockPos feet = npc.blockPosition();
            if (pos.equals(feet) || pos.equals(feet.above())) {
                return null;
            }
        }
        // 【fix57】同格 60 tick 内不重复放块：正常搭桥/垫柱都是逐格推进、不回头同格。
        long nowTick = npc.level().getGameTime();
        if (pos.equals(lastBridgePos) && nowTick - lastBridgeTick < 60L) {
            return null;
        }
        String path = picker.get();
        if (path == null) {
            return null;
        }
        Item item = itemOf(path);
        Block block = blockOf(path);
        if (item == null || block == null || countItem(path) <= 0) {
            return null;
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
        return path;
    }

    /**
     * 【fix228】该格是否在床边保护集内：床两格本身、四邻站位格、
     * 站位格上格（压顶同样封路）。保护集 100 tick 缓存一次（账本
     * 读文件 + Gson 解析，放块高频时不值得每调用都读）；床账本
     * 无床、床本体不在（被拆/区块未加载读空气）时保护集为空。
     */
    private boolean isBedGuardCell(BlockPos pos) {
        long nowTick = npc.level().getGameTime();
        if (nowTick - bedGuardCacheTick > 100L) {
            bedGuardCacheTick = nowTick;
            // 【fix257】先备份旧集：刷新中途抛错时恢复，不让床边裸奔。
            java.util.Set<BlockPos> previousCells = new java.util.HashSet<>(bedGuardCells);
            bedGuardCells.clear();
            try {
                com.example.aivillager.goals.BaseMemory.bindWorld(npc.level());
                com.example.aivillager.goals.BaseMemory.Base base =
                        com.example.aivillager.goals.BaseMemory.base(npc.npcName());
                if (base != null && base.bedPos() != null) {
                    BlockPos foot = base.bedPos();
                    net.minecraft.world.level.block.state.BlockState state =
                            npc.level().getBlockState(foot);
                    if (state.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
                        net.minecraft.core.Direction facing = state.getValue(
                                net.minecraft.world.level.block.BedBlock.FACING);
                        BlockPos other = state.getValue(
                                        net.minecraft.world.level.block.BedBlock.PART)
                                        == net.minecraft.world.level.block.state.properties.BedPart.FOOT
                                ? foot.relative(facing) : foot.relative(facing.getOpposite());
                        for (BlockPos cell : new BlockPos[]{foot, other}) {
                            bedGuardCells.add(cell);
                            for (net.minecraft.core.Direction dir
                                    : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                                BlockPos stand = cell.relative(dir);
                                if (stand.equals(foot) || stand.equals(other)) {
                                    continue;
                                }
                                bedGuardCells.add(stand);
                                bedGuardCells.add(stand.above());
                            }
                        }
                    }
                }
            } catch (Throwable throwable) {
                // 【fix257】刷新失败不许静默清空保护集：恢复上次成功的
                // 保护集（最多陈旧一个缓存窗），并打 warning 留痕——
                // 旧版 catch 静默 clear，一旦刷新抛错床边就裸奔且无日志。
                bedGuardCells.addAll(previousCells);
                LOG.log(java.util.logging.Level.WARNING,
                        "[AIV][SKILL] " + npc.npcName()
                                + " 床边保护集刷新失败，保留旧集 | cells=" + bedGuardCells.size(),
                        throwable);
            }
        }
        return bedGuardCells.contains(pos);
    }

    /**
     * 【fix194】流体脱困垫柱入口（生存层调用）：全淹水囊里游不动、横挖也无推进时，
     * 玩家会脚下垫块逐格把自己垫出水面。此处只负责「垫一格」：目标=脚下一格，
     * 须为空气/流体且再下一格有支撑（实心碰撞），选料/消耗/自放登记与
     * placeBuildingBlockAt 同款；顶升与节奏由生存层负责。每放一格脚下支撑面上移
     * 一格，天然逐格不同位，不套用搭桥的同格 60 tick 去重。无料/无支撑/已是
     * 实心时诚实返回 false。
     */
    public boolean placeFluidPillarStep() {
        BlockPos feet = npc.blockPosition();
        BlockPos target = feet.below();
        net.minecraft.world.level.block.state.BlockState targetState =
                npc.level().getBlockState(target);
        if (!targetState.isAir() && targetState.getFluidState().isEmpty()) {
            return false;
        }
        BlockPos support = target.below();
        if (npc.level().getBlockState(support)
                .getCollisionShape(npc.level(), support).isEmpty()) {
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
        npc.level().setBlockAndUpdate(target, block.defaultBlockState());
        long nowTick = npc.level().getGameTime();
        lastBridgePos = target;
        lastBridgeTick = nowTick;
        SELF_PLACED.put(target, nowTick);
        SELF_PLACED.values().removeIf(t -> nowTick - t > 400L);
        LOG.info("[AIV][SKILL] " + npc.npcName() + " fluid-pillar | block=" + path
                + " pos=" + target.toShortString());
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
     * 【fix107】边缘悬挂复位：身体被邻格方块的边缘碰撞托住、站立列正下方是
     * 空气时（onGround 恒 false、Y 纹丝不动），descend 往自己列中心「向下走
     * 一格」只会原地 arrive、永远落不下去（farm67 山顶桥头悬停 7 分钟实证）。
     * 玩家的做法是先横移一步站回实地：扫四邻，找「下有实心支撑（非流体、
     * 非滴石锥）∧ 本格与头格可过」的邻列走过去站稳，descend 再按正常
     * 阶梯逻辑接管。
     *
     * @return 是否找到并走向了复位列
     */
    private boolean descendRecenterFromEdge(BlockPos feet) {
        // 先正交后对角：farm67 实证里支撑块可能在对角列（身体 bbox 覆盖
        // 3×3 列、正交四邻的下格却全空），只扫四邻会漏掉真正的落脚点。
        int[][] scan = {{1, 0}, {0, 1}, {-1, 0}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        // 【fix253】落差维度：桥台阶/坡面的真实落点常在低 1–2 格（farm255
        // 桥顶两连实证：同层邻列支撑全灭、台阶本体低一层，旧扫描全灭后
        // 只能自走一格原地打转）。同层优先、再 -1、再 -2（落差 ≤2 不摔伤），
        // 每层内仍先正交后对角。
        for (int dy = 0; dy >= -2; dy--) {
        for (int[] d : scan) {
            BlockPos n = feet.offset(d[0], dy, d[1]);
            BlockPos support = n.below();
            BlockState supportState = npc.level().getBlockState(support);
            BlockState nState = npc.level().getBlockState(n);
            BlockState nHeadState = npc.level().getBlockState(n.above());
            if (supportState.getCollisionShape(npc.level(), support).isEmpty()
                    || !supportState.getFluidState().isEmpty()
                    || supportState.is(Blocks.POINTED_DRIPSTONE)
                    || isFluid(nState) || isFluid(nHeadState)
                    || !(nState.isAir() || nState.canBeReplaced()
                            || nState.getCollisionShape(npc.level(), n).isEmpty())
                    || !(nHeadState.isAir() || nHeadState.canBeReplaced()
                            || nHeadState.getCollisionShape(npc.level(), n.above()).isEmpty())) {
                continue;
            }
            if (d[0] != 0 && d[1] != 0) {
                // 对角穿行：两个正交过渡格至少要有一个脚位不挡，
                // 否则身体会被墙角卡住、白走一趟。
                BlockPos sideA = feet.offset(d[0], dy, 0);
                BlockPos sideB = feet.offset(0, dy, d[1]);
                boolean aBlocked = !npc.level().getBlockState(sideA)
                        .getCollisionShape(npc.level(), sideA).isEmpty();
                boolean bBlocked = !npc.level().getBlockState(sideB)
                        .getCollisionShape(npc.level(), sideB).isEmpty();
                if (aBlocked && bBlocked) {
                    continue;
                }
            }
            npc.walkTo(new Vec3(n.getX() + 0.5D, n.getY(), n.getZ() + 0.5D), false, null);
            LOG.info("[AIV][SKILL] " + npc.npcName() + " descend 边缘悬挂复位 | from="
                    + feet.toShortString() + " to=" + n.toShortString()
                    + " 落差=" + (-dy)
                    + "（fix107/fix253：站立列悬空、支撑在邻列，先站回实地）");
            return true;
        }
        }
        return false;
    }

    /**
     * 阶梯式下挖：沿一个水平方向挖 1x2 通道逐层下降，直到接近目标 Y。
     *
     * <p>为什么不做垂直挖坑：垂直坑需要垫脚才能上来，伪玩家不擅长放方块自救；
     * 阶梯通道每步只降 1 格，随时能走回去，也是玩家最常用的下矿方式。
     * 每一步先检查前方/下方是不是流体或基岩，遇到就转 90 度，避免挖进岩浆。
     */
    /**
     * 【fix198】为 descend 找避开自家农田的井口列：自 NPC 脚位向外 3..6 格扫
     * 8 向，要求该列离任一注册田格 ≥3（nearFarmCells 半径 2 不命中）、脚下是
     * 实心非耕地非液体、身位两格无碰撞。找不到返回 null（调用方避让收场）。
     */
    private Vec3 findFarmRelocColumn() {
        BlockPos feet = npc.blockPosition();
        int[][] dirs8 = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
        for (int r = 3; r <= 6; r++) {
            for (int[] d : dirs8) {
                BlockPos cand = feet.offset(d[0] * r, 0, d[1] * r);
                if (com.example.aivillager.goals.FarmMemory.nearFarmCells(
                        npc.npcName(), cand, 2)) {
                    continue;
                }
                net.minecraft.world.level.block.state.BlockState ground =
                        npc.level().getBlockState(cand.below());
                if (ground.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock
                        || ground.is(net.minecraft.world.level.block.Blocks.FARMLAND)
                        || ground.getCollisionShape(npc.level(), cand.below()).isEmpty()) {
                    continue;
                }
                if (!npc.level().getBlockState(cand).getCollisionShape(npc.level(), cand).isEmpty()
                        || !npc.level().getBlockState(cand.above())
                                .getCollisionShape(npc.level(), cand.above()).isEmpty()) {
                    continue;
                }
                return new Vec3(cand.getX() + 0.5D, cand.getY(), cand.getZ() + 0.5D);
            }
        }
        return null;
    }

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
            /**
             * 【fix253】本级起步时的精确 Y：结算增步除整格 Y 越线外还要精确
             * Y 真降 ≥0.5 格。旧口径只看整格：边缘悬挂时身体精确 Y 纹丝
             * 不动（farm255 桥顶 y=91.02 恒定）、整格 Y 靠摆动跨线就结算
             * 出幻影步，步数看门狗被喂饱、真降看门狗拖满 90 秒/轮。
             */
            private double stepStartPreciseY = Double.NaN;
            /** 连续几级挖不动/走不动。用来在"镐子全碎了"时及时收手，而不是无限转向。 */
            private int failedSteps;
            /**
             * 连续"没有安全台阶"的转向次数。
             * 在雨林树冠上，四个方向的下一格脚下全是空气，原本每 3 tick 转一次方向、
             * 每转一次刷一条日志，NPC 却永远不落地。这里累计到整圈（4 次）后改成垂直下挖。
             */
            private int unsafeTurns;
            /**
             * 【fix107】「脚下悬空，向下走一格」的同点无下降记账：同一站位
             * 第二次进该分支且 Y 未降，判定边缘悬挂（支撑在邻列），先横移
             * 复位（descendRecenterFromEdge）再回正常阶梯逻辑。
             */
            private BlockPos lastAirWalkPos;
            private int lastAirWalkY = Integer.MIN_VALUE;
            private int airWalkStuck;
            /**
             * 【fix113】本级落地复位已用标记：结算分支落地等待超时先给一次
             * 横移复位站稳的机会，复位无路或复位后同级再超时才判失败。
             */
            private boolean settleRecentered;
            /**
             * 【fix113】真降看门狗三件套：上次出现 ≥0.5 格真实下降时的精确 Y
             * 与当时步数、以及步数已增长但无真降的累计轮数。幻影步（结算在
             * 涨、高度不降）会喂饱步数看门狗和目标层位置进展，farm76 实证
             * 地表横漂 9 分钟零真降，必须以精确高度为准单独设线。
             */
            private double lastRealY = Double.NaN;
            private int stepsAtLastReal = -1;
            private int roundsWithoutRealDescent;
            /**
             * 【fix211】崖面转换（脚下垂直下挖穿岩）状态：阶梯步在涨但真降
             * 断档（fix113 三件套）时，沿崖横漂永远有一向可站、unsafeTurns
             * 集不满四向，垂直下挖分支轮不到（farm183 第二/四轮同崖面
             * 实证）。断档过半看门狗窗口且脚下是可挖实块时，一次性转
             * 脚下穿岩：shaftMode 下每 tick 走既有垂直下挖动作与结算，
             * 真降恢复或脚下转空/流体或级数到帽即回阶梯逻辑。
             */
            private boolean shaftTried;
            private boolean shaftMode;
            private int shaftSteps;
            private double shaftStartY = Double.NaN;
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
            /**
             * 【fix113b】本段无目标悬空是否已用过死线复位：落地（onGround）
             * 即清零，保证每段悬空只有第一次死线先复位、之后照旧放行。
             */
            private boolean airRecentered;
            /**
             * 【fix286】悬空托底闩锁：身体物理上有托底（本站位列/四邻列任一
             * 可站格的落脚面在脚下 1.3 格内）∧ 精确 Y 在锚点 ±1.0 带内连续
             * 停留的轮数与锚点。柱顶/台缘常态站姿是 onGround=false＋Y 以
             * 0.5–0.8 格幅度边缘蠕动（134 棒克隆实证幅度 0.76），悬空窗的
             * 0.25 阈值永不点火、选步被饿死到 30 秒死线一轮一级。闩锁是与
             * 窗/死线并联的第二条放行路：托底∧带内满 40 轮（约 6 秒）后
             * 本分支不再 return、每 tick 落到选步。真下落 6 秒走 ≫1 格、
             * 永远出带，不会误闩；闩锁不移动身体，选步规则原样。
             */
            private int restLatchRounds;
            private double restAnchorY = Double.NaN;
            private boolean restLatchLogged;
            /**
             * 【fix94】泡水累计轮数（本 descend 内）：真步数推进时清零。
             * descend 是挖楼梯、不是游泳——入口选在湖/河里时身体会长期泡水，
             * 旧口径靠步数看门狗单次磨 3 分钟、dig 再重派同点，farm54 实证
             * 一个湖岸挖点空转 827 秒 0/2、hp 还被磨掉。累计约 22 秒
             * （150 轮×3 tick）仍在水里就快速认输，让目标层换区挖干地。
             */
            private int waterRounds;
            /**
             * 【fix198】井口避让自家农田的状态：-1=未判定，0=正在挪井口，1=已就绪。
             * descend 以当前位置为井口原地开挖，NPC 站在自家田上被派 dig 时
             * 楼梯井会斜穿田面挖穿耕地（farm164/166/167 三度实证、各死 2 格）。
             * 开挖前先判：脚位离注册田格 ≤2 格就横挪到 3 格外的安全列再开挖
             * （走原版行走、不传送）；挪不开/挪超时以避让 outcome 收场。
             * 身体层 mine-farm-guard 是第二道：井道半途撞进田同样拒挖+避让收场。
             */
            private int farmRelocPhase = -1;
            private Vec3 farmRelocTarget;
            private int farmRelocRounds;

            @Override
            public void run() {
                if (runId != id) {
                    return;
                }
                if (farmRelocPhase < 0) {
                    farmRelocPhase = 1;
                    com.example.aivillager.goals.FarmMemory.bindWorld(npc.level());
                    if (com.example.aivillager.goals.FarmMemory.nearFarmCells(
                            npc.npcName(), npc.blockPosition(), 2)) {
                        farmRelocTarget = findFarmRelocColumn();
                        if (farmRelocTarget == null) {
                            finish(id, "descend", "农田避让收场：井口在自家田里且附近无安全井口 Y="
                                    + Math.round(npc.getY()));
                            return;
                        }
                        farmRelocPhase = 0;
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " descend 农田避让 | 井口离自家田太近，先挪到 "
                                + String.format(java.util.Locale.ROOT, "%.1f,%.1f",
                                        farmRelocTarget.x, farmRelocTarget.z));
                    }
                }
                if (farmRelocPhase == 0) {
                    double rdx = npc.getX() - farmRelocTarget.x;
                    double rdz = npc.getZ() - farmRelocTarget.z;
                    if (rdx * rdx + rdz * rdz < 0.81D) {
                        farmRelocPhase = 1;
                    } else if (++farmRelocRounds > 200) {
                        finish(id, "descend", "农田避让收场：挪井口超时 Y="
                                + Math.round(npc.getY()));
                        return;
                    } else {
                        if (!npc.isWalking()) {
                            npc.walkTo(farmRelocTarget, false, null);
                        }
                        return;
                    }
                }
                if (npc.isInWater()) {
                    if (++waterRounds > 150) {
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " descend 水体快速失败 | 累计泡水轮数=" + waterRounds
                                + " Y=" + Math.round(npc.getY()));
                        finish(id, "descend", "下潜路径在水体中（入口选在水里） Y="
                                + Math.round(npc.getY()));
                        return;
                    }
                }
                // 总进度看门狗：failedSteps 只在少数分支累加，四方向轮换/垂直下挖之间
                // 存在步数永远不变、失败计数也永远不涨的空转组合（只能等 10 分钟技能看门狗）。
                // 这里按"步数多久没变"一刀切：约 3 分钟（1200 轮×3 tick）无一步进展就收手，
                // 让目标层的无进展处理接管。正常下挖每级约 2 秒，3 分钟足够宽容。
                if (steps != lastStepsSeen) {
                    lastStepsSeen = steps;
                    roundsWithoutProgress = 0;
                    lateralMoves = 0;
                    waterRounds = 0;
                } else if (++roundsWithoutProgress > 1200) {
                    finish(id, "descend", "长时间无进展（步数约3分钟未变） Y="
                            + Math.round(npc.getY()) + " 步数=" + steps);
                    return;
                }
                // 【fix113】真降看门狗：步数看门狗只看 steps 涨没涨，幻影步
                // （边缘悬挂放行后横向漂移进低一格列、靠 blockY 比较结算出
                // 的假步数）会把它和目标层位置进展一起喂饱——farm76 实证
                // 地表横漂 9 分钟、steps 一直在涨、精确高度零下降。这里以
                // 精确 Y 为准：steps 较上次真降时有增长、且累计约 90 秒
                // （600 轮×3 tick）无 ≥0.5 格真降，即判状态机失同步收场，
                // 交目标层换位/换区。正常阶梯每级真降约 1 格、数秒一记，
                // 90 秒窗口永不误杀。
                double yPrecise = npc.getY();
                if (Double.isNaN(lastRealY)) {
                    lastRealY = yPrecise;
                    stepsAtLastReal = steps;
                }
                if (yPrecise <= lastRealY - 0.5D) {
                    lastRealY = yPrecise;
                    stepsAtLastReal = steps;
                    roundsWithoutRealDescent = 0;
                } else if (steps > stepsAtLastReal
                        && ++roundsWithoutRealDescent > 600) {
                    finish(id, "descend", "无实际下降（步数=" + steps
                            + " 但高度约 90 秒未降） Y=" + Math.round(npc.getY()));
                    return;
                }
                // 【fix211】崖面转换触发：步数在涨、真降断档过半个看门狗
                // 窗口（约 45 秒）、脚下是可挖实块（非空气/流体/基岩/
                // 钟乳石链）——判定为崖面横漂陷阱，转脚下穿岩模式。
                // 只转一次：穿岩仍不降则由 fix113 看门狗照旧收场，兜底不缺。
                // 【fix211b】站立判定放宽：楔挂型陷阱 onGround 恒 false
                // （farm184 洞内 (304,45,-205) 实证：站立窗口不足 45 秒
                // 即楔住、原条件永远轮不到）；airTotalRounds>200 是悬空
                // 总死线已放行、主流程本就按已站稳处理的状态，同等受理。
                if (!shaftTried && !shaftMode && roundsWithoutRealDescent > 300
                        && steps > stepsAtLastReal
                        && (npc.onGround || airTotalRounds > 200)) {
                    BlockPos shaftBelow = npc.blockPosition().below();
                    BlockState shaftBelowState = npc.level().getBlockState(shaftBelow);
                    if (!shaftBelowState.isAir() && !isFluid(shaftBelowState)
                            && !shaftBelowState.is(Blocks.BEDROCK)
                            && stalactiteHazard(shaftBelow) == null) {
                        shaftTried = true;
                        shaftMode = true;
                        shaftSteps = 0;
                        shaftStartY = npc.getY();
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " descend 崖面转换 | 横漂多步无真降，改脚下垂直下挖穿岩"
                                + " 脚下=" + shaftBelowState.getBlock()
                                + " Y=" + Math.round(npc.getY()));
                    }
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
                        // 【fix253】再加精确 Y 真降 ≥0.5 格一道：整格越线但精确
                        // Y 没降的是边缘悬挂摆动（幻影步），不结算、落到下面的
                        // 落地等待/复位口径处理。
                        if (npc.blockPosition().getY() < stepStartY
                                && npc.getY() <= stepStartPreciseY - 0.5D) {
                            lastTarget = null;
                            landWaitRounds = 0;
                            stepWaitRounds = 0;
                            steps++;
                            failedSteps = 0;
                            settleRecentered = false;
                        } else if (!npc.isWalking() && ++landWaitRounds > 200) {
                            // 落地等待死线（第十轮）：卡在边缘小数高度时不在走、不落地，
                            // stepWait 的"在走"分支管不到——30 秒落不了地按一级失败处理
                            // （清目标+转向+计数），换方向挖通常能把它带出边缘状态。
                            landWaitRounds = 0;
                            // 【fix113】先复位再判失败：身体多半挂在目标列边缘
                            // （与 farm67 山顶桥头同几何，但发生在结算分支，
                            // fix107 的复位在「脚下悬空」分支内够不着——farm76
                            // 实证入口第一级就挂住、随后幻影步横漂 9 分钟）。
                            // 每级给一次横移站回有支撑实地的机会：成功清目标、
                            // 不计失败不转向，主步从新站位重评方向（洞列若在
                            // 邻列，下一级自然踩得进去）；无路或复位后同级再
                            // 超时，才走下面原失败口径（兜底不缺位）。
                            if (!settleRecentered
                                    && descendRecenterFromEdge(npc.blockPosition())) {
                                settleRecentered = true;
                                lastTarget = null;
                                LOG.info("[AIV][SKILL] " + npc.npcName()
                                        + " descend 落地等待超时，先复位站稳 | Y="
                                        + Math.round(npc.getY()));
                                return;
                            }
                            settleRecentered = false;
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
                    // 【fix113b】悬空死线先复位：无目标悬空满 30 秒多半是身体
                    // 楔在台地/洞壁边缘（farm77 洞穴口袋三连实证：死线放行后
                    // 主步从楔住站位开挖、结算全是幻影步，只能等真降看门狗
                    // 90 秒收口）。死线触发时先给一次横移站回有支撑实地的
                    // 机会（与 fix113 结算分支复位同药、不同触发点）：成功
                    // 清悬空计数、本 tick 收手，主步下一 tick 从实地重评；
                    // 无路或本段悬空已用过，才走下面原强制放行（兜底不缺位）。
                    if (airTotalRounds == 201 && !airRecentered
                            && descendRecenterFromEdge(npc.blockPosition())) {
                        airRecentered = true;
                        airGateRounds = 0;
                        airTotalRounds = 0;
                        airGateMinY = Double.NaN;
                        airGateMaxY = Double.NaN;
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " descend 悬空超 30 秒死线，先复位站稳 | y="
                                + String.format(java.util.Locale.ROOT, "%.2f", npc.getY())
                                + " pos=" + npc.blockPosition().toShortString());
                        return;
                    }
                    // 【第四十一轮】悬空总死线：窗口快路走不通时，累计悬空约
                    // 30 秒（200 轮）强制放行，不再满 3 分钟步数看门狗当活靶子。
                    if (airTotalRounds == 201) {
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " descend 悬空超 30 秒死线，按已站稳强制放行 | y="
                                + String.format(java.util.Locale.ROOT, "%.2f", npc.getY())
                                + " pos=" + npc.blockPosition().toShortString());
                    }
                    // 【fix286】托底闩锁（与窗/死线并联的第二条放行路）：
                    // 身体 5 列任一可站格的落脚面在脚下 1.3 格内（物理上有
                    // 托底）∧ 精确 Y 在锚点 ±1.0 带内连续满 40 轮 → 不再
                    // return，每 tick 落到下方选步。出带（真下落）或托底
                    // 消失即清零重计；既有窗判定/死线/复位一字未动。
                    boolean restSupported = false;
                    BlockPos restFeet = npc.blockPosition();
                    for (int[] ro : new int[][]{{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        for (int rdy = 0; rdy <= 2 && !restSupported; rdy++) {
                            BlockPos cand = restFeet.offset(ro[0], -rdy, ro[1]);
                            if (PathFinder.isStandable(npc.level(), cand)) {
                                double gap = yNow - cand.getY();
                                if (gap >= -0.05D && gap <= 1.3D) {
                                    restSupported = true;
                                }
                            }
                        }
                    }
                    if (restSupported) {
                        if (Double.isNaN(restAnchorY)
                                || Math.abs(yNow - restAnchorY) > 1.0D) {
                            restAnchorY = yNow;
                            restLatchRounds = 0;
                            restLatchLogged = false;
                        }
                        restLatchRounds++;
                    } else {
                        restLatchRounds = 0;
                        restAnchorY = Double.NaN;
                        restLatchLogged = false;
                    }
                    boolean restLatched = restLatchRounds >= 40;
                    if (restLatched && !restLatchLogged) {
                        restLatchLogged = true;
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " descend 悬空有托底，闩锁放行选步（fix286） | y="
                                + String.format(java.util.Locale.ROOT, "%.2f", yNow)
                                + " pos=" + restFeet.toShortString());
                    }
                    if (!restLatched
                            && airGateRounds <= 40 && airTotalRounds <= 200) {
                        return;
                    }
                } else {
                    airGateRounds = 0;
                    airTotalRounds = 0;
                    airGateMinY = Double.NaN;
                    airGateMaxY = Double.NaN;
                    airRecentered = false;
                    restLatchRounds = 0;
                    restAnchorY = Double.NaN;
                    restLatchLogged = false;
                }
                landWaitRounds = 0;
                if (lastTarget != null) {
                    // 不能用 blockPosition().equals(lastTarget)：walkTo 允许在 0.6 格内停下，
                    // 常常停在原列而不是目标列的格心上，导致永远判不"到达"，每级都要等卡住超时。
                    // 【fix253】同悬空分支：整格越线之外还要精确 Y 真降 ≥0.5 格才结算。
                    if (npc.blockPosition().getY() < stepStartY
                            && npc.getY() <= stepStartPreciseY - 0.5D) {
                        lastTarget = null;
                        stepWaitRounds = 0;
                        steps++;
                        failedSteps = 0;
                        settleRecentered = false;
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
                // 【fix211】崖面穿岩模式：与既有垂直下挖同动作同结算（挖
                // 脚下一格、重力带落一级、lastTarget 走上方结算分支）。
                // 退出条件：脚下转空/流体/基岩/钟乳石链（岩层性状变了、
                // 交回阶梯逻辑重评）、自转换点真降 ≥8 格（已穿过崖面带）、
                // 或穿岩 16 级到帽（防异常深穿，退出后看门狗仍在）。
                if (shaftMode) {
                    BlockPos shaftFeet = feet.below();
                    BlockState shaftState = npc.level().getBlockState(shaftFeet);
                    if (shaftState.isAir() || isFluid(shaftState)
                            || shaftState.is(Blocks.BEDROCK)
                            || stalactiteHazard(shaftFeet) != null
                            || lavaDropHazard(shaftFeet)
                            || npc.getY() <= shaftStartY - 8.0D
                            || shaftSteps >= 16) {
                        shaftMode = false;
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " descend 崖面转换结束 | 回阶梯逻辑 穿岩级数=" + shaftSteps
                                + " Y=" + Math.round(npc.getY()));
                    } else {
                        stepStartY = feet.getY();
                        stepStartPreciseY = npc.getY();
                        npc.mineWithBestTool(shaftFeet);
                        lastTarget = shaftFeet;
                        settleRecentered = false;
                        shaftSteps++;
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " descend 垂直下挖（崖面转换）| 脚下=" + shaftState.getBlock()
                                + " 级数=" + shaftSteps + " Y=" + feet.getY());
                        return;
                    }
                }
                int[] dir = dirs[dirIndex % dirs.length];
                BlockPos ahead = feet.offset(dir[0], 0, dir[1]);
                BlockPos aheadHead = ahead.above();
                BlockPos nextFeet = ahead.below();
                BlockState aheadState = npc.level().getBlockState(ahead);
                BlockState aheadHeadState = npc.level().getBlockState(aheadHead);
                BlockState nextFeetState = npc.level().getBlockState(nextFeet);
                // 流体 / 基岩 / 悬空：这一级不安全，转向。悬空会直接掉进洞穴或岩浆湖。
                // 【第七十八轮】下一格的落脚支撑若是尖滴石锥也不安全：锥尖有碰撞
                // 会被下面的"碰撞为空"检查误放行，但锥尖根本不是落脚面，踩上去
                // 只会顺着锥列滑进大厅（farm37 死链一环）。
                if (nextFeetState.is(Blocks.BEDROCK) || aheadState.is(Blocks.BEDROCK)
                        || aheadHeadState.is(Blocks.BEDROCK)
                        || isFluid(nextFeetState) || isFluid(aheadState) || isFluid(aheadHeadState)
                        || lavaBeside(ahead) || lavaBeside(nextFeet)
                        || npc.level().getBlockState(nextFeet.below()).is(Blocks.POINTED_DRIPSTONE)
                        || npc.level().getBlockState(nextFeet.below()).getCollisionShape(
                                npc.level(), nextFeet.below()).isEmpty()) {
                    // 【fix253】台阶下行（落差 2）：本方向只是「台面低一格」
                    // 被判不安全——前方脚/头格可过、nextFeet 可过且其正下格
                    // 是空气、再下一格是实心台面（桥台阶/坡面台阶的本体形
                    // 态，farm255 桥顶实证旧判定四向全灭）。真人下台阶就是
                    // 迈过去让身体落一级到台面上：落差合计 2 格不摔伤。
                    // 岩浆邻接/流体/基岩/钟乳石/耕地一律不走此路，仍旧转向。
                    BlockPos stepDownSupport = nextFeet.below(2);
                    BlockState stepDownSupportState = npc.level().getBlockState(stepDownSupport);
                    BlockState stepDownGapState = npc.level().getBlockState(nextFeet.below());
                    if (!npc.isWalking()
                            && (aheadState.isAir() || aheadState.canBeReplaced()
                                    || aheadState.getCollisionShape(npc.level(), ahead).isEmpty())
                            && (aheadHeadState.isAir() || aheadHeadState.canBeReplaced()
                                    || aheadHeadState.getCollisionShape(npc.level(), aheadHead).isEmpty())
                            && (nextFeetState.isAir() || nextFeetState.canBeReplaced()
                                    || nextFeetState.getCollisionShape(npc.level(), nextFeet).isEmpty())
                            && !isFluid(aheadState) && !isFluid(aheadHeadState)
                            && !isFluid(nextFeetState)
                            && !lavaBeside(ahead) && !lavaBeside(nextFeet)
                            && (stepDownGapState.isAir()
                                    || stepDownGapState.getCollisionShape(
                                            npc.level(), nextFeet.below()).isEmpty())
                            && !stepDownGapState.is(Blocks.POINTED_DRIPSTONE)
                            && !stepDownSupportState.getCollisionShape(
                                    npc.level(), stepDownSupport).isEmpty()
                            && !isFluid(stepDownSupportState)
                            && !stepDownSupportState.is(Blocks.BEDROCK)
                            && !stepDownSupportState.is(Blocks.POINTED_DRIPSTONE)
                            && !stepDownSupportState.is(Blocks.FARMLAND)
                            && stalactiteHazard(ahead) == null
                            && stalactiteHazard(aheadHead) == null
                            && stalactiteHazard(nextFeet) == null) {
                        stepStartY = feet.getY();
                        stepStartPreciseY = npc.getY();
                        npc.walkTo(new Vec3(nextFeet.getX() + 0.5D + dir[0] * 0.4D,
                                nextFeet.getY(), nextFeet.getZ() + 0.5D + dir[1] * 0.4D),
                                false, null);
                        lastTarget = nextFeet;
                        settleRecentered = false;
                        unsafeTurns = 0;
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " descend 台阶下行 | to="
                                + nextFeet.toShortString() + " 台面="
                                + stepDownSupportState.getBlock() + " Y=" + feet.getY()
                                + "（fix253：台面低一格，迈步落一级）");
                        return;
                    }
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
                            && !isFluid(nextFeetState) && !lavaBeside(ahead)
                            && !nextFeetState.getCollisionShape(
                                    npc.level(), nextFeet).isEmpty()) {
                        lateralMoves++;
                        // 【fix363】横移目标加 +0.4 超前量（与主步/台阶下行
                        // 同口径）：只给格心时，NPC 离目标仅 1.0 格、到达
                        // 半径 0.5 格，走到格界即被判到达停步、从未真正跨
                        // 入目标格（床顶实证：横移 6 次皆在格界假到达，Y
                        // 恒 65.56、结算永判走位中断，目标层原样重派 44
                        // 次）。+0.4 后停点落在目标格内 0.4 格，必离开原
                        // 站位（床顶）落到台面；目标仍在 ahead 格内，不
                        // 越入再下一格。
                        npc.walkTo(new Vec3(ahead.getX() + 0.5D + dir[0] * 0.4D, feet.getY(),
                                ahead.getZ() + 0.5D + dir[1] * 0.4D), false, null);
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " descend 同层横移（fix363） | to="
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
                        // 【fix107】边缘悬挂：同一站位反复进此分支且 Y 不降，
                        // 说明身体被邻格边缘托住（站立列下空、支撑在邻列），
                        // 往自己列中心走只会原地 arrive（farm67 山顶桥头实证：
                        // Y=149 悬停 7 分钟，直到目标看门狗处决）。第二次同点
                        // 无下降先横移复位到有支撑的邻列站稳，再回阶梯逻辑；
                        // 复位无路则落回原 walk-down，由 failedSteps 与目标层
                        // 竖向对齐死线兜底收场。真悬空（树冠等 walk-down 一次
                        // 就能落下）的情形第一次照旧走、不受影响。
                        if (feet.equals(lastAirWalkPos) && feet.getY() >= lastAirWalkY) {
                            airWalkStuck++;
                        } else {
                            lastAirWalkPos = feet.immutable();
                            lastAirWalkY = feet.getY();
                            airWalkStuck = 0;
                        }
                        if (airWalkStuck >= 1 && descendRecenterFromEdge(feet)) {
                            airWalkStuck = 0;
                            lastTarget = null;
                            return;
                        }
                        // 【fix286】fix107 注释承诺的 failedSteps 兜底在本分支
                        // 从未接线：复位无路时同点无下降的走位空转只剩 fix113
                        // 90 秒真降看门狗收场（134 棒克隆极限格实证：闩锁放行
                        // 后 36 次「向下走一格」零净降、拖满 90 秒）。接上既有
                        // 口径：同点无下降且复位无路计入 failedSteps、攒满 6
                        // 次收场交编排层换路；首次进入本分支与 Y 真降即清账
                        // 的情形（真悬空一次落下）一字未动。
                        if (airWalkStuck >= 1) {
                            failedSteps++;
                            if (failedSteps >= 6) {
                                finish(id, "descend", "边缘悬挂复位无路、走位无下降 Y="
                                        + feet.getY());
                                return;
                            }
                        }
                        stepStartY = feet.getY();
                        stepStartPreciseY = npc.getY();
                        lastTarget = belowFeet;
                        settleRecentered = false;
                        npc.walkTo(new Vec3(belowFeet.getX() + 0.5D, belowFeet.getY(),
                                belowFeet.getZ() + 0.5D), false, null);
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " descend 脚下悬空，向下走一格 | Y="
                                + feet.getY());
                        return;
                    }
                    // 【第七十八轮】垂直下挖同样查钟乳石：脚下块是悬挂链的支撑
                    // 就不挖——farm37 实证 descend 挖断链、坠落钟乳石砸死（第 6 死）。
                    BlockPos belowDrip = stalactiteHazard(belowFeet);
                    if (belowDrip != null) {
                        failedSteps++;
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " descend 垂直下挖钟乳石回避 | 脚下="
                                + belowState.getBlock() + " 钟乳石=" + belowDrip.toShortString()
                                + " 连续失败=" + failedSteps + " Y=" + feet.getY());
                        if (failedSteps >= 6) {
                            finish(id, "descend", "脚下连着钟乳石，四周也不安全 Y=" + feet.getY());
                        }
                        return;
                    }
                    // 【fix248】垂直下挖岩浆回避：脚下块正下方/落点是岩浆、或
                    // 落点四邻有岩浆（挖开涌入）就不挖——#92/#93 与实验场 G5
                    // 三度实证旧逻辑只查脚下块本身、照挖直坠浆池。拒挖走既有
                    // failedSteps 口径，攒满收场交目标层换点（兜底不缺位）。
                    if (lavaDropHazard(belowFeet)) {
                        failedSteps++;
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " descend 垂直下挖岩浆回避 | 脚下="
                                + belowState.getBlock() + " 下方/落点有岩浆 连续失败=" + failedSteps
                                + " Y=" + feet.getY());
                        if (failedSteps >= 6) {
                            finish(id, "descend", "脚下是岩浆/落点贴岩浆，四周也不安全 Y=" + feet.getY());
                        }
                        return;
                    }
                    stepStartY = feet.getY();
                    stepStartPreciseY = npc.getY();
                    // 【fix358】先记挖前块名（挖完再读只剩 air）：只当脚下是
                    // 钻石矿变体时，挖完当即同步收掉落（descend 是赶路技能，
                    // 其他掉落不动）；非钻石矿不收。
                    String belowPath = variantPath(belowFeet);
                    npc.mineWithBestTool(belowFeet);
                    if (isDiamondOrePath(belowPath)) {
                        collectMinedDrops(belowFeet);
                    }
                    // 【fix357】垂直下挖同款素手兜底（见主步）：无镐无料时
                    // 脚下块挖不开即素手清掉，否则本分支每 tick 空转。
                    if (!npc.level().getBlockState(belowFeet).getCollisionShape(npc.level(), belowFeet).isEmpty()) {
                        clearByBareHandIfNoTool(belowFeet);
                    }
                    lastTarget = belowFeet;
                    settleRecentered = false;
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " descend 垂直下挖 | 脚下="
                            + belowState.getBlock() + " Y=" + feet.getY());
                    return;
                }
                unsafeTurns = 0;

                // 【第七十八轮】主步三格开挖前查钟乳石断链（fix77 扩面到 descend）：
                // farm37 实证 descend 主步挖掉同列两块滴石块后被坠落钟乳石满血
                // 砸死——凡是挖块入口都得查，不止 ascend 的上挖路径。
                BlockPos stepDrip = stalactiteHazard(ahead);
                BlockPos stepPos = ahead;
                String stepCell = "前方格";
                if (stepDrip == null) {
                    stepDrip = stalactiteHazard(aheadHead);
                    stepPos = aheadHead;
                    stepCell = "前方头格";
                }
                if (stepDrip == null) {
                    stepDrip = stalactiteHazard(nextFeet);
                    stepPos = nextFeet;
                    stepCell = "下一格";
                }
                if (stepDrip != null) {
                    dirIndex++;
                    failedSteps++;
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " descend 钟乳石回避，转向 | "
                            + stepCell + "=" + stepPos.toShortString()
                            + " 钟乳石=" + stepDrip.toShortString()
                            + " 连续失败=" + failedSteps);
                    if (failedSteps >= 6) {
                        finish(id, "descend", "连续 " + failedSteps + " 级被钟乳石挡住 Y="
                                + Math.round(npc.getY()));
                    }
                    return;
                }
                // 一次挖掉三格：新站位脚部（y-1）、新站位头部（y）、以及当前头部高度的 y+1。
                // 少挖 y+1 的话，身体还在一层高的位置时脑袋会撞上左侧的方块，
                // walkTo 会一直卡住等 4 秒超时，下矿速度只有每分钟十几格。
                stepStartY = feet.getY();
                stepStartPreciseY = npc.getY();
                // 【fix358】先记三格挖前块名（挖完再读只剩 air）：只当某格是
                // 钻石矿变体时，挖完当即同步收掉落（descend 是赶路技能，
                // 其他掉落不动）；非钻石矿不收。
                String aheadPath = variantPath(ahead);
                String aheadHeadPath = variantPath(aheadHead);
                String nextFeetPath = variantPath(nextFeet);
                npc.mineWithBestTool(ahead);
                npc.mineWithBestTool(aheadHead);
                npc.mineWithBestTool(nextFeet);
                if (isDiamondOrePath(aheadPath)) {
                    collectMinedDrops(ahead);
                }
                if (isDiamondOrePath(aheadHeadPath)) {
                    collectMinedDrops(aheadHead);
                }
                if (isDiamondOrePath(nextFeetPath)) {
                    collectMinedDrops(nextFeet);
                }
                // 【fix357】无料合不出石镐时素手清路（fix332 同款）：
                // 分级守卫礼让落空且包里无镐时 descend 在此空转——
                // mineWithBestTool 全败、fix225 探针又谎报"石镐可合"
                // 致礼让收场，fix320 守卫"合不出"分支空转返回、目标层
                // 原样重派 descend（269 棒实验场实证 1.5 次/秒）。
                // 素手只清石镐可采且合不出石镐的方块（clearByBareHandIfNoTool
                // 内置双门槛）；可合时返回 false 不动正常链路，须铁镐
                // 以上者亦不动。
                if (!npc.level().getBlockState(ahead).getCollisionShape(npc.level(), ahead).isEmpty()) {
                    clearByBareHandIfNoTool(ahead);
                }
                if (!npc.level().getBlockState(aheadHead).getCollisionShape(npc.level(), aheadHead).isEmpty()) {
                    clearByBareHandIfNoTool(aheadHead);
                }
                if (!npc.level().getBlockState(nextFeet).getCollisionShape(npc.level(), nextFeet).isEmpty()) {
                    clearByBareHandIfNoTool(nextFeet);
                }
                // 挖完立刻检查：下一格的脚部如果还有碰撞，说明镐子碎了或方块挖不动。
                // 这时候再 walkTo 只会白等 4 秒并被判卡住，直接转向更省时间。
                if (!npc.level().getBlockState(nextFeet).getCollisionShape(npc.level(), nextFeet).isEmpty()) {
                    // 【fix196】分级守卫礼让（石镐可合、拒用高级镐）不是挖不动：
                    // 立即礼让收场交规划层补造，不喂挖不动连败、outcome 不带
                    // "缺少镐"字样免触发 fix109 速断（staging fix195e 实证全链）。
                    if (npc.mineRefusedByTierGuardRecently()) {
                        finish(id, "descend", "礼让收场：分级守卫拒用高级镐（石镐可合），交规划层补造石镐 Y="
                                + Math.round(npc.getY()));
                        return;
                    }
                    // 【fix198】农田守卫拒挖同理不是挖不动：井道半途撞进自家
                    // 田（井口避让只管起点），避让收场、不喂挖不动连败。
                    if (npc.mineRefusedByFarmGuardRecently()) {
                        finish(id, "descend", "农田避让收场：井道撞进自家农田，拒挖田格 Y="
                                + Math.round(npc.getY()));
                        return;
                    }
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
                // 【fix363】主步前路仍实心（床/存储块/基岩/已点亮门框等拒挖块）：
                // 挖完 ahead/aheadHead 仍有碰撞，说明前路挖不动——走过去只会
                // 踏上拒挖块顶（床顶 0.56 格）反向爬升，结算永无真降（G1 实证：
                // 横移已离床，Y 70.56→70.0，主步北向把它带回床上，520 级零进展
                // 的另一半）。与下一格挖不动同口径：转向，不走此路；四向皆
                // 堵时由全灭分支垂直下挖接手。
                if (!npc.level().getBlockState(ahead).getCollisionShape(npc.level(), ahead).isEmpty()
                        || !npc.level().getBlockState(aheadHead).getCollisionShape(npc.level(), aheadHead).isEmpty()) {
                    dirIndex++;
                    failedSteps++;
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " descend 前路挖不动 | 前方="
                            + npc.level().getBlockState(ahead).getBlock() + " 连续失败=" + failedSteps);
                    if (failedSteps >= 6) {
                        finish(id, "descend", "连续 " + failedSteps + " 格前路挖不动，可能被拒挖块挡住 Y="
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
                settleRecentered = false;
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
    /**
     * 【fix300】现挖现垫（149 棒）：包里无垫块时，现挖身边一格可挖的建筑块当垫块。
     * 只挖最近一块（够一块垫块就停，不把墙掏空）；石系优先于木板（护木料经济）。
     * 救援挖：放行最好镐（钻镐亦可，烧 1 点耐久换脱困——分级守卫只挡"有石镐时烧高级镐"，
     * 包里无建筑块时圆石 <3、守卫放行当块；存储/床/基岩/农田守卫全在 mine() 层）。
     * 挖完当即把掉落收进包（同步，不等 GoalExecutor 拾取——act 直发时拾取不跑）：
     * 收进包才返回 true，调用方随即 placeBuildingBlockAt 扣一块垫上；找不到掉落
     * （素手挖无掉落/包满装不下）返回 false，掉落物留在原地、不凭空造块。
     * 无候选（纯空气泡/基岩墙）返回 false，调用方诚实收场。
     */
    private boolean tryDigPadBlock(BlockPos stepPos) {
        BlockPos feet = npc.blockPosition();
        java.util.List<BlockPos> cands = new java.util.ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                // 【fix368】下界口袋取材：扫描下限 −1→−2，够到菌岩正下方的下界岩；
                // 绯红／诡异菌岩也算可挖来源（挖下掉下界岩，正好是垫块）。
                // 正式服口袋实证：地表全是菌岩＋菌柄，旧扫描框内无一下界岩，
                // tryDigPadBlock 恒空、ascend 2 秒即败。菌柄不加：挖下掉落是
                // 菌柄本身，不在垫块池内，收不进垫脚。
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos p = feet.offset(dx, dy, dz);
                    if (p.equals(feet.below()) || p.equals(stepPos)) {
                        continue;
                    }
                    BlockState st = npc.level().getBlockState(p);
                    if (st.isAir() || st.is(net.minecraft.world.level.block.Blocks.BEDROCK)
                            || isFluid(st)) {
                        continue;
                    }
                    String path = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
                    if (!BUILDING_BLOCK_PATHS.contains(path)
                            && !"crimson_nylium".equals(path) && !"warped_nylium".equals(path)) {
                        continue;
                    }
                    if (!withinMineReach(p)) {
                        continue;
                    }
                    if (stalactiteHazard(p) != null) {
                        continue;
                    }
                    cands.add(p);
                }
            }
        }
        if (cands.isEmpty()) {
            return false;
        }
        cands.sort(java.util.Comparator
                .comparingInt((BlockPos p) -> {
                    String path = BuiltInRegistries.BLOCK.getKey(
                            npc.level().getBlockState(p).getBlock()).getPath();
                    return path.endsWith("_planks") ? 1 : 0;
                })
                .thenComparingDouble(p -> feet.distSqr(p)));
        BlockPos target = cands.get(0);
        if (!npc.mineWithBestTool(target, true, true)) {
            return false;
        }
        // 同步收掉落：只收本次挖掘在目标格附近掉的建筑块（石头→圆石等），
        // 找不到或包满装不下就不垫——掉落物留在原地等自然拾取，不凭空造块。
        net.minecraft.world.phys.Vec3 center = net.minecraft.world.phys.Vec3.atCenterOf(target);
        java.util.List<net.minecraft.world.entity.item.ItemEntity> drops = npc.level()
                .getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(center, center).inflate(1.5D),
                        e -> e.isAlive() && !e.getItem().isEmpty()
                                && BUILDING_BLOCK_PATHS.contains(
                                        BuiltInRegistries.ITEM.getKey(e.getItem().getItem()).getPath()));
        if (drops.isEmpty()) {
            return false;
        }
        drops.sort(java.util.Comparator.comparingDouble(
                e -> e.position().distanceToSqr(center)));
        net.minecraft.world.entity.item.ItemEntity drop = drops.get(0);
        net.minecraft.world.item.ItemStack stack = drop.getItem().copy();
        if (!npc.getInventory().add(stack)) {
            return false;
        }
        drop.discard();
        return true;
    }

    /**
     * 【fix358】挖完当即同步收掉落（271 棒：新图钻石挖下未入包定谳）。
     * 唯一收集口 GoalExecutor.pickupDrops() 只在目标层 tick 且当前技能 idle
     * 时跑，strip／descend 运行期拾取不跑；strip 没挖够数会走满 24 格长度、
     * 结束时已离矿 7 格开外，掉落 5 分钟后 despawn、且无回头机制。实验室
     * G0 复现：strip 挖 1 块深板岩钻石矿后走满 24 格，INVENTORY diamond=0、
     * 「拾取掉落 | diamond」0 行（boot-lab151.log 行 1049864）。
     * 本 helper 在刚挖掉的方块中心 1.5 格内查 ItemEntity，inventory.add
     * 成功才 discard；包满装不下时掉落留在原地、不凭空造物。只认刚挖掉
     * 的这一格，不扫周边（fix300 tryDigPadBlock 同款先挖后查）。
     */
    private boolean collectMinedDrops(BlockPos mined) {
        net.minecraft.world.phys.Vec3 center = net.minecraft.world.phys.Vec3.atCenterOf(mined);
        java.util.List<net.minecraft.world.entity.item.ItemEntity> drops = npc.level()
                .getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(center, center).inflate(1.5D),
                        e -> e.isAlive() && !e.getItem().isEmpty());
        if (drops.isEmpty()) {
            return false;
        }
        drops.sort(java.util.Comparator.comparingDouble(
                e -> e.position().distanceToSqr(center)));
        boolean any = false;
        for (net.minecraft.world.entity.item.ItemEntity drop : drops) {
            net.minecraft.world.item.ItemStack stack = drop.getItem().copy();
            int count = stack.getCount();
            String itemPath = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (npc.getInventory().add(stack)) {
                drop.discard();
                any = true;
                LOG.info("[AIV][SKILL] " + npc.npcName() + " 挖完即收（fix358） | "
                        + itemPath + " x" + count + " @ " + mined.toShortString());
            }
        }
        return any;
    }

    /** 钻石矿变体（普通／深板岩）——fix358 descend 只收这两种的掉落。 */
    private static boolean isDiamondOrePath(String path) {
        return "diamond_ore".equals(path) || "deepslate_diamond_ore".equals(path);
    }

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
            /** 【fix287】单级等待死线轮数（照 descend stepWaitRounds 同口径）。 */
            private int stepWaitRounds;
            /** 【fix287】悬空托底闩锁（照 fix286 descend 闩锁同口径）：
             * descend 在 134 棒补了并联放行路，ascend 当时漏补——腔缘
             * 半挂站姿下悬空窗被蠕动反复重置、选步饿死到 30 秒死线
             * （克隆 135 棒 30 秒零步派发复现、生产 fresh161/164 同签名）。 */
            private int restLatchRounds;
            private double restAnchorY = Double.NaN;
            private boolean restLatchLogged;


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
                            stepWaitRounds = 0;
                            steps++;
                            failedSteps = 0;
                            unsafeTurns = 0;
                        }
                        else if (!npc.isWalking()) {
                            // 【fix295】与下方 onGround 分支逐字同构：身体已水平到达
                            // 待结算步（walk-arrive 清掉行走目标）或行走被打断
                            // （walk-stuck / 救援），但 Y 永不结算——悬空分支旧版
                            // 无条件 return，静默烧满 120 秒看门狗（生产 fresh177
                            // 08:07:44 walk-arrive 后 101 秒零行、实验场 11:34:04
                            // 派发烧满 120 秒步数=0 实证）。立即按一级失败处理，
                            // 不等死线：清目标、转向、计数，攒满 6 次走既有收场。
                            lastTarget = null;
                            stepWaitRounds = 0;
                            dirIndex++;
                            failedSteps++;
                            LOG.info("[AIV][SKILL] " + npc.npcName()
                                    + " ascend 悬空走位中断，强制转向（fix295） | 连续失败="
                                    + failedSteps + " Y=" + Math.round(npc.getY()));
                            if (failedSteps >= 6) {
                                finish(id, "ascend", "连续 " + failedSteps + " 级上不去 Y="
                                        + Math.round(npc.getY()));
                            }
                        } else {
                            // 【fix295】与下方 onGround 分支 fix287 死线逐字同构：
                            // 身体自称在走但步永不结算时，>200 轮（×3 tick ≈30 秒）
                            // 按一级失败处理：停走位、清目标、转向、计数，攒满 6 次
                            // 走既有「连续 6 级上不去」收场。与 onGround 分支共用
                            // stepWaitRounds、同一 200 轮阈值。
                            if (++stepWaitRounds > 200) {
                                stepWaitRounds = 0;
                                lastTarget = null;
                                npc.stopWalking();
                                dirIndex++;
                                failedSteps++;
                                LOG.info("[AIV][SKILL] " + npc.npcName()
                                        + " ascend 悬空单级等待超时，强制转向（fix295） | 连续失败="
                                        + failedSteps + " Y=" + Math.round(npc.getY()));
                                if (failedSteps >= 6) {
                                    finish(id, "ascend", "连续 " + failedSteps + " 级上不去 Y="
                                            + Math.round(npc.getY()));
                                }
                            }
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
                    // 【fix287】托底闩锁（与窗/死线并联的第二条放行路，
                    // 照 fix286 descend 闩锁逐字同构）：身体 5 列任一可站
                    // 格的落脚面在脚下 1.3 格内（物理上有托底）∧ 精确 Y
                    // 在锚点 ±1.0 带内连续满 40 轮 → 不再 return、每 tick
                    // 落到下方选步。出带（真下落）或托底消失即清零重计；
                    // 既有窗判定/死线一字未动。
                    boolean restSupported = false;
                    BlockPos restFeet = npc.blockPosition();
                    for (int[] ro : new int[][]{{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        for (int rdy = 0; rdy <= 2 && !restSupported; rdy++) {
                            BlockPos cand = restFeet.offset(ro[0], -rdy, ro[1]);
                            if (PathFinder.isStandable(npc.level(), cand)) {
                                double gap = yNow - cand.getY();
                                if (gap >= -0.05D && gap <= 1.3D) {
                                    restSupported = true;
                                }
                            }
                        }
                    }
                    if (restSupported) {
                        if (Double.isNaN(restAnchorY)
                                || Math.abs(yNow - restAnchorY) > 1.0D) {
                            restAnchorY = yNow;
                            restLatchRounds = 0;
                            restLatchLogged = false;
                        }
                        restLatchRounds++;
                    } else {
                        restLatchRounds = 0;
                        restAnchorY = Double.NaN;
                        restLatchLogged = false;
                    }
                    boolean restLatched = restLatchRounds >= 40;
                    if (restLatched && !restLatchLogged) {
                        restLatchLogged = true;
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " ascend 悬空有托底，闩锁放行选步（fix287） | y="
                                + String.format(java.util.Locale.ROOT, "%.2f", yNow)
                                + " pos=" + restFeet.toShortString());
                    }
                    if (!restLatched
                            && airGateRounds <= 40 && airTotalRounds <= 200) {
                        return;
                    }
                } else {
                    airGateRounds = 0;
                    airTotalRounds = 0;
                    airGateMinY = Double.NaN;
                    airGateMaxY = Double.NaN;
                    restLatchRounds = 0;
                    restAnchorY = Double.NaN;
                    restLatchLogged = false;
                }
                if (lastTarget != null) {
                    if (npc.blockPosition().getY() > stepStartY) {
                        lastTarget = null;
                        stepWaitRounds = 0;
                        steps++;
                        failedSteps = 0;
                        unsafeTurns = 0;
                    } else if (!npc.isWalking()) {
                        lastTarget = null;
                        stepWaitRounds = 0;
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
                        // 【fix287】单级等待死线（照 descend stepWaitRounds
                        // 同口径）：身体自称在走但步永不结算（贴边磨蹭、
                        // 滑落中微位移反复重置身体层卡住计时）时，旧版
                        // 无条件 return 干等、只能烧满 120 秒看门狗
                        // （fresh161 单步悬置 89 秒、fresh164 一派悬置
                        // 115 秒净 −3 实证）。>200 轮（×3 tick ≈30 秒）
                        // 按一级失败处理：停走位、清目标、转向、计数，
                        // 攒满 6 次走既有「连续 6 级上不去」收场。
                        if (++stepWaitRounds > 200) {
                            stepWaitRounds = 0;
                            lastTarget = null;
                            npc.stopWalking();
                            dirIndex++;
                            failedSteps++;
                            LOG.info("[AIV][SKILL] " + npc.npcName()
                                    + " ascend 单级等待超时，强制转向（fix287） | 连续失败="
                                    + failedSteps + " Y=" + Math.round(npc.getY()));
                            if (failedSteps >= 6) {
                                finish(id, "ascend", "连续 " + failedSteps + " 级上不去 Y="
                                        + Math.round(npc.getY()));
                            }
                        }
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
                // 【fix248】岩浆补查：目标脚/头格四邻有岩浆（迈进去会涌入）、
                // 台阶格正下方是岩浆（实验场 G4b 实证旧查只看台阶格本身、
                // NPC 会在浆面一格高处搭桥过池）、要挖的头顶格/目标头格正
                // 上方是岩浆（挖开灌顶）——任一命中同口径转向。
                // 【fix345】水台阶垫脚：台阶/目标格是水时不转向，改走垫脚分支
                // （水无碰撞形，placeAscendPadBlockAt 可进水格放块；岩浆/基岩/火仍转向）。
                // 生产定谳：新图水洼 ascend 连败 9 次皆因水台阶被本查先行拦下，
                // 垫脚分支永不可达；实验场 G0c 终态「无法上挖」复现 1 次。
                // 兜底：包里无垫块时垫脚分支走原有转向/收场（fix300 现挖现垫在内），不硬撑。
                if (targetFeetState.is(Blocks.BEDROCK) || targetHeadState.is(Blocks.BEDROCK)
                        || (isFluid(targetFeetState) && !targetFeetState.is(Blocks.WATER))
                        || (isFluid(targetHeadState) && !targetHeadState.is(Blocks.WATER))
                        || (isFluid(stepState) && !stepState.is(Blocks.WATER))
                        || lavaBeside(targetFeet) || lavaBeside(targetHead)
                        || isLavaAt(targetFeet.below().below())
                        || isLavaAt(currentCeiling.above()) || isLavaAt(targetHead.above())) {
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
                if (stepState.getCollisionShape(npc.level(), targetFeet.below()).isEmpty()) {
                    // 【fix321】(A) ascend 台阶垫脚走垫脚专用口径（石系／木板优先、羊毛兜底）。
                    // placeBuildingBlockAt 本身不动（travel 搭桥等共用入口，口径不变）。
                    String padPath = placeAscendPadBlockAt(targetFeet.below());
                    boolean padOk = padPath != null;
                    if (padOk && padPath.endsWith("_wool")) {
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " ascend 垫脚用羊毛（fix321） | block="
                                + padPath + " 台阶=" + targetFeet.below().toShortString()
                                + " Y=" + feet.getY());
                    }
                    // 【fix300】现挖现垫（149 棒：生产 fresh177 19:37:41、实验场格 R 同型实证——
                    // 包里垫块烧光时 ascend 两圈即收场，NPC 在床下干空气泡里原地等死；
                    // 玩家会先挖身边墙/顶/地拿一块石头再垫脚爬上去）。
                    // 同步收掉落：挖出的掉落物落在原地，act 直发技能时 GoalExecutor 的
                    // pickupDrops 不跑、异步等拾取会超时（格 F 首版实证：40 轮≈6 秒零拾取）；
                    // 故 tryDigPadBlock 挖完当即把掉落收进包、再走正常垫脚口径（床边保护/
                    // 同格节流/自放登记全在 placeAscendPadBlockAt 内）。四周确实无可挖块
                    // （纯空气泡/基岩墙）时返回 false，走原有转向/收场，不硬撑。
                    if (!padOk && pickAscendPadBlock() == null
                            && tryDigPadBlock(targetFeet.below())) {
                        padPath = placeAscendPadBlockAt(targetFeet.below());
                        padOk = padPath != null;
                        if (padOk) {
                            LOG.info("[AIV][SKILL] " + npc.npcName() + " ascend 现挖现垫 | 台阶="
                                    + targetFeet.below().toShortString() + " Y=" + feet.getY());
                        }
                    }
                    if (!padOk) {
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
                }
                unsafeTurns = 0;

                // 【第七十七轮】钟乳石邻接防护（照 fix37 口径）：这一步要挖的
                // 头顶格/目标脚格/目标头格里，任一格挖掉会断钟乳石链就别挖——
                // 坠落的滴石锥满血可秒（farm35 第 5 死）。目标格命中就转向换列；
                // 头顶格每方向都得挖、转向没用，这一列放弃交目标层换点。
                BlockPos dripHazard = stalactiteHazard(currentCeiling);
                String dripCell = "头顶格";
                if (dripHazard == null) {
                    dripHazard = stalactiteHazard(targetFeet);
                    dripCell = "目标脚格";
                }
                if (dripHazard == null) {
                    dripHazard = stalactiteHazard(targetHead);
                    dripCell = "目标头格";
                }
                if (dripHazard != null) {
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " ascend 钟乳石回避 | "
                            + dripCell + "=" + (dripCell.equals("头顶格") ? currentCeiling
                            : dripCell.equals("目标脚格") ? targetFeet : targetHead).toShortString()
                            + " 钟乳石=" + dripHazard.toShortString()
                            + " Y=" + feet.getY());
                    if (dripCell.equals("头顶格")) {
                        finish(id, "ascend", "头顶方块连着钟乳石，换点上升 Y=" + feet.getY());
                    } else {
                        dirIndex++;
                        unsafeTurns++;
                        if (unsafeTurns < dirs.length * 2) {
                            return;
                        }
                        unsafeTurns = 0;
                        failedSteps++;
                        if (failedSteps >= 2) {
                            finish(id, "ascend", "四周台阶都连着钟乳石，无法上挖 Y=" + feet.getY());
                        }
                    }
                    return;
                }

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
     * 【fix359】沿柱直上（273 棒）：staging 全灭、pillarStand 非 null、NPC 已在
     * 柱底水平位置时，沿垫脚站位正下方的通空柱逐格垫脚上行至站位高度，再由
     * 目标层横移站上 pillarStand。复用 ore-reach pillar 的跳搭节奏
     * （jumpNow → 腾空 ≥0.55 格放脚下垫块 → 落地），取块口径为 fix321 垫脚
     * 专用（石系／木板优先、羊毛兜底），无垫块时 fix300 现挖现垫；垫块只走
     * placeAscendPadBlockAt，不经过 mine、不挖门框、不用贵重块。
     * 派发参数：x/z=柱心（格心），y=站位 Y（脚到该层即完成）。
     */
    private boolean runPillarClimb(Intent intent, int id) {
        current = "pillarclimb";
        final double colX = intent.doubleParam("x", npc.getX());
        final double colZ = intent.doubleParam("z", npc.getZ());
        final int targetY = intent.intParam("y", npc.blockPosition().getY());
        final int startY = npc.blockPosition().getY();
        final int maxSteps = Math.max(4, targetY - startY + 4);
        LOG.info("[AIV][SKILL] " + npc.npcName() + " pillarclimb 开始（fix359） | 柱心="
                + String.format(Locale.ROOT, "%.1f, %.1f", colX, colZ)
                + " 目标Y=" + targetY + " 当前Y=" + startY);
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            /** 0=对柱心/起跳 1=腾空等放块 2=等落地。 */
            private int sub;
            private int subWait;
            private int stepFails;
            private int centerWalkTries;
            private int steps;
            private int rounds;
            private BlockPos levelCell;
            private double jumpBaseY;

            @Override
            public void run() {
                if (runId != id) {
                    return;
                }
                // 总进度看门狗（照 ascend 1200 轮口径，3 tick 一轮约 3 分钟）。
                if (++rounds > 1200) {
                    finish(id, "pillarclimb", "长时间无进展（约3分钟） Y="
                            + Math.round(npc.getY()) + " 步数=" + steps);
                    return;
                }
                BlockPos feet = npc.blockPosition();
                double dx = npc.getX() - colX;
                double dz = npc.getZ() - colZ;
                // 【fix359】到达须脚已落地：跳跃顶点 feet.getY() 已≥目标层时若直接结算，
                // 最后一格垫脚来不及放、NPC 落回下一层（G0c3 实证：顶点误判 Y=65，
                // (351,64,299) 仍为空气）。照 fix353(A) 到达须 onGround 的口径。
                if (feet.getY() >= targetY && npc.onGround) {
                    if (dx * dx + dz * dz <= 0.36D) {
                        finish(id, "pillarclimb", "已爬到站位层 Y=" + feet.getY()
                                + " 步数=" + steps);
                        return;
                    }
                    // 脚已到站位层但水平偏出柱心：走回柱心再结算。
                    if (!npc.isWalking()) {
                        npc.walkTo(new Vec3(colX, npc.getY(), colZ), false, null);
                    }
                    return;
                }
                if (steps > maxSteps) {
                    finish(id, "pillarclimb", "垫脚步数超限 Y=" + feet.getY()
                            + " 步数=" + steps);
                    return;
                }
                if (sub == 0) {
                    // 先对准柱心格：脚格不是柱心格时走一格过去再起跳。
                    BlockPos colCell = BlockPos.containing(colX, feet.getY(), colZ);
                    if (!feet.equals(colCell)) {
                        if (!npc.isWalking()) {
                            if (++centerWalkTries > 4) {
                                finish(id, "pillarclimb", "走不到柱心格 Y=" + feet.getY());
                                return;
                            }
                            npc.walkTo(new Vec3(colX, npc.getY(), colZ), false, null);
                        }
                        return;
                    }
                    // 头顶遮挡：起跳会撞头，直接收场交目标层回落，不空转。
                    BlockPos headCell = feet.above(2);
                    if (!npc.level().getBlockState(headCell)
                            .getCollisionShape(npc.level(), headCell).isEmpty()) {
                        finish(id, "pillarclimb", "头顶遮挡无法起跳 Y=" + feet.getY());
                        return;
                    }
                    if (!npc.onGround) {
                        if (++subWait > 40) {
                            finish(id, "pillarclimb", "起跳前长时间未落地 Y=" + feet.getY());
                        }
                        return;
                    }
                    subWait = 0;
                    // 无垫块兜底（fix300 现挖现垫）：身边无可挖垫料即诚实收场。
                    if (pickAscendPadBlock() == null && !tryDigPadBlock(feet)) {
                        finish(id, "pillarclimb", "无垫脚方块 Y=" + feet.getY());
                        return;
                    }
                    levelCell = feet;
                    jumpBaseY = npc.getY();
                    npc.jumpNow();
                    sub = 1;
                    return;
                }
                if (sub == 1) {
                    // 腾空到 0.55 格以上就往脚下放块：放早了会和身体碰撞箱重叠，
                    // 放晚了身体已经开始下落、块会垫不到正下方（ore-reach pillar 同款）。
                    if (npc.getY() - jumpBaseY >= 0.55D) {
                        String padPath = placeAscendPadBlockAt(levelCell);
                        if (padPath == null && pickAscendPadBlock() == null
                                && tryDigPadBlock(levelCell)) {
                            padPath = placeAscendPadBlockAt(levelCell);
                        }
                        if (padPath == null) {
                            sub = 0;
                            if (++stepFails >= 4) {
                                finish(id, "pillarclimb", "垫脚失败多次 Y=" + feet.getY());
                            }
                            return;
                        }
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " pillarclimb 垫脚（fix359） | block="
                                + padPath + " 柱=" + levelCell.toShortString()
                                + " 步数=" + (steps + 1));
                        sub = 2;
                        subWait = 0;
                        return;
                    }
                    if (++subWait > 20) {
                        sub = 0;
                        if (++stepFails >= 4) {
                            finish(id, "pillarclimb", "起跳失败多次（头顶遮挡？） Y=" + feet.getY());
                        }
                    }
                    return;
                }
                // sub == 2：等落到刚垫的块上。
                if (npc.onGround) {
                    sub = 0;
                    subWait = 0;
                    steps++;
                    stepFails = 0;
                    return;
                }
                if (++subWait > 40) {
                    finish(id, "pillarclimb", "垫柱后未落地 Y=" + feet.getY());
                }
            }
        }, 1L, 3L));
        armWatchdog(id, "pillarclimb", SKILL_TIMEOUT_TICKS * 3);
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
                // 【fix255】再加脚下一格：脚/头虽干、脚下却是流体面（岩浆池面正
                // 上方一格、身体骑在 rim 边缘）不算上岸——farm260 #107 实证
                // dryland 在 below=lava 的格子宣告「已到干燥地面」，目标层随即
                // 派 ore-reach 就地挖煤，人在原地烧死。脚下是流体面时不收场，
                // 继续朝目标岸格走（岸格脚下是实心，不受此条影响）。
                if (!isFluid(npc.level().getBlockState(feet))
                        && !isFluid(npc.level().getBlockState(feet.above()))
                        && !isFluid(npc.level().getBlockState(feet.below()))) {
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
            /** 【fix308】步进停滞看门狗基线：steps/oreFound/failedSteps 任一
             * 变化即刷新；三者皆不变的时长超过阈值即判挂死收场。 */
            private long lastProgressMillis = System.currentTimeMillis();
            private int lastProgressSteps = -1;
            private int lastProgressOre = -1;
            private int lastProgressFailed = -1;

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
                // 【fix308·158 棒】步进停滞看门狗：本技能在「等走位结算」
                // （isWalking 恒真）与「悬空未落地」（onGround 恒假）两条
                // 分支上都是静默 return——正式服实证挖进一格后 37 秒全文件
                // 零日志、只能等 40 秒技能看门狗掐断（strip 超时全史 39
                // 次）。这里按状态变化计时：步数/已挖/连败任一变化即刷新，
                // 10 秒全无变化即按 timeout 类结局收场（目标层
                // noteStripOutcome 既有口径认 timeout 结局置换位标记，
                // 下一条 strip 先换位再掘进）。正常一步（挖 2 格＋走 1 格）
                // 约 ≤2 秒，10 秒阈值永不误杀；阈值与 Fong「链上停顿超
                // 10 秒判不合格」口径一致。
                if (steps != lastProgressSteps || oreFound != lastProgressOre
                        || failedSteps != lastProgressFailed) {
                    lastProgressSteps = steps;
                    lastProgressOre = oreFound;
                    lastProgressFailed = failedSteps;
                    lastProgressMillis = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - lastProgressMillis > 10_000L) {
                    end("掘进停滞 timeout：10 秒无步进（步数=" + steps
                            + " 已挖=" + oreFound + "） Y=" + Math.round(npc.getY()));
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
                BlockPos oreDrip = ore != null ? stalactiteHazard(ore) : null;
                if (oreDrip != null) {
                    // 【第七十八轮】钟乳石支撑矿不挖（fix77 扩面，与岩浆跳过同口径）：
                    // 这块矿是悬挂链的支撑/链身，挖掉链就砸在掘进面上。
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " strip 跳过钟乳石支撑矿 | "
                            + variantPath(ore) + " " + ore.toShortString()
                            + " 钟乳石=" + oreDrip.toShortString());
                    ore = null;
                }
                if (ore != null) {
                    // 必须在挖掉之前取方块名：挖完再读坐标只会拿到 air，
                    // 日志里全变成「挖到矿石 | air」，没法判断真正挖到的是哪种矿。
                    String oreName = variantPath(ore);
                    if (npc.mineWithBestTool(ore)) {
                        oreFound++;
                        // 【fix358】挖完当即同步收掉落（见 collectMinedDrops）：
                        // strip 没挖够数会走满 24 格、结束时已离矿多格，目标层
                        // 拾取只在技能 idle 时跑，掉落 5 分钟后 despawn、永不回头。
                        collectMinedDrops(ore);
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
                    if (coalOre != null && stalactiteHazard(coalOre) != null) {
                        // 【第七十八轮】钟乳石支撑煤同样不挖（fix77 扩面）。
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " strip 跳过钟乳石支撑煤 | "
                                + variantPath(coalOre) + " " + coalOre.toShortString());
                        coalOre = null;
                    }
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
                    // 【第七十七轮】钟乳石邻接的方向同样按"无路"处理：挖开候选格
                    // 或其头位格会断悬挂链，坠落的滴石锥正砸掘进面（farm35 第 5 死）。
                    BlockPos candDrip = stalactiteHazard(candidate);
                    if (candDrip == null) {
                        candDrip = stalactiteHazard(candidate.above());
                    }
                    if (candDrip != null) {
                        LOG.info("[AIV][SKILL] " + npc.npcName() + " strip 跳过方向 | 钟乳石邻接 "
                                + candidate.toShortString() + " 钟乳石=" + candDrip.toShortString());
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
                // 【第七十七轮】下镐前钟乳石复查（与候选过滤同口径双保险）：
                // 命中按"挖不动"转向，别把钟乳石砸下来。
                BlockPos digDrip = stalactiteHazard(ahead);
                if (digDrip == null) {
                    digDrip = stalactiteHazard(ahead.above());
                }
                if (digDrip != null) {
                    dirIndex++;
                    failedSteps++;
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " strip 挖前钟乳石邻接，转向 | 前方="
                            + ahead.toShortString() + " 钟乳石=" + digDrip.toShortString()
                            + " 连续失败=" + failedSteps);
                    if (failedSteps >= 6) {
                        end("连续 " + failedSteps + " 步被钟乳石邻接挡住 Y="
                                + Math.round(npc.getY()));
                    }
                    return;
                }
                npc.mineWithBestTool(ahead);
                npc.mineWithBestTool(ahead.above());
                // 挖完还挡着：镐子碎了或这块挖不动，继续 walkTo 只是空等卡住。
                if (!npc.level().getBlockState(ahead).getCollisionShape(npc.level(), ahead).isEmpty()) {
                    // 【fix196】同 descend：分级守卫礼让不算挖不动，立即收场交补造。
                    if (npc.mineRefusedByTierGuardRecently()) {
                        end("礼让收场：分级守卫拒用高级镐（石镐可合），交规划层补造石镐 Y="
                                + Math.round(npc.getY()));
                        return;
                    }
                    // 【fix198】同 descend：农田守卫拒挖不算挖不动，避让收场。
                    if (npc.mineRefusedByFarmGuardRecently()) {
                        end("农田避让收场：矿道撞进自家农田，拒挖田格 Y="
                                + Math.round(npc.getY()));
                        return;
                    }
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
        // 【fix247】羊毛目标来源过滤（床料链）：filter=wool 时只认羊——Wiki 羊毛
        // 词条：未剪毛的成年绵羊死亡掉 1 个与其颜色相同的羊毛（不受抢夺影响）；
        // 剪毛 1–3 需剪刀、NPC 无剪毛技能，走击杀路线（与皮革杀牛同型）。
        // 附近没羊就如实报"没有羊毛来源"，让上层退避换时段，不许追其他动物凑数。
        if ("wool".equals(intent.param("filter", ""))) {
            List<Entity> filtered = candidates.stream()
                    .filter(e -> "sheep".equals(
                            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                                    .getKey(e.getType()).getPath()))
                    .toList();
            if (filtered.isEmpty()) {
                lastOutcome = "hunt 附近没有羊毛来源（只认羊）radius=" + radius
                        + " 附近动物=" + candidates.size();
                LOG.info("[AIV][SKILL] " + npc.npcName() + " hunt 没有羊毛来源 | radius=" + radius
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
        HuntPick pick = pickHuntTarget(sorted, java.util.Set.of());
        if (pick.target() == null) {
            lastOutcome = "hunt 候选均不可达 tried=" + pick.tried() + " candidates=" + sorted.size()
                    + " radius=" + radius;
            LOG.info("[AIV][SKILL] " + npc.npcName() + " hunt 候选均不可达 | tried=" + pick.tried()
                    + " candidates=" + sorted.size() + " radius=" + radius);
            return false;
        }
        current = "hunt";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " hunt 开始 | target=" + pick.target().getType()
                + " hits=" + maxHits + " distance=" + String.format(Locale.ROOT, "%.1f", npc.distanceTo(pick.target())));
        // 【fix268】接近段改技能内监督（119 棒定谳）：旧版接近是一次性
        // followPath——onAbandon 即 finish「接近路径中断」，而静态 A* 在坑缘/
        // 高台地形恒 found（68–82 节点长路），执行却三型恒败（ledge 慢磨
        // stuck-giveup、跌落 1.9 格即时 abort、首航点悬置），目标层每次重派
        // 又重选同一头牛，同一面墙撞满 fix106 的 120 秒零增益。追击段早有
        // 「不在跟路就重算」的恢复、接近段独缺。现首路照旧立即发出，其后由
        // 监督器（1 秒一轮）接管：不在跟路/走路 → 结算无菌（发路后水平位移
        // <1 格，与 travel 的 fix202 同口径）→ 从当前位置重规划；无菌连 2 次
        // → 朝猎物 travelStepToward 挖/搭一步（玩家式物理兜底）；无菌轮次
        // 超 3、一步也迈不出连 2 次、或重规划静态无路 → 本目标记执行失败、
        // 轮换下一候选；
        // 候选全灭才以「接近路径中断（重规划耗尽）」收场，目标层随后
        // wanderForHunt 换区，链有出口。看门狗放宽到 2400 tick 与 fix106
        // 的 120 秒同口径（旧 800 tick 会把合法的长爬坡接近中途掐断）；
        // fix106 本身、半径/heat、航向与计数（fix267）一字不动。
        final class HuntApproach implements Runnable {
            private LivingEntity target = pick.target();
            private int hits;
            private int attempts;
            private int replans;
            private int sterile;
            private int sterileCycles;
            private int failedSteps;
            private BlockPos issuedPos;
            private final java.util.Set<java.util.UUID> failedTargets = new java.util.HashSet<>();

            private void issue(PathFinder.Result path) {
                issuedPos = npc.blockPosition();
                npc.followPath(path.path(), true, null, null);
            }

            /** 当前目标记执行失败并轮换下一候选；无候选返回 false。 */
            private boolean rotate() {
                failedTargets.add(target.getUUID());
                List<Entity> rescan = rescanHuntCandidates(intent, radius);
                HuntPick next = pickHuntTarget(rescan, failedTargets);
                if (next.target() == null) {
                    return false;
                }
                target = next.target();
                sterile = 0;
                sterileCycles = 0;
                failedSteps = 0;
                LOG.info("[AIV][SKILL] " + npc.npcName() + " hunt 接近换目标（fix268） | 新目标="
                        + target.getType() + " 已失败目标=" + failedTargets.size()
                        + " 重规划=" + replans);
                issue(next.path());
                return true;
            }

            private void finishExhausted() {
                finish(id, "hunt", "接近路径中断（重规划耗尽 replans=" + replans
                        + " 换目标=" + failedTargets.size() + " hits=" + hits + "）");
            }

            @Override
            public void run() {
                if (runId != id) {
                    return;
                }
                attempts++;
                if (target.isRemoved() || !target.isAlive()) {
                    finish(id, "hunt", "猎物已倒下 hits=" + hits);
                    return;
                }
                if (attempts > 160) {
                    finish(id, "hunt", "超时 hits=" + hits);
                    return;
                }
                double distanceSq = npc.distanceToSqr(target);
                if (distanceSq <= 16.0D) {
                    npc.attackTarget(target);
                    hits++;
                    if (hits >= maxHits) {
                        finish(id, "hunt", "达到攻击次数 hits=" + hits + " 存活=" + target.isAlive());
                    }
                    return;
                }
                if (npc.isFollowingPath() || npc.isWalking()) {
                    return;
                }
                // 不在移动：先结算上一条路的产量（无菌判定，fix202 同口径）。
                if (issuedPos != null) {
                    double movedDx = npc.getX() - (issuedPos.getX() + 0.5D);
                    double movedDz = npc.getZ() - (issuedPos.getZ() + 0.5D);
                    double movedSq = movedDx * movedDx + movedDz * movedDz;
                    if (movedSq < 1.0D) {
                        sterile++;
                    } else {
                        sterile = 0;
                        if (movedSq >= 16.0D) {
                            // 路径真把身体带出一段（≥4 格）：本目标的无菌
                            // 轮次清零，别把正常赶路攒的数带进兜底判定。
                            sterileCycles = 0;
                        }
                    }
                    issuedPos = null;
                }
                if (sterile >= 2) {
                    sterile = 0;
                    sterileCycles++;
                    if (sterileCycles > 3) {
                        // 同一目标已 3 轮「发路无位移→挖/搭一步」仍无路可走
                        // （如深坑底对高台平挖隧道）：玩家会换一头/换个地方，
                        // 不在一面墙上耗满整轮——记执行失败轮换。
                        sterileCycles = 0;
                        if (!rotate()) {
                            finishExhausted();
                        }
                        return;
                    }
                    if (travelStepToward(target.getX(), target.getZ())) {
                        failedSteps = 0;
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " hunt 接近挖/搭一步（fix268） | 目标=" + target.getType()
                                + " 位置=" + npc.blockPosition().toShortString());
                        return;
                    }
                    failedSteps++;
                    if (failedSteps < 2) {
                        return;
                    }
                    failedSteps = 0;
                    if (!rotate()) {
                        finishExhausted();
                    }
                    return;
                }
                // 从当前位置重规划（接近中断/追击脱位共用此恢复口）。
                replans++;
                BlockPos chaseGoal = PathFinder.standableGoal(npc.level(), target.blockPosition(), 3);
                PathFinder.Result chase = chaseGoal == null ? null
                        : PathFinder.find(npc.level(), npc.blockPosition(), chaseGoal,
                                !npc.wearingLeatherBoots());
                if (chase != null) {
                    logPath("hunt-approach", chaseGoal, chase);
                }
                if (chase != null && chase.found()) {
                    issue(chase);
                    return;
                }
                // 静态无路（地形已变/猎物挪到墙后）：本目标失败，轮换。
                if (!rotate()) {
                    finishExhausted();
                }
            }
        }
        HuntApproach supervisor = new HuntApproach();
        supervisor.issue(pick.path());
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, supervisor, 20L, 20L));
        armWatchdog(id, "hunt", 2400);
        return true;
    }

    /** hunt 候选接近验路结果（fix268 抽出供首选与轮换共用）。 */
    private record HuntPick(LivingEntity target, PathFinder.Result path, int tried) {
    }

    /**
     * 【fix268】按距离序试候选的静态接近路径（排除已失败目标）：前 4 个可试
     * 候选都无路才返回空——与第四十三轮首选口径一致（试满 4 个即止）。
     */
    private HuntPick pickHuntTarget(List<Entity> sorted, java.util.Set<java.util.UUID> excluded) {
        LivingEntity target = null;
        PathFinder.Result approachPath = null;
        int tried = 0;
        for (Entity cand : sorted) {
            if (tried >= 4) {
                break;
            }
            if (excluded.contains(cand.getUUID())) {
                continue;
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
        return new HuntPick(target, approachPath, tried);
    }

    /**
     * 【fix268】轮换重扫：与 runHunt 首扫同一套谓词（成年 Animal＋皮革/羊毛
     * 过滤＋prefer=cow），按距离排序返回；任一硬空情形返回空表（监督器据此
     * 判候选全灭）。首扫的失败文案口径不变，仍走 runHunt 原路。
     */
    private List<Entity> rescanHuntCandidates(Intent intent, double radius) {
        List<Entity> candidates = npc.level().getEntities(npc, npc.getBoundingBox().inflate(radius),
                entity -> entity instanceof Animal animal && animal.isAlive() && !animal.isBaby());
        if ("leather".equals(intent.param("filter", ""))) {
            java.util.Set<String> leatherSources = java.util.Set.of(
                    "cow", "mooshroom", "horse", "donkey", "mule", "llama", "trader_llama");
            candidates = candidates.stream()
                    .filter(e -> leatherSources.contains(
                            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                                    .getKey(e.getType()).getPath()))
                    .toList();
        }
        if ("wool".equals(intent.param("filter", ""))) {
            candidates = candidates.stream()
                    .filter(e -> "sheep".equals(
                            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                                    .getKey(e.getType()).getPath()))
                    .toList();
        }
        if ("cow".equals(intent.param("prefer", ""))) {
            List<Entity> cows = candidates.stream()
                    .filter(e -> "cow".equals(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                            .getKey(e.getType()).getPath()))
                    .toList();
            if (!cows.isEmpty()) {
                candidates = cows;
            }
        }
        return candidates.stream()
                .sorted((left, right) -> Double.compare(npc.distanceToSqr(left), npc.distanceToSqr(right)))
                .toList();
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

    /** 【fix248】指定格是否为岩浆（岩浆池五杀整链修·环 1：下挖/上行入口的岩浆感知）。 */
    private boolean isLavaAt(BlockPos pos) {
        return npc.level().getBlockState(pos).is(Blocks.LAVA);
    }

    /** 【fix248】指定格的四个水平邻格任一为岩浆：打开本格/站进本格后岩浆会涌入。 */
    private boolean lavaBeside(BlockPos pos) {
        return isLavaAt(pos.offset(1, 0, 0)) || isLavaAt(pos.offset(-1, 0, 0))
                || isLavaAt(pos.offset(0, 0, 1)) || isLavaAt(pos.offset(0, 0, -1));
    }

    /**
     * 【fix248】垂直下挖的落浆判定：挖掉 belowFeet 后身体落入 belowFeet 格、
     * 站在 belowFeet.below() 上（或继续下落到首个有支撑的格）。以下任一成立
     * 即危险，拒挖——#92/#93 定谳：旧垂直下挖只查脚下块本身，脚下块正下方
     * 是岩浆/岩浆贴邻落点时照挖不误、直坠浆池。
     * ①落点格（belowFeet）四邻有岩浆（挖开即涌入站位格）；
     * ②脚下块正下方就是岩浆；③向下 ≤8 格的下落路径中落点格是岩浆，
     * 或落点格四邻有岩浆。
     */
    private boolean lavaDropHazard(BlockPos belowFeet) {
        if (lavaBeside(belowFeet)) {
            return true;
        }
        BlockPos cell = belowFeet.below();
        for (int i = 0; i < 8; i++) {
            BlockState state = npc.level().getBlockState(cell);
            if (state.is(Blocks.LAVA)) {
                return true;
            }
            if (!state.getCollisionShape(npc.level(), cell).isEmpty()) {
                // cell 是实心支撑：落点格是 cell 上方那格（i=0 时即 belowFeet
                // 本格，四邻已在入口查过）；更深的落点再查一次四邻。
                return i > 0 && lavaBeside(cell.above());
            }
            cell = cell.below();
        }
        return false;
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
        /** 【fix74】位移监测：每 40 步查一次脚位，连续两次没挪窝就诚实收手，
         * 不再原地烧满 200 步（farm33 实证纯下挖自环 200 步零位移）。 */
        final BlockPos[] lastCheckPos = {npc.blockPosition()};
        final int[] stallChecks = {0};
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
            if (mx == 0 && mz == 0 && my < 0) {
                // 【fix74】纯下挖但脚正下方碰撞为空（空气/水/可替换）：身体多半被
                // 邻列崖边块搭边撑住、脚位列悬空——继续下挖只是挖空气，walkTo
                // 目标又是自身格（水平已达标），原地自环（farm33 坐标级实证）。
                // 改朝水源水平主轴横移脱边：移出搭撑后身体自然下落，
                // 落进水塘也算到达（feet==water 是本技能的完成判定）。
                BlockPos below = feet.below();
                if (npc.level().getBlockState(below).getCollisionShape(npc.level(), below).isEmpty()) {
                    int wdx = water.getX() - feet.getX();
                    int wdz = water.getZ() - feet.getZ();
                    if (wdx == 0 && wdz == 0) {
                        wdx = dx;
                        wdz = dz;
                    }
                    if (Math.abs(wdx) >= Math.abs(wdz) && wdx != 0) {
                        mx = (int) Math.signum(wdx);
                        my = 0;
                    } else if (wdz != 0) {
                        mz = (int) Math.signum(wdz);
                        my = 0;
                    }
                }
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
            if (stepsDone[0] % 40 == 0) {
                if (feet.distSqr(lastCheckPos[0]) < 1.0D) {
                    if (++stallChecks[0] >= 2) {
                        finish(id, "fillbucket", "挖路无位移（地形接近失败）");
                        return;
                    }
                } else {
                    stallChecks[0] = 0;
                    lastCheckPos[0] = feet;
                }
            }
            if (stepsDone[0] > 200) {
                finish(id, "fillbucket", "挖路步数用尽");
            }
        }, 1L, 2L));
        return true;
    }

    /**
     * 【fix105·Fong 2026-10-28 纠正：黑曜石主路】真玩家拿黑曜石就是挖：
     * 走到岩浆湖边，水桶往湖面一浇，水流漫过的岩浆源全结成黑曜石壳，
     * 再拿钻石镐把壳一块块挖走（Wiki：黑曜石硬度 50、须钻石镐以上才掉落；
     * 身体层 destroyBlock 瞬时挖掘不走原版耗时，但工具等级正确性照常校验）。
     * 浇铸（runCastObsidian）自 fix105 起降为兜底：本路线连败 3 次才切过去。
     * 浇水用 {@code DispensibleContainerItem.emptyContents}——发射器同款的
     * 原版放流体入口：放下的是真水源，之后水流与岩浆的相遇全走原版流体
     * 结算结壳，不做逐格 setBlock 转置（旧浇铸正是逐格转置+setBlock 收水，
     * 把 descend 守卫/热循环一串故障全绑在它身上）。
     * 安全口径：先浇后挖；绝不挖脚下格（站立面）；着火/落岩浆立刻收手，
     * 捡命交生存层；收尾把浇出去的水源舀回桶里循环用。
     * 参数：x/y/z=岩浆源提示点（过时无妨，附近 6 格内重找露面岩浆源）、
     * count=本趟要挖的块数。
     */
    private boolean runMineObsidian(Intent intent, int id) {
        // 【fix254】占用补设：本技能自诞生起漏设 current（同批技能 goto/mine/
        // descend/fillbucket/castobsidian 均在 run* 内自设），后果有二——
        // 目标层 951 行「技能非 idle 不派发」的忙门对本技能恒失效，异步链
        // （挖路/浇水/挖壳回调）在飞时目标层每 tick 都当空闲重派别的技能，
        // stop() 把 runId 一换、在飞链条全被掐死（fresh108 实证：挖路三度
        // 重复启动、wander/descend 交错、855 秒零产出且全程零条技能结束行）；
        // armWatchdog 的点火条件含 action.equals(current)，看门狗永不点火、
        // 异步链真卡死也无超时收场。与 castobsidian 同口径在入口补设。
        current = "mineobsidian";
        int x = intent.intParam("x", 0);
        int y = intent.intParam("y", 0);
        int z = intent.intParam("z", 0);
        int want = Math.max(1, intent.intParam("count", 20));
        BlockPos hint = new BlockPos(x, y, z);
        // 选浇水中心：够得着的露面岩浆源里挑离 NPC 最远的一块（深进湖面）。
        // staging 实证：浇在湖边最近格，水流要逐格爬到对岸、还被先挖的坑
        // 引流，3×3 小池远端结壳拖到 1 分钟开外；浇在够得着的最深处
        // （真玩家也是探身往湖里倒），水流四面铺开、整片 12 秒结齐。
        // 够不着任何一块时退回最近的一块，站位判定会诚实失败交目标层。
        // 【fix254】选源验站位：旧口径只按距离定 surface（够得着的最远、
        // 否则最近），站位留到浇水周期才判——选中的源若是无岸岩浆袋
        // （岩浆封在岩层里、正上方只有气腔、同层环全是实心岩；fresh107
        // 账本点 (-63,-55,-38) 只读探针逐格定谳），周期一开即以「无安全
        // 站位」秒败，目标层换 hint 邻点重派又重扫回同一格、同点同因
        // 连烧两路连败计数。改为：盒内露面源全收成候选，按旧偏好序
        // （≤5.5 格内最远优先、其余按距近）逐个验站位（天然或壁龛合成，
        // 见 findObsidianStand/findObsidianNicheStand），取第一个有站位
        // 的；全无站位才按旧文案收场（lava= 记旧偏好首选格，目标层
        // 结局口径一字不变）；盒内无源仍走下方 fix243 成形回收。
        List<BlockPos> sources = new ArrayList<>();
        List<Double> sourceDist = new ArrayList<>();
        BlockPos nearest = null;
        double bestNear = Double.MAX_VALUE;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    // 【fix242】旧代码三层循环把 dz/dy 写反：offset(dx, dy, dz) 的
                    // 第二参实际吃的是名为 dz 的 ±6、第三参吃名为 dy 的 ±2——扫描盒
                    // 成了「上下 ±6、南北 ±2」的窄高盒，提示点南北 3 格开外的露面
                    // 岩浆恒判「无露面」（83 棒实验场复盘时先误判为夹具问题、逐格
                    // 对照目标层 scanExposedLava 的正确盒形才定位）。改回 x/z ±6、
                    // y ±2，与目标层扫描同盒形。
                    BlockPos p = hint.offset(dx, dy, dz);
                    BlockState s = npc.level().getBlockState(p);
                    if (!s.is(Blocks.LAVA) || !s.getFluidState().isSource()
                            || !npc.level().getBlockState(p.above()).isAir()) {
                        continue;
                    }
                    double d = npc.position().distanceToSqr(Vec3.atCenterOf(p));
                    sources.add(p);
                    sourceDist.add(d);
                    if (d < bestNear) {
                        bestNear = d;
                        nearest = p;
                    }
                }
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> {
            double da = sourceDist.get(a);
            double db = sourceDist.get(b);
            boolean ra = da <= 5.5D * 5.5D;
            boolean rb = db <= 5.5D * 5.5D;
            if (ra != rb) {
                return ra ? -1 : 1;
            }
            return ra ? Double.compare(db, da) : Double.compare(da, db);
        });
        BlockPos surface = null;
        for (int idx : order) {
            BlockPos cand = sources.get(idx);
            if (findObsidianStand(cand, cand.above()) != null
                    || findObsidianNicheStand(cand, cand.above()) != null) {
                surface = cand;
                break;
            }
        }
        if (surface == null && !sources.isEmpty()) {
            BlockPos firstChoice = sources.get(order.get(0));
            finish(id, "mineobsidian", "mineobsidian 未挖到 reason=岩浆边无安全站位 lava="
                    + firstChoice.toShortString());
            return true;
        }
        if (surface == null) {
            // 【fix243】无露面岩浆但提示点周边有已成形黑曜石：多半是上一轮
            // 浇水刚成壳、目标层改派别的技能把挖壳状态机掐断（runId 一换续跑
            // 即失效）留下的孤儿（83 棒实验场 83o 实证：浇水 2 秒后目标层判
            // 「附近无岩浆」走 fix111 换位，4 块成形黑曜石无人收）。直接进
            // 挖壳状态机收掉，锚点口径同 repour 的 next.above()。
            BlockPos formed = null;
            double bestFormed = Double.MAX_VALUE;
            for (int dx = -6; dx <= 6; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -6; dz <= 6; dz++) {
                        BlockPos p = hint.offset(dx, dy, dz);
                        if (!npc.level().getBlockState(p).is(Blocks.OBSIDIAN)) {
                            continue;
                        }
                        double d = npc.position().distanceToSqr(Vec3.atCenterOf(p));
                        if (d < bestFormed) {
                            bestFormed = d;
                            formed = p;
                        }
                    }
                }
            }
            if (formed != null) {
                LOG.info("[AIV][SKILL] " + npc.npcName()
                        + " obsidian-route | step=recover-formed pos=" + formed.toShortString()
                        + "（fix243）");
                mineObsidianNext(id, want, 0, new HashSet<>(), formed.above(), 0, 0, 3);
                return true;
            }
            finish(id, "mineobsidian", "mineobsidian 未挖到 reason=提示点附近无露面岩浆源");
            return true;
        }
        // 【fix254b】看门狗放宽至 descend 同档（×15，约 10 分钟）：
        // 默认 800 tick（40 秒）只够地表小池的近身活，深层链一趟
        // 「挖路 20+ 格→浇水等壳 10 秒→耐心扫壳 30 秒→逐块挖」必然
        // 超 40 秒——103 棒实验场实证：浇水/退避均正常，看门狗在
        // 扫壳耐心期把整链腰斩。目标层另有 300 秒无进展收场兜底，
        // 技能看门狗只防真卡死，不抢正常长活。
        armWatchdog(id, "mineobsidian", SKILL_TIMEOUT_TICKS * 15);
        startObsidianPourCycle(id, surface, want, 0, 3);
        return true;
    }

    /**
     * 一轮「浇→等壳→挖」：走到 lavaCell 岸边站位、水浇到其上方一格、
     * 等结壳后进挖壳状态机。本趟最多 4 轮（含首轮，cyclesLeft 递减），
     * 每轮挖尽后由状态机舀水换下一片岩浆重浇——staging 实证：结壳在
     * 有人边挖边等时会被挖坑引流拖慢（整片 30–60 秒），按轮推进比
     * 死等一片可靠；纯等壳约 10 秒/片。
     */
    private void startObsidianPourCycle(int id, BlockPos lavaCell, int want, int mined,
                                        int cyclesLeft) {
        if (runId != id) {
            return;
        }
        // 浇水前的位置 = 退避点：浇完立刻退回这里等壳。站在浇水点岸边等壳
        // 会被漫开的水流一路冲走（staging 三度实证被冲下地形），真玩家
        // 浇完水也会退后两步看着它结壳。
        final BlockPos homeStand = npc.blockPosition();
        final BlockPos pourAt = lavaCell.above();
        BlockPos stand = findObsidianStand(lavaCell, pourAt);
        boolean niche = false;
        if (stand == null) {
            // 【fix254】无天然岸 → 壁龛合成站位（岩环上挖一格站进去，
            // 玩家口径）；合成格是实心岩，接近走既有 A*→挖路兜底掘进，
            // 末端把壁龛格本身挖开站入，与天然站位共用后续全链。
            stand = findObsidianNicheStand(lavaCell, pourAt);
            if (stand != null) {
                niche = true;
                LOG.info("[AIV][SKILL] " + npc.npcName()
                        + " obsidian-route | step=niche-stand pos=" + stand.toShortString()
                        + " lava=" + lavaCell.toShortString()
                        + "（fix254：无天然岸，挖壁龛造站位）");
            }
        }
        final boolean nicheStand = niche;
        if (stand == null) {
            finish(id, "mineobsidian", mined > 0
                    ? "mineobsidian 完成 mined=" + mined + " want=" + want
                    : "mineobsidian 未挖到 reason=岩浆边无安全站位 lava="
                    + lavaCell.toShortString());
            return;
        }
        Runnable pour = () -> {
            if (runId != id) {
                return;
            }
            if (countItem("water_bucket") <= 0) {
                finish(id, "mineobsidian", mined > 0
                        ? "mineobsidian 完成 mined=" + mined + " want=" + want
                        : "mineobsidian 未挖到 reason=到湖边时水桶已空");
                return;
            }
            net.minecraft.world.item.DispensibleContainerItem bucketItem =
                    (net.minecraft.world.item.DispensibleContainerItem) Items.WATER_BUCKET;
            boolean placed = bucketItem.emptyContents(null, npc.level(), pourAt, null);
            if (!placed) {
                placed = bucketItem.emptyContents(null, npc.level(), lavaCell, null);
            }
            if (!placed) {
                finish(id, "mineobsidian", mined > 0
                        ? "mineobsidian 完成 mined=" + mined + " want=" + want
                        : "mineobsidian 未挖到 reason=浇水失败 pour="
                        + pourAt.toShortString());
                return;
            }
            replaceOneItem("water_bucket", "bucket");
            LOG.info("[AIV][SKILL] " + npc.npcName() + " obsidian-route | step=pour lava="
                    + lavaCell.toShortString() + " pour=" + pourAt.toShortString()
                    + " want=" + want + " mined=" + mined + " cyclesLeft=" + cyclesLeft);
            // 浇完退回退避点等壳（约 10 秒）：站在浇水岸边会被漫开的水流
            // 冲走（守位回走也顶不住持续水流，staging 三度实证被冲下
            // 地形）；退避点是浇水前站的位置，水流漫不到。等满再开挖：
            // 边结边挖时先挖出的坑会让上层水流优先灌进坑里、横流断链，
            // 远处结壳被拖慢数倍。真玩家也是浇完退后看着壳结硬再挖。
            Runnable beginMining = () -> Bukkit.getScheduler().runTaskLater(plugin,
                    () -> mineObsidianNext(id, want, mined, new HashSet<>(), pourAt, 0,
                            cyclesLeft, 3), 200L);
            if (homeStand.equals(npc.blockPosition())) {
                beginMining.run();
            } else if (nicheStand) {
                // 【fix254c】壁龛站位浇完不退避：密封岩浆袋里挖壳的站位
                // 只有壁龛本身（壳面站位全被水占、岩环站位是实心岩——
                // 103 棒实验场实证：退回出发点后挖壳扫描的候选逐个因
                // 「无站位」被跳过、耐心耗尽 mined=0 收场）。壁龛是
                // 1×2 封闭格，漫水冲不走人（选点又以对角格优先、浇水
                // 点的水流漫不进对角格），就地等壳、伸手挖脚边壳面，
                // 正是玩家在岩浆袋边的做法。漂移兜底仍有 reanchor。
                LOG.info("[AIV][SKILL] " + npc.npcName()
                        + " obsidian-route | step=pour-hold stand="
                        + npc.blockPosition().toShortString()
                        + "（fix254c：壁龛内不退避，就地等壳开挖）");
                beginMining.run();
            } else {
                PathFinder.Result back = PathFinder.find(npc.level(), npc.blockPosition(),
                        homeStand, !npc.wearingLeatherBoots());
                if (back.found()) {
                    npc.followPath(back.path(), false, beginMining, beginMining);
                } else {
                    beginMining.run();
                }
            }
        };
        if (stand.equals(npc.blockPosition())) {
            pour.run();
        } else {
            PathFinder.Result path = PathFinder.find(npc.level(), npc.blockPosition(), stand,
                    !npc.wearingLeatherBoots());
            if (!path.found()) {
                // 【fix222】走路 A* 无路 ≠ 到不了：提示点在地下深处时站位
                // 隔着整片岩层，walk-only 必然 found=false，旧版就地秒收
                // 「到不了岩浆边」（farm196 实证：人已到 (262,-52,-184)、
                // 站位 (237,-53,-167) 只隔 30 格深板岩被秒拒、目标层一秒
                // 一败热循环）。改走挖路兜底（fillbucket 同款成例）：直线
                // 挖到站位再续浇。挖路自身失败才落回原来的诚实收尾。
                startObsidianDigAdvance(id, stand, pour, mined, want);
                return;
            }
            npc.followPath(path.path(), false, pour, () -> {
                if (runId == id) {
                    finish(id, "mineobsidian", mined > 0
                            ? "mineobsidian 完成 mined=" + mined + " want=" + want
                            : "mineobsidian 未挖到 reason=前往岩浆边失败");
                }
            });
        }
    }

    /** 岩浆边安全站位：够得着浇水点、脚下与头顶无流体、不在岩浆上。
     * 高度优先：先找脚比岩浆面高 2 格的（站在高一格的岸上，水漫不到），
     * 再退高 1 格/同层/低层——浇出的水在岩浆面上层横流，同层岸边站位
     * 会被水流推走（staging 实证被冲下地形）。 */
    /**
     * 【fix222】浇水周期站位的挖路兜底：走路 A* 找不到路（站位隔着整片
     * 岩层）时，照 fillbucket startFillBucketMining 的成例朝 stand 直线
     * 挖过去——主轴推进、每步挖掉当前头顶/目标脚格/目标头格、跟上一步；
     * fix74 同款位移监测（40 步一查、连两次没挪窝收手）+ 240 步上限，
     * 挖不动以原词汇诚实收尾。岩浆守卫：目标格本身是流体、或待挖块
     * 正上方是岩浆（挖开会灌进通道）→ 立刻停步收手，绝不挖穿液体层；
     * 站位本身贴岩浆是 findObsidianStand 的设计内口径（离岩浆 ≤3 格、
     * 末格必然岩浆邻接——真玩家挖到湖边同样如此），不拦。抵达（同格
     * 或距 ≤2）自停本定时器并续跑 pour。
     */
    /** 【fix254d】挖路水平步的落点是否无支撑：脚格下方是流体，或脚格与下方都无碰撞（悬空）。 */
    private boolean obsidianStepUnsupported(BlockPos targetFeet) {
        if (!npc.level().getBlockState(targetFeet.below()).getFluidState().isEmpty()) {
            return true;
        }
        return npc.level().getBlockState(targetFeet).getCollisionShape(npc.level(), targetFeet).isEmpty()
                && npc.level().getBlockState(targetFeet.below())
                        .getCollisionShape(npc.level(), targetFeet.below()).isEmpty();
    }

    private void startObsidianDigAdvance(int id, BlockPos stand, Runnable pour,
                                         int mined, int want) {
        LOG.info("[AIV][SKILL] " + npc.npcName() + " 黑曜石挖路（fix222） | stand="
                + stand.toShortString() + "（走路 A* 无路，改直线挖进）");
        final int[] stepsDone = {0};
        final BlockPos[] lastCheckPos = {npc.blockPosition()};
        final int[] stallChecks = {0};
        final org.bukkit.scheduler.BukkitTask[] self = new org.bukkit.scheduler.BukkitTask[1];
        armWatchdog(id, "mineobsidian", SKILL_TIMEOUT_TICKS * 15); // 【fix254b】同入口处放宽口径
        self[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (runId != id) {
                return;
            }
            BlockPos feet = npc.blockPosition();
            // 【fix254b】抵达口径分站位虚实：天然站位（空气脚位）距
            // ≤√2 提前抵达没问题（剩下一格平地可走）；壁龛合成站位是
            // 实心格，必须掘进到身体真进格内才算抵达——103 棒实验场
            // 实证：旧口径在壁龛前一格（distSqr=1）即报抵达浇水，
            // 壁龛格始终没挖开，挖壳阶段的 A* 仍被这格实心岩挡死。
            if (feet.equals(stand) || (feet.distSqr(stand) <= 2.0D
                    && PathFinder.isStandable(npc.level(), stand))) {
                LOG.info("[AIV][SKILL] " + npc.npcName() + " 黑曜石挖路抵达（fix222） | stand="
                        + stand.toShortString() + " 步数=" + stepsDone[0]);
                self[0].cancel();
                pour.run();
                return;
            }
            if (!npc.level().getBlockState(feet).getFluidState().isEmpty()
                    || !npc.level().getBlockState(feet.above()).getFluidState().isEmpty()) {
                finish(id, "mineobsidian", mined > 0
                        ? "mineobsidian 完成 mined=" + mined + " want=" + want
                        : "mineobsidian 未挖到 reason=挖路陷入流体 stand="
                        + stand.toShortString());
                return;
            }
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
                finish(id, "mineobsidian", mined > 0
                        ? "mineobsidian 完成 mined=" + mined + " want=" + want
                        : "mineobsidian 未挖到 reason=挖路找不到方向 stand="
                        + stand.toShortString());
                return;
            }
            BlockPos targetFeet = feet.offset(mx, my, mz);
            BlockPos targetHead = targetFeet.above();
            // 岩浆/流体守卫：目标格是流体、或待挖块正上方压着岩浆，停步。
            if (!npc.level().getBlockState(targetFeet).getFluidState().isEmpty()
                    || !npc.level().getBlockState(targetHead).getFluidState().isEmpty()
                    || npc.level().getBlockState(targetHead.above()).is(Blocks.LAVA)) {
                finish(id, "mineobsidian", mined > 0
                        ? "mineobsidian 完成 mined=" + mined + " want=" + want
                        : "mineobsidian 未挖到 reason=挖路遇流体层收手 stand="
                        + stand.toShortString());
                return;
            }
            // 【fix254d】落点支撑检查（仅水平步）：目标脚格下方是流体、
            // 或脚格与下方都无碰撞（悬空）时，直线迈步会踏进岩浆/掉下
            // 空腔（103 棒实验场实证：挖路横穿露天池面上空去对岸站位，
            // 半路踏空落浆以「陷入流体」收场）。真玩家贴岸绕行：先试
            // 另一水平轴朝站位的一步（绕池角），其落点须过同样的流体
            // 守卫与支撑检查；两轴皆无路才按无支撑诚实收手。
            if (my == 0 && obsidianStepUnsupported(targetFeet)) {
                BlockPos altFeet = null;
                if (mx != 0 && dz != 0) {
                    altFeet = feet.offset(0, 0, (int) Math.signum(dz));
                } else if (mz != 0 && dx != 0) {
                    altFeet = feet.offset((int) Math.signum(dx), 0, 0);
                }
                boolean altOk = altFeet != null
                        && npc.level().getBlockState(altFeet).getFluidState().isEmpty()
                        && npc.level().getBlockState(altFeet.above()).getFluidState().isEmpty()
                        && !npc.level().getBlockState(altFeet.above(2)).is(Blocks.LAVA)
                        && !obsidianStepUnsupported(altFeet);
                if (altOk) {
                    targetFeet = altFeet;
                    targetHead = altFeet.above();
                } else {
                    finish(id, "mineobsidian", mined > 0
                            ? "mineobsidian 完成 mined=" + mined + " want=" + want
                            : "mineobsidian 未挖到 reason=挖路落点无支撑 stand="
                            + stand.toShortString());
                    return;
                }
            }
            npc.mineWithBestTool(feet.above());
            npc.mineWithBestTool(targetFeet);
            npc.mineWithBestTool(targetHead);
            npc.walkTo(new Vec3(targetFeet.getX() + 0.5D, targetFeet.getY(),
                    targetFeet.getZ() + 0.5D), false, null);
            stepsDone[0]++;
            if (stepsDone[0] % 40 == 0) {
                if (feet.distSqr(lastCheckPos[0]) < 1.0D) {
                    if (++stallChecks[0] >= 2) {
                        finish(id, "mineobsidian", mined > 0
                                ? "mineobsidian 完成 mined=" + mined + " want=" + want
                                : "mineobsidian 未挖到 reason=挖路无位移 stand="
                                + stand.toShortString());
                        return;
                    }
                } else {
                    stallChecks[0] = 0;
                    lastCheckPos[0] = feet;
                }
            }
            if (stepsDone[0] > 240) {
                finish(id, "mineobsidian", mined > 0
                        ? "mineobsidian 完成 mined=" + mined + " want=" + want
                        : "mineobsidian 未挖到 reason=到不了岩浆边 stand="
                        + stand.toShortString());
            }
        }, 1L, 2L);
        tasks.add(self[0]);
    }

    private BlockPos findObsidianStand(BlockPos lavaCell, BlockPos pourAt) {
        for (int dy : new int[] {2, 1, 0, -1}) {
            // 【fix254d】高度档（dy 优先序）内取距 NPC 最近的一格，
            // 不再按扫描序取第一格：旧口径常选中池子对岸的远岸站位，
            // 逼挖路横穿池面/整片洞室（103 棒实验场实证踏空落浆）。
            // 玩家选岸先看高低、再看远近，同档取近是自然口径。
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos p = lavaCell.offset(dx, dy, dz);
                    if (p.equals(lavaCell) || p.equals(pourAt)
                            || npc.level().getBlockState(p).is(Blocks.LAVA)
                            || !npc.level().getBlockState(p).getFluidState().isEmpty()
                            || !npc.level().getBlockState(p.above()).getFluidState().isEmpty()
                            || !PathFinder.isStandable(npc.level(), p)) {
                        continue;
                    }
                    Vec3 eye = new Vec3(p.getX() + 0.5, p.getY() + 1.62, p.getZ() + 0.5);
                    if (eye.distanceToSqr(Vec3.atCenterOf(pourAt)) <= 4.6 * 4.6) {
                        double d = npc.position().distanceToSqr(Vec3.atCenterOf(p));
                        if (d < bestD) {
                            bestD = d;
                            best = p;
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /**
     * 【fix254】壁龛合成站位：无岸岩浆袋（岩浆封在岩层里、正上方只有
     * 气腔、同层环全是实心岩——fresh107 账本点探针定谳）里天然站位
     * 恒为空，真玩家的做法是在岩环上挖出一格壁龛站进去浇水。合成
     * 口径：脚位只取岩浆格的水平相邻环（dx/dz∈{-1,0,1} 除中心）、
     * 岩浆面上一层（dy=+1，与岩环同顶面）——103 棒实验场首验教训：
     * 候选放宽到 ±3 时选中了离 NPC 最近但隔着整片岩层、与壳面不
     * 相邻的格，浇水虽成、挖壳阶段够不着壳（壳面站位仍在岩环上）。
     * 玩家挖壁龛一定紧贴岩浆格：浇完脚边即是壳、伸手可挖。脚下格
     * 须有碰撞且非岩浆/非流体（实心岩环，挖开脚位后踩得住）；脚格
     * 与头格非流体/非岩浆/非基岩（实心可挖或已是空气，掘进末端由
     * 挖路逐格挖开）；头格再上一格非岩浆（与挖路守卫同口径，防挖
     * 穿后顶上岩浆灌入）；脚位同层四邻无岩浆（防更高一层岩浆面
     * 侧灌）。眼距口径与天然站位一致（≤4.6）。候选先对角后正交
     * （【fix254c】对角格不迎浇水点的正交水流、壁龛内保持干燥，
     * 且对脚边壳角的挖掘距离与正交格同级），同类内按距 NPC 近序
     * （掘进成本最低优先）。返回的格可能是实心格——这正是与天然
     * 站位的分工：天然站位走 A* 与走路，合成站位靠 fix222 挖路
     * 掘进抵达（挖路对实心站位会挖进格内才算抵达，见彼处注释）。
     */
    private BlockPos findObsidianNicheStand(BlockPos lavaCell, BlockPos pourAt) {
        BlockPos best = null;
        boolean bestDiag = false;
        double bestD = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos p = lavaCell.offset(dx, 1, dz);
                if (p.equals(pourAt)
                        || npc.level().getBlockState(p).is(Blocks.LAVA)
                        || !npc.level().getBlockState(p).getFluidState().isEmpty()
                        || !npc.level().getBlockState(p.above()).getFluidState().isEmpty()
                        || npc.level().getBlockState(p).is(Blocks.BEDROCK)
                        || npc.level().getBlockState(p.above()).is(Blocks.BEDROCK)
                        || npc.level().getBlockState(p.above(2)).is(Blocks.LAVA)) {
                    continue;
                }
                BlockState support = npc.level().getBlockState(p.below());
                if (support.is(Blocks.LAVA)
                        || !support.getFluidState().isEmpty()
                        || support.is(net.minecraft.tags.BlockTags.LEAVES)
                        || support.getCollisionShape(npc.level(), p.below()).isEmpty()) {
                    continue;
                }
                boolean sideLava = false;
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    if (npc.level().getBlockState(p.relative(dir)).is(Blocks.LAVA)) {
                        sideLava = true;
                        break;
                    }
                }
                if (sideLava) {
                    continue;
                }
                Vec3 eye = new Vec3(p.getX() + 0.5, p.getY() + 1.62, p.getZ() + 0.5);
                if (eye.distanceToSqr(Vec3.atCenterOf(pourAt)) > 4.6 * 4.6) {
                    continue;
                }
                boolean diag = dx != 0 && dz != 0;
                double d = npc.position().distanceToSqr(Vec3.atCenterOf(p));
                if (best == null || (diag && !bestDiag)
                        || (diag == bestDiag && d < bestD)) {
                    bestD = d;
                    bestDiag = diag;
                    best = p;
                }
            }
        }
        return best;
    }

    /**
     * 被水流冲离工位时走回浇水点岸边站位再续挖（staging 实证：浇水后
     * 站在岸边的身体会被漫过的水流推下地形边缘，扫描还在绕浇水点转、
     * 人却越漂越远）。回位次数有上限，耗尽按已挖数收尾。
     */
    private void reanchorObsidian(int id, int want, int mined, Set<BlockPos> skipped,
                                  BlockPos pourAt, int cyclesLeft, int anchorsLeft) {
        BlockPos stand = findObsidianStand(pourAt.below(), pourAt);
        if (stand == null) {
            LOG.info("[AIV][SKILL] " + npc.npcName()
                    + " obsidian-route | step=reanchor result=无站位可回，按已挖数收尾");
            scoopMineObsidianWater(id, mined, want, pourAt);
            return;
        }
        if (stand.equals(npc.blockPosition())) {
            mineObsidianNext(id, want, mined, skipped, pourAt, 0, cyclesLeft, anchorsLeft);
            return;
        }
        PathFinder.Result path = PathFinder.find(npc.level(), npc.blockPosition(), stand,
                !npc.wearingLeatherBoots());
        if (!path.found()) {
            LOG.info("[AIV][SKILL] " + npc.npcName()
                    + " obsidian-route | step=reanchor result=寻路回位失败，按已挖数收尾 from="
                    + npc.blockPosition().toShortString());
            scoopMineObsidianWater(id, mined, want, pourAt);
            return;
        }
        LOG.info("[AIV][SKILL] " + npc.npcName() + " obsidian-route | step=reanchor stand="
                + stand.toShortString() + " anchorsLeft=" + anchorsLeft);
        npc.followPath(path.path(), false,
                () -> mineObsidianNext(id, want, mined, skipped, pourAt, 0, cyclesLeft,
                        anchorsLeft),
                () -> scoopMineObsidianWater(id, mined, want, pourAt));
    }

    /**
     * 本片挖尽后换下一片：找 NPC 附近下一块露面岩浆源——其上方是水流的
     * 说明壳还在结（旧水流未尽），直接回挖壳状态机在那片继续等挖；上方
     * 是空气的才舀水重浇。找不到下一片/水源丢失 → 按已挖数收尾。
     */
    private void repourObsidianCycle(int id, int want, int mined, int cyclesLeft,
                                     BlockPos oldPourAt) {
        if (runId != id) {
            return;
        }
        BlockPos next = null;
        double bestD = Double.MAX_VALUE;
        BlockPos center = npc.blockPosition();
        for (int dx = -14; dx <= 14; dx++) {
            for (int dz = -14; dz <= 14; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    BlockState s = npc.level().getBlockState(p);
                    if (!s.is(Blocks.LAVA) || !s.getFluidState().isSource()) {
                        continue;
                    }
                    BlockState above = npc.level().getBlockState(p.above());
                    if (!above.isAir() && !above.is(Blocks.WATER)) {
                        continue;
                    }
                    double d = npc.position().distanceToSqr(Vec3.atCenterOf(p));
                    if (d < bestD) {
                        bestD = d;
                        next = p;
                    }
                }
            }
        }
        if (next == null) {
            scoopMineObsidianWater(id, mined, want, oldPourAt);
            return;
        }
        if (npc.level().getBlockState(next.above()).is(Blocks.WATER)) {
            LOG.info("[AIV][SKILL] " + npc.npcName() + " obsidian-route | step=resume lava="
                    + next.toShortString() + " mined=" + mined
                    + " reason=上方水流未尽、结壳进行中");
            mineObsidianNext(id, want, mined, new HashSet<>(), next.above(), 0, cyclesLeft, 3);
            return;
        }
        if (countItem("water_bucket") <= 0 && !scoopObsidianSource(oldPourAt)) {
            finish(id, "mineobsidian", mined > 0
                    ? "mineobsidian 完成 mined=" + mined + " want=" + want
                    : "mineobsidian 未挖到 reason=水源丢失");
            return;
        }
        LOG.info("[AIV][SKILL] " + npc.npcName() + " obsidian-route | step=repour lava="
                + next.toShortString() + " mined=" + mined + " cyclesLeft=" + cyclesLeft);
        startObsidianPourCycle(id, next, want, mined, cyclesLeft);
    }

    /** 把 near 附近够得着的水源舀回桶里（fix70 同款模拟取水）；成功返回 true。 */
    private boolean scoopObsidianSource(BlockPos near) {
        if (countItem("bucket") <= 0) {
            return countItem("water_bucket") > 0;
        }
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos p = near.offset(dx, dy, dz);
                    BlockState s = npc.level().getBlockState(p);
                    if (s.is(Blocks.WATER) && s.getFluidState().isSource()
                            && withinMineReach(p)) {
                        npc.level().setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                        replaceOneItem("bucket", "water_bucket");
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " obsidian-route | step=scoop water=" + p.toShortString());
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 挖壳状态机：扫浇水点周边黑曜石，够得着直接挖、够不着走上壳面再挖。 */
    private void mineObsidianNext(int id, int want, int mined, Set<BlockPos> skipped,
                                  BlockPos pourAt, int emptyScans, int cyclesLeft,
                                  int anchorsLeft) {
        if (runId != id) {
            return;
        }
        if (npc.level().getBlockState(npc.blockPosition()).is(Blocks.LAVA)
                || npc.getRemainingFireTicks() > 40) {
            finish(id, "mineobsidian", "mineobsidian 中止 reason=着火或落入岩浆 mined=" + mined);
            return;
        }
        // 被水流冲离工位（掉下地形/漂出本片）：先回位再续挖。
        BlockPos feetNow = npc.blockPosition();
        double driftSq = (feetNow.getX() - pourAt.getX()) * (double) (feetNow.getX() - pourAt.getX())
                + (feetNow.getZ() - pourAt.getZ()) * (double) (feetNow.getZ() - pourAt.getZ());
        if ((feetNow.getY() < pourAt.getY() - 2 || driftSq > 121.0D) && anchorsLeft > 0) {
            reanchorObsidian(id, want, mined, skipped, pourAt, cyclesLeft, anchorsLeft - 1);
            return;
        }
        if (mined >= want) {
            scoopMineObsidianWater(id, mined, want, pourAt);
            return;
        }
        List<BlockPos> cands = new ArrayList<>();
        for (int dx = -9; dx <= 9; dx++) {
            for (int dz = -9; dz <= 9; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos p = pourAt.offset(dx, dy, dz);
                    if (skipped.contains(p) || p.equals(npc.blockPosition().below())) {
                        continue;
                    }
                    if (npc.level().getBlockState(p).is(Blocks.OBSIDIAN)) {
                        cands.add(p);
                    }
                }
            }
        }
        // 排序：近的先挖，但浇水格正下方那块壳必须最后挖——它是水源的
        // 立足格，先挖掉水源会掉进坑里、上层横流断掉，整片结壳就此停摆
        // （staging 实证：不留它时每趟只挖到 2 块）。真玩家也会留着水源
        // 底下那块最后收。
        BlockPos pourBelow = pourAt.below();
        cands.sort((a, b) -> {
            int pa = a.equals(pourBelow) ? 1 : 0;
            int pb = b.equals(pourBelow) ? 1 : 0;
            if (pa != pb) {
                return Integer.compare(pa, pb);
            }
            return Double.compare(npc.position().distanceToSqr(Vec3.atCenterOf(a)),
                    npc.position().distanceToSqr(Vec3.atCenterOf(b)));
        });
        if (cands.isEmpty()) {
            // 结壳是渐进的，且有人在场边挖边等时会被挖坑引流拖慢
            // （staging 实证整片可达 30–60 秒）：统一耐心 30 轮扫描
            // （约 30 秒），挖尽后还有轮次就换下一片重浇。
            int patience = 30;
            if (emptyScans < patience) {
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> mineObsidianNext(id, want, mined, skipped, pourAt, emptyScans + 1, cyclesLeft, anchorsLeft), 20L);
                return;
            }
            if (cyclesLeft > 0) {
                repourObsidianCycle(id, want, mined, cyclesLeft - 1, pourAt);
            } else if (mined > 0) {
                scoopMineObsidianWater(id, mined, want, pourAt);
            } else {
                finish(id, "mineobsidian", "mineobsidian 未挖到 reason=浇水后未形成黑曜石壳");
            }
            return;
        }
        BlockPos target = cands.get(0);
        if (withinMineReach(target)) {
            boolean ok = npc.mineWithBestTool(target);
            if (!ok) {
                skipped.add(target);
            }
            int m2 = mined + (ok ? 1 : 0);
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> mineObsidianNext(id, want, m2, skipped, pourAt, 0, cyclesLeft, anchorsLeft), 2L);
            return;
        }
        // 走上壳面再挖：站位 = 目标相邻实心格（壳面/岸石）的上方一格。
        // 旧写法拿目标同层格当站位——那格要么是实心黑曜石（站不进去）、
        // 要么是岩浆格（isStandable 只看碰撞，岩浆无碰撞会被误判可站，
        // staging 实证 NPC 被引到岩浆格站位、掉出池外 mined=0 收场）。
        BlockPos stand = null;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos footing = target.relative(dir);
            BlockState footingState = npc.level().getBlockState(footing);
            if (!footingState.getFluidState().isEmpty()
                    || footingState.getCollisionShape(npc.level(), footing).isEmpty()) {
                continue;
            }
            BlockPos s = footing.above();
            if (s.equals(pourAt)
                    || !npc.level().getBlockState(s).getFluidState().isEmpty()
                    || !PathFinder.isStandable(npc.level(), s)) {
                continue;
            }
            stand = s;
            break;
        }
        if (stand == null) {
            skipped.add(target);
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> mineObsidianNext(id, want, mined, skipped, pourAt, 0, cyclesLeft, anchorsLeft), 1L);
            return;
        }
        PathFinder.Result shellPath = PathFinder.find(npc.level(), npc.blockPosition(), stand,
                !npc.wearingLeatherBoots());
        if (!shellPath.found()) {
            skipped.add(target);
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> mineObsidianNext(id, want, mined, skipped, pourAt, 0, cyclesLeft, anchorsLeft), 1L);
            return;
        }
        npc.followPath(shellPath.path(), false,
                () -> mineObsidianNext(id, want, mined, skipped, pourAt, 0, cyclesLeft, anchorsLeft),
                () -> {
                    skipped.add(target);
                    mineObsidianNext(id, want, mined, skipped, pourAt, 0, cyclesLeft, anchorsLeft);
                });
    }

    /** 收尾：浇出去的水源还在够得着处就舀回桶里，一桶水循环用。 */
    private void scoopMineObsidianWater(int id, int mined, int want, BlockPos pourAt) {
        scoopObsidianSource(pourAt);
        finish(id, "mineobsidian", "mineobsidian 完成 mined=" + mined + " want=" + want);
    }

    /**
     * 浇铸一个黑曜石：水浇岩浆源 → 用钻石镐挖掉 → 把水收回桶里。
     * 【fix105 起降为兜底路线：主路是 runMineObsidian 的挖矿路线，本路线只在
     * 挖矿路线连败 3 次后由目标层切换过来；产物同样要钻石镐才挖得下，
     * 无钻镐时目标层直接交规划层补镐、不会派到这里。】
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
        // 【fix361】重燃模式：只点火、不补框，0 黑曜石消耗，故跳过黑曜石检查。
        boolean relight = intent.intParam("relight", 0) == 1;
        if (!relight && countItem("obsidian") < 10) {
            finish(id, "buildportal", "黑曜石不足 10");
            return true;
        }
        if (countItem("flint_and_steel") <= 0) {
            finish(id, "buildportal", "缺少打火石");
            return true;
        }
        // 【fix362·280 棒】重燃模式预数缺格：缺 0 格纯重燃；缺 1 格补框重燃；
        // 缺 ≥2 格直接收场，不耗黑曜石（落回浇铸链）。
        final int relightGapCount;
        if (relight) {
            int gaps = 0;
            for (int i = 0; i < PORTAL_FRAME.length; i++) {
                if (!npc.level().getBlockState(framePosition(origin, alongX, i)).is(Blocks.OBSIDIAN)) {
                    gaps++;
                }
            }
            relightGapCount = gaps;
            if (gaps >= 2) {
                finish(id, "buildportal", "重燃失败：框体缺失（fix361）");
                return true;
            }
        } else {
            relightGapCount = 0;
        }
        current = "buildportal";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " buildportal 开始 | origin="
                + origin.toShortString() + " axis=" + (alongX ? "x" : "z")
                + (relight ? " 重燃（fix361）" + (relightGapCount == 1 ? " 补框（fix362）" : "") : ""));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int step;
            /** 【fix362】单格补框是否已补（每轮技能只补一格）。 */
            private boolean fix362Patched;

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
                    // 【fix362·280 棒】重燃模式：预数仅缺 1 格时补 1 块黑曜石
                    // 再点火；缺格在技能中途新出现（第 2 格）时收场，不硬补。
                    if (relight) {
                        if (relightGapCount == 1 && !fix362Patched) {
                            if (!consumeItem("obsidian", 1)) {
                                finish(id, "buildportal", "黑曜石中途不足");
                                return;
                            }
                            npc.level().setBlockAndUpdate(pos, Blocks.OBSIDIAN.defaultBlockState());
                            fix362Patched = true;
                            LOG.info("[AIV][SKILL] " + npc.npcName() + " 补框一格（fix362） | pos="
                                    + pos.toShortString());
                            return;
                        }
                        finish(id, "buildportal", "重燃失败：框体缺失（fix361）");
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
        // 【fix261】staging 格（可选）：门洞同高侧格，先到这里再平走入门洞。
        final double sx = intent.doubleParam("sx", Double.NaN);
        final double sy = intent.doubleParam("sy", Double.NaN);
        final double sz = intent.doubleParam("sz", Double.NaN);
        final boolean hasStaging = !Double.isNaN(sx) && !Double.isNaN(sz);
        current = "enterportal";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " enterportal 开始 | 目标="
                + String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", x, y, z)
                + (hasStaging ? " staging=" + String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", sx, sy, sz) : "")
                + " 维度=" + startDimension);
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int ticks;
            private int insideTicks = -1;
            private boolean stagingDropped = !hasStaging;
            private int stallSampleTick = -100;
            private double stallX;
            private double stallZ;
            private int stallTicks;
            /** 【fix360】近距 walk 标记是否已打过（每轮技能只打一次）。 */
            private boolean fix360NearWalkLogged;

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
                ++ticks;
                // 【fix261】脚格已在门洞内：立即停步站定，只等原版传送。
                // 继续发 walk 会让跟随层在门洞里反复微调浮动，凑不齐传送
                // 需要的静止站立（112 棒实验场实证：门内浮动 30 秒不传，
                // 平地干净站定 9 秒即传）。站满 200 tick 仍未传送以专有
                // outcome 收场交目标层换轮，不白耗满 600 tick。
                if (npc.level().getBlockState(npc.blockPosition()).is(Blocks.NETHER_PORTAL)) {
                    if (insideTicks < 0) {
                        insideTicks = 0;
                        npc.stopWalking();
                        // 【fix356】进门即清传送冷却：伪玩家维度切换后
                        //   portalCooldown 恒为 10 且不衰减（268 棒 NBT 四读
                        //   皆 10，门内门外均不衰减），原版传送被永久堵住；
                        //   进门清零，让原版 80 tick 传送接手。清零后不再
                        //   被重置（原版重置只针对非零冷却），一次清零即够。
                        npc.setPortalCooldown(0);
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " enterportal 清传送冷却（fix356） | pos="
                                + npc.blockPosition().toShortString());
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " enterportal 已站进门内，停步等传送 | pos="
                                + npc.blockPosition().toShortString());
                    } else {
                        insideTicks++;
                    }
                    if (insideTicks > 200) {
                        finish(id, "enterportal", "已在门内静止 10 秒仍未传送");
                        return;
                    }
                    if (ticks > 600) {
                        finish(id, "enterportal", "超时未传送");
                    }
                    return;
                }
                insideTicks = -1;
                if (ticks > 600) {
                    finish(id, "enterportal", "超时未传送");
                    return;
                }
                // 两段走：未到 staging 先走 staging（同高侧格），到了再走门洞。
                double tx = x;
                double ty = y;
                double tz = z;
                boolean toStaging = false;
                if (!stagingDropped) {
                    double sdx = sx - npc.getX();
                    double sdz = sz - npc.getZ();
                    if (sdx * sdx + sdz * sdz > 0.8D) {
                        tx = sx;
                        ty = sy;
                        tz = sz;
                        toStaging = true;
                    }
                }
                // staging 停滞检测：每 20 tick 采样，无位移累计 200 tick
                // 判定此侧不通，回落直指门洞（兜底不堵死，目标层重试再算）。
                if (toStaging && ticks - stallSampleTick >= 20) {
                    if (stallSampleTick >= 0
                            && Math.abs(npc.getX() - stallX) < 0.05D
                            && Math.abs(npc.getZ() - stallZ) < 0.05D) {
                        stallTicks += 20;
                    } else {
                        stallTicks = 0;
                    }
                    stallSampleTick = ticks;
                    stallX = npc.getX();
                    stallZ = npc.getZ();
                    if (stallTicks >= 200) {
                        stagingDropped = true;
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " enterportal staging 无进展，回落直指门洞 | pos="
                                + npc.blockPosition().toShortString());
                    }
                }
                double dx = tx - npc.getX();
                double dz = tz - npc.getZ();
                double distSq = dx * dx + dz * dz;
                // 【fix360】垫脚站位→进门交接：NPC 已站门洞相邻格（距门洞中心
                //   平方距 ≤0.8）但脚格不是传送门方块时，旧口径（>0.8 才发 walk）
                //   永不发 walk、技能空等 30 秒超时（275 棒 G0d2 实证：6 次超时
                //   零位移；柱心 (351.5,299.5) 距门洞中心 (350.5,299.5) 仅 1.0，
                //   落点在 0.6 带内时平方距常 ≤0.8）。脚格门洞检查在上、进门即
                //   停步等传送，故近距 walk 不致门内浮动；0.04（0.2 格）已在门洞
                //   内部几何内，脚格必为门洞、由上方检查接手。
                if (ticks % 10 == 0 && !npc.isWalking() && distSq > 0.04D) {
                    if (distSq <= 0.8D && !fix360NearWalkLogged) {
                        fix360NearWalkLogged = true;
                        LOG.info("[AIV][SKILL] " + npc.npcName()
                                + " enterportal 进门近距走（fix360） | distSq="
                                + String.format(Locale.ROOT, "%.2f", distSq)
                                + " pos=" + npc.blockPosition().toShortString());
                    }
                    npc.walkTo(new Vec3(tx, ty, tz), false, null);
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

    /**
     * 【fix321】(A) ascend 垫脚专用取块：石系／木板优先（同 pickBuildingBlock 口径），
     * 兜底放行羊毛（16 色全认）。羊毛是玩家垫脚爬坑的常用廉价料；195 棒定谳：
     * 无镐被困地下时包里常有羊毛（床料余量），只认石系／木板会把垫脚口径锁死。
     * 泥土／原木不放行：泥土是种田储备、原木是木镐原料，各留给别的口径。
     */
    private String pickAscendPadBlock() {
        String stone = pickBuildingBlock();
        if (stone != null) {
            return stone;
        }
        for (String wool : List.of("white_wool", "light_gray_wool", "gray_wool", "black_wool",
                "brown_wool", "red_wool", "orange_wool", "yellow_wool", "lime_wool", "green_wool",
                "cyan_wool", "light_blue_wool", "blue_wool", "purple_wool", "magenta_wool",
                "pink_wool")) {
            if (countItem(wool) > 0) {
                return wool;
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

    /**
     * 【fix245】goto 瞬时失败记账：同目标格、同身体位置、100 tick 内累加
     * 连环数；换目标、身体挪窝或超时窗则重新计数。熔断判定在 goto 派发处。
     */
    private void noteGotoFailure(BlockPos goal) {
        long now = npc.level().getGameTime();
        if (goal != null && goal.equals(gotoFuseGoal)
                && npc.blockPosition().equals(gotoFusePos)
                && now - gotoFuseTick <= 100L) {
            gotoFuseStrikes++;
        } else {
            gotoFuseStrikes = 1;
        }
        gotoFuseGoal = goal;
        gotoFusePos = npc.blockPosition();
        gotoFuseTick = now;
    }

    /** 【fix245】goto 到达：熔断账清零。 */
    private void noteGotoSuccess() {
        gotoFuseGoal = null;
        gotoFusePos = null;
        gotoFuseStrikes = 0;
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

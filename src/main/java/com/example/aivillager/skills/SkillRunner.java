package com.example.aivillager.skills;

import com.example.aivillager.intent.Intent;
import com.example.aivillager.npc.FakePlayerBody;
import com.example.aivillager.pathfinding.PathFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
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
                if (goal == null) {
                    lastOutcome = "goto 目标附近没有可站立位置 x=" + Math.round(x) + " z=" + Math.round(z);
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " goto 目标不可站立 | x=" + x + " z=" + z);
                    return false;
                }

                PathFinder.Result result = PathFinder.find(npc.level(), npc.blockPosition(), goal);
                logPath("goto", goal, result);
                if (!result.found()) {
                    lastOutcome = "goto 找不到路径 " + result.detail();
                    return false;
                }

                current = "goto";
                LOG.info("[AIV][SKILL] " + npc.npcName() + " goto 开始 | goal=" + goal.toShortString()
                        + " 节点=" + result.path().size());
                npc.followPath(result.path(), false,
                        () -> finish(id, "goto", "到达 " + goal.toShortString()),
                        () -> finish(id, "goto", "路径中断 " + goal.toShortString()));
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
            // 死局自救：被困在地底且没有任何工具/木料时，按玩家方式重生于出生点。
            case "respawn" -> {
                return runRespawn(id);
            }
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

    // ---------------------------------------------------------------- 具体技能

    /**
     * 主动重生：只用于"镐子全部耗尽 + 地下没有木头"这种真正无法自救的死局。
     *
     * <p>为什么这么做：伪玩家没有客户端，不能自己走回地表；没有镐又挖不开石头。
     * 原版玩家遇到这种情况会主动送死回出生点，生存层复活时会恢复背包并把 NPC 放回地表，
     * 等于给它一次重新发育的机会，而不是永远卡在矿道里刷日志。
     */
    private boolean runRespawn(int id) {
        current = "respawn";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " respawn 主动重生 | " + npc.describe());
        npc.hurtServer((net.minecraft.server.level.ServerLevel) npc.level(),
                npc.damageSources().genericKill(), 1000.0F);
        finish(id, "respawn", "已触发死亡，等待生存层复活");
        return true;
    }

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
        int radius = Math.max(2, Math.min(8, intent.intParam("radius", 8)));
        List<BlockPos> targets = findNearestBlocks(block, anyLog, radius);
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
                PathFinder.Result result = PathFinder.find(npc.level(), npc.blockPosition(), candidate);
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
            lastOutcome = "mine 没有可到达的站立点 候选=" + tried;
            LOG.info("[AIV][SKILL] " + npc.npcName() + " mine 候选全部不可达 | block=" + blockName
                    + " 候选=" + tried);
            return false;
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

    /** 最近的敌对生物（排除玩家和 NPC 自己），走到 2 格内后按 1 秒一下连续攻击。 */
    private boolean runAttack(Intent intent, int id) {
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
        PathFinder.Result approachPath = PathFinder.find(npc.level(), npc.blockPosition(), approach);
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
                                PathFinder.Result chase = PathFinder.find(npc.level(), npc.blockPosition(), chaseGoal);
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
                : PathFinder.find(npc.level(), npc.blockPosition(), approach);
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
                                        npc.blockPosition(), chaseGoal);
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
                        goal, 24000);
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

    /** 放一块垫脚方块（优先圆石/下界岩这类廉价方块）。 */
    private boolean placeBuildingBlockAt(BlockPos pos) {
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

            @Override
            public void run() {
                if (runId != id) {
                    return;
                }
                // 下落/走位过程中不重复挖：等身体真正站到上一格目标再挖下一级。
                if (!npc.onGround) {
                    return;
                }
                if (lastTarget != null) {
                    // 不能用 blockPosition().equals(lastTarget)：walkTo 允许在 0.6 格内停下，
                    // 常常停在原列而不是目标列的格心上，导致永远判不"到达"，每级都要等卡住超时。
                    if (npc.blockPosition().getY() < stepStartY) {
                        lastTarget = null;
                        steps++;
                        failedSteps = 0;
                    } else if (!npc.isWalking()) {
                        // 走位被身体层判超时放弃（掉坑、被方块卡住）：换方向重挖，
                        // 否则这里会一直等一个永远到不了的目标。
                        lastTarget = null;
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
     * 连续 6 次挖不动（比如被基岩层封死）才传送到最近的地表，作为最后的自救手段。
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

            @Override
            public void run() {
                if (runId != id) {
                    return;
                }
                // 空中（起跳/下落）时不挖下一格，等站稳再说。
                if (!npc.onGround) {
                    return;
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
                npc.mineWithBestTool(currentCeiling);
                npc.mineWithBestTool(targetFeet);
                npc.mineWithBestTool(targetHead);
                // 挖完立刻验证：目标脚部还有碰撞说明挖不动（基岩/保护区域），换方向。
                if (!npc.level().getBlockState(targetFeet).getCollisionShape(npc.level(), targetFeet).isEmpty()) {
                    dirIndex++;
                    failedSteps++;
                    LOG.info("[AIV][SKILL] " + npc.npcName() + " ascend 挖不动 | 目标="
                            + npc.level().getBlockState(targetFeet).getBlock() + " 连续失败=" + failedSteps);
                    if (failedSteps >= 6) {
                        // 被基岩/保护区域彻底封死：像卡住自救一样对齐到最近的地表，
                        // 否则整条科技树会被困死在矿道里永远无法推进。
                        BlockPos safe = surfaceStandNear(feet);
                        if (safe != null) {
                            npc.teleportTo(npc.level(), safe.getX() + 0.5D, safe.getY() + 0.001D,
                                    safe.getZ() + 0.5D, Set.of(), npc.getYRot(), npc.getXRot(), true);
                            finish(id, "ascend", "挖不动，传送到地表自救 " + safe.toShortString());
                        } else {
                            finish(id, "ascend", "挖不动且找不到地表落脚点 Y=" + feet.getY());
                        }
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
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        // 先看上层再看下层：从水里出来一般要往上走，落在岸边而不是水底。
        // 只接受 isStandable（脚下实心、脚/头可通过且非流体）的格子。
        for (int dy = 2; dy >= -3; dy--) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = from.offset(dx, dy, dz);
                    if (!PathFinder.isStandable(npc.level(), pos)) {
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
                PathFinder.Result result = PathFinder.find(npc.level(), feet, target, 4000);
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

    /** 在当前位置附近找一个地表可站立格，供彻底挖不动时自救。 */
    private BlockPos surfaceStandNear(BlockPos around) {
        for (int radius = 0; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    BlockPos candidate = new BlockPos(around.getX() + dx,
                            surfaceHeightAt(around.getX() + dx, around.getZ() + dz),
                            around.getZ() + dz);
                    if (PathFinder.isStandable(npc.level(), candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
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
        double radius = Math.max(6.0D, Math.min(MAX_SEARCH_RADIUS, intent.doubleParam("radius", 12.0D)));
        int maxHits = Math.max(1, Math.min(12, intent.intParam("hits", 10)));
        List<Entity> candidates = npc.level().getEntities(npc, npc.getBoundingBox().inflate(radius),
                entity -> entity instanceof Animal animal && animal.isAlive() && !animal.isBaby());
        if (candidates.isEmpty()) {
            lastOutcome = "hunt 附近没有动物 radius=" + radius;
            LOG.info("[AIV][SKILL] " + npc.npcName() + " hunt 附近没有动物 | radius=" + radius);
            return false;
        }
        LivingEntity target = (LivingEntity) candidates.stream()
                .min((left, right) -> Double.compare(npc.distanceToSqr(left), npc.distanceToSqr(right)))
                .orElseThrow();
        BlockPos approach = PathFinder.standableGoal(npc.level(), target.blockPosition(), 3);
        if (approach == null) {
            lastOutcome = "hunt 目标附近没有可站立位置 target=" + target.getType();
            return false;
        }
        PathFinder.Result approachPath = PathFinder.find(npc.level(), npc.blockPosition(), approach);
        logPath("hunt-approach", approach, approachPath);
        if (!approachPath.found()) {
            lastOutcome = "hunt 找不到接近路径 " + approachPath.detail();
            return false;
        }
        current = "hunt";
        LOG.info("[AIV][SKILL] " + npc.npcName() + " hunt 开始 | target=" + target.getType()
                + " hits=" + maxHits + " distance=" + String.format(Locale.ROOT, "%.1f", npc.distanceTo(target)));
        npc.followPath(approachPath.path(), true, () -> {
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
                    if (target.isRemoved() || !target.isAlive()) {
                        finish(id, "hunt", "猎物已倒下 hits=" + hits);
                        return;
                    }
                    if (attempts > 160) {
                        finish(id, "hunt", "超时 hits=" + hits);
                        return;
                    }
                    double distanceSq = npc.distanceToSqr(target);
                    if (distanceSq > 16.0D) {
                        if (!npc.isFollowingPath()) {
                            BlockPos chaseGoal = PathFinder.standableGoal(npc.level(), target.blockPosition(), 3);
                            if (chaseGoal != null) {
                                PathFinder.Result chase = PathFinder.find(npc.level(), npc.blockPosition(), chaseGoal);
                                if (chase.found()) {
                                    npc.followPath(chase.path(), true, null, null);
                                }
                            }
                        }
                        return;
                    }
                    npc.attackTarget(target);
                    hits++;
                    if (hits >= maxHits) {
                        finish(id, "hunt", "达到攻击次数 hits=" + hits + " 存活=" + target.isAlive());
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
                        PathFinder.Result result = PathFinder.find(npc.level(), npc.blockPosition(), goal);
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
        PathFinder.Result path = PathFinder.find(npc.level(), npc.blockPosition(), stand);
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
        PathFinder.Result path = PathFinder.find(npc.level(), npc.blockPosition(), stand);
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

package com.example.aivillager.progression;

import com.example.aivillager.AiVillagerPlugin;
import com.example.aivillager.goals.GoalExecutor;
import com.example.aivillager.intent.Intent;
import com.example.aivillager.npc.FakePlayerBody;
import com.example.aivillager.skills.SkillRunner;
import com.example.aivillager.snapshot.InventorySnapshot;
import com.example.aivillager.survival.SurvivalController;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * 发展层：不带 LLM 的确定性"玩家式"科技树，负责回答「现在这一步该做什么」。
 *
 * <p>为什么把决策从模型手里拿走：本地 9B 模型一次推理 20-40 秒，而且经常重复同一个目标
 * （竹子够了还去砍竹子、有 9 个圆石还去做工作台）。要"通关"必须保证每一步都朝
 * 木头→工具→石头→煤铁→钻石→护甲 推进，这需要一个能读懂背包、算清材料、自己排优先级的规划器。
 * 模型仍然负责聊天和吐槽，但不再决定生存发展路线。
 *
 * <p>推进方式：每次只下一个中期目标（gather/craft/dig/smelt/hunt/build/explore），
 * 由 {@link GoalExecutor} 执行到结束；结束后 1 秒内规划器立刻接上下一步，NPC 不会闲着。
 */
public final class ProgressionPlanner {

    private static final Logger LOG = Logger.getLogger("AIVillager");

    /** 每 20 tick（1 秒）检查一次：足够快，又不会在主线程上反复扫背包。 */
    private static final int CHECK_INTERVAL_TICKS = 20;
    /** 出错后的冷却：目标失败立刻重下同一个目标会形成死循环，先让 NPC 停 4 秒。 */
    private static final long FAIL_COOLDOWN_MILLIS = 4000L;
    /** 夜晚判定：13000 tick 之后怪物开始生成。 */
    private static final long NIGHT_START = 13000L;
    /**
     * 木料安全线（单位是木板当量：wood = 木板数 + 原木×4 + 竹子折算）。
     * 12 ≈ 3 根原木的量，足够拆出工作台、木棍和备用工具的料；
     * 低于它就先补给再下矿，避免"地下断木→工具坏→造不出"的死结。
     */
    private static final int WOOD_SAFETY_MIN = 12;
    /**
     * 【fix38·Fong 04:11/04:13/04:14 定版】常备储备向导值——全部是"顺路型"软向导
     * （顺路补、够了停、急用优先），不是开工条件：只有安全类门槛（WOOD_SAFETY_MIN、
     * MINING_FOOD_NUTRITION、铁镐总耐久门）保留硬性。旧 COAL_STOCK_TARGET=8 的
     * 常驻挖煤目标已按 04:14 拆除（#11 挖煤常驻饿死冶炼的病根正是硬目标）。
     */
    /** 补仓触发批量线：无即时需求时，生铁攒到这个数才开炉批量烧（04:13）。 */
    private static final int RAW_IRON_BATCH = 32;
    /** 铁锭常备向导：补仓触发把铁锭补回这个数（04:11）。 */
    private static final int INGOT_RESERVE_TARGET = 32;
    /** 原木常备向导：实物计数 ~32 根，低优先补仓步补（04:11）。 */
    private static final int LOG_RESERVE_TARGET = 32;
    /** 煤顺手采集上限向导：掘进顺手挖煤，存量到这个数就跳过省镐耐久（04:14）。 */
    private static final int COAL_OPPORTUNISTIC_CAP = 64;
    /** 熟食储备向导：总营养（顺路型，与 24 的安全型口粮门分开）。 */
    private static final int FOOD_RESERVE_NUTRITION = 40;
    /** 熟食储备向导：食物总份数余量（顺路型）。 */
    private static final int FOOD_RESERVE_PORTIONS = 8;
    /** 【第二十二轮】下矿口粮线（Fong 18:51）：按食物总营养值计，24≈3 块牛排（Wiki 营养值牛排 8）。安全型硬门。 */
    private static final int MINING_FOOD_NUTRITION = 24;
    /** 【第二十二轮】皮革目标存量：一双皮靴 4 张（Wiki 皮革词条：牛等 0–2/只、平均 1）。 */
    private static final int LEATHER_STOCK_TARGET = 4;

    private final AiVillagerPlugin plugin;
    private final FakePlayerBody npc;
    private final SkillRunner skills;
    private final GoalExecutor goals;
    /** 本 NPC 的阶段模式（按 NPC 存储，插件层按 config 分配，见 AiVillagerPlugin#modeForNpc）。 */
    private final ProgressionMode mode;
    /** 本模式对应的阶段序列（集中定义在 StagePlan，decide() 的各段与之一一对应）。 */
    private final List<StagePlan.StageDef> stagePlan;
    /** 启用的阶段 id 集合：decide() 每段先问 planHas，没启用的阶段整段跳过（阶段级自定义的执行点）。 */
    private final java.util.Set<String> enabledStages;
    /** 是否为 config 自定义计划（启动日志标注用，便于核对生效的是模板还是自定义）。 */
    private final boolean customPlan;

    private BukkitTask task;
    private long lastStartAt;
    private String lastStage = "-";
    /** 【第二十三轮】上次实际下发的阶段名（与 lastStage 的结果归因口径分开），供阶段切换盘点判定。 */
    private String lastIssuedStage = "";
    /** 已处理到的目标结束序号；目标层每次 finish 自增，用它判断"有没有新结果"。 */
    private int lastOutcomeId;
    /** 技能层也会有 finish（ascend/descend/dryland 等），同样需要驱动失败退避。 */
    private int lastSkillOutcomeSeq;
    private String lastFailedStage = "";
    /** 同一阶段连续失败次数，用于指数退避（4s→8s→16s→32s→最长 60s）。 */
    private int failStreak;
    private long lastFailAt;
    /** 最近一次下发的挖矿目标（阶段名+资源），用来把"挖矿放弃"的结果归因到具体资源。 */
    private String issuedDigStage = "";
    private String issuedDigResource = "";
    /** 同一资源连续被目标层放弃的次数（跨阶段名统计，只认目标层结果）。 */
    private int digAbandonStreak;
    private String digAbandonResource = "";
    /** 【第二十四轮】皮革专属连败与退避到期：共享 failStreak 按"上一阶段名"记账，
     * 皮革与挖铁交替派活时每次都重置成 1，6.8 的"连败 2 次退避"永远凑不满
     * （长跑#8 F 段皮革/挖铁 ping-pong 实证）。皮革自己记账：失败退避 60 秒起、
     * 随连败翻倍、上限 5 分钟；成功清零；其他阶段的成败不冲它。 */
    private int leatherFailStreak;
    private long leatherBackoffUntil;
    /** 【第二十四轮】口粮补给专属连败与退避：与皮革同理。补给连败时短暂让位
     * 主线（附近真没动物不能把整条科技树堵死），退避到期仍由它优先重试。 */
    private int foodFailStreak;
    private long foodBackoffUntil;
    /** 【fix116】备用镐合成专属退避：第十八轮"常备 ≥2 把石镐"是软向导，
     * 却无退避——背包塞满做不出来时（farm80 实证：41 槽皆贵重/重复物、
     * dropJunk 无可扔、合成失败 26 次）它排在阶段链前部，把 PORTAL 等
     * 全部后续阶段饿死。照皮革/口粮模式：只认"背包已满"失败，连败退避
     * 120 秒起、随连败增长、上限 600 秒；退避期铁镐/钻镐顶上干主线
     * （fix101 镐子兜底的规划层镜像），成功清零。 */
    private int pickCraftFailStreak;
    private long pickCraftBackoffUntil;
    /** 【fix49】开田专属连败与退避：与皮革/口粮同理——没水没平地的地形里
     * 开田注定连败，不能让它每轮 decide 都抢在铁线前面重试；退避到期再试。 */
    private int farmFailStreak;
    private long farmBackoffUntil;
    /** 【fix108】夜间小屋专属连败退避 + 失败位置：build 放置=0 改判失败后
     * （GoalExecutor.tickBuild），同一站位反复建不出来不能整夜重派
     * （farm70 实证「部分完成 0/25」被当成功、96+ 次 1 Hz 热循环）。
     * 失败记当前站位，冷却 min(600s, 120s×连败)；NPC 离开失败点 8 格外
     * 视为换了站位、冷却失效（新营地照常能建）；成功清零。 */
    private int shelterFailStreak;
    private long shelterBackoffUntil;
    private net.minecraft.core.BlockPos shelterFailPos;
    /** 【fix50】采集木头专属连败计数：木料安全线（步 6.5）的放行阀原读共享
     * failStreak，但采集失败后规划层总会先插一个 0.5 秒必成的合成步（补石镐），
     * 成功即把共享连败清零，放行阀永远凑不满 2——fix49 短验实证 NPC 在
     * gather↔craft 之间 ping-pong、每轮白烧 203 秒。与皮革/口粮同病同治：
     * 采集木头自己记账，其他阶段的成败不冲它；采集成功清零。 */
    private int woodGatherFailStreak;
    /** 【fix51】建屋专属连败与退避：与开田同理——附近没平地的地形里建屋
     * 注定连败，不能让它反复抢主线；退避到期再试。 */
    private int baseFailStreak;
    private long baseBackoffUntil;
    /** 【fix56】安置床专属连败与退避（与建屋同口径）：屋内没空位等环境问题
     * 注定连败时不能让床务反复抢主线；退避到期再试。 */
    private int bedFailStreak;
    private long bedBackoffUntil;
    /** 【fix69】存箱专属连败与退避（与安置床同口径）。 */
    private int storeFailStreak;
    private long storeBackoffUntil;
    /** 【fix70·D4】装水专属连败与退避：附近没水源时注定连败，不能让装水步
     * 在地表反复抢钻石主线；退避到期再试。 */
    private int bucketFillFailStreak;
    private long bucketFillBackoffUntil;
    /** 【fix88】步 10「挖铁」专属连败与退避：该步是早期补 8 锭的老口径
     * （够镐+剑+余量），钻石时代只剩补仓价值，却排在步 11 保镐门与 13.1
     * 钻石甲之前——farm47 实证这片水涝贫铁矿区挖铁三连 0/4、步 10 每轮
     * 重派，钻石 ×10 够护腿却被 4 锭缺口无限期挡住。与 Fong「常备数是
     * 软向导、不许硬指标卡进度」冲突：连败退避让位主线，退避到期再补
     * （补仓本职已有步 13.3 在主线后兜底；保镐安全门步 11 不受影响）。
     * 与皮革/存箱同口径自己记账（共享连败会被中间的补石镐成功清零）。 */
    private int ironStepFailStreak;
    private long ironStepBackoffUntil;
    /** 【fix98】步 13「储备食物」专属连败与退避：与 fix88 挖铁同病同治——储备
     * 40 是 Fong 定的顺路型软向导，却排在 13.5 下界链之前白天必派 hunt；出生点/
     * 农场一带动物被猎稀后 hunt 趟趟全灭（farm57/58 实证：半径 32/48、escalate
     * 远走 64 格仍零猎物，常驻加载区块被动生物刷新近乎为零=结构性枯竭），
     * nutrition 在 12–32 磨耗、下界主线被无限期堵死，与「软向导不许硬卡进度」
     * 冲突。自己记账（共享连败会被中间 tend 成功冲掉）：失败退避 120 秒×连败、
     * 上限 600 秒（≈一个白昼窗口），退避中让位主线（13.5 自有 fix96 入境口粮
     * 软闸、安全型步 0/6.6 在步 13 之前，均不受影响）；退避到期重试储备，
     * 猎物回来达标即清零。40 这个向导值本身一字不改。 */
    private int reserveFailStreak;
    private long reserveBackoffUntil;
    /** 【fix103】专程回程补给专属连败与退避 + 在途标记：与 fix98 同款自己
     * 记账（travel 单趟以分钟计，退避 120 秒×连败、上限 600 秒）；在途标记
     * 只为到达专行日志（回到目标 48 格内打一行、清标记）。 */
    private int homeReturnFailStreak;
    private long homeReturnBackoffUntil;
    private boolean homeReturnActive;
    /** 【fix117】饥饿求生专属记账：步 0 是 decide() 顶部绝对抢占（零食物
     * 时只派 hunt 半径 12），fix103 回程步（6.65）与 tend（6.7）都在它之下、
     * 永远轮不到——动物荒芜带里 hunt 连败没有任何出口（farm82 实证空转
     * 20+ 分钟、hp 磨到 9.1）。hunt 专属连败 ≥3 时步 0 自己开回程出口
     * （派 travel 回农场/落脚点，形态照 fix103）；回程 travel 连败退避
     * 120 秒×连败、上限 600 秒，退避期内照旧 hunt 兜底不干等。 */
    private int hungerHuntFailStreak;
    private int hungerReturnFailStreak;
    private long hungerReturnBackoffUntil;
    /** 连败换区状态：同资源连弃 2 次后置位，先回地表再远距离移动，换一片矿区再挖。 */
    private boolean relocationActive;
    private String relocationResource = "";
    private int relocationPhase;
    /** 当前科技树阶段，仅用于日志和聊天状态。 */
    private String stage = "准备";

    public ProgressionPlanner(AiVillagerPlugin plugin, FakePlayerBody npc,
                              SkillRunner skills, GoalExecutor goals, ProgressionMode mode) {
        this(plugin, npc, skills, goals, mode, null);
    }

    /**
     * @param planOverride 自定义阶段计划（StagePlan.resolvePlan 的结果，已过前置校验）；
     *                     为 null 时用模式预设。模式只决定默认模板，计划才是执行依据。
     */
    public ProgressionPlanner(AiVillagerPlugin plugin, FakePlayerBody npc,
                              SkillRunner skills, GoalExecutor goals, ProgressionMode mode,
                              List<StagePlan.StageDef> planOverride) {
        this.plugin = plugin;
        this.npc = npc;
        this.skills = skills;
        this.goals = goals;
        this.mode = mode == null ? ProgressionMode.SPEEDRUN : mode;
        this.stagePlan = planOverride != null ? planOverride : StagePlan.forMode(this.mode);
        this.customPlan = planOverride != null;
        this.enabledStages = new java.util.HashSet<>();
        for (StagePlan.StageDef def : this.stagePlan) {
            this.enabledStages.add(def.id());
        }
    }

    /** 本 NPC 的计划里是否启用了某阶段。decide() 各段的唯一门控口径。 */
    private boolean planHas(StagePlan.StageDef def) {
        return enabledStages.contains(def.id());
    }

    /** 本 NPC 当前的阶段模式。 */
    public ProgressionMode mode() {
        return mode;
    }

    public void start() {
        // 先把附近熔炉里上一轮遗留的原料/燃料收回背包：
        // 停服或切换目标时，生铁已经进了熔炉方块，规划器只数背包会当成"没有铁"，白白重新下矿。
        int recovered = goals.recoverNearbyFurnaces(16);
        if (recovered > 0) {
            LOG.info("[AIV][PLAN] " + npc.npcName() + " 启动时回收熔炉遗留物品 | 槽=" + recovered);
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::safeTick,
                CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
        LOG.info("[AIV][PLAN] " + npc.npcName() + " 发展层启动 | 目标=像玩家一样从木头推进到钻石装备");
        // 阶段模式必须在启动日志里可见：哪个 NPC 走哪条路线，一眼可查、便于核对 config 是否生效。
        LOG.info("[AIV][PLAN] " + npc.npcName() + " 阶段模式 | mode=" + mode.configKey()
                + "（" + mode.displayName() + "） 阶段数=" + stagePlan.size()
                + " 来源=" + (customPlan ? "config-custom" : "mode-preset")
                + " 序列=" + StagePlan.describe(stagePlan));
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        LOG.info("[AIV][PLAN] " + npc.npcName() + " 发展层停止");
    }

    /** 当前阶段，供聊天状态回灌。带上模式：同一个阶段名在不同模式下含义不同。 */
    public String stage() {
        return stage + "｜模式=" + mode.displayName() + "｜" + goals.status();
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable throwable) {
            // 周期任务抛异常会被 Bukkit 静默取消，整个发展层就停了；必须自己兜住。
            LOG.log(java.util.logging.Level.WARNING,
                    "[AIV][PLAN] " + npc.npcName() + " tick 异常", throwable);
        }
    }

    private void tick() {
        if (!npc.isAlive()) {
            return;
        }
        // 已有中期目标在执行：规划器不插手，等目标层自己完成/放弃。
        if (goals.active()) {
            return;
        }
        // 技能正在执行（走路/挖矿/战斗）：不要用新目标把动作冲掉。
        if (!"idle".equals(skills.currentSkill())) {
            return;
        }
        SurvivalController survival = plugin.survival(npc.npcName());
        if (survival != null && survival.isFleeing()) {
            return;
        }
        // 附近有敌对生物：交给生存层自卫，规划器暂停，避免"边打边跑去挖矿"。
        // 但必须用生存层的 isEngaged() 口径：只算贴脸（5 格内）或正在挨打的怪，
        // 并且排除生存层已拉黑的不可达威胁。旧逻辑自己拿 8 格半径生扫，
        // 矿道里隔壁洞穴的怪隔着墙永远过不来、也不会消失，规划器却会因为
        // 它们在 8 格内而永久暂停——实测 NPC 在矿道里满血沉默 5 分钟以上、
        // 生存层每 10 秒把同一只苦力怕拉黑一次又一次，目标一个都不下发。
        if (survival != null && survival.isEngaged()) {
            return;
        }
        // 惜命（Fong 定：生命第一）：血量低（≤35%）且包里有吃的时不派任何新活，
        // 先让生存层带它脱离危险、吃东西把血回上来再说，尤其不许派战斗/危险目标。
        // 包里没吃的时不拦：规划器的找食/砍树正是回血的前置，拦住反而会饿着回不了血。
        if (survival != null && survival.isLowHealth() && survival.hasEdibleFood()) {
            return;
        }
        if (npc.isUsingItem()) {
            return;
        }

        long now = System.currentTimeMillis();
        // 上一轮目标/技能失败后先停几秒：否则会在同一个做不到的目标上死循环。
        // goal 层和 skill 层各自维护一个自增 seq，都要检查。
        int outcomeId = goals.outcomeCount();
        int skillSeq = skills.outcomeSeq();
        boolean newOutcome = false;
        String outcome = null;
        // 目标层自己的结果单独留一份：技能层结果会在下面覆盖 outcome，
        // 但"挖矿连败"只能认目标层的放弃/完成，认错来源会误触发换区。
        String goalOutcomeText = null;
        boolean goalNewOutcome = false;
        if (outcomeId != lastOutcomeId) {
            lastOutcomeId = outcomeId;
            newOutcome = true;
            goalNewOutcome = true;
            outcome = goals.lastOutcome();
            goalOutcomeText = outcome;
        }
        String skillOutcomeText = null;
        if (skillSeq != lastSkillOutcomeSeq) {
            lastSkillOutcomeSeq = skillSeq;
            newOutcome = true;
            skillOutcomeText = skills.lastOutcome();
            if (!goalNewOutcome) {
                outcome = skillOutcomeText;
            }
        }
        // 【第十九轮附带修复】失败记账分开算（雪原复验实测逮到）：旧版技能层
        // 结果会覆盖目标层结果——目标刚"放弃"、同 tick 一个技能报"到达"，
        // 失败就被冲掉、连败清零，木料安全线放行/挖煤退避/皮靴退避这些靠
        // failStreak 的机制全部失效（长跑#7 挖煤看门狗连烧 4 轮同源）。
        // 改为：目标结果是权威（目标内部本来就驱动技能），目标失败优先记账；
        // 技能结果只在没有新目标结果时单独记账；连败只被"目标成功完成"
        // 或"技能步骤成功"清零，技能的附带成功不再冲掉目标的失败。
        boolean goalFailed = goalNewOutcome && isFailureText(goalOutcomeText);
        boolean skillFailed = !goalNewOutcome && skillOutcomeText != null
                && isFailureText(skillOutcomeText);
        if (goalFailed || skillFailed) {
            String failedText = goalFailed ? goalOutcomeText : skillOutcomeText;
            failStreak = lastStage.equals(lastFailedStage) ? failStreak + 1 : 1;
            lastFailedStage = lastStage;
            lastFailAt = now;
            LOG.info("[AIV][PLAN] " + npc.npcName() + " 检测到失败 | outcome=" + failedText
                    + " 阶段=" + lastStage + " 连续=" + failStreak);
        } else if (goalNewOutcome || (!goalNewOutcome && skillOutcomeText != null)) {
            // 目标成功完成，或（无新目标结果时）技能步骤有了结果且非失败：清零。
            failStreak = 0;
            lastFailedStage = "";
        }
        // 【第二十四轮】皮革/口粮专属退避记账：只认目标层结果、只认各自阶段名，
        // 与共享 failStreak 分开（理由见字段注释）。退避时长随各自连败增长。
        if (goalNewOutcome) {
            // 【fix116】备用镐合成"背包已满"专属记账（与上面的阶段名链独立：
            // 触发条件是阶段名前缀 + 失败文案，只堵不疏的软向导必须让位）。
            // 【fix126】扩面：farm89 实证主线步「制作石镐」同病热循环 29 次
            // （阶段名前缀不匹配、文案又被 craftMissing 掩盖——掩盖已由
            // fix126-A 修）。条件扩为：（备用步前缀 ∨ 合成失败文案）∧ 背包
            // 已满，同字段记账；派单侧 L880/L936 制作石镐与木镐支同加闸。
            if (lastStage != null && lastStage.startsWith("补一把备用")
                    || (goalOutcomeText != null && goalOutcomeText.contains("合成失败")
                            && goalOutcomeText.contains("背包已满"))) {
                if (goalFailed && goalOutcomeText != null && goalOutcomeText.contains("背包已满")) {
                    pickCraftFailStreak++;
                    pickCraftBackoffUntil = now + Math.min(600_000L, 120_000L * pickCraftFailStreak);
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 合成退避 | 连败=" + pickCraftFailStreak
                            + " 退避秒=" + (pickCraftBackoffUntil - now) / 1000L
                            + "（背包已满做不出来，先干别的）阶段=" + lastStage);
                } else if (!goalFailed) {
                    pickCraftFailStreak = 0;
                    pickCraftBackoffUntil = 0L;
                }
            }
            if ("找皮革做皮靴".equals(lastStage)) {
                if (goalFailed) {
                    leatherFailStreak++;
                    leatherBackoffUntil = now + Math.min(300_000L, 60_000L * leatherFailStreak);
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 皮革退避 | 连败=" + leatherFailStreak
                            + " 退避秒=" + (leatherBackoffUntil - now) / 1000L);
                } else {
                    leatherFailStreak = 0;
                    leatherBackoffUntil = 0L;
                }
            } else if ("补随身口粮".equals(lastStage)) {
                if (goalFailed) {
                    foodFailStreak++;
                    foodBackoffUntil = now + Math.min(180_000L, 45_000L * foodFailStreak);
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 口粮补给退避 | 连败=" + foodFailStreak
                            + " 退避秒=" + (foodBackoffUntil - now) / 1000L);
                } else {
                    foodFailStreak = 0;
                    foodBackoffUntil = 0L;
                }
            } else if ("开垦农田".equals(lastStage)) {
                if (goalFailed) {
                    farmFailStreak++;
                    farmBackoffUntil = now + Math.min(300_000L, 60_000L * farmFailStreak);
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 开田退避 | 连败=" + farmFailStreak
                            + " 退避秒=" + (farmBackoffUntil - now) / 1000L);
                } else {
                    farmFailStreak = 0;
                    farmBackoffUntil = 0L;
                }
            } else if ("农田维护".equals(lastStage)) {
                // 【fix60】与开田共用 farm 退避字段：tend 空转（动作全跳过）也要退避，
                // 不许 90 秒连派三次（boot-farm17 实锤）。
                if (goalFailed) {
                    farmFailStreak++;
                    farmBackoffUntil = now + Math.min(300_000L, 60_000L * farmFailStreak);
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 农田维护退避 | 连败=" + farmFailStreak
                            + " 退避秒=" + (farmBackoffUntil - now) / 1000L);
                } else {
                    farmFailStreak = 0;
                    farmBackoffUntil = 0L;
                }
            } else if ("建落脚点".equals(lastStage)) {
                if (goalFailed) {
                    baseFailStreak++;
                    baseBackoffUntil = now + Math.min(300_000L, 60_000L * baseFailStreak);
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 建屋退避 | 连败=" + baseFailStreak
                            + " 退避秒=" + (baseBackoffUntil - now) / 1000L);
                } else {
                    baseFailStreak = 0;
                    baseBackoffUntil = 0L;
                }
            } else if ("安置床".equals(lastStage)) {
                if (goalFailed) {
                    bedFailStreak++;
                    bedBackoffUntil = now + Math.min(300_000L, 60_000L * bedFailStreak);
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 安置床退避 | 连败=" + bedFailStreak
                            + " 退避秒=" + (bedBackoffUntil - now) / 1000L);
                } else {
                    bedFailStreak = 0;
                    bedBackoffUntil = 0L;
                }
            } else if ("夜间小屋".equals(lastStage)) {
                // 【fix108】小屋失败退避：记站位、同位置冷却（见字段注释）。
                if (goalFailed) {
                    shelterFailStreak++;
                    shelterBackoffUntil = now + Math.min(600_000L, 120_000L * shelterFailStreak);
                    shelterFailPos = npc.blockPosition();
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 小屋退避 | 连败=" + shelterFailStreak
                            + " 退避秒=" + (shelterBackoffUntil - now) / 1000L
                            + " 位置=" + shelterFailPos.toShortString());
                } else {
                    shelterFailStreak = 0;
                    shelterBackoffUntil = 0L;
                    shelterFailPos = null;
                }
            } else if ("回箱存物".equals(lastStage)) {
                if (goalFailed) {
                    storeFailStreak++;
                    storeBackoffUntil = now + Math.min(300_000L, 60_000L * storeFailStreak);
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 存箱退避 | 连败=" + storeFailStreak
                            + " 退避秒=" + (storeBackoffUntil - now) / 1000L);
                } else {
                    storeFailStreak = 0;
                    storeBackoffUntil = 0L;
                }
            } else if ("装满水桶".equals(lastStage)) {
                if (goalFailed) {
                    bucketFillFailStreak++;
                    bucketFillBackoffUntil = now + Math.min(300_000L, 60_000L * bucketFillFailStreak);
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 装水退避 | 连败=" + bucketFillFailStreak
                            + " 退避秒=" + (bucketFillBackoffUntil - now) / 1000L);
                } else {
                    bucketFillFailStreak = 0;
                    bucketFillBackoffUntil = 0L;
                }
            } else if ("挖铁".equals(lastStage)) {
                // 【fix88】步 10 专属退避记账（理由见字段注释）。
                if (goalFailed) {
                    ironStepFailStreak++;
                    ironStepBackoffUntil = now + Math.min(300_000L, 60_000L * ironStepFailStreak);
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 挖铁退避 | 连败=" + ironStepFailStreak
                            + " 退避秒=" + (ironStepBackoffUntil - now) / 1000L);
                } else {
                    ironStepFailStreak = 0;
                    ironStepBackoffUntil = 0L;
                }
            }
            // 【fix98】储备食物专属连败记账（独立于 else-if 链，只认目标层结果、
            // 只认阶段名恰为「储备食物」——安稳维持的同名步不走这条链，不冲账）。
            if ("储备食物".equals(lastStage)) {
                if (goalFailed) {
                    reserveFailStreak++;
                    reserveBackoffUntil = now + Math.min(600_000L, 120_000L * reserveFailStreak);
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 储备退避 | 连败=" + reserveFailStreak
                            + " 退避秒=" + (reserveBackoffUntil - now) / 1000L
                            + " foodNutrition=" + foodNutrition());
                } else {
                    reserveFailStreak = 0;
                    reserveBackoffUntil = 0L;
                }
            }
            // 【fix103】回程补给专属连败记账（独立于 else-if 链，只认阶段名
            // 「回程补给」）：travel 被看门狗处决/失败 → 退避，别刚到不了就
            // 立刻再派同一趟长途；成功清零。在途标记失败时一并清掉，防 NPC
            // 日后因别的原因漂进 48 格时误报到达。
            if ("回程补给".equals(lastStage)) {
                if (goalFailed) {
                    homeReturnFailStreak++;
                    homeReturnBackoffUntil = now + Math.min(600_000L, 120_000L * homeReturnFailStreak);
                    homeReturnActive = false;
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 回程补给退避 | 连败=" + homeReturnFailStreak
                            + " 退避秒=" + (homeReturnBackoffUntil - now) / 1000L);
                } else {
                    homeReturnFailStreak = 0;
                    homeReturnBackoffUntil = 0L;
                }
            }
            // 【fix117】饥饿求生专属记账（独立于 else-if 链）：步 0 的 hunt
            // 连败攒够 3 次，步 0 才开「回农场」出口（见步 0 分支）；hunt
            // 成功即清零。「饥饿求生：回农场」的 travel 连败走自己的退避，
            // 退避期内步 0 照旧 hunt，不把 NPC 钉在原地干等。
            if ("饥饿求生".equals(lastStage)) {
                if (goalFailed) {
                    hungerHuntFailStreak++;
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 饥饿狩猎连败=" + hungerHuntFailStreak
                            + "（≥3 且未到家（水平>48 或高差>12）时改走回农场）");
                } else {
                    hungerHuntFailStreak = 0;
                }
            }
            if ("饥饿求生：回农场".equals(lastStage)) {
                if (goalFailed) {
                    hungerReturnFailStreak++;
                    hungerReturnBackoffUntil = now + Math.min(600_000L, 120_000L * hungerReturnFailStreak);
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 饥饿回程退避 | 连败=" + hungerReturnFailStreak
                            + " 退避秒=" + (hungerReturnBackoffUntil - now) / 1000L);
                } else {
                    hungerReturnFailStreak = 0;
                    hungerReturnBackoffUntil = 0L;
                    hungerHuntFailStreak = 0;
                }
            }
            // 【fix50】采集木头专属连败记账（独立于上面的 else-if 链：阶段名带
            // 原因后缀，只认前缀）。供步 6.5 木料安全线放行阀读取。
            if (lastStage.startsWith("采集木头")) {
                if (goalFailed) {
                    woodGatherFailStreak++;
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 采集木头专属连败=" + woodGatherFailStreak
                            + "（≥2 时木料安全线放行下矿/种田）");
                } else {
                    woodGatherFailStreak = 0;
                }
            }
        }
        // 挖矿连败统计（第三轮修复 C）：目标层放弃一次挖矿目标不奇怪（这一片可能真没矿），
        // 注：连败判定关键词集中在 isFailureText（第十九轮抽出，目标/技能共用）。
        // 但同一资源连弃 2 次还留在原地重发，就是长跑里"贫铁洞原地烧掉几十分钟"的死循环。
        // 连弃 2 次 → 置位换区：下次再给同一资源下挖矿目标前，先回地表 + 远距离移动换矿区。
        if (goalOutcomeText != null && !issuedDigStage.isEmpty()
                && issuedDigStage.equals(lastStage)) {
            boolean digFailed = goalOutcomeText.contains("放弃") || goalOutcomeText.contains("失败")
                    || goalOutcomeText.contains("超时");
            // "没有可用的镐"是工具问题不是矿区问题，换区也治不了，别算进连败。
            if (digFailed && !goalOutcomeText.contains("没有可用的镐")) {
                if (issuedDigResource.equals(digAbandonResource)) {
                    digAbandonStreak++;
                } else {
                    digAbandonResource = issuedDigResource;
                    digAbandonStreak = 1;
                }
                if (digAbandonStreak >= 2 && !relocationActive) {
                    relocationActive = true;
                    relocationResource = issuedDigResource;
                    relocationPhase = 0;
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 挖矿连败换区 | resource="
                            + relocationResource + " 连弃=" + digAbandonStreak
                            + " 位置=" + npc.blockPosition().toShortString());
                    // 【fix81】钻石换区即下探（Fong 拍板越挖越深）：换区=新开井群，
                    // 目标层下探 2 层、-59 封底；进行中的井群已经收场，新深度只
                    // 作用于换区后的下一次挖钻派单，不废弃任何在挖矿道。
                    if ("diamond".equals(relocationResource)
                            || "diamond_ore".equals(relocationResource)) {
                        com.example.aivillager.goals.DigDepthMemory.bindWorld(npc.level());
                        int[] deep = com.example.aivillager.goals.DigDepthMemory.deepen(npc.npcName());
                        if (deep[1] < deep[0]) {
                            LOG.info("[AIV][PLAN] " + npc.npcName() + " 深度下探 | from=" + deep[0]
                                    + " to=" + deep[1] + " 触发=第" + deep[2] + "次钻石换区"
                                    + " 封底=" + com.example.aivillager.goals.DigDepthMemory.FLOOR_DEPTH);
                        } else {
                            LOG.info("[AIV][PLAN] " + npc.npcName() + " 深度维持 | depth=" + deep[1]
                                    + " 已封底（-59 为 Wiki 钻石最密层，再往下基岩混层、矿道不成形），"
                                    + "本次换区只横移");
                        }
                    }
                }
            } else if (!digFailed) {
                // 挖矿目标正常完成：连败清零。
                digAbandonStreak = 0;
                digAbandonResource = "";
            }
        }
        if (now - lastStartAt < 600L) {
            return;
        }
        long failCooldown = failStreak <= 0 ? 0L
                : Math.min(60000L, FAIL_COOLDOWN_MILLIS << Math.min(4, failStreak - 1));
        if (lastFailedStage.equals(lastStage) && now - lastFailAt < failCooldown) {
            return;
        }

        Step step = decide();
        if (step == null) {
            return;
        }
        // 【第二十三轮】盘点定时器：约 5 分钟一张家底快照（节流在 InventorySnapshot 内）。
        if (now - InventorySnapshot.lastSnapshotAt(npc.npcName()) >= 300_000L) {
            InventorySnapshot.log(npc, "timer", stage, false);
        }
        // 换区拦截：同一资源连弃 2 次后，再给它下挖矿目标之前先执行换区步骤
        // （回地表 → 远距离移动）。放在失败跳过检查之前：换区步骤的阶段名不同，
        // 不会被挖矿阶段自己的退避冷却挡住。
        if (relocationActive && "dig".equals(step.intent.action())
                && relocationResource.equals(step.intent.param("resource", ""))) {
            Step relocation = relocationStep();
            if (relocation != null) {
                step = relocation;
            }
        }
        // 同一阶段连续失败时跳过它，避免一个做不到的步骤卡死整条科技树。
        // 这里用带上指数退避的 failCooldown，而不是固定 4 秒：
        // "缺少材料：stick" 这类必然失败的目标每次 4 秒重试只会刷屏空转。
        if (step.stage.equals(lastFailedStage) && now - lastFailAt < failCooldown) {
            LOG.info("[AIV][PLAN] " + npc.npcName() + " 跳过刚失败的阶段 | " + step.stage);
            return;
        }
        // 【fix127】合成退避派单总闸：退避记账（fix116/126）已覆盖一切
        // 「合成失败+背包已满」，但派单侧靠逐个派单位加闸——fix126 部署
        // 当晚「制作钻石靴子」绕过全部已加闸的补镐派单位：记账涨到
        // 连败=8、退避恒 600 秒，仍每 60 秒照派照败。收口到派单总口：
        // 候选是 craft 目标且退避未到期 → 本轮不派（专行日志），到期后
        // decide 自然重派。全 craft 步一致口径、新派单位自动受闸。
        if (step.kind == Kind.GOAL && "craft".equals(step.intent.action())
                && System.currentTimeMillis() < pickCraftBackoffUntil) {
            LOG.info("[AIV][PLAN] " + npc.npcName() + " 合成退避中跳过派单 | 阶段=" + step.stage
                    + " 剩余秒=" + (pickCraftBackoffUntil - System.currentTimeMillis()) / 1000L);
            return;
        }

        lastStartAt = now;
        lastStage = step.stage;
        stage = step.stage;
        // 记住这次下发的是不是挖矿目标：它的结果回来时要归因到资源，喂给连败换区统计。
        if ("dig".equals(step.intent.action())) {
            issuedDigStage = step.stage;
            issuedDigResource = step.intent.param("resource", "");
        }
        // 【fix121】阶段名透传给目标层：入夜收工要认派单来源
        // （stage=饥饿求生 的 hunt 豁免，见 GoalExecutor.checkNightAbort）。
        if (step.kind == Kind.GOAL) {
            step.intent.params().addProperty("stage", step.stage);
        }
        boolean accepted = switch (step.kind) {
            case GOAL -> goals.start(step.intent);
            case SKILL -> skills.run(step.intent, "规划");
        };
        com.example.aivillager.goals.DigDepthMemory.bindWorld(npc.level());
        LOG.info("[AIV][PLAN] " + npc.npcName() + " 下一步 | 阶段=" + step.stage
                + " action=" + step.intent.action() + " params=" + step.intent.params()
                + " accepted=" + accepted
                // 【第二十三轮】决策带理由（日志标准第 1 条）：派活当口的关键量直接
                // 跟在同一行，key=value 可对账，不再靠事后翻 PLAN-DEBUG 拼。
                + " | why hp=" + String.format(java.util.Locale.ROOT, "%.1f", npc.getHealth())
                + " food=" + npc.getFoodData().getFoodLevel()
                + " foodNutrition=" + foodNutrition()
                + " leather=" + countItem("leather")
                + " coal=" + countItem("coal")
                + " rawIron=" + countItem("raw_iron")
                + " ingots=" + countItem("iron_ingot")
                + " diamonds=" + countItem("diamond")
                + " depth=" + com.example.aivillager.goals.DigDepthMemory.depth(npc.npcName())
                + " cobble=" + (countItem("cobblestone") + countItem("cobbled_deepslate"))
                + " failStreak=" + failStreak
                + " lastFailed=" + (lastFailedStage.isEmpty() ? "-" : lastFailedStage));
        // 【第二十三轮】阶段切换盘点：阶段变了打一张家底快照（节流在盘点类内）。
        if (!step.stage.equals(lastIssuedStage)) {
            lastIssuedStage = step.stage;
            InventorySnapshot.log(npc, "stage", step.stage, false);
        }
        if (!accepted) {
            lastFailedStage = step.stage;
            lastFailAt = now;
        }
    }

    // ---------------------------------------------------------------- 科技树

    private enum Kind { GOAL, SKILL }

    private record Step(String stage, Kind kind, Intent intent) {
    }

    /** 【fix108】夜间小屋是否在同位置冷却中（离开失败点 8 格外即失效）。 */
    private boolean shelterCoolingDown() {
        if (shelterFailPos == null || System.currentTimeMillis() >= shelterBackoffUntil) {
            return false;
        }
        return npc.blockPosition().distSqr(shelterFailPos) < 64.0D;
    }

    /** 结果文本是否算"失败"（第十九轮抽出）：目标层与技能层共用同一套关键词。 */
    private static boolean isFailureText(String text) {
        return text != null && (text.contains("失败") || text.contains("放弃")
                || text.contains("超时") || text.contains("不可达") || text.contains("无法")
                || text.contains("找不到") || text.contains("挖不动"));
    }

    private Step goal(String stage, String action, JsonObject params) {
        return new Step(stage, Kind.GOAL, new Intent("", action, params));
    }

    /** 直接调用技能层（不经过目标层），用于绕过目标层做不了的自救动作。 */
    private Step skill(String stage, String action) {
        return new Step(stage, Kind.SKILL, new Intent("", action, new JsonObject()));
    }

    private static JsonObject params(Object... pairs) {
        JsonObject json = new JsonObject();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            String key = String.valueOf(pairs[index]);
            Object value = pairs[index + 1];
            if (value instanceof Number number) {
                json.addProperty(key, number);
            } else {
                json.addProperty(key, String.valueOf(value));
            }
        }
        return json;
    }

    /**
     * 从背包和环境算出下一步。顺序就是优先级，先保证活下来和基础工具，再逐级升级。
     * 返回 null 表示当前没有必须做的事（理论上不会，最后总有探索兜底）。
     */
    private Step decide() {
        // 维度优先：下界/末地的资源、威胁和目标与主世界完全不同，
        // 继续跑主世界科技树只会在没有树的维度里反复"砍木头"。
        if (inNether()) {
            return decideNether();
        }
        if (inEnd()) {
            return goal("击杀末影龙", "dragon", params());
        }
        int eyes = countItem("ender_eye");
        int logs = countSuffix("_log");
        int planks = countSuffix("_planks");
        int sticks = countItem("stick");
        int bamboo = countItem("bamboo");
        int bambooBlocks = countItem("bamboo_block");
        // 【fix57】石材口径与合成配方组对齐：圆石与深板岩圆石在石器/熔炉配方里
        // 完全等价，只数 cobblestone 会让包里躺着 55 块深板岩圆石却判定「没石头」
        // 在地表无限重派挖石头（boot-farm12 实锤）。黑石仅下界产出、不入此口径。
        int cobble = countItem("cobblestone") + countItem("cobbled_deepslate");
        int coal = countItem("coal") + countItem("charcoal");
        int rawIron = countItem("raw_iron");
        int ingots = countItem("iron_ingot");
        int diamonds = countItem("diamond");
        int leather = countItem("leather");
        int food = countFood();
        boolean hasPickaxe = hasUsable("wooden_pickaxe", "stone_pickaxe", "iron_pickaxe",
                "diamond_pickaxe", "netherite_pickaxe");
        boolean hasStonePickaxe = hasUsable("stone_pickaxe", "iron_pickaxe",
                "diamond_pickaxe", "netherite_pickaxe");
        boolean hasIronPickaxe = hasUsable("iron_pickaxe", "diamond_pickaxe", "netherite_pickaxe");
        boolean hasSword = hasAny("wooden_sword", "stone_sword", "iron_sword", "diamond_sword",
                "netherite_sword");
        // 熔炉放下后物品栏里就没有了，但脚下那一台还能继续用，不能为此反复重做熔炉。
        boolean hasFurnace = hasAny("furnace") || nearbyFurnace();
        // 木板折算：1 原木 = 4 木板；9 根竹子 = 1 竹块 = 2 竹木板（1.20+ 的真实比例）。
        int woodFromLogs = planks + logs * 4;
        int woodFromBamboo = (bambooBlocks + bamboo / 9) * 2;
        int wood = woodFromLogs + woodFromBamboo;
        // 铁/钻石工具都要木棍。判定必须按"能不能真做出 2 根木棍"来算：
        // 2 根木棍 = 2 块木板，只有 1 块木板 / 1 根竹子时原料不够。
        // 之前写成 planks>=1 || logs>=1 || bamboo>=1 会把这种情况判成够用，
        // 于是"合成 stone_pickaxe x2"每秒失败一次、日志刷屏"缺少材料：stick"。
        // 但注意：只有竹子没有真正木板/原木时，合成系统可能走 bamboo_planks 路径
        // 卡住（1.21 某些配方用标签后候选里混进无法合成的半成品），
        // 所以棍子材料不足 2 块、且只有竹子没有原木时，强制去砍木头。
        boolean stickMaterial = sticks >= 2 || woodFromLogs >= 2
                || (woodFromLogs >= 1 && woodFromBamboo >= 1)
                || (woodFromBamboo >= 2 && (planks > 0 || logs > 0));
        // 只有竹子的情况 stickMaterial = false，所以所有木棍需求的合成都会走 gatherLogs
        // 强制砍树。crafting_table 只需木板（无木棍），直接用 wood >= 4 判断不受影响。
        int foodLevel = npc.getFoodData().getFoodLevel();

        // 0) 快饿死了：先找吃的，其他都往后排。
        if (planHas(StagePlan.HUNGER) && foodLevel <= 8 && food == 0) {
            // 但在地下打猎是死路（短验实测）：矿道里没有动物，hunt 每次必失败，
            // 却凭"最高优先级"把回地表的 ascend、换几何形状的挖矿步骤全挤掉，
            // NPC 在 Y=55 原地空转到饿死。真人玩家的做法是先回地表再找吃的。
            if (underground()) {
                return skill("饥饿求生：先回地表", "ascend");
            }
            // 【fix117】hunt 连败 ≥3 次的回家出口：本地猎物已被实证打不着、
            // 再原地转圈只是磨血——真玩家会回农场（口粮源头）/落脚点。
            // 白天才派长途（夜里长途是 #7/#8/#9 死型，夜里照旧本地 hunt）；
            // 退避期（回程 travel 刚失败过）照旧 hunt 兜底、不干等。
            if (hungerHuntFailStreak >= 3
                    && System.currentTimeMillis() >= hungerReturnBackoffUntil
                    && !isNight()) {
                com.example.aivillager.goals.FarmMemory.bindWorld(npc.level());
                com.example.aivillager.goals.FarmMemory.Farm hungerFarm =
                        com.example.aivillager.goals.FarmMemory.farm(npc.npcName());
                com.example.aivillager.goals.BaseMemory.bindWorld(npc.level());
                com.example.aivillager.goals.BaseMemory.Base hungerBase =
                        com.example.aivillager.goals.BaseMemory.base(npc.npcName());
                BlockPos hungerHome = hungerFarm != null ? hungerFarm.anchor()
                        : (hungerBase != null ? hungerBase.anchor() : null);
                if (hungerHome != null) {
                    double hDx = hungerHome.getX() + 0.5D - npc.getX();
                    double hDz = hungerHome.getZ() + 0.5D - npc.getZ();
                    double hDy = npc.getY() - hungerHome.getY();
                    // 【fix118】「未到家」须算垂直差：悬在农场上方高原时水平
                    // 距离 ≤48 会误判已到家（farm83 实证：水平 32 格、高差
                    // 37 格，退避到期后回头高原空猎、hp 钉在 1.7）。高差
                    // >12（与 fix114 柱顶分档同量级）同样派 travel，让目标层
                    // 竖向对齐（fix107）把它带下崖。
                    if (hDx * hDx + hDz * hDz > 48.0D * 48.0D || Math.abs(hDy) > 12.0D) {
                        LOG.info("[AIV][PLAN] " + npc.npcName()
                                + " 饥饿回程触发 | 目标=" + (hungerFarm != null ? "农场" : "落脚点")
                                + " " + hungerHome.toShortString()
                                + " 距离=" + Math.round(Math.sqrt(hDx * hDx + hDz * hDz))
                                + " 高差=" + Math.round(hDy)
                                + " 饥饿狩猎连败=" + hungerHuntFailStreak);
                        return goal("饥饿求生：回农场", "travel",
                                params("x", hungerHome.getX(), "y", hungerHome.getY(),
                                        "z", hungerHome.getZ()));
                    }
                }
            }
            // 【fix122】夜猎 hp 闸：夜里且血已到惜命线（≤35%）时步 0 整体
            // 暂缓——#12/#13 都是 hp 1.7 入夜开猎、被一击收尾。落到下面
            // 睡觉（5.55）/小屋（5.6）步过夜，天亮 decide 自然回到本步续猎；
            // 饿一晚掉血有限，被怪打死是全损，玩家也是先躲后吃。
            boolean hungerNightHold = isNight() && npc.getHealth() <= 7.0D;
            if (hungerNightHold) {
                LOG.info("[AIV][PLAN] " + npc.npcName() + " 饥饿夜猎暂缓（fix122） | hp="
                        + String.format(java.util.Locale.ROOT, "%.1f", npc.getHealth())
                        + " foodLevel=" + foodLevel + "（落睡觉/小屋步过夜，天亮再猎）");
            }
            if (!hungerNightHold && hasSword) {
                return goal("饥饿求生", "hunt",
                        params("need", "food", "stock", 12, "radius", 12));
            }
            // 【第三十二轮】材料够就先合一把武器再去找吃的：长跑#9 工具全灭后，
            // 背包里原木/圆石/铁锭明明够合剑，这一支却只派「采集木头」，
            // 在雪原找树连败 5 次死锁到收尾。合成只要几秒，先有武器 hunt 才派得出去。
            if (!hungerNightHold && ingots >= 2 && stickMaterial) {
                return goal("饥饿求生：先造铁剑", "craft",
                        params("item", "iron_sword", "count", 1));
            }
            if (!hungerNightHold && cobble >= 2 && stickMaterial) {
                return goal("饥饿求生：先造石剑", "craft",
                        params("item", "stone_sword", "count", 1));
            }
            if (!hungerNightHold && stickMaterial && wood >= (sticks >= 1 ? 2 : 4)) {
                return goal("饥饿求生：先造木剑", "craft",
                        params("item", "wooden_sword", "count", 1));
            }
            if (!hungerNightHold) {
                return gatherLogs("饥饿但无武器");
            }
        }

        // 1) 工作台（玩家发展的第一个标志物）。
        if (planHas(StagePlan.WORKBENCH) && !hasAny("crafting_table")) {
            if (wood >= 4) {
                return goal("制作工作台", "craft", params("item", "crafting_table", "count", 1));
            }
            return gatherLogs("先砍木头做工作台");
        }

        // 2) 镐子：没有镐就永远挖不到石头，整条科技树都卡住。
        if (!hasPickaxe && planHas(StagePlan.WOOD_TOOLS)) {
            // 能直接做石镐就做石镐：131 点耐久是木镐的两倍多，圆石在地表到处都是。
            // 【fix126】背包已满退避期不派合成（同备用镐派单位口径）：落到
            // 下面的 ascend/gatherLogs 支先改变处境，不原地热循环。
            if (cobble >= 3 && stickMaterial
                    && System.currentTimeMillis() >= pickCraftBackoffUntil) {
                return goal("制作石镐", "craft", params("item", "stone_pickaxe", "count", 1));
            }
            // 木镐配方 = 3 木板 + 2 木棍；2 根木棍自己还要 2 块木板，
            // 所以没有存货木棍时需要 5 块木板当量，不是 4（1 根原木只出 4 块，必须再砍一棵）。
            // 另外必须有真实木棍材料（stickMaterial），只有竹子也做不出木棍来。
            if (stickMaterial && wood >= (sticks >= 2 ? 3 : 5)
                    && System.currentTimeMillis() >= pickCraftBackoffUntil) {
                return goal("制作木镐", "craft", params("item", "wooden_pickaxe", "count", 1));
            }
            // 两手空空（镐全碎、连做木镐的木料都没有）又在地下：靠手挖阶梯回地表。
            // 旧做法是故意重生回出生点逃出死局——已按 Fong 的决定拆除：
            // 不许拿死亡当传送用（何况本服死亡不掉落，重生等于免费回家）。
            // ascend 现在会素手挖开土/石（无掉落）脱困；真被基岩封死挖不动，
            // 它会失败收场，规划器按失败退避换活/待命并打日志，不许自杀逃逸。
            if (underground()) {
                return skill("工具耗尽被困地下，用手挖阶梯返回地表", "ascend");
            }
            // 地下没有树，这里 gatherLogs 会自动改判成"挖阶梯回地表"。
            return gatherLogs("木头不够做木镐");
        }

        // 3) 木剑：生存层只有手里有武器才敢反击，否则一直逃跑。
        // 木剑配方 = 2 木板 + 1 木棍；木棍自己做要 2 块木板，
        // 所以没有存货木棍时需要 4 块木板当量（之前写 3，导致每次都差一块、永远合成失败）。
        // 但如果已经有足够铁锭（2 个）做铁剑，直接跳过木剑——更快更强，还能避开
        // 合成层对 bamboo_planks 等新木板类型的兼容坑（1.21 后多种木板加入 #planks 标签，
        // 但服务端配方解析有时只返回旧 4 种，导致竹子多的地形里木剑永远合成失败）。
        if (!hasSword && planHas(StagePlan.WOOD_TOOLS)) {
            if (ingots >= 2 && stickMaterial) {
                return goal("直接制作铁剑", "craft", params("item", "iron_sword", "count", 1));
            }
            if (stickMaterial && wood >= (sticks >= 1 ? 2 : 4)) {
                return goal("制作木剑", "craft", params("item", "wooden_sword", "count", 1));
            }
            return gatherLogs("木头不够做木剑");
        }

        // 4) 石头：3 个起步做石镐，更多留给熔炉。
        // 但在下矿之前先保证两把木镐：木镐只有 59 点耐久，从地表阶梯下挖到石头层
        // 要挖掉 40-60 个方块，一把常常中途就碎。实测两把木镐在 y≈59 同时消失后，
        // NPC 就再也挖不动石头，只能在矿道里无限空转。
        int woodenPickaxes = countUsableItem("wooden_pickaxe");
        if (planHas(StagePlan.WOOD_TOOLS) && !hasStonePickaxe && woodenPickaxes < 2 && stickMaterial
                && wood >= (sticks >= 4 ? 6 : 8)
                && System.currentTimeMillis() >= pickCraftBackoffUntil) {
            // 一次只排"再补一把"：一把木镐要 2 根木棍，直接排 2 把但只够 1 把的材料时，
            // 目标会在第二把失败，整轮时间全浪费（材料虽然回滚了，但节奏空转）。
            return goal("补一把备用木镐", "craft",
                    params("item", "wooden_pickaxe", "count", woodenPickaxes + 1));
        }
        if (planHas(StagePlan.STONE_TOOLS) && cobble < 3 && hasPickaxe) {
            return goal("挖石头", "dig", params("resource", "stone", "count", 8));
        }

        // 5) 石镐 → 石剑。
        if (planHas(StagePlan.STONE_TOOLS) && !hasStonePickaxe) {
            // 【fix126】同退避闸：背包已满时穿过合成支，落到补石头/gather 支。
            if (cobble >= 3 && stickMaterial
                    && System.currentTimeMillis() >= pickCraftBackoffUntil) {
                return goal("制作石镐", "craft", params("item", "stone_pickaxe", "count", 1));
            }
            if (!stickMaterial) {
                return gatherLogs("石镐缺木棍");
            }
            return goal("补石头做石镐", "dig", params("resource", "stone", "count", 8));
        }
        if (planHas(StagePlan.STONE_TOOLS)
                && !hasAny("stone_sword", "iron_sword", "diamond_sword", "netherite_sword")) {
            if (cobble >= 2 && stickMaterial) {
                return goal("制作石剑", "craft", params("item", "stone_sword", "count", 1));
            }
            if (!stickMaterial) {
                return gatherLogs("石剑缺木棍");
            }
            return goal("补石头做石剑", "dig", params("resource", "stone", "count", 8));
        }

        // 石镐主力保障（第十八轮，Fong 工具分级口径）：石镐是全程主力——
        // 石头/圆石、煤、铁都用石镐挖，铁镐只挖必须铁镐的矿（钻石/金/红石/绿宝石）。
        // 旧版这条只在"没有铁镐之前保持 3 把"，有了铁镐就不再补石镐，于是掘进
        // 把石镐烧光后身体只能拿铁镐顶上（fix24 只解决了选工具顺序，没解决
        // 石镐库存断供）。改为全程保持至少 2 把可用石镐：挖到圆石随手就做，
        // 掘进前/掘进间隙自动补齐；一把不剩且圆石也不够时，先挖石头补圆石。
        int stonePickaxes = countUsableItem("stone_pickaxe");
        LOG.info("[AIV][PLAN-DEBUG] cobble=" + cobble + " stickMaterial=" + stickMaterial
                + " woodFromLogs=" + woodFromLogs + " woodFromBamboo=" + woodFromBamboo
                + " stonePickaxes=" + stonePickaxes + " hasIronPickaxe=" + hasIronPickaxe
                + " logs=" + logs + " planks=" + planks + " sticks=" + sticks
                + " bamboo=" + bamboo + " ingots=" + ingots);
        if (planHas(StagePlan.STONE_TOOLS) && stonePickaxes < 2
                && System.currentTimeMillis() >= pickCraftBackoffUntil) {
            if (stonePickaxes == 0 && cobble < 3 && hasPickaxe) {
                return goal("补圆石做石镐", "dig", params("resource", "stone", "count", 8));
            }
            // 1 把石镐 = 3 圆石 + 2 木棍；而合成木棍的配方本身要一次性放入 2 块木板，
            // 所以有 2 根木棍或 2 块木板当量就能再做一把。
            // 每次只排一把（count = 现有 + 1），材料刚好够时才下单，避免"排 3 把只做成 1 把"的空转。
            if (cobble >= 3 && stickMaterial) {
                return goal("补一把备用石镐", "craft",
                        params("item", "stone_pickaxe", "count", stonePickaxes + 1));
            }
            if (cobble >= 3) {
                // 有石头没木棍：去补木料。不去补的话这一阶段会永远判定材料不足而跳过。
                return gatherLogs("备用石镐缺木棍");
            }
        }

        // 5.5) 皮靴顺手做（第十九轮）：打猎顺手攒够 4 张皮革就做皮靴——不限
        // 群系（皮都到手了，做双靴子不亏），穿戴由生存层护甲逻辑负责（细雪
        // 地带强制优先皮靴）。受 leather_boots 阶段开关控制：自定义计划关了
        // 这个阶段就完全不做。放在熔炉之前只是图它便宜（4 张皮、无前置工具），
        // 没皮时这一段直接跳过、不耽误主线。
        if (planHas(StagePlan.LEATHER_BOOTS) && leather >= 4 && !hasAny("leather_boots")) {
            return goal("制作皮靴", "craft", params("item", "leather_boots", "count", 1));
        }

        // 6) 熔炉：冶炼铁必须用，8 个圆石。
        if (planHas(StagePlan.FURNACE) && !hasFurnace && cobble >= 8) {
            return goal("制作熔炉", "craft", params("item", "furnace", "count", 1));
        }
        // 圆石不够 8 个、也没有熔炉时先补圆石；
        // 否则会一直挖铁矿却没法冶炼，生铁越堆越多、科技树原地打转。
        if (planHas(StagePlan.FURNACE) && !hasFurnace && hasPickaxe && cobble < 8) {
            return goal("补圆石做熔炉", "dig", params("resource", "stone", "count", 8));
        }

        // 5.55) 【fix56·D1】有床就回床真睡：跳夜 + 防幻翼，强于下面的搭棚
        // 蹲守。只在床已落盘、距床 ≤32 格（夜里赶远路反而送死，照旧走小屋
        // 步/生存层）、血量在安全线以上时派；睡不睡得成（床边有怪/床被占）
        // 由 startSleepInBed 的原版判定兜底，失败当晚照旧蹲守。
        if (planHas(StagePlan.BASE) && isNight() && npc.getHealth() > 7.0D) {
            com.example.aivillager.goals.BaseMemory.bindWorld(npc.level());
            com.example.aivillager.goals.BaseMemory.Base baseForSleep =
                    com.example.aivillager.goals.BaseMemory.base(npc.npcName());
            if (baseForSleep != null && baseForSleep.bedPos() != null
                    && npc.blockPosition().distSqr(baseForSleep.bedPos()) <= 32.0D * 32.0D) {
                return goal("回床睡觉", "sleep", params());
            }
        }

        // 5.6) 【fix39·夜晚总闸】夜里先搭棚再谈补给（审计第 3 环：长跑#12
        // 两死皆夜间地表补给——小屋原排第 14 步、主线没走完轮不到，夜里照样
        // 被派去地表打猎砍树）。与步 14 同条件、排位提前到木料/口粮补给步
        // 之前；步 14 保留作主线走完后的兜底。
        if (planHas(StagePlan.SHELTER) && isNight() && !underRoof() && cobble >= 25
                && !shelterCoolingDown()) {
            return goal("夜间小屋", "build", params("structure", "shelter"));
        }

        // 6.5) 木料安全线（第三轮修复 E 前半，StagePlan.WOOD_RESERVE）：下矿之前先看木料。
        // 长跑实测的死结：地下把原木/木板/木棍用光 → 镐一坏就造不出新工具 → 回地表又在
        // 没树的山顶小范围内打转。这里把"先补给再下矿"钉成硬规矩：一旦木料（原木等价，
        // 含木板与竹子折算）低于安全线，先回地表补满再继续——这一处检查排在所有挖矿
        // 步骤之前，一处即可管住挖煤/挖铁/挖钻石全部下矿动作。
        if (planHas(StagePlan.WOOD_RESERVE) && wood < WOOD_SAFETY_MIN) {
            // 但采集木头本身连败 2 次时必须放行（短验实测）：NPC 掉进自挖的浅坑时，
            // 所有步行寻路瞬间失败、采集永远完不成，安全线若死等补给就会把挖煤/挖铁
            // 全部堵死、原地空转。挖矿技能自己会挖路（descend/strip，还可能触发连败
            // 换区的 ascend 挖出坑），先靠它脱困/推进，木料等能走了再补——安全线
            // 每个 tick 都在查，不会被永久绕过。
            boolean gatherStuck = woodGatherFailStreak >= 2;
            if (!gatherStuck) {
                return gatherLogs("木料低于安全线，先补给再下矿");
            }
            LOG.info("[AIV][PLAN] " + npc.npcName()
                    + " 采集木头连败，木料安全线临时放行挖矿步骤 | wood=" + wood
                    + " woodGatherFailStreak=" + woodGatherFailStreak);
        }

        // 6.6) 随身口粮见底（第二十四轮，Fong 18:49/18:51 的存量口径）：看背包
        // 营养存量、不看饥饿条——长跑#8 F 段实证：复活后饥饿条满格（20）把
        // "背包零食物"盖住，步骤 0（饥饿求生，要 foodLevel≤8）与步骤 13
        // （食物储备，旧版要 foodLevel<16）双双不触发，派活在无源皮革与挖铁
        // 之间 ping-pong 到收尾、终局盘点 foodNutrition=0。改：随身存量为 0
        // 且有剑时，先把口粮补到下矿线再谈皮靴/煤铁；地下没动物，先回地表；
        // 没剑不挡路（工具线优先，真饿着有步骤 0 兜底）。补给自身连败退避中
        // 时让位主线，退避到期仍由它优先重试（专属记账见上方结果处理段）。
        if (planHas(StagePlan.FOOD) && hasSword && foodNutrition() <= 0
                && System.currentTimeMillis() >= foodBackoffUntil) {
            if (underground()) {
                return skill("补随身口粮", "ascend");
            }
            // 【fix39·夜晚总闸】夜里在地表不为补口粮远行打猎（#12 两死型）：
            // 未挨饿（foodLevel>6）时跳过 hunt，落到冶炼/合成/下矿等原地或
            // 地下步骤；真饿了照旧先补给——饿死比夜怪更确定。
            // 【fix122】真饿放行加 hp 下限：血已到惜命线（≤7）时同禁——
            // #13 即 food=4、hp 1.7 夜猎开打、一击致死；先过夜、天亮再补。
            if (isNight() && (npc.getFoodData().getFoodLevel() > 6
                    || npc.getHealth() <= 7.0D)) {
                LOG.info("[AIV][PLAN] " + npc.npcName() + " 口粮 hunt 夜里跳过（夜晚总闸） | foodLevel="
                        + npc.getFoodData().getFoodLevel()
                        + " hp=" + String.format(java.util.Locale.ROOT, "%.1f", npc.getHealth()));
            } else {
                LOG.info("[AIV][PLAN] " + npc.npcName() + " 随身口粮见底，先补口粮 | foodNutrition=0");
                return goal("补随身口粮", "hunt",
                        params("need", "food", "stock", MINING_FOOD_NUTRITION, "radius", 12));
            }
        }

        // 6.65) 【fix103】专程回程补给（地理分离定案）：tend/store/安置床/
        // 睡觉全是 ≤48 格「顺路触发、不专程跑」，活动中心漂远后（西区荒芜
        // 带 ↔ 东区猎场 ↔ 农场/锚点三地分立，farm61/62 实证两小时无一顺路
        // 步派发）nutrition 钉死个位数、下界入境闸（fix96）正当地拦着主线，
        // 却没有任何步会带它回家——真玩家口粮见底会专程回农场/家里补给。
        // 本步只补 krit 这一档，不动日常顺路 doctrine：距补给点 >48 格、
        // nutrition 跌破入境闸量级（<20）、且（≤12 krit 线（fix79 物资门
        // 同值）或储备 hunt 专属连败 ≥2（本地补给已被实证打不着））才专程
        // 回一趟；白天派（夜里长途是 #7/#8/#9 死型），地下先回地表。
        // 目标点：有农场先回农场（口粮源头），没农场回落脚点锚点。到达
        // 48 格内本步自动停火，tend/store/安置床顺路链自然接管。
        if (planHas(StagePlan.FOOD)
                && System.currentTimeMillis() >= homeReturnBackoffUntil) {
            com.example.aivillager.goals.FarmMemory.bindWorld(npc.level());
            com.example.aivillager.goals.FarmMemory.Farm homeFarm =
                    com.example.aivillager.goals.FarmMemory.farm(npc.npcName());
            com.example.aivillager.goals.BaseMemory.bindWorld(npc.level());
            com.example.aivillager.goals.BaseMemory.Base homeBase =
                    com.example.aivillager.goals.BaseMemory.base(npc.npcName());
            BlockPos homeTarget = homeFarm != null ? homeFarm.anchor()
                    : (homeBase != null ? homeBase.anchor() : null);
            if (homeTarget != null) {
                double homeDx = homeTarget.getX() + 0.5D - npc.getX();
                double homeDz = homeTarget.getZ() + 0.5D - npc.getZ();
                double homeDist = Math.sqrt(homeDx * homeDx + homeDz * homeDz);
                if (homeDist <= 48.0D) {
                    if (homeReturnActive) {
                        homeReturnActive = false;
                        LOG.info("[AIV][PLAN] " + npc.npcName()
                                + " 回程补给到达 | 目标=" + (homeFarm != null ? "农场" : "落脚点")
                                + " 距离=" + Math.round(homeDist)
                                + " foodNutrition=" + foodNutrition()
                                + "（tend/store/安置床顺路链接管）");
                    }
                } else if (!isNight() && foodNutrition() < 20
                        && (foodNutrition() <= 12 || reserveFailStreak >= 2)) {
                    if (underground()) {
                        return skill("回程补给：先回地表", "ascend");
                    }
                    LOG.info("[AIV][PLAN] " + npc.npcName()
                            + " 回程补给触发 | 目标=" + (homeFarm != null ? "农场" : "落脚点")
                            + " " + homeTarget.toShortString()
                            + " 距离=" + Math.round(homeDist)
                            + " foodNutrition=" + foodNutrition()
                            + " reserveFailStreak=" + reserveFailStreak);
                    homeReturnActive = true;
                    return goal("回程补给", "travel",
                            params("x", homeTarget.getX(), "y", homeTarget.getY(),
                                    "z", homeTarget.getZ()));
                }
            }
        }

        // 6.7) 种田（fix49·D11，P2 落脚点套装第一位）：五局群系合证 hunt 在
        // 动物稀疏/斑块分布下靠不住（沙漠局死因主项），田是自己能控的口粮
        // 来源——靠水开田、循环种植、收了立刻补种。维护（有熟可收/有空地
        // 可种/口粮紧且有骨粉可催）优先于开田；开田只在口粮低于下矿线时
        // 立项（富足时不抢主线），且带专属退避防没水地形反复重试。
        // 夜里不派（夜晚总闸同口径：地表农活属远行型暴露活动）。
        if (planHas(StagePlan.FARMING) && hasSword && !underground()) {
            com.example.aivillager.goals.FarmMemory.bindWorld(npc.level());
            com.example.aivillager.goals.FarmMemory.Farm farm =
                    com.example.aivillager.goals.FarmMemory.farm(npc.npcName());
            if (farm != null && !farm.cells().isEmpty()) {
                double farmDistSq = npc.blockPosition().distSqr(farm.anchor());
                if (!isNight() && farmDistSq <= 48.0D * 48.0D) {
                    com.example.aivillager.goals.FarmMemory.Scan scan =
                            com.example.aivillager.goals.FarmMemory.scan(npc.level(), npc.npcName());
                    // 骨粉先行：田里有在长的、口粮紧、手里有骨头没骨粉——先把
                    // 骨头磨成骨粉（原版无序合成 1 骨=3 粉，不需工作台），
                    // 下一轮 decide 的维护步才带得动催熟。
                    if (scan.growing() > 0 && countItem("bone_meal") <= 0 && countItem("bone") > 0
                            && foodNutrition() < MINING_FOOD_NUTRITION) {
                        return goal("磨骨粉", "craft", params("item", "bone_meal",
                                "count", countItem("bone") * 3));
                    }
                    boolean canPlant = countItem("wheat_seeds") > 0
                            || countItem("carrot") > 0 || countItem("potato") > 0;
                    boolean bonemealCase = scan.growing() > 0 && countItem("bone_meal") > 0
                            && foodNutrition() < MINING_FOOD_NUTRITION;
                    if ((scan.mature() > 0 || (canPlant && (scan.emptyFarmland() > 0
                            || scan.lost() > 0)) || bonemealCase)
                            && System.currentTimeMillis() >= farmBackoffUntil) {
                        LOG.info("[AIV][PLAN] " + npc.npcName() + " 农田维护触发 | 熟=" + scan.mature()
                                + " 空=" + scan.emptyFarmland() + " 坏=" + scan.lost()
                                + " 长=" + scan.growing());
                        return goal("农田维护", "farm", params("mode", "tend"));
                    }
                }
            } else if (!isNight() && foodNutrition() < MINING_FOOD_NUTRITION
                    && System.currentTimeMillis() >= farmBackoffUntil) {
                // 先有锄头再开田（玩家常识）：石锄优先、木锄兜底；
                // 材料不够就落空本步，主线砍树/挖石自然会把材料补上。
                if (!hasAny("wooden_hoe", "stone_hoe", "iron_hoe", "golden_hoe",
                        "diamond_hoe", "netherite_hoe")) {
                    if (cobble >= 2 && stickMaterial) {
                        return goal("制作石锄", "craft", params("item", "stone_hoe", "count", 1));
                    }
                    if (stickMaterial && wood >= (sticks >= 2 ? 2 : 4)) {
                        return goal("制作木锄", "craft", params("item", "wooden_hoe", "count", 1));
                    }
                } else {
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 开田触发 | foodNutrition="
                            + foodNutrition() + "/" + MINING_FOOD_NUTRITION
                            + "（hunt 不稳，田是根治项）");
                    return goal("开垦农田", "farm", params("mode", "establish"));
                }
            }
        }

        // 6.75) 落脚点（fix51·D2）：有了田或铁器雏形、开始有"怕丢的东西"时
        // 建火柴盒小屋作锚点（床/箱子/死后回程都挂它）。太早建拖主线、太晚
        // 建没意义：触发 =（有田 || 铁锭≥8）+ 建材富余 ≥80 + 白天 + 地表。
        // 已有落脚点（本世界账本）就永不再派。
        if (planHas(StagePlan.BASE) && hasSword && !underground() && !isNight()
                && System.currentTimeMillis() >= baseBackoffUntil) {
            com.example.aivillager.goals.BaseMemory.bindWorld(npc.level());
            if (!com.example.aivillager.goals.BaseMemory.hasBase(npc.npcName())) {
                // 【fix57】cobbled_deepslate 已并入 cobble 口径，这里不再单加防双算。
                int buildable = cobble + countItem("dirt") + countItem("andesite")
                        + countItem("diorite") + countItem("granite")
                        + countItem("tuff")
                        + countItem("stone") + countSuffix("_planks");
                com.example.aivillager.goals.FarmMemory.bindWorld(npc.level());
                boolean hasFarm = com.example.aivillager.goals.FarmMemory.hasFarm(npc.npcName());
                if (buildable >= 80 && (hasFarm || ingots >= 8)) {
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 建屋触发 | 建材=" + buildable
                            + " hasFarm=" + hasFarm + " ingots=" + ingots);
                    return goal("建落脚点", "base", params());
                }
            }
        }

        // 6.76) 床（fix56·D1）：落脚点建成后把床备齐——先有床才能谈真睡
        // 与重生点。包里有床先安置；没床时同色羊毛 ≥3 才合床（原版各色床
        // 独立配方、必须同色）。白天进行（夜里优先 5.55 睡觉/5.6 小屋）。
        if (planHas(StagePlan.BASE) && hasSword && !underground() && !isNight()
                && System.currentTimeMillis() >= bedBackoffUntil) {
            com.example.aivillager.goals.BaseMemory.bindWorld(npc.level());
            com.example.aivillager.goals.BaseMemory.Base baseForBed =
                    com.example.aivillager.goals.BaseMemory.base(npc.npcName());
            if (baseForBed != null && baseForBed.bedPos() == null) {
                if (countSuffix("_bed") > 0) {
                    return goal("安置床", "place_bed", params());
                }
                String woolColor = woolColorWithThree();
                if (woolColor != null) {
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 做床触发 | 同色羊毛="
                            + woolColor + "_wool x" + countItem(woolColor + "_wool"));
                    return goal("制作床", "craft", params("item", woolColor + "_bed", "count", 1));
                }
                // 【fix68·Fong 指示】线折算床料：蜘蛛掉的线 ×4（2×2）原版可合
                // 1 张白羊毛，一张床 = 白羊毛×3 = 线×12。白羊毛与线混算够 3
                // 即视为料齐：先派合成把线折成白羊毛（合成系统查原版配方表，
                // 无需加表），下一轮规划自然走上面的白床分支。其他颜色羊毛
                // 不与线混算（线只能合白羊毛），仍走同色规则。只做折算/合成，
                // 不为凑线新开打蜘蛛目标（夜闸/惜命优先，线靠自卫击杀自然攒）。
                int whiteWool = countItem("white_wool");
                int stringCount = countItem("string");
                int stringWool = stringCount / 4;
                if (whiteWool + stringWool >= 3) {
                    int craftWool = Math.min(3 - whiteWool, stringWool);
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 线折算床料触发 | 白羊毛="
                            + whiteWool + " 线=" + stringCount + " 可折=" + stringWool
                            + " 本次合白羊毛=" + craftWool);
                    return goal("线合成白羊毛", "craft",
                            params("item", "white_wool", "count", craftWool));
                }
            }
        }

        // 6.77) 存箱（fix69·P2 箱子）：回落脚点附近时把贵重物存进屋里箱子
        // （v1 只存不取）——玩家回家先存东西再出门，顺路办不专程跑：只在
        // 距锚点 ≤48 格、白天地表时触发。没箱先做箱（8 木板）；有箱（已安置
        // 或包里有）且包里有存入名单物才派存箱目标。名单与执行层 STORE_LOOT
        // 同步（贵重物 + 第二档杂物 + 铁锭超 32 的部分）。
        if (planHas(StagePlan.BASE) && hasSword && !underground() && !isNight()
                && System.currentTimeMillis() >= storeBackoffUntil) {
            com.example.aivillager.goals.BaseMemory.bindWorld(npc.level());
            com.example.aivillager.goals.BaseMemory.Base baseForStore =
                    com.example.aivillager.goals.BaseMemory.base(npc.npcName());
            if (baseForStore != null
                    && npc.blockPosition().distSqr(baseForStore.anchor()) <= 48.0D * 48.0D) {
                if (baseForStore.chestPos() == null && countItem("chest") == 0
                        && countSuffix("_planks") >= 8) {
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 做箱子触发 | 木板="
                            + countSuffix("_planks"));
                    return goal("制作箱子", "craft", params("item", "chest", "count", 1));
                }
                if ((baseForStore.chestPos() != null || countItem("chest") > 0)
                        && hasStorableLoot()) {
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " 存箱触发 | 钻石="
                            + countItem("diamond") + " 珍珠=" + countItem("ender_pearl")
                            + " 骨头=" + countItem("bone") + " 线=" + countItem("string")
                            + " 铁锭=" + countItem("iron_ingot")
                            + " 红石=" + countItem("redstone"));
                    return goal("回箱存物", "store_loot", params());
                }
            }
        }

        // 6.78) 死后回程（fix79·D3）：死亡点有账就回现场续干，别把挖了一半
        // 的矿洞扔下另起炉灶（第 6 死后它换到新区重挖整条竖井，实证浪费）。
        // 物资门：夜里不跑尸（路上送命）、nutrition 不够不回（回程靠顺位实现
        // "不足先补给"——口粮步 6.6 排在本步之前会先派活，本步再查硬线双
        // 保险）、没镐回了也干不了活。到点销账在 decide 内就地判定；账本
        // 2 小时过期（DeathMemory 读时失效），连败靠阶段跳过兜底。
        com.example.aivillager.goals.DeathMemory.bindWorld(npc.level());
        com.example.aivillager.goals.DeathMemory.Death deathReturn =
                com.example.aivillager.goals.DeathMemory.death(npc.npcName());
        if (deathReturn != null) {
            BlockPos deathPos = deathReturn.pos();
            double deathDx = deathPos.getX() + 0.5D - npc.getX();
            double deathDz = deathPos.getZ() + 0.5D - npc.getZ();
            double deathDist = Math.sqrt(deathDx * deathDx + deathDz * deathDz);
            if (deathDist <= 8.0D && Math.abs(deathPos.getY() - npc.getY()) <= 8.0D) {
                com.example.aivillager.goals.DeathMemory.clear(npc.npcName());
                LOG.info("[AIV][PLAN] " + npc.npcName() + " 死后回程销账 | 已回到死亡点 "
                        + deathPos.toShortString() + " 附近，续干");
            } else if (!isNight() && foodNutrition() >= 12
                    && (stonePickaxes > 0 || countItem("iron_pickaxe") > 0
                    || countItem("diamond_pickaxe") > 0)) {
                LOG.info("[AIV][PLAN] " + npc.npcName() + " 死后回程触发 | 死亡点="
                        + deathPos.toShortString() + " 距离=" + Math.round(deathDist)
                        + " foodNutrition=" + foodNutrition());
                return goal("死后回程", "travel",
                        params("x", deathPos.getX(), "y", deathPos.getY(),
                                "z", deathPos.getZ()));
            }
        }

        // 6.79) 搜箱（fix80·D12）：地表白天活动时，16 格内有没搜过的世界
        // 箱子就顺路搜（Fong 拍板：村庄只搜箱子、只拿用得上的）。感知口径
        // （status-round21 D12 定案）：规划层扫方块只作"瞥见"，执行层必须
        // 真走到箱边（寻路门）——隔墙封死的箱子到不了就放弃记账。自家存箱
        // （BaseMemory chestPos）显式排除，防它搜自己刚存的东西。
        if (!underground() && !isNight()) {
            com.example.aivillager.goals.LootMemory.bindWorld(npc.level());
            com.example.aivillager.goals.BaseMemory.bindWorld(npc.level());
            com.example.aivillager.goals.BaseMemory.Base baseForLoot =
                    com.example.aivillager.goals.BaseMemory.base(npc.npcName());
            BlockPos ownChest = baseForLoot != null ? baseForLoot.chestPos() : null;
            BlockPos feetPos = npc.blockPosition();
            BlockPos foundChest = null;
            double foundSq = Double.MAX_VALUE;
            for (int scanDx = -16; scanDx <= 16; scanDx++) {
                for (int scanDz = -16; scanDz <= 16; scanDz++) {
                    for (int scanDy = -2; scanDy <= 3; scanDy++) {
                        BlockPos scanPos = feetPos.offset(scanDx, scanDy, scanDz);
                        net.minecraft.world.level.block.state.BlockState scanState =
                                npc.level().getBlockState(scanPos);
                        if (!scanState.is(net.minecraft.world.level.block.Blocks.CHEST)
                                && !scanState.is(
                                        net.minecraft.world.level.block.Blocks.TRAPPED_CHEST)) {
                            continue;
                        }
                        if (scanPos.equals(ownChest)
                                || com.example.aivillager.goals.LootMemory.isLooted(
                                        npc.npcName(), scanPos)) {
                            continue;
                        }
                        double scanSq = scanPos.distSqr(feetPos);
                        if (scanSq < foundSq) {
                            foundSq = scanSq;
                            foundChest = scanPos;
                        }
                    }
                }
            }
            if (foundChest != null) {
                LOG.info("[AIV][PLAN] " + npc.npcName() + " 搜箱触发 | 箱="
                        + foundChest.toShortString() + " 距离="
                        + Math.round(Math.sqrt(foundSq)));
                return goal("搜刮箱子", "loot_chest",
                        params("x", foundChest.getX(), "y", foundChest.getY(),
                                "z", foundChest.getZ()));
            }
        }

        // 6.8) 细雪地带皮靴驱动（第十九轮，Fong 拍板）：出生/活动区在雪原、
        // 针叶林等细雪地带时，皮靴从"顺手做"升级为"优先做"——没皮靴在雪地
        // 里寸步难行（寻路已避开细雪，没靴寸步难行的是探索范围被雪原封死）。
        // 皮不够就去打猎（hunt 已优先牛、牛肉皮革双收）；【第二十四轮】退避
        // 改皮革专属记账（见字段注释）：共享 failStreak 在交替派活时永远攒
        // 不到 2，旧门形同虚设、皮革与挖铁 ping-pong 到长跑收尾。
        if (planHas(StagePlan.LEATHER_BOOTS) && npc.inPowderSnowCountry()
                && !hasAny("leather_boots") && leather < 4 && hasSword
                && System.currentTimeMillis() >= leatherBackoffUntil) {
            LOG.info("[AIV][PLAN] " + npc.npcName() + " 细雪地带优先皮靴 | biome="
                    + npc.biomePath() + " leather=" + leather);
            return goal("找皮革做皮靴", "hunt",
                    params("need", "leather", "stock", LEATHER_STOCK_TARGET, "radius", 12));
        }

        // 7) 冶炼（原第 9 步，【第三十四轮】提到挖煤/挖铁之前）：手里有生铁、有熔炉、
        // 有燃料就先炼。长跑#11 实证：冶炼原排在挖煤(原7)/挖铁(原8)两个"存量达标型"
        // 长目标之后——挖煤目标几乎常驻、单次以分钟计且在雪原地表反复零进展失败，
        // 其跳过条件 coalStarved 又靠共享 failStreak（被口粮/皮革 hunt 交替改写、永远
        // 凑不满 2），结果生铁在包里躺 1 小时以上、冶炼/挖铁/铁装备全程 0 次。
        // 玩家是有料先炼、先把铁镐铁剑做出来再去挖新的，这里把转化口钉在挖矿之前。
        // 燃料口径不变：规划层 canFuel 与执行层 pickFuel 都认煤/木炭/木板/原木/木棍，
        // #11 不是"木头不算燃料"，是根本轮不到冶炼这一步。
        // 【fix38·Fong 04:13】触发改双路径并分别记账：需求触发（急用先烧、有几块烧
        // 几块）与补仓触发（无急用时生铁攒到 RAW_IRON_BATCH 批量烧、铁锭补回常备线；
        // 另设低位维持小批量）。批量是习惯不是门槛，见 smelt 触发= 日志行对账。
        if (planHas(StagePlan.SMELT_IRON) && rawIron > 0 && hasFurnace && canFuel()) {
            if (ingots < 4) {
                LOG.info("[AIV][PLAN] " + npc.npcName() + " smelt 触发=demand | ingots=" + ingots
                        + " rawIron=" + rawIron);
                return goal("冶炼铁锭", "smelt",
                        params("item", "iron_ingot", "count", Math.min(8, rawIron)));
            }
            if (rawIron >= RAW_IRON_BATCH && ingots < INGOT_RESERVE_TARGET) {
                LOG.info("[AIV][PLAN] " + npc.npcName() + " smelt 触发=restock | rawIron=" + rawIron
                        + " ingots=" + ingots + " 目标=" + INGOT_RESERVE_TARGET);
                return goal("冶炼铁锭（补仓）", "smelt",
                        params("item", "iron_ingot", "count", Math.min(8, rawIron)));
            }
            if (ingots < 8 && rawIron >= 8) {
                LOG.info("[AIV][PLAN] " + npc.npcName() + " smelt 触发=restock-low | rawIron=" + rawIron
                        + " ingots=" + ingots);
                return goal("冶炼铁锭（补仓）", "smelt",
                        params("item", "iron_ingot", "count", Math.min(8 - ingots, rawIron)));
            }
        }

        // 7.9) 【fix48】记矿回访前置——"下潜之前必查"（fix40 的 12.9 顺位后置
        // 被两局实证架空：针叶林/沙漠局账本条目 attempts 恒 0——12.9 排在全部
        // 主线步之后，而铁器时代主线永远有活（挖铁→合成→再挖），回访永远轮
        // 不到；等主线真空档时条目又多半已过 2 小时有效期）。现前置到步 8
        // （铁装备）之前：步 8 起全是会下潜/远行的挖矿步，每轮 decide 先查账
        // 本——有工具够得着的记账矿就先去收了再下潜；步 7 冶炼与更早的合成/
        // 采集步不下潜、仍排在回访前面（回访一趟远行不该打断手头冶炼）。
        // 账本已按世界打标（OreMemory.bindWorld），异世界串账条目读时剔除。
        com.example.aivillager.goals.OreMemory.bindWorld(npc.level());
        for (com.example.aivillager.goals.OreMemory.Entry oreEntry
                : com.example.aivillager.goals.OreMemory.entries(npc.npcName())) {
            int ironTierPicks = countUsableItem(
                    "iron_pickaxe", "diamond_pickaxe", "netherite_pickaxe");
            boolean tierOk = switch (oreEntry.needTier()) {
                case 2 -> ironTierPicks > 0;
                case 1 -> stonePickaxes > 0 || ironTierPicks > 0;
                default -> true;
            };
            if (!tierOk) {
                continue;
            }
            LOG.info("[AIV][PLAN] " + npc.npcName() + " 回访记账矿 | " + oreEntry.block()
                    + " @ " + oreEntry.x() + "," + oreEntry.y() + "," + oreEntry.z()
                    + " needTier=" + oreEntry.needTier());
            return goal("回访记账矿", "revisit",
                    params("x", oreEntry.x(), "y", oreEntry.y(), "z", oreEntry.z(),
                            "resource", oreEntry.block().replace("_ore", "")));
        }

        // 8) 铁制装备（原第 10 步，【第三十四轮】随冶炼一并提前到挖煤/挖铁之前）：
        // 剑 → 镐 → 胸甲 → 头盔 → 护腿 → 靴子。本块是阻塞式的（缺锭自己炼/挖），
        // 只要轮得到就能自驱走完铁线；原排序下被挖煤常驻目标挡在后面，贫瘠群系
        // 永远轮不到（长跑#11 实证），故随冶炼一起提前。
        // 每一件都要求背包里真的有对应数量的锭，避免反复下"材料不够"的合成目标。
        if (planHas(StagePlan.IRON_GEAR)
                && !hasAny("iron_sword", "diamond_sword", "netherite_sword") && ingots >= 2) {
            if (!stickMaterial) {
                return gatherLogs("铁剑缺木棍");
            }
            return goal("制作铁剑", "craft", params("item", "iron_sword", "count", 1));
        }
        if (planHas(StagePlan.IRON_GEAR) && !hasIronPickaxe && ingots >= 3) {
            if (!stickMaterial) {
                return gatherLogs("铁镐缺木棍");
            }
            return goal("制作铁镐", "craft", params("item", "iron_pickaxe", "count", 1));
        }
        // 【第十六轮】四件铁甲改"缺锭就挖、够锭就造"的阻塞式（照 13.1 钻石甲的
        // 现成写法）：旧版每件都带 ingots>=门槛才合成、不够就穿堂而过——便宜的
        // 靴子(4)/备用镐(3) 总是先把锭花掉，胸甲(8) 永远攒不出来，长跑#6 实测
        // 只穿头盔+靴子（armor=4）就下钻石层，胸甲/护腿整轮没造。缺锭时先炼掉
        // 手里的生铁，再按差额挖铁，把这一件钉死造出来再走下一件。
        if (planHas(StagePlan.IRON_GEAR)
                && !hasAny("iron_chestplate", "diamond_chestplate", "netherite_chestplate")) {
            if (rawIron > 0 && hasFurnace && canFuel()) {
                LOG.info("[AIV][PLAN] " + npc.npcName() + " smelt 触发=demand need=铁胸甲 | ingots=" + ingots
                        + " rawIron=" + rawIron);
                return goal("冶炼铁锭（铁胸甲）", "smelt",
                        params("item", "iron_ingot", "count", Math.min(8, rawIron)));
            }
            if (ingots >= 8) {
                return goal("制作铁胸甲", "craft", params("item", "iron_chestplate", "count", 1));
            }
            return goal("挖铁（铁胸甲）", "dig",
                    params("resource", "iron", "count", Math.max(1, 8 - ingots - rawIron)));
        }
        if (planHas(StagePlan.IRON_GEAR)
                && !hasAny("iron_helmet", "diamond_helmet", "netherite_helmet")) {
            if (rawIron > 0 && hasFurnace && canFuel()) {
                LOG.info("[AIV][PLAN] " + npc.npcName() + " smelt 触发=demand need=铁头盔 | ingots=" + ingots
                        + " rawIron=" + rawIron);
                return goal("冶炼铁锭（铁头盔）", "smelt",
                        params("item", "iron_ingot", "count", Math.min(8, rawIron)));
            }
            if (ingots >= 5) {
                return goal("制作铁头盔", "craft", params("item", "iron_helmet", "count", 1));
            }
            return goal("挖铁（铁头盔）", "dig",
                    params("resource", "iron", "count", Math.max(1, 5 - ingots - rawIron)));
        }
        if (planHas(StagePlan.IRON_GEAR)
                && !hasAny("iron_leggings", "diamond_leggings", "netherite_leggings")) {
            if (rawIron > 0 && hasFurnace && canFuel()) {
                LOG.info("[AIV][PLAN] " + npc.npcName() + " smelt 触发=demand need=铁护腿 | ingots=" + ingots
                        + " rawIron=" + rawIron);
                return goal("冶炼铁锭（铁护腿）", "smelt",
                        params("item", "iron_ingot", "count", Math.min(8, rawIron)));
            }
            if (ingots >= 7) {
                return goal("制作铁护腿", "craft", params("item", "iron_leggings", "count", 1));
            }
            return goal("挖铁（铁护腿）", "dig",
                    params("resource", "iron", "count", Math.max(1, 7 - ingots - rawIron)));
        }
        if (planHas(StagePlan.IRON_GEAR)
                && !hasAny("iron_boots", "diamond_boots", "netherite_boots")) {
            if (rawIron > 0 && hasFurnace && canFuel()) {
                LOG.info("[AIV][PLAN] " + npc.npcName() + " smelt 触发=demand need=铁靴 | ingots=" + ingots
                        + " rawIron=" + rawIron);
                return goal("冶炼铁锭（铁靴）", "smelt",
                        params("item", "iron_ingot", "count", Math.min(8, rawIron)));
            }
            if (ingots >= 4) {
                return goal("制作铁靴", "craft", params("item", "iron_boots", "count", 1));
            }
            return goal("挖铁（铁靴）", "dig",
                    params("resource", "iron", "count", Math.max(1, 4 - ingots - rawIron)));
        }
        // 9) 煤（原第 7 步，【第三十四轮】顺延到冶炼/铁装备之后）。
        // 【fix38·Fong 04:14】拆除常驻挖煤目标：煤是顺路型储备——掘进时看到裸露煤
        // 顺手挖（技能层 strip 顺手扫描，存量近 COAL_OPPORTUNISTIC_CAP 就跳过省
        // 镐耐久），规划层不再为凑煤立项。#11 的病根正是"挖煤存量目标常驻、把
        // 冶炼/铁装备饿死"，硬目标思维勿重蹈。这里只留燃料告急兜底（安全型）：
        // 煤与木炭皆 0（coal 计数已含木炭）、木燃料当量也将尽时，才许挖一小批
        // 煤自救，脱离告急即停。连续两次失败 = 当前矿层确实没有煤，退避让路
        // （玩家这时会改烧木炭，而不是永远卡在"煤不足"上）。
        boolean coalStarved = failStreak >= 2 && lastFailedStage != null
                && (lastFailedStage.equals("挖煤") || lastFailedStage.startsWith("燃料告急"));
        if (planHas(StagePlan.COAL) && coal == 0 && wood < 16 && hasStonePickaxe && !coalStarved) {
            return goal("燃料告急挖煤", "dig",
                    params("resource", "coal", "count", 16));
        }

        // 10) 铁矿石（原第 8 步，【第三十四轮】顺延）：石镐就能挖，Y=16 附近最密集。
        // 【第二十二轮】同理按存量差额：生铁+铁锭合计补到 8（够镐+剑+余量）。
        // 【fix88】连败退避中跳过本步：补 8 锭是早期口径，钻石时代（剑/甲已
        // 钻石化、保镐门在步 11 独立把关）它只剩补仓价值；farm47 实证水涝贫
        // 铁区三连 0/4 时它每轮重派、把 13.1 钻石甲（钻石 ×10 够护腿）无限
        // 期挡住——违 Fong「常备数软向导、不许硬指标卡进度」。退避让位主
        // 线（补仓另有步 13.3 在主线后兜底），退避到期照常重试。
        if (planHas(StagePlan.IRON_ORE) && rawIron + ingots < 8 && hasStonePickaxe
                && System.currentTimeMillis() >= ironStepBackoffUntil) {
            return goal("挖铁", "dig",
                    params("resource", "iron", "count", 8 - rawIron - ingots));
        }


        // 11) 钻石前的工具保障：至少 2 把可用铁镐，且总剩余耐久 >= 250。
        // 先补铁镐，再继续挖铁——否则"只有 1 把铁镐但只剩 2 锭"会被判成"继续挖铁"，
        // 然后铁镐在 descend 里碎掉，NPC 从 Y=-54 卡死再重生于出生点，白费 10 分钟。
        // 【第十七轮】旧版只数把数、不看耐久：2 把残血铁镐也算"保障达标"，
        // 长跑#6d 实测补满 2 把新铁镐不到 6 分钟全碎（身体选工具专烧铁镐，
        // 已在身体层改成"最便宜够用"）；这里把注释早写了的总耐久门槛落成真判定：
        // 两把合计不足一把新镐（250）就当没保障，先补再走。
        int usableIronPickaxes = countUsableItem("iron_pickaxe", "diamond_pickaxe", "netherite_pickaxe");
        int ironPickDurability = totalRemainingDurability(
                "iron_pickaxe", "diamond_pickaxe", "netherite_pickaxe");
        if (planHas(StagePlan.SPARE_PICKS) && (usableIronPickaxes < 2 || ironPickDurability < 250)
                && System.currentTimeMillis() >= pickCraftBackoffUntil) {
            if (!stickMaterial) {
                return gatherLogs("备用铁镐缺木棍");
            }
            if (ingots >= 3) {
                return goal("补一把备用铁镐", "craft",
                        params("item", "iron_pickaxe", "count", usableIronPickaxes + 1));
            }
            return goal("补铁做备用铁镐", "dig", params("resource", "iron", "count", 4));
        }
        // 12) 钻石阶段的工具够了，但装备还缺锭（造剑/护甲/下界传送门的铁桶+打火石）：
        // 这时才回头挖铁，别在钻矿前浪费时间。
        if (planHas(StagePlan.IRON_ORE) && ingots < 8 && hasStonePickaxe && rawIron == 0 && diamonds >= 3) {
            return goal("继续挖铁", "dig", params("resource", "iron", "count", 4));
        }
        if (planHas(StagePlan.SMELT_IRON) && rawIron > 0 && ingots < INGOT_RESERVE_TARGET
                && hasFurnace && canFuel()) {
            // 【fix38】归入补仓路径记账：铁消费步都走完后，把手头生铁继续烧向常备线，
            // 烧满 INGOT_RESERVE_TARGET 就停（多余的生铁留作生铁形态，不浪费燃料）。
            LOG.info("[AIV][PLAN] " + npc.npcName() + " smelt 触发=restock | rawIron=" + rawIron
                    + " ingots=" + ingots + " 目标=" + INGOT_RESERVE_TARGET);
            return goal("继续冶炼", "smelt",
                    params("item", "iron_ingot", "count", Math.min(8, rawIron)));
        }

        // 12.5) 烤肉前置（【fix38】，原步 13 位于钻石段之后）：有生肉+熔炉+燃料
        // 就先烤——熟肉营养是生肉两倍以上，能把口粮需求砍半的加工步骤不能被它
        // 服务的下矿口粮门挡在后面（#12 实证卡点）。转化手头存货不算追储备，
        // 与冶炼补仓同理，位置放在钻石段之前、补给区之首。
        String rawMeatEarly = firstRawMeat();
        if (planHas(StagePlan.FOOD) && rawMeatEarly != null && hasFurnace && canFuel()) {
            return goal("烤肉", "smelt",
                    params("item", cookedOf(rawMeatEarly), "count", 8));
        }

        // 12.6) 农产品加工（fix49·D11）：只有富余才进口粮——小麦做面包
        // （3 小麦=1 面包，Wiki；小麦本身不能直接吃），土豆烤熟再吃且只烤
        // 留苗线（8）以上的富余。都排在钻石段之前，口径同烤肉前置。
        int wheatCount = countItem("wheat");
        if (planHas(StagePlan.FARMING) && wheatCount >= 3) {
            return goal("做面包", "craft",
                    params("item", "bread", "count", countItem("bread") + wheatCount / 3));
        }
        int potatoCount = countItem("potato");
        if (planHas(StagePlan.FARMING) && potatoCount > 8 && hasFurnace && canFuel()) {
            return goal("烤土豆", "smelt",
                    params("item", "baked_potato", "count", potatoCount - 8));
        }

        // 13) 钻石：铁镐才能挖，Y=-54 附近最多。
        if (planHas(StagePlan.IRON_ORE) && !hasIronPickaxe) {
            // 铁锭不够但铁也挖完了：继续挖铁，别在原地空等。
            return goal("积攒铁锭", "dig", params("resource", "iron", "count", 4));
        }
        // usableIronPickaxes / ironPickDurability 已在前面算过了，直接复用。
        if (planHas(StagePlan.DIAMOND) && diamonds < 3 && !hasAny("diamond_pickaxe", "netherite_pickaxe")) {
            // 【第十七轮】钻石阶段内的保镐：下钻石层前和每轮重下挖钻目标前都复核——
            // 没有铁镐以上、或铁镐总耐久不足 60（挖一簇矿+容错都不够）时，先原地补镐
            // 再下，别再出现长跑#6d 那种"矿在眼前（58,-50,23）、手里只剩石镐、
            // mine-no-tool 刷 159 次最后 0/3 放弃"。补镐链：有锭先造、没锭先炼、
            // 没生铁先挖铁；人尽量留在矿区附近，补完回来 ore-scan 还能再找到那簇矿。
            if (!hasIronPickaxe || ironPickDurability < 60) {
                if (ingots >= 3) {
                    if (!stickMaterial) {
                        return gatherLogs("钻石前补铁镐缺木棍");
                    }
                    return goal("挖钻石前补铁镐", "craft",
                            params("item", "iron_pickaxe", "count", usableIronPickaxes + 1));
                }
                if (rawIron > 0 && hasFurnace && canFuel()) {
                    LOG.info("[AIV][PLAN] " + npc.npcName() + " smelt 触发=demand need=钻石前补镐 | ingots=" + ingots
                            + " rawIron=" + rawIron);
                    return goal("挖钻石前炼铁补镐", "smelt",
                            params("item", "iron_ingot", "count", Math.min(8, rawIron)));
                }
                return goal("挖钻石前挖铁补镐", "dig", params("resource", "iron", "count", 4));
            }
            Step foodGate = miningFoodGate(hasSword);
            if (foodGate != null) {
                return foodGate;
            }
            // 【fix38】下潜预算一次查齐留痕（安全型硬门汇总）：口粮已由上方
            // miningFoodGate 把关，这里把木料当量/煤/铁镐耐久一并打印，
            // 验收按 descent-budget 行对账，不再逐项猜卡点。
            LOG.info("[AIV][PLAN] " + npc.npcName() + " descent-budget | food=" + foodNutrition()
                    + "/" + MINING_FOOD_NUTRITION + " wood=" + wood + " coal=" + coal
                    + " ironPickDurability=" + ironPickDurability + "/250"
                    + " stonePickaxes=" + stonePickaxes);
            return goal("挖钻石", "dig", params("resource", "diamond", "count", 3));
        }
        if (planHas(StagePlan.DIAMOND) && diamonds >= 3 && !hasAny("diamond_pickaxe", "netherite_pickaxe")) {
            if (!stickMaterial) {
                return gatherLogs("钻石镐缺木棍");
            }
            return goal("制作钻石镐", "craft", params("item", "diamond_pickaxe", "count", 1));
        }
        if (planHas(StagePlan.DIAMOND_SWORD) && diamonds >= 2 && !hasAny("diamond_sword", "netherite_sword")) {
            if (!stickMaterial) {
                return gatherLogs("钻石剑缺木棍");
            }
            return goal("制作钻石剑", "craft", params("item", "diamond_sword", "count", 1));
        }

        // 13.05) 【fix70·D4】水桶常备：D4 规格是"下矿常带一桶水"（灭火/防
        // 岩浆），不是等造下界门（13.5）才做桶——挖钻石阶段正贴着岩浆层，
        // 没桶时着火只能硬烧。软向导口径：铁锭 ≥6 才做桶（花 3 留 ≥3 保底，
        // 3~5 锭不硬凑、不为桶新开挖铁目标，13.5 自有补铁兜底）；装水只在
        // 地表派（下矿前装满），地下不派（fillbucket 的挖路模式会打断矿线）。
        // 不挂 StagePlan 门控：安稳模式同样要防岩浆。生存层的倒水自救见
        // SurvivalController.douseWithWaterBucket（fix70 同版）。
        if (!hasAny("bucket", "water_bucket")) {
            if (ingots >= 6) {
                LOG.info("[AIV][PLAN] " + npc.npcName() + " 水桶常备触发 | 铁锭=" + ingots
                        + "（做桶 3 锭、留保底）");
                return goal("制作铁桶", "craft", params("item", "bucket", "count", 1));
            }
        } else if (!hasAny("water_bucket") && !underground()
                && System.currentTimeMillis() >= bucketFillBackoffUntil) {
            LOG.info("[AIV][PLAN] " + npc.npcName() + " 装水常备触发 | 有空桶、地表、先装满再下矿");
            return goal("装满水桶", "fillbucket", params());
        }

        // 13.06) 【fix71·D5】火把常备：矿道照明的消耗品（执行层 maybePlaceTorch
        // 只在真黑且 8 格内无火把时插）。存量 <8 且有煤/木炭+木棍（或木板，
        // 合成系统自链）就补 8 支（2 次配方）；没料跳过、不新开采集目标。
        if (countItem("torch") < 8
                && (countItem("coal") > 0 || countItem("charcoal") > 0)
                && (countItem("stick") > 0 || countSuffix("_planks") > 0)) {
            LOG.info("[AIV][PLAN] " + npc.npcName() + " 火把常备触发 | 火把="
                    + countItem("torch") + " 煤=" + countItem("coal"));
            return goal("制作火把", "craft", params("item", "torch", "count", 8));
        }

        // 13.07) 【fix76·D6】盾：规格=材料富余且已有武器才做（盾是武器的搭档，
        // 空手举盾没意义）。铁锭 ≥6 时做一块（耗 1 锭+6 木板，不动工具/护甲的
        // 需求冶炼优先级）；副手装备与格挡在生存层（equipShieldOffhand/defend）。
        // hasAny 按 41 格全扫（含副手），盾在副手也算"有"，不会重复合成。
        if (!hasAny("shield")
                && hasAny("wooden_sword", "stone_sword", "iron_sword", "golden_sword",
                        "diamond_sword", "netherite_sword")
                && ingots >= 6) {
            LOG.info("[AIV][PLAN] " + npc.npcName() + " 盾常备触发 | 铁锭=" + ingots
                    + "（有武器、材料富余，做盾）");
            return goal("制作盾牌", "craft", params("item", "shield", "count", 1));
        }

        // 13.1) 钻石甲（第三轮新增，StagePlan.DIAMOND_ARMOR）：镐和剑都有了之后，
        // 继续挖钻石凑齐四件甲（头盔 5 + 胸甲 8 + 护腿 7 + 靴子 4 = 24 颗），再走下界线。
        // 原版路线里钻石只预算了镐(3)+剑(2)，长跑验收因此判定"钻石套不可能"——这里补齐。
        // 穿戴不用管：生存层的 AutoArmor 会把做好的甲自动穿上；hasAny 已含装备槽，
        // 穿在身上的那件算"有"，不会重复合成。护甲配方不需要木棍，不查 stickMaterial。
        // dig 的 count 是"这一趟新挖多少"，所以每件只挖还差的差额，别多挖浪费时间。
        if (planHas(StagePlan.DIAMOND_ARMOR) && hasAny("diamond_pickaxe", "netherite_pickaxe")) {
            String[][] diaArmor = {
                    {"diamond_helmet", "netherite_helmet", "制作钻石头盔"},
                    {"diamond_chestplate", "netherite_chestplate", "制作钻石胸甲"},
                    {"diamond_leggings", "netherite_leggings", "制作钻石护腿"},
                    {"diamond_boots", "netherite_boots", "制作钻石靴子"},
            };
            int[] diaCost = {5, 8, 7, 4};
            for (int i = 0; i < diaArmor.length; i++) {
                if (hasAny(diaArmor[i][0], diaArmor[i][1])) {
                    continue;
                }
                if (diamonds >= diaCost[i]) {
                    return goal(diaArmor[i][2], "craft",
                            params("item", diaArmor[i][0], "count", 1));
                }
                Step armorFoodGate = miningFoodGate(hasSword);
                if (armorFoodGate != null) {
                    return armorFoodGate;
                }
                return goal("挖钻石（钻石甲）", "dig",
                        params("resource", "diamond", "count", diaCost[i] - diamonds));
            }
        }

        // 13.0) 弓与箭挪到传送门准备之后（见 13.2）：
        // 蜘蛛只在夜晚出没，先凑弓弦会让白天的整条下线主链（下界→末影珍珠）全堵在后面。

        // （原 12.9 记矿回访已由 fix48 前置到步 8 之前——"下潜之前必查"，见彼处。）

        // 13) 食物储备（顺路型软向导，【fix38】目标改常备口径）：有剑以后打猎，
        // 把储备补到"熟食为主、总营养 FOOD_RESERVE_NUTRITION 且份数
        // FOOD_RESERVE_PORTIONS"——储备是平时状态（Fong 04:11），排在主线之后
        // 只填空档；真正挡下潜的是 miningFoodGate 的 24 安全门（步骤内另查），
        // 两者分工不混。生肉已由 12.5 烤肉前置转化，这里 hunt 补的是总量缺口。
        // 【第二十四轮】去掉旧版 foodLevel<16 的饥饿条门槛：储备看背包存量，
        // 饥饿条满格时包里照样可能一口吃的都没有（长跑#8 F 段实证）。
        if (planHas(StagePlan.FOOD) && hasSword
                && (foodNutrition() < FOOD_RESERVE_NUTRITION
                    || countFood() < FOOD_RESERVE_PORTIONS)) {
            // 【fix97】地下不派 hunt：农场动物只在地表，地下 hunt 的 relocate
            // 只会在洞网里挖石空转（farm57 实证：地表派单被 relocate 带进出生
            // 点旧井区洞网、数分钟无果；死亡 #8 地下 restock 同型）。步 0/6.6
            // 都有 underground→ascend 先回地表，唯独步 13 漏了，此处补齐。
            // 夜里升到地表后由步 13 夜闸在下一轮拦，不会半夜地表裸猎。
            if (underground()) {
                LOG.info("[AIV][PLAN] " + npc.npcName() + " 储备食物 hunt 不在地下派：先回地表 | foodNutrition="
                        + foodNutrition());
                return skill("储备食物：先回地表", "ascend");
            }
            // 【fix39·夜晚总闸】储备是顺路型软向导：夜里地表不为囤食远行打
            // 猎，落到补仓/维持步，天亮再补。挨饿急救走步 0/6.6，不经这里。
            if (isNight() && !underground()) {
                LOG.info("[AIV][PLAN] " + npc.npcName() + " 食物储备 hunt 夜里跳过（夜晚总闸） | foodNutrition=" + foodNutrition());
            } else if (System.currentTimeMillis() < reserveBackoffUntil) {
                // 【fix98】储备 hunt 连败退避中：软向导让位主线，落到 13.3 补仓/
                // 13.5 下界链（入境口粮由 fix96 软闸另行把关）；退避到期回步 13 重试。
                LOG.info("[AIV][PLAN] " + npc.npcName() + " 储备食物退避中，让位主线 | 连败=" + reserveFailStreak
                        + " 退避余秒=" + (reserveBackoffUntil - System.currentTimeMillis()) / 1000L
                        + " foodNutrition=" + foodNutrition());
            } else {
                return goal("储备食物", "hunt",
                        params("need", "food", "stock", FOOD_RESERVE_NUTRITION, "radius", 12));
            }
        }

        // 13.3) 常备补仓（【fix38】顺路型软向导，Fong 04:11/04:14）：主线步骤都
        // 走完才轮到这里——原木补到 LOG_RESERVE_TARGET（实物计数）、铁
        // （生铁+铁锭）补到 INGOT_RESERVE_TARGET。只填空档、永不挡主线；
        // 下潜硬门（木料安全线/口粮门/铁镐耐久门）在各自位置另行把关，
        // 与补仓互不替代。挖铁补仓单趟封顶 16，防一趟挖太久变相常驻。
        // 【fix39·夜晚总闸】原木补仓同为顺路型：夜里地表跳过，天亮再补；
        // 挖铁补仓走地下、不受影响。
        if (planHas(StagePlan.WOOD_RESERVE) && logs < LOG_RESERVE_TARGET && !underground() && !isNight()) {
            return gatherLogs("原木常备补仓");
        }
        if (planHas(StagePlan.IRON_ORE) && rawIron + ingots < INGOT_RESERVE_TARGET
                && hasStonePickaxe) {
            return goal("铁储备补仓", "dig",
                    params("resource", "iron", "count",
                            Math.min(16, INGOT_RESERVE_TARGET - rawIron - ingots)));
        }

        // 13.5) 下界传送门准备（通关路线的前置门槛）。
        // 顺序严格照玩家做法：铁桶 -> 装水 -> 挖燧石 -> 打火石 -> 钻石镐浇铸 20 黑曜石 -> 建门进入。
        // 这里用"眼睛不足 12 个"作为整个通关链的入口条件：
        // 已经有 12 只眼睛说明下界阶段完成了，不应该再回头造门。
        //
        // 【模式分界】安稳发育模式到此为止：钻石套已齐（13.1 走完）、食物储备已在上面处理，
        // 之后只维持生存，不再进入下界/末地线。通关模式不受影响，继续往下走原链。
        // sustain 是终点站语义（第七轮起由计划决定，不再只看模式）：计划里有 sustain
        // 且没启用任何后期阶段时，到此停步进维持循环；后期阶段同时启用时 sustain
        // 不触发（StagePlan.resolvePlan 已在加载时打过 plan-note 提醒）。
        boolean lateStagesEnabled = planHas(StagePlan.PORTAL_PREP) || planHas(StagePlan.NETHER)
                || planHas(StagePlan.STRONGHOLD) || planHas(StagePlan.END);
        if (planHas(StagePlan.SUSTAIN) && !lateStagesEnabled) {
            return sustainStep(food, hasSword, wood);
        }
        // 【fix96·审计C】入境口粮软闸：步 13 储备（目标营养 40）排在本段之前，
        // 白天路径营养已被顶住；漏口在夜里——步 13 被夜晚总闸只记日志跳过、
        // 直落本段带着低口粮建门/进门，下界没有计划内食物来源。整段加软闸：
        // food<14 或随身营养 <20 时本轮不派下界链（落到后续维持步，补给步在
        // 前序/天亮后自会把口粮顶上来），与 fix86 下矿口粮门同族、只拦不锁。
        boolean netherFoodOk = npc.getFoodData().getFoodLevel() >= 14 && foodNutrition() >= 20;
        if (planHas(StagePlan.PORTAL_PREP) && eyes < 12 && !netherFoodOk) {
            LOG.info("[AIV][PLAN] " + npc.npcName() + " [NETHER] 入境口粮软闸未过，本轮不派下界链 | food="
                    + npc.getFoodData().getFoodLevel() + " foodNutrition=" + foodNutrition()
                    + "（需 food>=14 且 nutrition>=20）");
        }
        if (planHas(StagePlan.PORTAL_PREP) && eyes < 12 && netherFoodOk) {
            // 【fix93·D8】下界金装闸：Fong 定下界必穿一件金装（猪灵只认
            // 身上的金子，没穿的当入侵者围殴）。金靴最便宜（4 锭），定为
            // 指定件：脚上/包里都没有金靴时，先烧金、做金靴、缺料挖金，
            // 全部排在造门/进门之前——门一走进去就是下界，没有补救窗口。
            // 执行层 tickPortal/tickEnterPortal 还有强制换靴的最后一道闸。
            boolean goldBootsReady = countItem("golden_boots") > 0
                    || npc.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET)
                            .is(net.minecraft.world.item.Items.GOLDEN_BOOTS);
            if (!goldBootsReady) {
                int goldIngots = countItem("gold_ingot");
                int rawGold = countItem("raw_gold");
                if (goldIngots >= 4) {
                    return goal("制作金靴（下界金装）", "craft",
                            params("item", "golden_boots", "count", 1));
                }
                if (rawGold > 0 && goldIngots + rawGold >= 4 && hasFurnace && canFuel()) {
                    return goal("冶炼金锭（下界金装）", "smelt",
                            params("item", "gold_ingot", "count", Math.min(rawGold, 4 - goldIngots)));
                }
                if (goldIngots + rawGold < 4) {
                    return goal("挖金（下界金装）", "dig",
                            params("resource", "gold", "count", 4 - goldIngots - rawGold));
                }
                // 有料但缺熔炉/燃料：不硬卡，落回下方常规链（熔炉在主线
                // 更前处维护），执行层进门闸会兜底拦住无金装进门。
            }
            BlockPos existingPortal = goals.nearbyPortal();
            if (existingPortal != null) {
                // 附近已经有亮着的门：玩家不会浪费材料再造一座，直接走进去。
                return goal("进入下界传送门", "enterportal", params());
            }
            if (!hasAny("bucket", "water_bucket")) {
                if (ingots >= 3) {
                    return goal("制作铁桶", "craft", params("item", "bucket", "count", 1));
                }
                return goal("补铁做铁桶", "dig", params("resource", "iron", "count", 4));
            }
            if (!hasAny("flint_and_steel")) {
                if (!hasAny("flint")) {
                    return goal("找燧石", "flint", params("count", 1));
                }
                if (ingots >= 1) {
                    return goal("制作打火石", "craft",
                            params("item", "flint_and_steel", "count", 1));
                }
                return goal("补铁做打火石", "dig", params("resource", "iron", "count", 4));
            }
            // 20 个黑曜石：10 个建去下界的门，留 10 个在下界要塞投影点建回程门。
            // 玩家跑速通时也是这么带的——下界没有水，无法现场浇铸黑曜石。
            if (countItem("obsidian") < 20) {
                return goal("浇铸黑曜石", "obsidian", params("count", 20));
            }
            return goal("建造传送门", "portal", params());
        }

        // 13.2) 弓与箭：末地的水晶几乎都在高柱上，必须远程拆。
        // 放在下界之后：这时才真的要用弓，主世界也有蜘蛛可打；
        // 早于建门会因"白天打不到蜘蛛"把整条下线主链堵死。
        if (planHas(StagePlan.BOW_ARROWS) && !hasAny("bow")) {
            if (countItem("string") >= 3) {
                if (!stickMaterial) {
                    return gatherLogs("弓缺木棍");
                }
                return goal("制作弓", "craft", params("item", "bow", "count", 1));
            }
            // 【fix120】步 13.2 夜闸：夜里不出去猎蜘蛛取线——天黑还在野外
            // 晃正是 #9/#13 的死型。穿过本段落到步 14 小屋等过夜步，天亮
            // decide 自然回到本段续派。
            if (isNight()) {
                LOG.info("[AIV][PLAN] " + npc.npcName() + " 猎蜘蛛取线夜里跳过（夜晚总闸·fix120）");
            } else {
                return goal("猎杀蜘蛛取线", "slay", params("entity", "spider", "count", 3));
            }
        }
        if (planHas(StagePlan.BOW_ARROWS) && countItem("arrow") < 24) {
            // 【fix120】箭的野外材料步（猎鸡取羽毛/找燧石）同闸：缺任一野外
            // 材料时夜里整段跳过，避免穿到合成步空转失败。
            if (isNight() && (countItem("feather") < 6 || countItem("flint") < 6)) {
                LOG.info("[AIV][PLAN] " + npc.npcName() + " 箭材料野外步夜里跳过（夜晚总闸·fix120）"
                        + " | feather=" + countItem("feather") + " flint=" + countItem("flint"));
            } else {
                if (countItem("feather") < 6) {
                    return goal("猎杀鸡取羽毛", "slay", params("entity", "chicken", "count", 6));
                }
                if (countItem("flint") < 6) {
                    return goal("找燧石", "flint", params("count", 6));
                }
                if (!stickMaterial) {
                    return gatherLogs("箭缺木棍");
                }
                return goal("制作箭", "craft", params("item", "arrow", "count", 24));
            }
        }

        // 13.4) 眼睛齐了：找要塞，走到末地传送门房间并镶满眼睛。
        if (planHas(StagePlan.STRONGHOLD) && eyes >= 12) {
            BlockPos stronghold = locateStronghold();
            if (stronghold != null) {
                // 结构 piece 能给出传送门房间的精确中心；拿不到就退回锚点位置。
                if (strongholdRoom == null) {
                    strongholdRoom = goals.strongholdPortalRoom(stronghold);
                }
                BlockPos target = strongholdRoom != null ? strongholdRoom : stronghold;
                double dx = target.getX() + 0.5D - npc.getX();
                double dz = target.getZ() + 0.5D - npc.getZ();
                double horizontalSq = dx * dx + dz * dz;
                if (horizontalSq <= 25.0D && Math.abs(target.getY() - npc.getY()) <= 4.0D) {
                    return goal("激活末地传送门", "endportal",
                            params("x", target.getX(), "y", target.getY(), "z", target.getZ()));
                }
                return goal("前往要塞", "travel",
                        params("x", target.getX(), "y", target.getY(), "z", target.getZ()));
            }
            // locate 失败（缓存冷却中）：先探索，等冷却结束再试。
            return goal("寻找要塞", "explore", params("distance", 32));
        }

        // 14) 夜晚没遮蔽就搭个 3x3 小屋：避免整夜被怪追着跑。
        if (planHas(StagePlan.SHELTER) && isNight() && !underRoof() && cobble >= 25
                && !shelterCoolingDown()) {
            return goal("夜间小屋", "build", params("structure", "shelter"));
        }

        // 15) 兜底：木材存量低就补木头，否则出门探索找资源。
        if (wood < 16) {
            return gatherLogs("木材储备不足");
        }
        if (planHas(StagePlan.EXPLORE)) {
            return goal("探索周边", "explore", params("distance", 32));
        }
        // 自定义计划走完、又没启用 sustain/explore 兜底：原地待命。
        // 返回 null 表示"这轮不下发任何步骤"，规划器就此安静待命（不刷日志），
        // 生存层（吃饭/逃跑/自卫）不受影响——计划管的是发展路线，不是生存本能。
        this.stage = "自定义计划已走完（待命）";
        return null;
    }

    /**
     * 挖矿连败换区的一步（第三轮修复 C）：同一资源连弃 2 次后，
     * 下一次挖矿目标先被换成换区步骤——地下先挖阶梯回地表，再远距离移动，
     * 换一片地形重新下矿。全程只用正常移动/挖阶梯技能，禁止任何传送。
     *
     * @return 本步要执行的换区步骤；换区已走完时返回 null，表示放行原来的挖矿目标
     */
    private Step relocationStep() {
        if (relocationPhase == 0) {
            relocationPhase = 1;
            if (underground()) {
                return skill("挖矿连败换区：先挖阶梯回地表", "ascend");
            }
        }
        if (relocationPhase == 1) {
            relocationPhase = 2;
            // explore 的距离是"至少走出这么远再停"，48 格足以离开原来那片贫矿洞。
            return goal("挖矿连败换区：远距离移动", "explore", params("distance", 48));
        }
        relocationActive = false;
        relocationPhase = 0;
        digAbandonStreak = 0;
        LOG.info("[AIV][PLAN] " + npc.npcName() + " 换区完成，重新尝试挖矿 | resource="
                + relocationResource + " 位置=" + npc.blockPosition().toShortString());
        relocationResource = "";
        return null;
    }

    /**
     * 安稳发育模式的终点站（StagePlan.SUSTAIN）：钻石套齐、食物储备达标后不再推进，
     * 只维持生存——饿了打猎、木料低了补木料、其余时间在附近巡视采集。
     * 工具损坏/缺镐等情况由 decide() 前半程的工具段自动接管（它们排在模式分界之前）。
     */
    private Step sustainStep(int food, boolean hasSword, int wood) {
        if (food < 4 && hasSword) {
            return goal("安稳维持：储备食物", "hunt", params("count", 2, "radius", 12));
        }
        if (wood < 24) {
            return gatherLogs("安稳维持：补木料");
        }
        return goal("安稳维持：附近巡视采集", "explore", params("distance", 16));
    }

    private Step gatherLogs(String reason) {
        // 地下没有树：在下矿途中下"砍树"目标只会连续失败，把 NPC 卡死在矿道里。
        // 玩家遇到这种情况会先沿阶梯爬回地面，这里直接改判为上挖自救技能。
        if (underground()) {
            // ascend 连续失败多次（failStreak >= 3）说明四周多半被流体/基岩封死。
            // 旧做法是直接重生回出生点——已按 Fong 的决定拆除（不许故意自杀逃跑）。
            // 继续派 ascend：它失败会老实收场，规划器有失败退避（站等+打日志），
            // 宁可慢、宁可待命，也只能靠挖/垫/换方向脱困。
            if (failStreak >= 3 && lastFailedStage != null && lastFailedStage.contains("地表")) {
                return skill("地下被封死，继续挖阶梯脱困，禁止重生逃逸（" + reason + "）", "ascend");
            }
            return skill("挖阶梯返回地表（" + reason + "）", "ascend");
        }
        JsonObject json = new JsonObject();
        json.addProperty("block", "*_log");
        json.addProperty("count", 6);
        return new Step("采集木头（" + reason + "）", Kind.GOAL, new Intent("", "gather", json));
    }

    /** 当前是否明显在地表以下（比该列高度图的站立 Y 低 3 格以上）。 */
    private boolean underground() {
        BlockPos pos = npc.blockPosition();
        int surface = npc.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(), pos.getZ());
        return pos.getY() < surface - 3;
    }

    // ---------------------------------------------------------------- 维度分支

    /** 1.21.11 的维度 key 是 minecraft:the_nether。 */
    private boolean inNether() {
        return npc.level().dimension().identifier().toString().contains("the_nether");
    }

    /** 末地维度 key 是 minecraft:the_end。 */
    private boolean inEnd() {
        return npc.level().dimension().identifier().toString().contains("the_end");
    }

    /**
     * 下界科技树：烈焰棒 → 末影珍珠 → 末影之眼 → 走到要塞投影点建门回主世界。
     *
     * <p>下界的资源、威胁与主世界完全不同：没有树、不能放水、到处都是岩浆，
     * 继续跑主世界科技树只会在没有树的地方反复"砍木头"。
     */
    private Step decideNether() {
        int rods = countItem("blaze_rod");
        int powder = countItem("blaze_powder");
        int pearls = countItem("ender_pearl");
        int eyes = countItem("ender_eye");
        // 一只眼睛 = 1 珍珠 + 1 烈焰粉；1 根烈焰棒能合成 2 份粉，所以 6 根棒刚好够 12 只眼。
        int powderUnits = powder + rods * 2;

        if (eyes < 12) {
            if (powderUnits < 12) {
                BlockPos fortress = locateFortress();
                if (fortress != null) {
                    double dx = fortress.getX() - npc.getX();
                    double dz = fortress.getZ() - npc.getZ();
                    if (dx * dx + dz * dz > 32.0D * 32.0D) {
                        return goal("前往下界要塞", "travel", params("x", fortress.getX() + 0.5D,
                                "y", npc.getY(), "z", fortress.getZ() + 0.5D));
                    }
                }
                int needed = Math.max(1, (12 - powderUnits + 1) / 2);
                return goal("猎杀烈焰人", "slay", params("entity", "blaze", "count", needed));
            }
            int pearlUnits = pearls + eyes;
            if (pearlUnits < 12) {
                return goal("猎杀末影人", "slay",
                        params("entity", "enderman", "count", Math.max(1, 12 - pearlUnits)));
            }
            return goal("合成末影之眼", "craft", params("item", "ender_eye", "count", 12));
        }

        // 眼睛齐了：按 1:8 的坐标比例走到主世界要塞在下界的投影点，
        // 再建一座门穿回主世界，落地位置就在要塞附近（速通常见做法）。
        BlockPos stronghold = locateStronghold();
        if (stronghold == null) {
            return goal("下界探索", "explore", params("distance", 24));
        }
        int netherX = stronghold.getX() >> 3;
        int netherZ = stronghold.getZ() >> 3;
        double dx = netherX + 0.5D - npc.getX();
        double dz = netherZ + 0.5D - npc.getZ();
        if (dx * dx + dz * dz > 16.0D * 16.0D) {
            return goal("前往要塞投影点", "travel", params("x", netherX + 0.5D,
                    "y", npc.getY(), "z", netherZ + 0.5D));
        }
        BlockPos portal = goals.nearbyPortal();
        if (portal != null) {
            return goal("进入现有传送门", "enterportal", params());
        }
        if (countItem("obsidian") >= 10 && hasAny("flint_and_steel")) {
            return goal("建造回程传送门", "portal", params());
        }
        // 材料意外丢失（例如死过一次）：先到处找出原来的门。
        return goal("下界探索", "explore", params("distance", 24));
    }

    // ---------------------------------------------------------------- 结构定位

    /** 结构定位失败后的重试间隔：/locate 级别的搜索会加载不少数据，不能每秒调一次。 */
    private static final long STRUCTURE_RETRY_MILLIS = 60000L;

    private BlockPos fortressPos;
    private long fortressLocateAt;
    private BlockPos strongholdPos;
    private long strongholdLocateAt;
    /** 要塞末地传送门房间中心，定位一次后复用。 */
    private BlockPos strongholdRoom;

    /** 定位最近的下界要塞。 */
    private BlockPos locateFortress() {
        if (fortressPos != null) {
            return fortressPos;
        }
        long now = System.currentTimeMillis();
        if (now - fortressLocateAt < STRUCTURE_RETRY_MILLIS) {
            return null;
        }
        fortressLocateAt = now;
        fortressPos = locateStructure("fortress", 100, npc.level());
        if (fortressPos != null) {
            LOG.info("[AIV][PLAN] " + npc.npcName() + " 定位下界要塞 | "
                    + fortressPos.toShortString());
        } else {
            LOG.info("[AIV][PLAN] " + npc.npcName() + " 1024 格内没有找到下界要塞，稍后重试");
        }
        return fortressPos;
    }

    /** 定位最近的要塞（只存在于主世界）。 */
    private BlockPos locateStronghold() {
        if (strongholdPos != null) {
            return strongholdPos;
        }
        long now = System.currentTimeMillis();
        if (now - strongholdLocateAt < STRUCTURE_RETRY_MILLIS) {
            return null;
        }
        strongholdLocateAt = now;
        ServerLevel overworld = npc.level().getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return null;
        }
        strongholdPos = locateStructure("stronghold", 100, overworld);
        if (strongholdPos != null) {
            LOG.info("[AIV][PLAN] " + npc.npcName() + " 定位要塞 | "
                    + strongholdPos.toShortString());
        } else {
            LOG.info("[AIV][PLAN] " + npc.npcName() + " 1024 格内没有找到要塞，稍后重试");
        }
        return strongholdPos;
    }

    /**
     * 用区块生成器的放置算法直接算出最近的结构位置（等价于 /locate structure）。
     *
     * <p>为什么不用末影之眼抛物线：伪玩家没有客户端，眼睛的飞行轨迹在服务端很难复刻；
     * 而 /locate 本身就是服务端提供的合法查询，拿到的只是结构坐标，仍然要自己挖过去。
     */
    private BlockPos locateStructure(String path, int radiusChunks, ServerLevel level) {
        try {
            Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            Structure structure = registry.getValue(ResourceKey.create(Registries.STRUCTURE,
                    Identifier.withDefaultNamespace(path)));
            if (structure == null) {
                LOG.info("[AIV][PLAN] " + npc.npcName() + " 结构注册项不存在：" + path);
                return null;
            }
            var found = level.getChunkSource().getGenerator().findNearestMapStructure(level,
                    HolderSet.direct(registry.wrapAsHolder(structure)),
                    npc.blockPosition(), radiusChunks, false);
            return found == null ? null : found.getFirst();
        } catch (Throwable throwable) {
            LOG.log(java.util.logging.Level.WARNING,
                    "[AIV][PLAN] " + npc.npcName() + " 定位结构失败 " + path, throwable);
            return null;
        }
    }

    // ---------------------------------------------------------------- 背包/环境查询

    /**
     * 计数时要扫的槽位总数：0-35 是主背包+快捷栏，36-39 是装备槽（靴/腿/胸/盔），40 是副手。
     *
     * <p>第三轮修复 B 的根因：以前只扫 0-35，穿在身上的铁头盔在计数里"不存在"，
     * 规划器以为没头盔、又花 5 个铁锭重复合成一顶（钻石套长跑实测）。
     * "判有没有/有多少"一律扫到 40；但真正动手存取物品的地方（合成扣料、裁剪腾格）
     * 仍只动主背包，不能把穿着的装备当材料扣掉——那类代码在 GoalExecutor 里，不走这里。
     */
    private static final int COUNT_SLOTS = 41;

    private int countItem(String path) {
        int total = 0;
        for (int slot = 0; slot < COUNT_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (path.equals(itemPath(stack))) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countSuffix(String suffix) {
        int total = 0;
        for (int slot = 0; slot < COUNT_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (!stack.isEmpty() && itemPath(stack).endsWith(suffix)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 包里有没有值得存箱的东西（fix69 步 6.77 触发量；名单与执行层 STORE_LOOT 同步）。 */
    private boolean hasStorableLoot() {
        return countItem("diamond") > 0 || countItem("emerald") > 0
                || countItem("netherite_ingot") > 0 || countItem("netherite_scrap") > 0
                || countItem("ancient_debris") > 0 || countItem("blaze_rod") > 0
                || countItem("ender_pearl") > 0 || countItem("gold_ingot") > 0
                || countItem("bone") > 0 || countItem("string") > 0
                || countItem("spider_eye") > 0 || countItem("iron_ingot") > 32
                // 【fix92·Fong 2026-10-28】红石留存一组回家存箱，也算可存物。
                || countItem("redstone") > 0;
    }

    /** 同色羊毛 ≥3 的颜色前缀（fix56：原版床配方要 3 张同色羊毛）；没有返回 null。 */
    private String woolColorWithThree() {        java.util.Map<String, Integer> byPath = new java.util.HashMap<>();
        for (int slot = 0; slot < COUNT_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String path = itemPath(stack);
            if (path.endsWith("_wool")) {
                byPath.merge(path, stack.getCount(), Integer::sum);
            }
        }
        for (java.util.Map.Entry<String, Integer> entry : byPath.entrySet()) {
            if (entry.getValue() >= 3) {
                return entry.getKey().substring(0, entry.getKey().length() - "_wool".length());
            }
        }
        return null;
    }

    private boolean hasAny(String... paths) {
        for (String path : paths) {
            if (countItem(path) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 和 hasAny 一样，但会跳过"只剩最后 1 点耐久"的工具。
     *
     * <p>为什么需要这个区别：身体层（FakePlayerBody.mineWithBestTool）已经约定
     * 保留最后 1 点耐久不用，因为工具彻底碎掉后 NPC 连石头都挖不动。
     * 如果规划器还用 hasAny 数"背包里有镐"，就会看到一把 0 耐久的废镐，
     * 认为科技树已经推进，结果 NPC 拿着废镐在地下无限空转。
     */
    private boolean hasUsable(String... paths) {
        return countUsableItem(paths) > 0;
    }

    /** 统计一组物品里"还能用"的总数量（过滤掉耐久见底的）。 */
    private int countUsableItem(String... paths) {
        int total = 0;
        for (String path : paths) {
            for (int slot = 0; slot < COUNT_SLOTS; slot++) {
                ItemStack stack = npc.getInventory().getItem(slot);
                if (stack.isEmpty() || !path.equals(itemPath(stack))) {
                    continue;
                }
                // 不可损坏的物品（如方块）永远"可用"；可损坏的留最后 1 点耐久不用。
                if (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
                    continue;
                }
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 一组工具的剩余耐久总和（第十七轮，钻石前/钻石内保镐门槛用；见底的按 0 计）。 */
    private int totalRemainingDurability(String... paths) {
        int total = 0;
        for (String path : paths) {
            for (int slot = 0; slot < COUNT_SLOTS; slot++) {
                ItemStack stack = npc.getInventory().getItem(slot);
                if (stack.isEmpty() || !path.equals(itemPath(stack))) {
                    continue;
                }
                if (!stack.isDamageableItem()) {
                    total += 1000;
                    continue;
                }
                int remaining = stack.getMaxDamage() - stack.getDamageValue();
                if (remaining > 1) {
                    total += remaining;
                }
            }
        }
        return total;
    }

    /** 食物总数：只算原版 FOOD 组件里 nutrition > 0 的，腐肉之类的也算能填肚子。 */
    private int countFood() {
        int total = 0;
        for (int slot = 0; slot < COUNT_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            var food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
            if (food != null && food.nutrition() > 0) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 【第二十二轮】背包食物总营养值（nutrition×数量合计），下矿口粮线与食物存量驱动的口径。
     * 【第二十四轮】腐肉打折：Wiki 食物表——腐肉恢复 4 点、饱和度只有 0.8，且
     * 80% 概率附加 30 秒饥饿效果（吃完掉得更快），按满值计会把口粮严重高估
     * （第二十三轮盘点实证：foodNutrition=32 全是腐肉）。腐肉每块按 1 点计，
     * 只当应急不当储备。规划层/目标层/盘点三处同一口径。
     */
    private int foodNutrition() {
        int total = 0;
        for (int slot = 0; slot < COUNT_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            var food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
            if (food != null && food.nutrition() > 0) {
                total += ("rotten_flesh".equals(itemPath(stack)) ? 1 : food.nutrition())
                        * stack.getCount();
            }
        }
        return total;
    }

    /**
     * 【第二十二轮】下矿口粮门（Fong 18:51 第 1 条）：下钻石层开工前先查口粮——
     * 长跑#7 反面教材是下到 -54 后 food=0 在矿下空转。营养值低于口粮线（24）时：
     * 人在地下先 ascend 回地表，在地表先 hunt 补到口粮线；没剑（极早期）不挡路。
     * 每次调用都打"下矿口粮检查"行，通过/拦截都留痕，验收按这行对账。
     */
    private Step miningFoodGate(boolean hasSword) {
        int value = foodNutrition();
        boolean ok = value >= MINING_FOOD_NUTRITION;
        LOG.info("[AIV][PLAN] " + npc.npcName() + " 下矿口粮检查 | foodNutrition=" + value
                + "/" + MINING_FOOD_NUTRITION + (ok ? " 通过" : " 不足，先补给再下矿"));
        if (ok) {
            return null;
        }
        // 【fix59】夜里地表没猎物：口粮门曾整夜派 hunt 空转（300s 看门狗超时连烧，
        // boot-farm14 实锤）。照步 6.6 夜间闸先例：夜里且并不真饿（foodLevel>6）时
        // 暂缓补猎、放行下潜——地下不分昼夜，储备 13 够用，饥饿升井链是硬兜底；
        // 真饿（foodLevel≤6）夜里照旧先猎，不拿命赌。
        // 【fix122】真饿放行同加 hp 下限（惜命线 ≤7 同禁，与步 0/6.6 同口径）。
        if (isNight() && (npc.getFoodData().getFoodLevel() > 6
                || npc.getHealth() <= 7.0D)) {
            LOG.info("[AIV][PLAN] " + npc.npcName() + " 下矿口粮门夜间暂缓 | foodNutrition=" + value
                    + " foodLevel=" + npc.getFoodData().getFoodLevel()
                    + " hp=" + String.format(java.util.Locale.ROOT, "%.1f", npc.getHealth())
                    + "（夜里无猎物或低血保命，天亮再补）");
            return null;
        }
        if (underground()) {
            return skill("下矿口粮不足：先回地表补给", "ascend");
        }
        if (hasSword) {
            return goal("下矿前补口粮", "hunt",
                    params("need", "food", "stock", MINING_FOOD_NUTRITION, "radius", 12));
        }
        return null;
    }

    /** 有煤/木炭，或者有木板/原木/木棍可以当燃料。 */
    private boolean canFuel() {
        return countItem("coal") > 0 || countItem("charcoal") > 0
                || countSuffix("_planks") > 0 || countSuffix("_log") > 0 || countItem("stick") > 0;
    }

    private String firstRawMeat() {
        for (String meat : List.of("raw_beef", "raw_porkchop", "raw_mutton", "raw_chicken", "raw_rabbit")) {
            if (countItem(meat) > 0) {
                return meat;
            }
        }
        return null;
    }

    private static String cookedOf(String raw) {
        return switch (raw) {
            case "raw_beef" -> "cooked_beef";
            case "raw_porkchop" -> "cooked_porkchop";
            case "raw_mutton" -> "cooked_mutton";
            case "raw_chicken" -> "cooked_chicken";
            case "raw_rabbit" -> "cooked_rabbit";
            default -> raw;
        };
    }

    private static String itemPath(ItemStack stack) {
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.getPath();
    }

    private boolean isNight() {
        return npc.level().getDayTime() % 24000L >= NIGHT_START;
    }

    /** 头顶 8 格内有实心方块，就算有遮蔽（小屋、洞穴、树下都算）。 */
    private boolean underRoof() {
        BlockPos base = npc.blockPosition();
        for (int dy = 1; dy <= 8; dy++) {
            BlockState state = npc.level().getBlockState(base.above(dy));
            if (!state.isAir() && !state.getCollisionShape(npc.level(), base.above(dy)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 附近是否已经放下过熔炉。
     *
     * <p>熔炉目标是把物品栏里的熔炉放到地上，放完背包就空了；
     * 如果只看背包，规划器会以为没有熔炉，反复合成新熔炉，把圆石耗光。
     * 因此把"脚边已放置的熔炉"也算作拥有。
     */
    private boolean nearbyFurnace() {
        BlockPos base = npc.blockPosition();
        // 6 格水平、上下 3 格：足够覆盖自己搭的小屋和挖矿的落脚点，又不至于扫太大范围。
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    if (npc.level().getBlockState(base.offset(dx, dy, dz)).is(Blocks.FURNACE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public String status() {
        return String.format(Locale.ROOT, "npc=%s stage=%s skill=%s goal=%s",
                npc.npcName(), stage, skills.currentSkill(), goals.status());
    }
}

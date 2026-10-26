package com.example.aivillager.progression;

import com.example.aivillager.AiVillagerPlugin;
import com.example.aivillager.goals.GoalExecutor;
import com.example.aivillager.intent.Intent;
import com.example.aivillager.npc.FakePlayerBody;
import com.example.aivillager.skills.SkillRunner;
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
        if (outcomeId != lastOutcomeId) {
            lastOutcomeId = outcomeId;
            newOutcome = true;
            outcome = goals.lastOutcome();
            goalOutcomeText = outcome;
        }
        if (skillSeq != lastSkillOutcomeSeq) {
            lastSkillOutcomeSeq = skillSeq;
            newOutcome = true;
            outcome = skills.lastOutcome();
        }
        if (newOutcome && outcome != null
                && (outcome.contains("失败") || outcome.contains("放弃") || outcome.contains("超时")
                        || outcome.contains("不可达") || outcome.contains("无法")
                        || outcome.contains("找不到") || outcome.contains("挖不动"))) {
            failStreak = lastStage.equals(lastFailedStage) ? failStreak + 1 : 1;
            lastFailedStage = lastStage;
            lastFailAt = now;
            LOG.info("[AIV][PLAN] " + npc.npcName() + " 检测到失败 | outcome=" + outcome
                    + " 阶段=" + lastStage + " 连续=" + failStreak);
        } else if (newOutcome) {
            failStreak = 0;
            lastFailedStage = "";
        }
        // 挖矿连败统计（第三轮修复 C）：目标层放弃一次挖矿目标不奇怪（这一片可能真没矿），
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

        lastStartAt = now;
        lastStage = step.stage;
        stage = step.stage;
        // 记住这次下发的是不是挖矿目标：它的结果回来时要归因到资源，喂给连败换区统计。
        if ("dig".equals(step.intent.action())) {
            issuedDigStage = step.stage;
            issuedDigResource = step.intent.param("resource", "");
        }
        boolean accepted = switch (step.kind) {
            case GOAL -> goals.start(step.intent);
            case SKILL -> skills.run(step.intent, "规划");
        };
        LOG.info("[AIV][PLAN] " + npc.npcName() + " 下一步 | 阶段=" + step.stage
                + " action=" + step.intent.action() + " params=" + step.intent.params()
                + " accepted=" + accepted);
        if (!accepted) {
            lastFailedStage = step.stage;
            lastFailAt = now;
        }
    }

    // ---------------------------------------------------------------- 科技树

    private enum Kind { GOAL, SKILL }

    private record Step(String stage, Kind kind, Intent intent) {
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
        int cobble = countItem("cobblestone");
        int coal = countItem("coal") + countItem("charcoal");
        int rawIron = countItem("raw_iron");
        int ingots = countItem("iron_ingot");
        int diamonds = countItem("diamond");
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
            if (hasSword) {
                return goal("饥饿求生", "hunt", params("count", 2, "radius", 12));
            }
            return gatherLogs("饥饿但无武器");
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
            if (cobble >= 3 && stickMaterial) {
                return goal("制作石镐", "craft", params("item", "stone_pickaxe", "count", 1));
            }
            // 木镐配方 = 3 木板 + 2 木棍；2 根木棍自己还要 2 块木板，
            // 所以没有存货木棍时需要 5 块木板当量，不是 4（1 根原木只出 4 块，必须再砍一棵）。
            // 另外必须有真实木棍材料（stickMaterial），只有竹子也做不出木棍来。
            if (stickMaterial && wood >= (sticks >= 2 ? 3 : 5)) {
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
                && wood >= (sticks >= 4 ? 6 : 8)) {
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
            if (cobble >= 3 && stickMaterial) {
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

        // 备用石镐：石制工具只有 131 点耐久，一条 24 格矿道加下挖就会见底。
        // 没有铁镐之前保持 3 把：实测 2 把在连续下挖+掘进时仍会同时耗尽，
        // 一旦断镐且地下没有木头，NPC 就只能原地空转。
        int stonePickaxes = countUsableItem("stone_pickaxe");
        LOG.info("[AIV][PLAN-DEBUG] cobble=" + cobble + " stickMaterial=" + stickMaterial
                + " woodFromLogs=" + woodFromLogs + " woodFromBamboo=" + woodFromBamboo
                + " stonePickaxes=" + stonePickaxes + " hasIronPickaxe=" + hasIronPickaxe
                + " logs=" + logs + " planks=" + planks + " sticks=" + sticks
                + " bamboo=" + bamboo + " ingots=" + ingots);
        if (planHas(StagePlan.STONE_TOOLS) && !hasIronPickaxe && stonePickaxes < 3) {
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

        // 6) 熔炉：冶炼铁必须用，8 个圆石。
        if (planHas(StagePlan.FURNACE) && !hasFurnace && cobble >= 8) {
            return goal("制作熔炉", "craft", params("item", "furnace", "count", 1));
        }
        // 圆石不够 8 个、也没有熔炉时先补圆石；
        // 否则会一直挖铁矿却没法冶炼，生铁越堆越多、科技树原地打转。
        if (planHas(StagePlan.FURNACE) && !hasFurnace && hasPickaxe && cobble < 8) {
            return goal("补圆石做熔炉", "dig", params("resource", "stone", "count", 8));
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
            boolean gatherStuck = lastFailedStage.startsWith("采集木头") && failStreak >= 2;
            if (!gatherStuck) {
                return gatherLogs("木料低于安全线，先补给再下矿");
            }
            LOG.info("[AIV][PLAN] " + npc.npcName()
                    + " 采集木头连败，木料安全线临时放行挖矿步骤 | wood=" + wood
                    + " failStreak=" + failStreak);
        }

        // 7) 煤：火把和燃料。没有煤也能用木板烧，但不能无限烧。
        // 连续两次挖煤失败 = 当前矿层确实没有煤（实测 Y=16 连挖两条 24 格矿道都是 0）。
        // 玩家这时会改用木板/木炭当燃料，而不是永远卡在"煤不足"这一条上；
        // 跳过煤以后，后面的铁装备、砍木头补木棍都能继续推进，过一阵带退避再回来挖。
        boolean coalStarved = failStreak >= 2 && "挖煤".equals(lastFailedStage);
        if (planHas(StagePlan.COAL) && coal < 4 && hasStonePickaxe && !coalStarved) {
            return goal("挖煤", "dig", params("resource", "coal", "count", 4));
        }

        // 8) 铁矿石：石镐就能挖，Y=16 附近最密集。
        if (planHas(StagePlan.IRON_ORE) && rawIron + ingots < 4 && hasStonePickaxe) {
            return goal("挖铁", "dig", params("resource", "iron", "count", 4));
        }

        // 9) 冶炼：手里有生铁、有熔炉、有燃料才炼。
        if (planHas(StagePlan.SMELT_IRON) && rawIron > 0 && ingots < 4 && hasFurnace && canFuel()) {
            return goal("冶炼铁锭", "smelt",
                    params("item", "iron_ingot", "count", Math.min(8, rawIron)));
        }

        // 10) 铁制装备：剑 → 镐 → 胸甲 → 头盔 → 护腿 → 靴子。
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
                return goal("冶炼铁锭（铁靴）", "smelt",
                        params("item", "iron_ingot", "count", Math.min(8, rawIron)));
            }
            if (ingots >= 4) {
                return goal("制作铁靴", "craft", params("item", "iron_boots", "count", 1));
            }
            return goal("挖铁（铁靴）", "dig",
                    params("resource", "iron", "count", Math.max(1, 4 - ingots - rawIron)));
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
        if (planHas(StagePlan.SPARE_PICKS) && (usableIronPickaxes < 2 || ironPickDurability < 250)) {
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
        if (planHas(StagePlan.SMELT_IRON) && rawIron > 0 && hasFurnace && canFuel()) {
            return goal("继续冶炼", "smelt",
                    params("item", "iron_ingot", "count", Math.min(8, rawIron)));
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
                    return goal("挖钻石前炼铁补镐", "smelt",
                            params("item", "iron_ingot", "count", Math.min(8, rawIron)));
                }
                return goal("挖钻石前挖铁补镐", "dig", params("resource", "iron", "count", 4));
            }
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
                return goal("挖钻石（钻石甲）", "dig",
                        params("resource", "diamond", "count", diaCost[i] - diamonds));
            }
        }

        // 13.0) 弓与箭挪到传送门准备之后（见 13.2）：
        // 蜘蛛只在夜晚出没，先凑弓弦会让白天的整条下线主链（下界→末影珍珠）全堵在后面。

        // 13) 食物储备：有剑以后打猎，再用熔炉把生肉烤熟（熟肉回复量更高）。
        // 但 foodLevel 还够高（>12）时不急——附近没动物会一直 hunt 失败卡死主线。
        if (planHas(StagePlan.FOOD) && food < 4 && hasSword && foodLevel < 12) {
            return goal("储备食物", "hunt", params("count", 2, "radius", 12));
        }
        String rawMeat = firstRawMeat();
        if (planHas(StagePlan.FOOD) && rawMeat != null && hasFurnace && canFuel()) {
            String cooked = cookedOf(rawMeat);
            return goal("烤肉", "smelt", params("item", cooked, "count", 8));
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
        if (planHas(StagePlan.PORTAL_PREP) && eyes < 12) {
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
            return goal("猎杀蜘蛛取线", "slay", params("entity", "spider", "count", 3));
        }
        if (planHas(StagePlan.BOW_ARROWS) && countItem("arrow") < 24) {
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
        if (planHas(StagePlan.SHELTER) && isNight() && !underRoof() && cobble >= 25) {
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

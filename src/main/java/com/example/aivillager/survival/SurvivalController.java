package com.example.aivillager.survival;

import com.example.aivillager.npc.FakePlayerBody;
import com.example.aivillager.pathfinding.PathFinder;
import com.example.aivillager.skills.SkillRunner;
import com.example.aivillager.snapshot.InventorySnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 生存层：把 Wurst 的 KillAura / AutoEat / AutoArmor 用"直接操作 ServerPlayer"的方式实现。
 *
 * <p>它不经过大脑，也不消耗 LLM：每 0.5 秒检查一次威胁、饥饿和装备，
 * 保证 NPC 在没人指挥时也能自卫、进食、穿装备。
 *
 * <p>和技能层的边界：
 * <ul>
 *   <li>没有威胁时完全不碰移动，避免和 goto/mine 抢输入。</li>
 *   <li>被自己正在打的怪击中、或怪已经贴脸（5 格内）时，才会强制中断当前技能。</li>
 *   <li>攻击间隔固定 12 tick（约 0.6 秒），不依赖客户端的挥击包，否则会每 tick 打一次。</li>
 * </ul>
 */
public final class SurvivalController {

    private static final Logger LOG = Logger.getLogger("AIVillager");

    /**
     * 攻击冷却：12 游戏 tick ≈ 0.6 秒，接近铁剑的满蓄力节奏。
     * 用 npc.tickCount 做时间基准，而不是"每生存 tick 减 1"：
     * 生存层本身每 10 tick 才跑一次，按次数递减会让实际间隔变成 6 秒，
     * 实测打一只骷髅要 40 秒、NPC 被白打十几下。
     */
    private static final int ATTACK_COOLDOWN_TICKS = 12;
    /** 装备检查间隔：每 3 次检查（约 1.5 秒）扫一次背包，换甲/换武器都要够快。 */
    private static final int ARMOR_CHECK_INTERVAL = 3;
    /** 死亡后多少生存层 tick 复活：每 tick 10 游戏 tick，4 tick ≈ 2 秒。 */
    private static final int REVIVE_DELAY_TICKS = 4;
    /** 进入攻击距离（3 格）的平方。 */
    private static final double ATTACK_RANGE_SQ = 9.0D;
    /**
     * 惜命线（Fong 定：把生命放在第一位）：血量 ≤7（满血 20 的 35%）就是低血，
     * 不管有没有装备都必须先脱离战斗、拉开距离回血，不许恋战。
     * 旧口径是 8 点（40%）且和"有没有武器"混在一个条件里，现拆成独立的明确阈值。
     */
    private static final float LOW_HEALTH_THRESHOLD = 7.0F;
    /**
     * 空手接战线：没武器也没装备时，只有血量 >14（70%，足够健康）才许空手打敌对生物；
     * 低于这条线又没装备，就按低血处理先跑。有武器或有装备则不受这条线限制（只受惜命线）。
     */
    private static final float BARE_HAND_MIN_HEALTH = 14.0F;
    /** 贴脸判定：5 格内会强制中断当前技能来自卫。 */
    private static final double FORCE_DEFEND_RANGE_SQ = 25.0D;
    /**
     * 卡进方块的自救间隔：每 2 次生存检查（约 1 秒）看一次。
     * 用 1 秒而不是每 tick：挖方块有耗时，连续重试没有意义。
     */
    private static final int UNSTUCK_CHECK_INTERVAL = 2;
    /**
     * 打不到的威胁要被无视多久（10 秒）。
     * 实测：一只卡在墙后/坑里的怪会让目标层和规划器全部暂停，
     * NPC 站着不动、日志一条没有，看起来像死了；玩家会无视这种怪继续做事。
     */
    private static final long THREAT_IGNORE_MILLIS = 10_000L;

    /**
     * 有害食物黑名单。
     * Wurst 的 AutoEat 也是按"能吃"直接吃，但服务端 NPC 没人帮它解毒，
     * 腐肉/生鸡肉的饥饿效果会让食物值越吃越低（实测腐肉把食物值从 2 吃回 0）。
     * 这里直接排除带负面效果的食物，宁可饿着等玩家给面包。
     */
    private static final Set<Item> HARMFUL_FOODS = Set.of(
            Items.ROTTEN_FLESH,
            Items.SPIDER_EYE,
            Items.POISONOUS_POTATO,
            Items.PUFFERFISH,
            Items.CHORUS_FRUIT);

    private final Plugin plugin;
    private final FakePlayerBody npc;
    private final SkillRunner skills;
    private final int eatThreshold;
    private final double defendRadius;

    private BukkitTask task;
    /** 下一次可以攻击的游戏 tick（npc.tickCount 基准）。 */
    private int nextAttackTick;
    private int armorCheckTimer;
    /** 卡方块自救的计时器，与装备检查分开计数（两者节奏不同）。 */
    private int unstuckTimer;
    /** 连续泡在流体里的自救检查次数（约 1 秒一次）：用于"泡久了挖开头顶"的升级脱困。 */
    private int fluidStuckChecks;
    /** 【第十四轮】连续完全离水的检查次数：泡水计数/脱困方向的清零滞后（见流体自救）。 */
    private int fluidDryChecks;
    /** 上次下发游泳脱困的时间：技能的行走目标会把游泳模式顶掉，短期内不重复下发，避免互相抢输入刷日志。 */
    private long lastSwimIssueMillis;
    /**
     * 挖岸脱困的方向（第四轮修复 B）：泡够约 12 秒还没上岸时选定——朝最近旱地的
     * 单位偏移（BlockPos 只用 x/z 分量）。null 表示还没选或附近 10 格内没有旱地
     * （全淹洞穴），此时挖岸无从下手，只挖头顶开气腔。离开流体即清零。
     */
    private net.minecraft.core.BlockPos fluidEscapeDir;
    /**
     * "原地不动"锚点与计数（第六轮补充·树干顶脱困）：水平离开锚点 2 格以上清零，
     * 垂直下落不算移动（逐层挖树干时每挖一层只落 1 格，不能被误判成"在动")。
     * 约 10 次检查（约 10 秒）不动且脚下是原木，才判定困在树干顶上。
     */
    private net.minecraft.core.BlockPos stillAnchor;
    private int stillChecks;
    private boolean chasing;
    private int deadTicks;
    private boolean deathLogged;
    /** 【第二十三轮】存活 tick 的最新血量：死亡现场行里的"最后一击前后血量"取它。 */
    private float lastAliveHealth = -1.0F;
    /** 当前主手武器的攻击力评分；赤手为 0，用来决定"能不能打"而不是送死。 */
    private int cachedWeaponScore;
    /**
     * 上一次记录过的武器+槽位。
     * 只在真正换武器时打日志：快捷栏被别处临时切走又切回时，
     * 每 1.5 秒刷一条"切换武器"会把关键生存日志淹没（实测每 1.5 秒一条）。
     */
    private String lastWeaponChoice = "";
    /** 逃跑后的冷却，避免每 0.5 秒重新规划一次路径。 */
    private int fleeCooldown;
    /** 是否处于逃跑状态（没有威胁后清除），目标层据此暂停推进。 */
    private boolean fleeing;
    /** 【fix39】逃跑记忆：正在逃的威胁本体。8 格扫描圈外不等于脱离（僵尸
     * 仇恨 40 格）——28 格（远程 36 格）安全圈内仍按威胁处理，防逃出扫描圈
     * 就清状态、目标层领着 NPC 往回走迎头相撞（#11/#12 逃跑 yo-yo 定因）。 */
    private LivingEntity fleeThreatRef;
    /** 暂时无视的威胁：实体 UUID → 解除无视的时间戳（毫秒）。 */
    private final java.util.Map<java.util.UUID, Long> ignoredThreats = new java.util.HashMap<>();
    /**
     * 【第二十四轮】逃跑直线脱离兜底（长跑#8 F 段实证）：A* 各档落点全灭时
     * 不再站着挨打——沿远离威胁方向 walkTo 直线跑，靠身体层的撞墙跳/卡住挖路
     * 硬走（玩家式跑法）。engaged=兜底进行中；attempts 轮换偏转角；anchor 记
     * 本轮兜底起点，连着两轮换方向都没挪出 2 格 = 真被地形困死，放弃兜底
     * 交回贴脸自卫；until 是本轮直行的观察窗（身体还在走就不重发）。
     */
    private boolean straightFleeEngaged;
    private int straightFleeAttempts;
    private BlockPos straightFleeAnchor;
    private long straightFleeUntil;
    /** 兜底偏转角序列：正后方优先，其次斜后、侧向，最后回头（总比原地强）。 */
    private static final int[] STRAIGHT_FLEE_ROTATIONS = {0, 45, -45, 90, -90, 180};
    /** 兜底直行距离：与远程加长档同量级，先脱离弩/弓射程再说。 */
    private static final double STRAIGHT_FLEE_DISTANCE = 16.0D;
    /** 兜底直行观察窗：走完/卡住都由 defend 的快速重试接管，这里只防同 tick 重发。 */
    private static final long STRAIGHT_FLEE_WINDOW_MILLIS = 5_000L;
    /**
     * 死亡前的背包快照。
     * 伪玩家死后原版会把背包全部掉在地上，目标层辛苦攒的材料会瞬间清零；
     * 复活时用这份快照恢复，才算真正"像玩家一样持续发展"（而不是死了重来）。
     */
    private List<ItemStack> inventorySnapshot = List.of();

    public SurvivalController(Plugin plugin, FakePlayerBody npc, SkillRunner skills,
                              int eatThreshold, double defendRadius) {
        this.plugin = plugin;
        this.npc = npc;
        this.skills = skills;
        this.eatThreshold = Math.max(1, Math.min(20, eatThreshold));
        this.defendRadius = Math.max(2.0D, Math.min(16.0D, defendRadius));
    }

    public void start() {
        if (task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::safeTick, 20L, 10L);
        LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 生存层启动 | eatThreshold="
                + eatThreshold + " defendRadius=" + defendRadius);
    }

    /**
     * 调度器包装：周期任务一旦抛异常会被 Bukkit 静默取消，整个生存层就停了。
     * 这里统一捕获并打日志，保证一次意外不会让 NPC 永久失去自卫能力。
     */
    private void safeTick() {
        try {
            tick();
        } catch (Throwable throwable) {
            LOG.log(java.util.logging.Level.WARNING,
                    "[AIV][SURVIVAL] " + npc.npcName() + " tick 异常", throwable);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 生存层停止");
    }

    private void tick() {
        // 注意：isAlive() 已经包含 isRemoved() 判断。
        // Paper 在玩家死亡后会把实体从世界实体表里摘掉（isRemoved=true），
        // 所以这里不能再单独判 isRemoved 提前 return，否则复活逻辑永远走不到。
        if (!npc.isAlive()) {
            if (!deathLogged) {
                deathLogged = true;
                // 【第二十三轮】死亡留现场（日志标准第 3 条）：死因（伤害类型+来源
                // 实体）、临死前一 tick 的血量、脚下/身前/头顶方块、当时在干什么，
                // 一条 [AIV][DEATH] 打全——不许再靠别的提交信息拼死因
                //（掠夺者射杀 3 次靠事后拼凑是反面教材）。
                LOG.info("[AIV][DEATH] " + npc.npcName() + " " + deathScene());
                // 死亡瞬间原版已经把背包掉在尸体周围；先清掉这些掉落物，
                // 否则复活后再恢复背包就等于凭空复制了一套物品。
                discardDeathDrops();
                LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 已死亡 | " + npc.describe()
                        + " " + REVIVE_DELAY_TICKS + " tick 后复活");
            }
            if (++deadTicks >= REVIVE_DELAY_TICKS) {
                revive();
            }
            return;
        }
        deathLogged = false;
        deadTicks = 0;
        lastAliveHealth = npc.getHealth();
        if (fleeCooldown > 0) {
            fleeCooldown--;
        }

        // 自埋自救：爬坡垫脚、从矿道破土而出时偶尔会把自己卡进一个实心方块里
        // （实测脚部格是圆石）。这时身体位置不可站立，A* 起点非法，所有寻路
        // （砍树/走路/找矿）都会瞬间失败，NPC 外表上就是站在原地反复报"不可达"。
        // 脱困办法见 unstuck()：挖开困住自己的方块（玩家手段），不再做位置修正。
        if (++unstuckTimer >= UNSTUCK_CHECK_INTERVAL) {
            unstuckTimer = 0;
            unstuck();
        }

        // 换甲/换武器必须在战斗之前：有威胁时 tick 会提前 return，
        // 如果把装备检查放在 eat 后面，NPC 会在被围攻时一直裸奔。
        if (++armorCheckTimer >= ARMOR_CHECK_INTERVAL) {
            armorCheckTimer = 0;
            equipBetterArmor();
            ensureBestWeaponInHand();
            // 换完武器再算评分，生存层要用它判断"现在打得过还是该跑"。
            cachedWeaponScore = weaponScore(npc.getMainHandItem());
            // 背包快照放在装备检查里：每 1.5 秒一次，死亡时最多丢 1.5 秒内的收入。
            snapshotInventory();
        }

        boolean threatNear = defend();

        // 有威胁时优先处理战斗；吃东西可以等，贪吃会被咬死。
        if (threatNear) {
            // 但饿到 0 会持续掉血，所以在没有 5 格内贴身威胁的间隙补一口。
            eat(true);
            return;
        }

        eat(false);
    }

    /**
     * 自埋/落水自救：每 1 秒检查一次。
     * 两件事：卡进实心方块 → 挖开困住自己的方块走出来；站在流体里 → 游泳上岸。
     * 两件都只许真玩家手段，不许传送（Fong 定：让他完全靠自己）。
     *
     * <p>只看脚部这一格：头被卡住时原版通常还能把身体挤出去，
     * 而脚部被占/被淹意味着"这一格站不了"，身体会被持续顶/冲、A* 起点永远非法。
     */
    private void unstuck() {
        net.minecraft.core.BlockPos feet = npc.blockPosition();
        net.minecraft.world.level.block.state.BlockState state = npc.level().getBlockState(feet);
        // 原地不动计时（树干顶脱困的门控）：只看水平位移，逐层下挖的 1 格下落不算动。
        if (stillAnchor == null
                || (feet.getX() - stillAnchor.getX()) * (feet.getX() - stillAnchor.getX())
                        + (feet.getZ() - stillAnchor.getZ()) * (feet.getZ() - stillAnchor.getZ()) >= 4) {
            stillAnchor = feet;
            stillChecks = 0;
        } else {
            stillChecks++;
        }
        // 站在树叶上（出生/掉落到树冠，fix7 长跑实测）：脚格是空气、脚下是树叶时，
        // 身体被树冠托在半空——寻路要么起点悬空、要么锚到正下方地面生成幻影路径，
        // 朝哪都走不了。玩家式下树：把脚下树叶逐层挖掉、靠物理逐层落下去
        // （树冠一般 2-4 层，最后一落也不超过 3 格）。全程只挖不传送。
        if (state.getFluidState().isEmpty()
                && (state.isAir() || state.getCollisionShape(npc.level(), feet).isEmpty())
                && npc.level().getBlockState(feet.below())
                        .is(net.minecraft.tags.BlockTags.LEAVES)) {
            if (npc.mineWithBestTool(feet.below()) || npc.mine(feet.below())) {
                LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " canopy-descent | pos="
                        + feet.toShortString());
            }
            fluidStuckChecks = 0;
            fluidEscapeDir = null;
            return;
        }
        // 困在树干顶（第六轮补充，fix8 长跑实测）：挖叶下树时落在 1 格宽的树干顶上，
        // 离地 7+ 格、四周无任何可站立邻格，A* 展开几个节点就耗尽，同样原地卡死。
        // 判定加门控：原地不动约 10 秒且脚下是原木——正常站在木头上干活的人不会
        // 10 秒水平纹丝不动（合成是瞬时的、冶炼站的是地面），困住了才挖脚下原木
        // 逐层落下去。和挖树叶一样只挖不传送；挖掉的是野生树干顶，不是玩家建筑
        // （NPC 自己也不会站在自己盖的木头上发呆 10 秒）。
        if (stillChecks >= 10
                && state.getFluidState().isEmpty()
                && (state.isAir() || state.getCollisionShape(npc.level(), feet).isEmpty())
                && npc.level().getBlockState(feet.below())
                        .is(net.minecraft.tags.BlockTags.LOGS)) {
            if (npc.mineWithBestTool(feet.below()) || npc.mine(feet.below())) {
                LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " trunk-descent | pos="
                        + feet.toShortString() + " stillSec=" + stillChecks);
            }
            fluidStuckChecks = 0;
            fluidEscapeDir = null;
            return;
        }
        // 【第十九轮】陷进细雪脱困（Fong 拍板）：没穿皮靴陷进细雪（脚格就是
        // 细雪方块）时不许靠硬走挣扎——把脚下/头顶的细雪逐格挖掉、跳着往外
        // 挪，落到实地后再由寻路（已改为避开细雪）接管。和挖树叶下树同理，
        // 只挖不传送。穿了皮靴按原版机制正常踩过，不进这一段。
        if (state.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)
                && !npc.wearingLeatherBoots()) {
            boolean dug = npc.mineWithBestTool(feet) || npc.mine(feet);
            net.minecraft.core.BlockPos head = feet.above();
            if (npc.level().getBlockState(head)
                    .is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)) {
                dug |= npc.mineWithBestTool(head) || npc.mine(head);
            }
            npc.jumpNow();
            LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " powder-snow-escape | pos="
                    + feet.toShortString() + " dug=" + dug);
            fluidStuckChecks = 0;
            fluidEscapeDir = null;
            return;
        }
        if ((state.isAir() || state.is(net.minecraft.world.level.block.Blocks.BEDROCK))
                && !npc.isInWater() && !npc.isInLava()) {
            // 【第十四轮】真离水（身体包围盒也不沾水）连续 2 次检查才清零泡水计数：
            // 旧版脚格一变空气就清零，NPC 在水面浮沉交界处脚格时空时水，计数永远
            // 到不了 12，挖岸升级从未触发（长跑#6 实测：泡水 10 分钟 fluid-escape
            // 事件 0 次、swim-timeout 循环 18 次）。浮沉时脚格是空气但身体还在水里，
            // 不走这条早退、落到下面的流体处理（其入口已按包围盒放宽）。
            if (++fluidDryChecks >= 2) {
                fluidStuckChecks = 0;
                fluidEscapeDir = null;
            }
            return;
        }
        // === 流体自救：游泳上岸 ===
        // 站在水/岩浆里会被流体推动、沉底上不了岸。旧做法是传送到岸上，已拆除：
        // 改为让身体进入游泳模式，朝最近的岸边格游过去、上浮、扒岸上岸，全程流体物理。
        // 岩浆里游得慢、会持续烧伤，游不出去就可能死——那是真玩家掉进岩浆的正常下场，
        // 按正常死亡处理（死亡不掉落是本服规则，见 revive 一节），不许用瞬移作弊脱身。
        if (!state.getFluidState().isEmpty() || npc.isInWater() || npc.isInLava()) {
            fluidDryChecks = 0;
            // 【第十四轮】流体判定放宽到身体包围盒（isInWater/isInLava）：浮沉交界处
            // 脚格可能暂时是空气，旧版只看脚格会漏检并清零泡水计数。
            boolean isWater = !npc.isInLava();
            String fluidName = isWater ? "water" : "lava";
            // 【第四十五轮】岩浆位置记忆：把这口池写进身体层——逃生后 90 秒内
            // 任何朝该格收敛的移动（路径/直线）都会被身体层拦截，防脱险点与
            // 再入点只差 1 格的同点复入（雨林局两度落入实证）。
            if (!isWater) {
                npc.rememberLava(feet);
            }
            // 浅水涉水不算被困：脚踩着地、眼睛不在水里时，玩家就是蹚水走过去的，
            // 行走/技能自己能处理；反复下发游泳只会在池塘边"游一步→上岸"4 秒一轮
            // 地刷屏打转（实测）。只有真泡在深水里（眼睛入水或离地沉浮）才游泳脱困。
            boolean eyeInFluid = !npc.level().getBlockState(
                    net.minecraft.core.BlockPos.containing(npc.getEyePosition()))
                    .getFluidState().isEmpty();
            if (!eyeInFluid && npc.onGround()) {
                // 【第十四轮】涉水早退不再清零泡水计数：浮沉中的 NPC 会间歇触底
                // 站稳（眼出水面），旧版在此清零使计数永远到不了挖岸门槛。
                // 计数只由流体入口上方的"连续 2 次完全离水"清零。
                return;
            }
            fluidStuckChecks++;
            // 挖岸脱困的方向在进入升级阶段时选定一次（第四轮修复 B）：
            // 朝最近的旱地方向贴墙挖台阶；10 格内没有旱地（全淹洞穴）就保持 null。
            // 【第十四轮】仍用 ==12 触发：计数已改为跨浮沉连续累计且只与方向
            // 一起在真离水时清零，每轮泡水必恰好经过 12 一次，不会漏选/重选。
            if (fluidStuckChecks == 12) {
                fluidEscapeDir = pickFluidEscapeDir(feet);
                LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " fluid-escape-dig start | fluid="
                        + fluidName + " pos=" + feet.toShortString()
                        + " dir=" + (fluidEscapeDir == null ? "none(全淹)"
                                : fluidEscapeDir.toShortString()));
            }
            // 下发游泳脱困，但要避开两个内耗：
            // ① dryland 技能正在接管时它自己就是上岸技能（带上浮+寻岸），生存层再下发
            //    游泳会互相顶掉对方的行走目标，两边都走不成——此时只帮忙挖顶（见下）。
            // ② 刚下发过游泳（4 秒内）就别重发：别的技能把游泳顶掉后，给它一个观察窗。
            boolean drylandActive = "dryland".equals(skills.currentSkill());
            long now = System.currentTimeMillis();
            if (!drylandActive && !npc.isSwimmingEscape()
                    && now - lastSwimIssueMillis >= 4_000L) {
                lastSwimIssueMillis = now;
                // 找岸按被困时长逐步扩围（第四轮修复 B）：旧版只找 ±3 格且不看岸高，
                // 陡岸水塘边明明 5 格外就有缓坡（长跑1 实测）却永远够不着；
                // 现在候选必须是"贴水面、最多高 1 格"的真岸（PathFinder.isShoreCell），
                // 找不到就沿岸横向扩围找缓坡，而不是对着崖壁干游。
                int shoreRadius = fluidStuckChecks <= 3 ? 4 : fluidStuckChecks <= 8 ? 7 : 11;
                net.minecraft.core.BlockPos shore = findShoreCell(feet, shoreRadius);
                if (shore != null) {
                    npc.swimTo(shore);
                    LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 落入流体，游泳上岸 | fluid="
                            + fluidName + " pos=" + feet.toShortString() + " 岸边=" + shore.toShortString());
                } else if (fluidStuckChecks >= 12 && fluidEscapeDir != null) {
                    // 没有能爬的岸：朝岸壁方向贴过去，配合下面的挖岸台阶脱困。
                    npc.swimTo(feet.offset(fluidEscapeDir.getX() * 3, 0, fluidEscapeDir.getZ() * 3));
                    LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " fluid-escape-press | fluid="
                            + fluidName + " pos=" + feet.toShortString()
                            + " dir=" + fluidEscapeDir.toShortString());
                } else {
                    // 附近没找到岸：先原地上浮到水面，别沉底，之后由技能层想办法靠岸。
                    npc.swimUp();
                    LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 落入流体，附近无岸，先上浮 | fluid="
                            + fluidName + " pos=" + feet.toShortString());
                }
            }
            // 升级脱困（第四轮修复 B）：泡够约 12 秒还没上岸，说明附近没有能爬的岸
            // （陡岸/崖壁水洼/全淹洞穴），光游永远出不去——改走玩家式物理脱困：
            // 有岸壁方向就贴墙把水面以上两格挖成台阶口，挖通后那一格自然成为合格的岸，
            // 上面的找岸逻辑会把 NPC 领进去站稳；全淹洞穴没有岸壁方向时挖头顶开气腔。
            // 只挖和走，全程无传送。挖岸节奏约 2 秒一次，给身体留出贴墙/上浮的时间。
            if (fluidStuckChecks >= 12 && fluidStuckChecks % 2 == 0) {
                boolean dug = fluidEscapeDir != null ? digBankStep(feet) : npc.digOutAbove();
                if (dug) {
                    LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " fluid-escape-dug | fluid="
                            + fluidName + " pos=" + feet.toShortString()
                            + " dir=" + (fluidEscapeDir == null ? "above"
                                    : fluidEscapeDir.toShortString()));
                }
            } else if (fluidStuckChecks >= 3 && fluidStuckChecks % 5 == 3 && npc.digOutAbove()) {
                // 未进入挖岸阶段前的旧升级：泡约 3 秒先试挖头顶（被水洞顶盖住时凿开继续上浮）。
                LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 流体脱困：挖开头顶 | fluid="
                        + fluidName + " pos=" + feet.toShortString());
            }
            return;
        }
        fluidStuckChecks = 0;
        fluidEscapeDir = null;
        // === 实心方块自救：挖开脱困 ===
        // 草、花、火把、告示牌这类方块不是空气，但碰撞盒是空的：站在上面是正常站位。
        // 注意 VoxelShape.bounds() 对空形状会直接抛异常（实测站在草丛里每 0.5 秒抛一次），
        // 必须先判 isEmpty() 再取 bounds()。
        net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(npc.level(), feet);
        // 只处理"整格实心"：地毯、雪片、半砖的碰撞盒不到 1 格高，也不能乱动。
        if (shape.isEmpty() || shape.bounds().maxY < 0.9D) {
            return;
        }
        // 旧做法是把身体"顶出"到方块上方（位置瞬移），已按 Fong 的决定拆除。
        // 改为挖开困住自己的方块：先用最合适的工具挖脚部格，挖不动再素手挖
        // （本插件所有挖掘都是 destroyBlock 一次成型，素手挖只是没有掉落，脱困够用），
        // 脚部实在挖不开（基岩级硬度）就试头顶格。挖开后身体自然落回/走出空格。
        // 代价：若困住他的是 ascend 刚垫的台阶石，挖掉会毁一级台阶——但玩家被方块
        // 卡住时也会挖开它，诚实脱困优先于保台阶，ascend 之后会自己重新垫。
        boolean dug = npc.mineWithBestTool(feet) || npc.mine(feet);
        net.minecraft.core.BlockPos used = feet;
        if (!dug) {
            net.minecraft.core.BlockPos head = feet.above();
            dug = npc.mineWithBestTool(head) || npc.mine(head);
            used = head;
        }
        if (dug) {
            LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 卡在方块里，挖开脱困 | block="
                    + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock())
                    + " pos=" + used.toShortString());
        } else {
            LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 卡在方块里且挖不开（基岩等），等待上层收场 | block="
                    + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock())
                    + " pos=" + feet.toShortString());
        }
    }

    /**
     * 在脚部周围找最近的"能从水里爬上去的岸"作为游泳上岸目标（第四轮修复 B）。
     *
     * <p>旧版 nearestShoreCell 只要求"可站立且干燥"，崖顶格照样入选，NPC 游到
     * 崖壁根部永远爬不上去（第三轮两轮长跑都死于此）。现在候选必须通过
     * {@link PathFinder#isShoreCell}：紧贴水面、落脚面不高于水面 1 格。
     * 半径由调用方按被困时长放大（4→7→11），相当于沿岸横向找缓坡。
     */
    private net.minecraft.core.BlockPos findShoreCell(net.minecraft.core.BlockPos feet, int radius) {
        net.minecraft.core.BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    net.minecraft.core.BlockPos cand = feet.offset(dx, dy, dz);
                    if (cand.equals(feet)) {
                        continue;
                    }
                    if (!PathFinder.isShoreCell(npc.level(), cand)) {
                        continue;
                    }
                    double sq = feet.distSqr(cand);
                    if (sq < bestSq) {
                        bestSq = sq;
                        best = cand.immutable();
                    }
                }
            }
        }
        return best;
    }

    /**
     * 选挖岸脱困的方向（第四轮修复 B）：8 个水平方向逐格外探 10 格，
     * 哪边先碰到"可站立的旱地列"（任意高度，是岸壁就行）就朝哪边挖。
     * 10 格内全是水/虚空（全淹洞穴、湖心）返回 null，改走挖头顶路线。
     */
    private net.minecraft.core.BlockPos pickFluidEscapeDir(net.minecraft.core.BlockPos feet) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        net.minecraft.core.BlockPos bestDir = null;
        int bestDist = Integer.MAX_VALUE;
        for (int[] d : dirs) {
            for (int step = 1; step <= 10; step++) {
                net.minecraft.core.BlockPos col = feet.offset(d[0] * step, 0, d[1] * step);
                boolean land = false;
                for (int dy = -2; dy <= 6 && !land; dy++) {
                    net.minecraft.core.BlockPos cand = col.offset(0, dy, 0);
                    if (PathFinder.isStandable(npc.level(), cand)
                            && npc.level().getBlockState(cand).getFluidState().isEmpty()) {
                        land = true;
                    }
                }
                if (land) {
                    if (step < bestDist) {
                        bestDist = step;
                        bestDir = new net.minecraft.core.BlockPos(d[0], 0, d[1]);
                    }
                    break;
                }
            }
        }
        return bestDir;
    }

    /** NPC 所在列的水面高度（最上面一格流体的 Y）；脚格不是流体时就用脚格 Y 兜底。 */
    private int fluidSurfaceY(net.minecraft.core.BlockPos feet) {
        int y = feet.getY();
        for (int dy = 0; dy <= 6; dy++) {
            net.minecraft.core.BlockPos pos = feet.offset(0, dy, 0);
            if (npc.level().getBlockState(pos).getFluidState().isEmpty()) {
                break;
            }
            y = pos.getY();
        }
        return y;
    }

    /**
     * 挖岸台阶（第四轮修复 B）：朝脱困方向的那一列，把水面以上 1、2 格挖空，
     * 形成一个"脚落在水面+1、头有空"的台阶口——挖完后该格正好通过 isShoreCell，
     * 游泳逻辑会自己爬进去。挖的是岸壁本身（玩家镐子活），不是传送也不是垫方块。
     *
     * @return 是否挖开了至少一块
     */
    private boolean digBankStep(net.minecraft.core.BlockPos feet) {
        int surfaceY = fluidSurfaceY(feet);
        net.minecraft.core.BlockPos lower = new net.minecraft.core.BlockPos(
                feet.getX() + fluidEscapeDir.getX(), surfaceY + 1,
                feet.getZ() + fluidEscapeDir.getZ());
        net.minecraft.core.BlockPos upper = lower.above();
        boolean dug = npc.mineWithBestTool(lower) || npc.mine(lower);
        dug |= npc.mineWithBestTool(upper) || npc.mine(upper);
        return dug;
    }

    /** 原地复活：不替换实体对象，保证技能层/大脑层手里的引用一直有效。 */
    private void revive() {
        deadTicks = 0;
        // 复活必须把死前的执行状态一并清掉：只搬身体不清状态的话，
        // 死前正在跑的技能会因为路径回调被 stopWalking() 静默丢弃而永远挂着
        // （上层全看它"在忙"而集体等待，只能等技能看门狗 40 秒~10 分钟后才醒），
        // fleeing/chasing 也会把目标层继续堵在复活点。
        skills.stop();
        fleeing = false;
        chasing = false;
        fleeCooldown = 0;
        npc.stopWalking();
        // 死亡后 ServerPlayer 会被摘出实体表。先清 removed 标记再重新挂回去，
        // 否则后面的传送、物理、拾取都不会生效（实体不在 level 的 tick 列表里）。
        if (npc.isRemoved()) {
            npc.unsetRemoved();
            ((net.minecraft.server.level.ServerLevel) npc.level()).addNewPlayer(npc);
            LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 重新加入世界实体表");
        }
        org.bukkit.World world = Bukkit.getWorlds().get(0);
        org.bukkit.Location spawn = world.getSpawnLocation();
        int blockX = spawn.getBlockX();
        int blockZ = spawn.getBlockZ();
        // 出生点可能是坑：用最高的可站立方块往上抬一格，避免复活就卡在方块里。
        double y = world.getHighestBlockYAt(blockX, blockZ) + 1.0D;
        npc.teleportTo(((org.bukkit.craftbukkit.CraftWorld) world).getHandle(),
                blockX + 0.5D, y, blockZ + 0.5D, java.util.Set.of(), spawn.getYaw(), spawn.getPitch(), true);
        npc.setHealth(npc.getMaxHealth());
        npc.deathTime = 0;
        npc.clearFire();
        npc.removeAllEffects();
        npc.getFoodData().setFoodLevel(20);
        npc.getFoodData().setSaturation(5.0F);
        restoreInventory();
        npc.level().getChunkSource().move(npc);
        LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 复活 | " + npc.describe());
        // 【第二十三轮】复活后盘点一次（强制打，死亡/复活永不抽样）。
        InventorySnapshot.log(npc, "respawn", "-", true);
    }

    /** 【第二十三轮】死亡现场快照：死因 + 环境 + 活动，key=value 一行打全。 */
    private String deathScene() {
        String cause = "unknown";
        String by = "-";
        try {
            var src = npc.getLastDamageSource();
            if (src != null) {
                cause = src.type().msgId();
                var entity = src.getEntity();
                if (entity != null) {
                    var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                            .getKey(entity.getType());
                    by = key == null ? "-" : key.getPath();
                }
            }
        } catch (Throwable ignored) {
            // 取不到伤害来源时退到最后击伤者，仍取不到就如实 unknown。
        }
        if ("unknown".equals(cause)) {
            var mob = npc.getLastHurtByMob();
            if (mob != null) {
                var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(mob.getType());
                cause = "mob";
                by = key == null ? "-" : key.getPath();
            }
        }
        BlockPos pos = npc.blockPosition();
        return "cause=" + cause + " by=" + by
                + " hpPrev=" + (lastAliveHealth < 0 ? "-"
                        : String.format(java.util.Locale.ROOT, "%.1f", lastAliveHealth))
                + " food=" + npc.getFoodData().getFoodLevel()
                + " armor=" + npc.getArmorValue()
                + " pos=" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                + " yaw=" + String.format(java.util.Locale.ROOT, "%.0f", npc.getYRot())
                + " below=" + blockPath(pos.below())
                + " front=" + blockPath(pos.relative(npc.getDirection()))
                + " head=" + blockPath(pos.above())
                + " skill=" + skills.currentSkill()
                + " fleeing=" + fleeing + " chasing=" + chasing;
    }

    private String blockPath(BlockPos pos) {
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(npc.level().getBlockState(pos).getBlock());
        return key == null ? "?" : key.getPath();
    }

    /** 记录死亡前的背包（含护甲槽），复活时用于恢复。 */
    private void snapshotInventory() {
        Inventory inventory = npc.getInventory();
        List<ItemStack> copy = new java.util.ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            copy.add(inventory.getItem(slot).copy());
        }
        inventorySnapshot = copy;
    }

    /**
     * 恢复死亡前背包。
     * 注意死亡掉落已经被 discardDeathDrops() 清掉，所以这里恢复不会复制物品。
     */
    private void restoreInventory() {
        if (inventorySnapshot.isEmpty()) {
            return;
        }
        Inventory inventory = npc.getInventory();
        int restored = 0;
        for (int slot = 0; slot < inventory.getContainerSize() && slot < inventorySnapshot.size(); slot++) {
            ItemStack stack = inventorySnapshot.get(slot);
            if (!stack.isEmpty()) {
                restored++;
            }
            inventory.setItem(slot, stack.copy());
        }
        LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 恢复死亡前背包 | 非空槽=" + restored);
    }

    /**
     * 清掉死亡时掉在尸体附近的物品。
     * 只删刚生成（tickCount 很小）且在 4 格内的掉落物，避免误删附近玩家丢的东西。
     */
    private void discardDeathDrops() {
        // getEntities 返回 List<Entity>，泛型不会跟着谓词收窄，这里只能先拿 Entity 再转。
        List<net.minecraft.world.entity.Entity> drops = npc.level().getEntities(
                npc, npc.getBoundingBox().inflate(4.0D),
                entity -> entity instanceof net.minecraft.world.entity.item.ItemEntity item && item.tickCount < 40);
        int removed = 0;
        for (net.minecraft.world.entity.Entity drop : drops) {
            if (drop instanceof net.minecraft.world.entity.item.ItemEntity item) {
                item.discard();
                removed++;
            }
        }
        if (removed > 0) {
            LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 清理死亡掉落 | 数量=" + removed);
        }
    }

    // ---------------------------------------------------------------- KillAura

    /**
     * 自动反击。只打 {@link Enemy}（僵尸、骷髅、苦力怕等），不会误伤鸡和牛。
     *
     * @return 附近是否存在敌对目标（有目标时上层逻辑会跳过进食/装备）
     */
    private boolean defend() {
        LivingEntity threat = findThreat();
        if (threat == null) {
            if (fleeing && fleeThreatRef != null) {
                // 【fix39】脱离留痕：验收按这行看逃跑是否真拉开到安全圈外。
                LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 脱离成功 | threat=" + fleeThreatRef.getType()
                        + " 距离=" + (fleeThreatRef.isAlive()
                            ? String.format(Locale.ROOT, "%.1f", Math.sqrt(npc.distanceToSqr(fleeThreatRef)))
                            : "已死亡"));
            }
            fleeing = false;
            fleeThreatRef = null;
            fleeCooldown = 0;
            resetStraightFlee();
            if (chasing) {
                // 目标死了或跑远了，停掉追击路径。
                // 但如果技能层此刻正在执行（比如玩家刚让它 goto），不能清，
                // 否则会把技能自己的 A* 路径一起清掉。
                if ("idle".equals(skills.currentSkill())) {
                    npc.stopWalking();
                }
                chasing = false;
            }
            return false;
        }

        boolean hurtByThreat = npc.getLastHurtByMob() == threat;
        double distanceSq = npc.distanceToSqr(threat);

        // 苦力怕不能贴脸：引爆一次就是二十多点伤害，铁剑还没砍死它就先被炸死。
        // 玩家遇到苦力怕会先拉开距离。跑不掉（矿道尽头/无路径）时不进入逃跑状态，
        // 落到下面的近战逻辑——总比站在原地等爆炸强。
        if (threat instanceof net.minecraft.world.entity.monster.Creeper && distanceSq < 49.0D) {
            // 【fix39】苦力怕同理加 pathDone 豁免：路径走完立刻续跑，不等冷却。
            if (fleeCooldown <= 0 || (fleeing && !npc.isWalking() && !npc.isFollowingPath())) {
                // 一秒后才能再规划一次逃跑路径，避免每 0.5 秒跑一次 A*。
                fleeCooldown = 20;
                // 顺序不能反：必须先停技能再铺逃跑路径。
                // skills.stop() 会清掉身体当前路径，反过来先 flee() 后 stop()，
                // 刚算好的逃跑路会被自己当场清掉，NPC 对着苦力怕原地冻住（实测过）。
                if (!"idle".equals(skills.currentSkill())) {
                    LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 中断技能躲避苦力怕 | skill="
                            + skills.currentSkill() + " hp=" + npc.getHealth());
                    skills.stop();
                }
                if (flee(threat)) {
                    fleeing = true;
                    return true;
                }
                fleeing = false;
            } else if (fleeing) {
                return true;
            }
        }

        // 惜命判定（Fong 定：生命第一）：不满足接战条件就先跑，不许恋战。
        // 接战条件收敛在 canEngage()：低血（≤35%）谁都不许打；有武器或有装备可以打；
        // 没武器没装备但血量健康（>70%）可以空手打。旧口径是"赤手或残血就跑"——
        // 健康空手也被迫逃跑，不符合"健康时可以空手 A 野怪"的新规矩，现已拆开。
        // 逃跑期间目标层必须暂停，否则它会在下一 tick 立刻开始挖矿，把逃跑路径冲掉。
        boolean desperateDefense = false;
        // 远程威胁（掠夺者弩/骷髅弓）不许迎着箭冲（第二十一轮，长跑#8 三次被
        // 射杀实测）：旧逻辑满血有石剑就算"能接战"，NPC 迎着弩箭横穿雪原去
        // 砍人，血被一路射到惜命线以下才想起逃，16 格逃跑档又出不了弩的
        // 射程和视线，逃到终点照样被射死。改：远程威胁不在贴脸距离内、且
        // 护甲不够厚（<8，约半套铁甲）或血已到惜命线时，一律走逃跑分支，
        // 并用加长档（28/22/16/10）先脱离射程；甲厚血足仍可按原逻辑压上。
        boolean rangedStandOff = isRangedThreat(threat) && distanceSq > ATTACK_RANGE_SQ
                && !(npc.getArmorValue() >= 8 && npc.getHealth() > LOW_HEALTH_THRESHOLD);
        // 【fix39】逃跑未脱离时不许半路改主意回头追击：血回过惜命线（回血）
        // 但怪还在安全圈内、又没贴脸时，继续把这一程跑完再说。
        boolean stillFleeing = fleeing && threat == fleeThreatRef
                && distanceSq > ATTACK_RANGE_SQ;
        if (!canEngage(npc.getHealth(), hasWeaponOrArmor()) || rangedStandOff || stillFleeing) {
            if (!"idle".equals(skills.currentSkill())) {
                LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 中断技能逃跑（惜命） | threat=" + threat.getType()
                        + " skill=" + skills.currentSkill() + " hp=" + npc.getHealth()
                        + (rangedStandOff ? " 远程威胁不接战" : ""));
                skills.stop();
            }
            if (npc.isUsingItem()) {
                npc.stopUsingItem();
            }
            // 【第二十四轮】直线脱离兜底进行中、身体已经停下（走完或卡住放弃）
            // 时不等 fleeCooldown：立刻重规划——A* 先行，仍无路就轮换方向接着跑。
            // 长跑#8 F 段实证旧节奏是灾难：逃跑失败每约 20 秒才重试一次，其余
            // 时间原地挨箭，直到被掠夺者射杀（第 4 次死亡，hpPrev=2.2）。
            boolean straightRetry = straightFleeEngaged && fleeing && !npc.isWalking();
            // 【fix39】无停顿重规划：A* 主路走完也立刻续跑，不等 cooldown——
            // 旧节奏每 16 格停 0~2 秒，怪是连续移动的，停顿期正好被贴脸
            // （#11/#12 四死 fleeing=true 的主因）。
            boolean pathDone = fleeing && !npc.isWalking() && !npc.isFollowingPath();
            if (fleeCooldown <= 0 || straightRetry || pathDone) {
                fleeCooldown = 40;
                if (flee(threat, rangedStandOff)) {
                    fleeing = true;
                    return true;
                }
                // 跑不掉（墙后/坑下/无可站立点）：绝不能把 fleeing 留在 true 干等——
                // 目标层、发展层、空闲走动都看 isFleeing() 暂停，fleeing 不清就是永久站桩。
                // 拉黑这只威胁让上层恢复干活，并置 desperateDefense：下面只许在被贴脸
                // 时还手自卫，绝不主动追击——低血恋战正是 Fong 要禁的。
                fleeing = false;
                desperateDefense = true;
                ignoreThreat(threat, "逃跑失败（无路径/无可站立点），低血只自卫不追击");
            } else if (fleeing) {
                return true;
            } else {
                desperateDefense = true;
            }
        }

        // 没有被打、也不贴脸时，不打断正在执行的技能（挖矿挖到一半看到远处僵尸不用跑）。
        if (!"idle".equals(skills.currentSkill()) && !hurtByThreat && distanceSq > FORCE_DEFEND_RANGE_SQ) {
            return true;
        }
        if (!"idle".equals(skills.currentSkill())) {
            LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 中断技能自卫 | threat=" + threat.getType()
                    + " skill=" + skills.currentSkill() + " hurt=" + hurtByThreat);
            skills.stop();
        }

        if (npc.isUsingItem()) {
            npc.stopUsingItem();
        }

        if (distanceSq > ATTACK_RANGE_SQ) {
            // 惜命逃跑没跑掉时只许贴脸还手，绝不追击（追击=恋战，低血必死）。
            if (desperateDefense) {
                return true;
            }
            // 超出攻击距离：走 A* 接近，正在走就不要每 0.5 秒重建路径。
            if (!npc.isFollowingPath()) {
                BlockPos goal = PathFinder.standableGoal(npc.level(), threat.blockPosition(), 3);
                if (goal == null) {
                    // 威胁周围没有能站的地方（墙后/坑里）：拉黑 10 秒，让目标层继续干活。
                    ignoreThreat(threat, "附近没有可站的追击点");
                    chasing = false;
                    return true;
                }
                PathFinder.Result result = PathFinder.find(npc.level(), npc.blockPosition(), goal, 3000, !npc.wearingLeatherBoots());
                if (result.found()) {
                    npc.followPath(result.path(), true, null, null);
                    chasing = true;
                    LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 追击 | threat=" + threat.getType()
                            + " 路径=" + result.path().size() + " 耗时=" + result.millis() + "ms");
                } else {
                    // 走不过去：同样拉黑，否则目标层会被这只怪永久暂停（实测沉默 90 秒以上）。
                    ignoreThreat(threat, "追击无路径 " + result.detail());
                    chasing = false;
                }
            }
            return true;
        }

        if (npc.tickCount < nextAttackTick) {
            return true;
        }
        // 进入攻击距离后先把最好的武器换到主手：空手 0.94 点伤害打不死怪，自己会先死。
        ensureBestWeaponInHand();
        float damage = npc.attackTarget(threat);
        nextAttackTick = npc.tickCount + ATTACK_COOLDOWN_TICKS;
        LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 自卫攻击 | threat=" + threat.getType()
                + " damage=" + String.format(Locale.ROOT, "%.2f", damage)
                + " hp=" + String.format(Locale.ROOT, "%.1f", threat.getHealth()));
        return true;
    }

    /** 目标层用它判断是否要暂停推进：逃跑时不能开始挖矿/合成目标。 */
    public boolean isFleeing() {
        return fleeing;
    }

    /**
     * 惜命接战判定（全插件唯一口径，技能层的 attack/slay 入口也用它把关）：
     * <ul>
     *   <li>血量 ≤ {@link #LOW_HEALTH_THRESHOLD}（35%）：不许接战，必须先跑、回血；</li>
     *   <li>有武器或身上有装备：可以打敌对生物；</li>
     *   <li>没武器没装备：只有血量 > {@link #BARE_HAND_MIN_HEALTH}（70%，足够健康）才许空手打。</li>
     * </ul>
     */
    public static boolean canEngage(float health, boolean equipped) {
        if (health <= LOW_HEALTH_THRESHOLD) {
            return false;
        }
        if (equipped) {
            return true;
        }
        return health > BARE_HAND_MIN_HEALTH;
    }

    /** 当前是否有武器可用（主手评分>0；生存层每 1.5 秒会把最好的武器换到主手）或身上有护甲。 */
    private boolean hasWeaponOrArmor() {
        return cachedWeaponScore > 0 || npc.getArmorValue() > 0;
    }

    /** 是否低血（≤35%）：规划器/目标层据此在恢复前不派战斗与危险目标。 */
    public boolean isLowHealth() {
        return npc.getHealth() <= LOW_HEALTH_THRESHOLD;
    }

    /** 背包里有没有能吃的（非黑名单）食物：低血暂停干活回血的前提是真有东西可吃。 */
    public boolean hasEdibleFood() {
        Inventory inventory = npc.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (foodScore(inventory.getItem(slot)) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 朝威胁的反方向跑 16 格。
     * 走 A* 而不是直线：出生点附近有树和坡，直线会被挡住，站着被咬更亏。
     *
     * @return 是否真的跑起来了；调用方据此决定"躲不掉就只能硬拼"
     */
    private boolean flee(LivingEntity threat) {
        return flee(threat, false);
    }

    /**
     * @param extended 远程威胁用加长档（28/22/16/10）：普通档最远 16 格出不了
     *                 弩/弓的射程和视线（第二十一轮），必须跑得更远才算脱离。
     */
    private boolean flee(LivingEntity threat, boolean extended) {
        fleeThreatRef = threat;
        double dx = npc.getX() - threat.getX();
        double dz = npc.getZ() - threat.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.01D) {
            // 完全重叠时随便挑一个方向，避免除以 0。
            dx = 1.0D;
            dz = 0.0D;
            length = 1.0D;
        }
        // 由远到近多档试：16 格反方向在矿道/水洞里常常没有可站位或没有路
        // （实测低血逃跑因此直接失败、被迫贴脸战死），退到 10 格、6 格再试——
        // 先跑开几格脱离攻击距离，也比原地恋战强，这正是惜命要的。
        int[] tiers = extended ? new int[]{28, 22, 16, 10} : new int[]{16, 10, 6};
        // 【第二十四轮】逐档失败明细（无可站立点/A* 详情）攒下来打进日志：
        // 旧版只报"各档全灭"，雪原上为什么全灭无从查起（日志标准：失败留痕）。
        StringBuilder diag = new StringBuilder();
        for (int distance : tiers) {
            double targetX = npc.getX() + dx / length * distance;
            double targetZ = npc.getZ() + dz / length * distance;
            BlockPos desired = BlockPos.containing(targetX, npc.getY(), targetZ);
            // standableGoal 只在目标那一列上下找：矿道里逃跑方向那一列多半是实心石，
            // 纵向翻遍也找不到能站的格（实测三档全灭）。补一圈横向搜索：
            // 以目标列为中心、半径 3 内逐列试，矿道横截面就这么宽，总有一列是通道。
            // 【fix39】落点避流体（沼泽定因）：干落点优先——候选格本身或四邻
            // 有流体的记为湿落点兜底，整圈搜完没有干的才用湿的（沼泽全是水
            // 时不至于无路可逃）；旧版拿第一个能站的点，常贴着水边、直线兜底
            // 再走进水里被史莱姆入水追杀。
            BlockPos goal = null;
            BlockPos wetGoal = null;
            outer:
            for (int r = 0; r <= 3; r++) {
                for (int ox = -r; ox <= r; ox++) {
                    for (int oz = -r; oz <= r; oz++) {
                        if (Math.max(Math.abs(ox), Math.abs(oz)) != r) {
                            continue;
                        }
                        BlockPos candidate = PathFinder.standableGoal(npc.level(), desired.offset(ox, 0, oz), 4);
                        if (candidate == null) {
                            continue;
                        }
                        if (!fluidAdjacent(candidate)) {
                            goal = candidate;
                            break outer;
                        }
                        if (wetGoal == null) {
                            wetGoal = candidate;
                        }
                    }
                }
            }
            if (goal == null) {
                goal = wetGoal;
            }
            if (goal == null) {
                diag.append(distance).append("格:无可站立点 ");
                continue;
            }
            PathFinder.Result result = PathFinder.find(npc.level(), npc.blockPosition(), goal, 4000, !npc.wearingLeatherBoots());
            if (!result.found()) {
                diag.append(distance).append("格:").append(result.detail()).append(' ');
                continue;
            }
            resetStraightFlee();
            npc.followPath(result.path(), true, null, null);
            chasing = true;
            LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 逃跑 | threat=" + threat.getType()
                    + " hp=" + String.format(Locale.ROOT, "%.1f", npc.getHealth())
                    + " 距离档=" + distance + " 路径=" + result.path().size() + " 终点=" + goal.toShortString());
            return true;
        }
        // 【第二十四轮】各档 A* 全灭 ≠ 站着等死：玩家这时沿远离威胁的方向
        // 直线跑，撞坡跳、撞墙挖。走兜底直线脱离；兜底也走不动（真困死）时
        // 才落回原失败语义（贴脸自卫+暂时无视），且远程威胁在兜底开启期间
        // 永远走不到"无视"这一步——箭还在飞，无视等于等死。
        if (fleeStraightLine(threat, dx, dz, diag.toString().trim())) {
            return true;
        }
        LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 逃跑失败：" + java.util.Arrays.toString(tiers)
                + " 格各档都没有可站立位置或路径 | 明细=" + diag.toString().trim());
        return false;
    }

    /**
     * 【第二十四轮】逃跑兜底：直线脱离。沿远离威胁方向（多轮失败后依次偏转
     * ±45°/±90°/180°）用身体层 walkTo 直行 16 格：撞到 1 格坡自动跳、卡住
     * 会挖开挡路方块——都是玩家手段，无传送。身体还在走时不重发；连着两轮
     * 换方向都没挪出 2 格，判定真被地形困死，返回 false 交回贴脸自卫。
     *
     * @param dx/dz 已归一化的远离方向（flee 主方法算好）
     * @param diag  A* 逐档失败明细，进日志留痕
     */
    private boolean fleeStraightLine(LivingEntity threat, double dx, double dz, String diag) {
        long now = System.currentTimeMillis();
        if (npc.isWalking() && now < straightFleeUntil) {
            straightFleeEngaged = true;
            return true;
        }
        BlockPos here = npc.blockPosition();
        boolean moved = straightFleeAnchor == null
                || here.distSqr(straightFleeAnchor) >= 4.0D;
        if (!moved && straightFleeAttempts >= 2) {
            LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 逃跑兜底放弃：直线脱离也走不动"
                    + " | threat=" + threat.getType() + " pos=" + here.toShortString()
                    + " A*明细=" + diag);
            resetStraightFlee();
            return false;
        }
        if (moved) {
            straightFleeAnchor = here;
            straightFleeAttempts = 0;
        }
        int rotation = STRAIGHT_FLEE_ROTATIONS[straightFleeAttempts % STRAIGHT_FLEE_ROTATIONS.length];
        double angle = Math.atan2(dz, dx) + Math.toRadians(rotation);
        double tx = npc.getX() + Math.cos(angle) * STRAIGHT_FLEE_DISTANCE;
        double tz = npc.getZ() + Math.sin(angle) * STRAIGHT_FLEE_DISTANCE;
        npc.walkTo(new net.minecraft.world.phys.Vec3(tx, npc.getY(), tz), true, null);
        straightFleeEngaged = true;
        straightFleeAttempts++;
        straightFleeUntil = now + STRAIGHT_FLEE_WINDOW_MILLIS;
        LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 逃跑兜底：直线脱离 | threat=" + threat.getType()
                + " hp=" + String.format(Locale.ROOT, "%.1f", npc.getHealth())
                + " 偏转=" + rotation + " 次数=" + straightFleeAttempts
                + " 目标=(" + (int) tx + "," + (int) tz + ") A*明细=" + diag);
        return true;
    }

    /** 【fix39】落点是否贴水：本格或四邻同层有流体（逃跑落点避流体用）。 */
    private boolean fluidAdjacent(BlockPos pos) {
        if (!npc.level().getFluidState(pos).isEmpty()) {
            return true;
        }
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            if (!npc.level().getFluidState(pos.relative(dir)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 直线脱离兜底状态清零：A* 逃跑成功、无威胁、兜底放弃时调用。 */
    private void resetStraightFlee() {
        straightFleeEngaged = false;
        straightFleeAttempts = 0;
        straightFleeAnchor = null;
        straightFleeUntil = 0L;
    }

    private LivingEntity findThreat() {
        long now = System.currentTimeMillis();
        List<net.minecraft.world.entity.Entity> candidates = npc.level().getEntities(
                npc,
                npc.getBoundingBox().inflate(defendRadius),
                entity -> entity instanceof Enemy && ((LivingEntity) entity).isAlive());
        LivingEntity best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (net.minecraft.world.entity.Entity entity : candidates) {
            // 之前判定"打不到"的怪在有效期内直接跳过，避免目标层被永久卡住。
            Long until = ignoredThreats.get(entity.getUUID());
            if (until != null) {
                if (now < until) {
                    // 它要是真能打到我们（正在挨打），说明其实够得着，立刻解除无视。
                    if (npc.getLastHurtByMob() != entity) {
                        continue;
                    }
                    ignoredThreats.remove(entity.getUUID());
                } else {
                    ignoredThreats.remove(entity.getUUID());
                }
            }
            double distanceSq = npc.distanceToSqr(entity);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = (LivingEntity) entity;
            }
        }
        if (best == null) {
            // 射手在防御半径外放箭时，上面的扫描看不见它（掠夺者射程远超
            // defendRadius=8），NPC 会站着挨射（第二十一轮）。用"最近打我
            // 的怪"兜底：是远程敌对且在 32 格内，就按威胁交给 defend() 的
            // 远程处置去逃；它正在打我们，拉黑表对它也不适用，一并摘掉。
            LivingEntity hurter = npc.getLastHurtByMob();
            if (hurter != null && hurter != npc && hurter.isAlive() && hurter instanceof Enemy
                    && isRangedThreat(hurter) && npc.distanceToSqr(hurter) <= 32.0D * 32.0D) {
                ignoredThreats.remove(hurter.getUUID());
                return hurter;
            }
            // 【fix39】逃跑记忆兜底：正在逃的怪出了 8 格扫描圈、但还在安全圈
            // （近战 28 格/远程 36 格）内，仍按威胁返回——逃跑不中断，直到
            // 真正拉开距离才脱离（拉黑表对它不适用：desperate 路径已先清
            // fleeing，记忆不会把被无视的怪复活）。
            if (fleeing && fleeThreatRef != null && fleeThreatRef.isAlive()) {
                double safeSq = isRangedThreat(fleeThreatRef) ? 36.0D * 36.0D : 28.0D * 28.0D;
                if (npc.distanceToSqr(fleeThreatRef) <= safeSq) {
                    return fleeThreatRef;
                }
            }
        }
        return best;
    }

    /** 远程威胁：掠夺者（弩），或主手拿弩/弓的敌对怪（骷髅等）。 */
    private boolean isRangedThreat(LivingEntity threat) {
        if (threat instanceof net.minecraft.world.entity.monster.illager.Pillager) {
            return true;
        }
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(threat.getMainHandItem().getItem()).getPath();
        return path.equals("crossbow") || path.equals("bow");
    }

    /**
     * 把打不到的威胁拉黑 10 秒：这是玩家"无视墙后的僵尸继续挖矿"的等价行为。
     */
    private void ignoreThreat(LivingEntity threat, String reason) {
        ignoredThreats.put(threat.getUUID(), System.currentTimeMillis() + THREAT_IGNORE_MILLIS);
        LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 威胁不可达，暂时无视 10 秒 | threat="
                + threat.getType() + " 原因=" + reason);
    }

    /**
     * 目标层用它判断"现在该不该让出移动权"。
     * 只看已经贴脸（5 格内）或正在攻击我们的怪：远处看到僵尸不需要停下挖矿。
     * 已拉黑的不可达威胁不算，否则目标层会被永久暂停。
     */
    public boolean isEngaged() {
        LivingEntity threat = findThreat();
        if (threat == null) {
            return false;
        }
        return npc.distanceToSqr(threat) <= FORCE_DEFEND_RANGE_SQ || npc.getLastHurtByMob() == threat;
    }

    // ---------------------------------------------------------------- AutoEat

    /**
     * 饥饿值低于阈值就找食物吃；食物优先取当前手持，其次快捷栏，最后从背包换进来。
     *
     * @param onlyWhenSafe true 表示只在没有贴身威胁时进食（战斗间隙补一口）
     */
    private void eat(boolean onlyWhenSafe) {
        // 回血优先（惜命）：原版要食物值 ≥18 才回血，只按平时的 eatThreshold（14）
        // 吃两口就停，血永远回不上来。所以只要血没满且包里有吃的，就吃到 19 再停，
        // 让原版回血真正转起来；血满后回到日常阈值，不浪费食物。
        int threshold = npc.getHealth() < npc.getMaxHealth() ? 19 : eatThreshold;
        if (npc.getFoodData().getFoodLevel() >= threshold) {
            return;
        }
        if (onlyWhenSafe && hasEnemyWithin(FORCE_DEFEND_RANGE_SQ)) {
            return;
        }
        if (npc.isUsingItem()) {
            // 原版正在进食，等它自然完成；这里不再重复触发使用。
            return;
        }
        int slot = ensureFoodInHand();
        if (slot < 0) {
            return;
        }
        ItemStack food = npc.getMainHandItem();
        if (food.isEmpty() || food.get(DataComponents.FOOD) == null) {
            return;
        }
        // 只调用一次 useItem：FoodItem 会 startUsingItem，之后由 ServerPlayer.tick 完成 32 tick 的进食。
        npc.gameMode.useItem(npc, npc.level(), food, InteractionHand.MAIN_HAND);
        LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 开始进食 | item="
                + food.getItem() + " food=" + npc.getFoodData().getFoodLevel() + " slot=" + slot);
    }

    /**
     * 保证主手是营养价值最高的食物：先扫快捷栏，找不到就把背包里的食物和当前选中格交换。
     * 用评分而不是"第一个食物"，是因为背包里会混进僵尸掉落的腐肉，
     * 按顺序取会优先吃腐肉，食物值反而被饥饿效果压回 0。
     *
     * @return 食物所在快捷栏索引；没有可用食物返回 -1
     */
    private int ensureFoodInHand() {
        Inventory inventory = npc.getInventory();
        // 1.21.11 的 selected 字段是 private，统一走 getSelectedSlot/setSelectedSlot。
        int selected = inventory.getSelectedSlot();
        int bestSlot = -1;
        int bestScore = -1;
        // 0..35 一起扫：0..8 是快捷栏，9..35 是背包。
        for (int slot = 0; slot < 36; slot++) {
            int score = foodScore(inventory.getItem(slot));
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        if (bestSlot < 0) {
            return -1;
        }
        if (bestSlot < 9) {
            // 已经在快捷栏，直接切换选中格，不移动物品。
            inventory.setSelectedSlot(bestSlot);
            return bestSlot;
        }
        // 在背包深处：和当前选中格整组交换，原来的物品放回背包，不会凭空复制。
        ItemStack old = inventory.getItem(selected);
        inventory.setItem(selected, inventory.getItem(bestSlot));
        inventory.setItem(bestSlot, old);
        return selected;
    }

    /**
     * 食物评分：营养值 ×2 + 饱和度 ×10（饱和度按 10 倍折算，和原版实际恢复量同量级）。
     * 非食物和黑名单食物统一返回 -1，保证不会被选中。
     */
    private int foodScore(ItemStack stack) {
        if (stack.isEmpty() || HARMFUL_FOODS.contains(stack.getItem())) {
            return -1;
        }
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null) {
            return -1;
        }
        return food.nutrition() * 2 + Math.round(food.saturation() * 10.0F);
    }

    /** 指定半径平方内是否有敌对生物；用于判断"现在能不能安全吃一口"。 */
    private boolean hasEnemyWithin(double radiusSq) {
        double radius = Math.sqrt(radiusSq);
        List<net.minecraft.world.entity.Entity> nearby = npc.level().getEntities(
                npc,
                npc.getBoundingBox().inflate(radius),
                entity -> entity instanceof LivingEntity living && living instanceof Enemy && living.isAlive());
        for (net.minecraft.world.entity.Entity entity : nearby) {
            if (npc.distanceToSqr(entity) <= radiusSq) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- AutoArmor

    /** 从背包里挑防御值更高的护甲换上，旧护甲放回原格。 */
    private void equipBetterArmor() {
        Inventory inventory = npc.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack candidate = inventory.getItem(slot);
            if (candidate.isEmpty()) {
                continue;
            }
            Equippable equippable = candidate.get(DataComponents.EQUIPPABLE);
            if (equippable == null) {
                continue;
            }
            EquipmentSlot equipmentSlot = equippable.slot();
            if (!equipmentSlot.isArmor()) {
                continue;
            }
            int candidateScore = armorScore(candidate);
            ItemStack current = npc.getItemBySlot(equipmentSlot);
            int currentScore = current.isEmpty() ? -1 : armorScore(current);
            // 【第十九轮】细雪地带护脚特例：原版只有皮靴防细雪下陷，铁靴防御
            // 更高但一穿上就又陷雪。在细雪地带（身体判定）里，脚部只认皮靴：
            // 皮靴在脚上时不许被任何其他靴子换下；背包里有皮靴而脚上不是，
            // 无视防御分直接换上。离开细雪地带后恢复纯防御评分。
            if (equipmentSlot == EquipmentSlot.FEET && npc.inPowderSnowCountry()) {
                boolean candidateLeather = candidate.is(Items.LEATHER_BOOTS);
                boolean currentLeather = current.is(Items.LEATHER_BOOTS);
                if (currentLeather && !candidateLeather) {
                    continue;
                }
                if (candidateLeather && !currentLeather) {
                    npc.setItemSlot(equipmentSlot, candidate);
                    inventory.setItem(slot, current);
                    LOG.info("[AIV][SURVIVAL] " + npc.npcName()
                            + " powder-snow-boots | 细雪地带换上皮靴 替换="
                            + (current.isEmpty() ? "空" : current.getItem().toString()));
                    continue;
                }
            }
            if (candidateScore <= currentScore) {
                continue;
            }
            npc.setItemSlot(equipmentSlot, candidate);
            inventory.setItem(slot, current);
            LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 更换装备 | slot=" + equipmentSlot
                    + " item=" + candidate.getItem() + " score=" + candidateScore
                    + " 替换=" + (current.isEmpty() ? "空" : current.getItem() + "(" + currentScore + ")"));
        }
    }

    /**
     * 护甲评分：护甲值 ×10 + 盔甲韧性。
     * 1.21 之后护甲是数据驱动的，没有 ArmorItem.getDefense() 可读，只能从属性修饰符里取。
     */
    private int armorScore(ItemStack stack) {
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers == null) {
            return 0;
        }
        int score = 0;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            Attribute attribute = entry.attribute().value();
            double amount = entry.modifier().amount();
            if (attribute == Attributes.ARMOR.value()) {
                score += (int) Math.round(amount * 10.0D);
            } else if (attribute == Attributes.ARMOR_TOUGHNESS.value()) {
                score += (int) Math.round(amount);
            }
        }
        return score;
    }

    // ---------------------------------------------------------------- AutoSword

    /**
     * 把攻击力最高的武器换到主手（Wurst AutoSword 的核心逻辑）。
     *
     * <p>评分直接读 {@code ATTACK_DAMAGE} 修饰符：1.21 的剑/斧伤害全是数据驱动的，
     * 没有 SwordItem.getDamage() 之类的接口。空手/食物/方块评分为 0，不会被选中。
     */
    private void ensureBestWeaponInHand() {
        // 正在吃东西时换手会打断进食，等吃完再说。
        if (npc.isUsingItem()) {
            return;
        }
        Inventory inventory = npc.getInventory();
        int selected = inventory.getSelectedSlot();
        int bestSlot = -1;
        int bestScore = weaponScoreCapped(inventory.getItem(selected));
        for (int slot = 0; slot < 36; slot++) {
            int score = weaponScoreCapped(inventory.getItem(slot));
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        if (bestSlot < 0) {
            return;
        }
        if (bestSlot < 9) {
            // 已经在快捷栏，直接切换选中格。
            inventory.setSelectedSlot(bestSlot);
        } else {
            // 在背包深处：和当前选中格整组交换，避免凭空生成物品。
            ItemStack old = inventory.getItem(selected);
            inventory.setItem(selected, inventory.getItem(bestSlot));
            inventory.setItem(bestSlot, old);
        }
        // 只在武器/槽位真的变了的时候打日志，避免快捷栏被临时切走后切回造成刷屏。
        ItemStack chosen = inventory.getItem(inventory.getSelectedSlot());
        String choice = chosen.getItem() + "@" + inventory.getSelectedSlot();
        if (!choice.equals(lastWeaponChoice)) {
            lastWeaponChoice = choice;
            LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 切换武器 | item=" + chosen.getItem()
                    + " score=" + bestScore + " slot=" + inventory.getSelectedSlot());
        }
    }

    /** 武器评分 = 所有 ATTACK_DAMAGE 修饰符之和；没有攻击加成的物品为 0。 */
    /**
     * 【第三十二轮】退役武器（只剩最后 1 点耐久）评分压到最低：有别的武器时绝不
     * 拿它打架——长跑#9 实测石剑剩 1 耐久仍被选去砍僵尸、一刀碎掉。真没别的
     * 武器时它评分仍为正，可当最后手段被选中。
     */
    private int weaponScoreCapped(ItemStack stack) {
        int score = weaponScore(stack);
        if (score <= 0) {
            return score;
        }
        if (stack.isDamageableItem()
                && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
            score = 1;
        }
        // 【fix39】武器类别权重：剑/斧是正经武器、永远排在镐/锹前面（退役
        // 压分在类别内生效）——沼泽实证：退役木剑评分 1 输给健康木镐 2，
        // NPC 拿镐打蜘蛛、剑镐先后打碎。类别 +100 后：退役剑 101 > 健康镐
        // 2；同类内健康件仍压退役件。
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).getPath();
        if (path.endsWith("_sword") || path.endsWith("_axe")) {
            score += 100;
        }
        return score;
    }

    private int weaponScore(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers == null) {
            return 0;
        }
        int score = 0;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.attribute().value() == Attributes.ATTACK_DAMAGE.value()) {
                score += (int) Math.round(entry.modifier().amount() * 10.0D);
            }
        }
        return score;
    }
}

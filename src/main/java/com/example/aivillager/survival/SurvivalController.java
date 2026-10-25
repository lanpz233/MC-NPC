package com.example.aivillager.survival;

import com.example.aivillager.npc.FakePlayerBody;
import com.example.aivillager.pathfinding.PathFinder;
import com.example.aivillager.skills.SkillRunner;
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
    private boolean chasing;
    private int deadTicks;
    private boolean deathLogged;
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
    /** 暂时无视的威胁：实体 UUID → 解除无视的时间戳（毫秒）。 */
    private final java.util.Map<java.util.UUID, Long> ignoredThreats = new java.util.HashMap<>();
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
        if (fleeCooldown > 0) {
            fleeCooldown--;
        }

        // 自埋自救：爬坡垫脚、从矿道破土而出时偶尔会把自己卡进一个实心方块里
        // （实测脚部格是圆石）。这时身体位置不可站立，A* 起点非法，所有寻路
        // （砍树/走路/找矿）都会瞬间失败，NPC 外表上就是站在原地反复报"不可达"。
        // 玩家遇到这种情况会被原版自动顶出方块，这里用同样的修正方式自救。
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
     * 两件事：卡进实心方块 → 顶出；站在流体里 → 跳出。
     *
     * <p>只看脚部这一格：头被卡住时原版通常还能把身体挤出去，
     * 而脚部被占/被淹意味着"这一格站不了"，身体会被持续顶/冲、A* 起点永远非法。
     */
    private void unstuck() {
        net.minecraft.core.BlockPos feet = npc.blockPosition();
        net.minecraft.world.level.block.state.BlockState state = npc.level().getBlockState(feet);
        if (state.isAir() || state.is(net.minecraft.world.level.block.Blocks.BEDROCK)) {
            return;
        }
        // === 流体自救 ===
        // 站在水/岩浆里会被流体推动、卡住跳不上岸：直接传送出流体。
        if (!state.getFluidState().isEmpty()) {
            boolean isWater = state.is(net.minecraft.world.level.block.Blocks.WATER);
            String fluidName = isWater ? "water" : (state.is(net.minecraft.world.level.block.Blocks.LAVA) ? "lava" : "fluid");
            // 先看正上方 1~4 格：如果上方是空气（头顶没遮），且下方有支撑，就跳上去。
            // 1.21 里流体高度不到 1 格，Y+2 基本能站在水面上。
            for (int up = 1; up <= 4; up++) {
                net.minecraft.core.BlockPos above = feet.above(up);
                net.minecraft.world.level.block.state.BlockState aboveState = npc.level().getBlockState(above);
                if (!aboveState.isAir() || !aboveState.getCollisionShape(npc.level(), above).isEmpty()) {
                    continue; // 上方是实心/有碰撞 → 不能站
                }
                // 再下一格应该是实心支撑：避免"跳到悬空空气里"。
                net.minecraft.core.BlockPos support = above.below();
                net.minecraft.world.level.block.state.BlockState supportState = npc.level().getBlockState(support);
                if (supportState.getCollisionShape(npc.level(), support).isEmpty()
                        && supportState.getFluidState().isEmpty()) {
                    continue; // 支撑也是空气 → 不稳，继续往上
                }
                npc.teleportTo(npc.level(), npc.getX(), above.getY() + 0.1D, npc.getZ(), java.util.Set.of(),
                        npc.getYRot(), npc.getXRot(), true);
                LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 从流体里跳出 | fluid="
                        + fluidName + " pos=" + feet.toShortString() + " → Y=" + above.getY());
                return;
            }
            // 上方不行：找 6 个相邻格里离自己最近的"实心可站立"格，传送过去。
            // 这会让 NPC 从水池中心跳到池边。
            net.minecraft.core.BlockPos best = null;
            double bestSq = Double.MAX_VALUE;
            for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                for (int dy : new int[]{0, 1, -1}) {
                    net.minecraft.core.BlockPos cand = feet.offset(d[0], d[1] + dy, d[2]);
                    net.minecraft.world.level.block.state.BlockState candState = npc.level().getBlockState(cand);
                    net.minecraft.world.level.block.state.BlockState belowState = npc.level().getBlockState(cand.below());
                    // 候选格是空气 + 下方是实心 → 可站立
                    if (candState.isAir()
                            && !belowState.getCollisionShape(npc.level(), cand.below()).isEmpty()) {
                        double sq = feet.distSqr(cand);
                        if (sq < bestSq) {
                            bestSq = sq;
                            best = cand.immutable();
                        }
                    }
                }
            }
            if (best != null) {
                npc.teleportTo(npc.level(), best.getX() + 0.5D, best.getY() + 0.1D, best.getZ() + 0.5D,
                        java.util.Set.of(), npc.getYRot(), npc.getXRot(), true);
                LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 从流体里传送 | fluid="
                        + fluidName + " pos=" + feet.toShortString() + " → " + best.toShortString());
                return;
            }
            // 池底也没有可跳的地方：放弃，让 descend/ascend 自己处理。
            return;
        }
        // === 实心方块自救 ===
        // 草、花、火把、告示牌这类方块不是空气，但碰撞盒是空的：站在上面是正常站位。
        // 注意 VoxelShape.bounds() 对空形状会直接抛异常（实测站在草丛里每 0.5 秒抛一次），
        // 必须先判 isEmpty() 再取 bounds()。
        net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(npc.level(), feet);
        // 只处理"整格实心"：地毯、雪片、半砖的碰撞盒不到 1 格高，也不能乱动。
        if (shape.isEmpty() || shape.bounds().maxY < 0.9D) {
            return;
        }
        // 优先把身体顶到方块上方，而不是把方块挖掉：
        // 实测这是 ascend 垒台阶时身体被挤进垫脚石造成的，挖掉等于把刚垒好的台阶毁掉，
        // 爬一级毁一级，NPC 会卡在原地反复挖。原版对卡进方块的玩家也是往上修正位置。
        double topY = feet.getY() + shape.bounds().maxY + 0.001D;
        npc.teleportTo(npc.level(), npc.getX(), topY, npc.getZ(), java.util.Set.of(),
                npc.getYRot(), npc.getXRot(), true);
        LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 卡在方块里，顶出自救 | block="
                + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock())
                + " pos=" + feet.toShortString() + " → Y=" + String.format(java.util.Locale.ROOT, "%.2f", topY));
    }

    /** 原地复活：不替换实体对象，保证技能层/大脑层手里的引用一直有效。 */
    private void revive() {
        deadTicks = 0;
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
            fleeing = false;
            fleeCooldown = 0;
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
            if (fleeCooldown <= 0) {
                // 一秒后才能再规划一次逃跑路径，避免每 0.5 秒跑一次 A*。
                fleeCooldown = 20;
                if (flee(threat)) {
                    if (!"idle".equals(skills.currentSkill())) {
                        LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 中断技能躲避苦力怕 | skill="
                                + skills.currentSkill() + " hp=" + npc.getHealth());
                        skills.stop();
                    }
                    fleeing = true;
                    return true;
                }
                fleeing = false;
            } else if (fleeing) {
                return true;
            }
        }

        // 打不过就跑：赤手（评分 0）或残血时和僵尸硬拼必死，死了还会丢光进度。
        // 逃跑期间目标层必须暂停，否则它会在下一 tick 立刻开始挖矿，把逃跑路径冲掉。
        if (cachedWeaponScore <= 0 || npc.getHealth() <= 8.0F) {
            if (!"idle".equals(skills.currentSkill())) {
                LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 中断技能逃跑 | threat=" + threat.getType()
                        + " skill=" + skills.currentSkill() + " hp=" + npc.getHealth());
                skills.stop();
            }
            if (npc.isUsingItem()) {
                npc.stopUsingItem();
            }
            fleeing = true;
            if (fleeCooldown <= 0) {
                flee(threat);
                fleeCooldown = 40;
            }
            return true;
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
            // 超出攻击距离：走 A* 接近，正在走就不要每 0.5 秒重建路径。
            if (!npc.isFollowingPath()) {
                BlockPos goal = PathFinder.standableGoal(npc.level(), threat.blockPosition(), 3);
                if (goal == null) {
                    // 威胁周围没有能站的地方（墙后/坑里）：拉黑 10 秒，让目标层继续干活。
                    ignoreThreat(threat, "附近没有可站的追击点");
                    chasing = false;
                    return true;
                }
                PathFinder.Result result = PathFinder.find(npc.level(), npc.blockPosition(), goal, 3000);
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
     * 朝威胁的反方向跑 16 格。
     * 走 A* 而不是直线：出生点附近有树和坡，直线会被挡住，站着被咬更亏。
     *
     * @return 是否真的跑起来了；调用方据此决定"躲不掉就只能硬拼"
     */
    private boolean flee(LivingEntity threat) {
        double dx = npc.getX() - threat.getX();
        double dz = npc.getZ() - threat.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.01D) {
            // 完全重叠时随便挑一个方向，避免除以 0。
            dx = 1.0D;
            dz = 0.0D;
            length = 1.0D;
        }
        double targetX = npc.getX() + dx / length * 16.0D;
        double targetZ = npc.getZ() + dz / length * 16.0D;
        BlockPos desired = BlockPos.containing(targetX, npc.getY(), targetZ);
        BlockPos goal = PathFinder.standableGoal(npc.level(), desired, 8);
        if (goal == null) {
            LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 逃跑失败：反方向没有可站立位置");
            return false;
        }
        PathFinder.Result result = PathFinder.find(npc.level(), npc.blockPosition(), goal, 4000);
        if (!result.found()) {
            LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 逃跑失败：无路径 " + result.detail());
            return false;
        }
        npc.followPath(result.path(), true, null, null);
        chasing = true;
        LOG.info("[AIV][SURVIVAL] " + npc.npcName() + " 逃跑 | threat=" + threat.getType()
                + " hp=" + String.format(Locale.ROOT, "%.1f", npc.getHealth())
                + " 路径=" + result.path().size() + " 终点=" + goal.toShortString());
        return true;
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
        return best;
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
        if (npc.getFoodData().getFoodLevel() >= eatThreshold) {
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
        int bestScore = weaponScore(inventory.getItem(selected));
        for (int slot = 0; slot < 36; slot++) {
            int score = weaponScore(inventory.getItem(slot));
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

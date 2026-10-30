package com.example.aivillager.npc;

import com.mojang.authlib.GameProfile;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 伪玩家身体：一个真正进入 PlayerList 的 ServerPlayer，但没有客户端。
 *
 * <p>它和普通玩家共享同一套服务端逻辑：实体追踪、碰撞、掉落物拾取、饥饿/生命、攻击判定。
 * 区别只在于移动输入由 {@link #applyMovementInput()} 直接写 {@code zza/xxa}，而不是从网络包读取。
 *
 * <p>这个类是 spike 阶段的验证载体，后续会被拆成 Body 接口 + 技能层（寻路/挖矿/战斗）。
 */
public final class FakePlayerBody extends ServerPlayer {

    private static final Logger LOG = Logger.getLogger("AIVillager");

    /** 到达目标的水平距离平方（0.5 格）。 */
    private static final double ARRIVE_DISTANCE_SQ = 0.25D;
    /** 连续多少 tick 没有靠近目标就判定为卡住（约 4 秒）。 */
    private static final int STUCK_TICKS = 80;
    /**
     * 路径跟随的卡住判定（约 2 秒）。
     * 不能设太长：卡住时 NPC 在玩家眼里就是站着不动，2 秒后放弃并重新规划，
     * 比顶着墙硬等 5 秒看起来自然得多。
     */
    private static final int PATH_STUCK_TICKS = 40;
    /**
     * 判定"到达某个 waypoint"的垂直容差。
     * 必须小于 1：曾经设成 1.2，站在台阶下（y=83）就把上面一格的 waypoint（y=84）
     * 当成"已到达"，连着跳两格 waypoint 却始终没真正爬上去，最后卡在 2 格高的墙前
     * 100 tick 不动——看起来就是"很呆、一直不动"。
     */
    private static final double ARRIVE_Y_TOLERANCE = 0.6D;

    private String npcName = "npc";
    private Vec3 walkTarget;
    private boolean walkSprint;
    private Runnable walkCallback;

    /**
     * 游泳目标（岸边格子）；非 null 时身体处于"游泳脱困"模式，优先于路径/直线行走。
     * 真玩家掉水里靠游泳上岸，伪玩家不许传送脱身（Fong 定：不许任何不属于玩家的手段），
     * 所以流体脱困只能走原版物理：朝岸边给前进输入 + 浮力上浮 + 到岸边跳上岸。
     */
    private BlockPos swimGoal;
    /** 游泳模式剩余 tick（约 10 秒）；超时放弃，交给技能/规划层重新处理，不无限泡着。 */
    private int swimTicksLeft;

    /** A* 路径；非 null 时优先级高于 walkTarget。 */
    private List<BlockPos> path;
    private int pathIndex;
    private boolean pathSprint;
    private Runnable pathArrive;
    private Runnable pathAbandon;

    /** 自救最多连续挖几次方块；超过说明不是"被方块挡住"而是卡在碰撞/半格地形里。 */
    private static final int MAX_DIG_RESCUES = 3;

    private int jumpCooldown;
    /** 工具使用取证日志（第十八轮）：上次记录的工具名 + 抽样计数。 */
    private String lastToolLogName = "";
    private int toolLogCounter;
    /** fix44 守卫拒挖日志节流：技能收场前会反复试挖，10 秒最多打一行。 */
    private long lastTierGuardLogTick = -100000L;
    /** 【fix101】镐子兜底链状态：无可用石镐且合不出石镐时，降级用铁/钻镐
     * 顶上干活（Fong 2026-10-28 通则：守卫必须配兜底，只堵不疏不许出厂）。
     * 启用/解除各打一条 pick-fallback 专行日志；石镐回包即解除。 */
    private boolean pickFallbackActive;
    /** fix99 床块守卫日志节流：卡住自救会反复试挖床格，10 秒最多打一行。 */
    private long lastBedGuardLogTick = -100000L;
    /** 【第四十五轮】逃生后岩浆位置记忆：生存层接触岩浆时写入的最后一格 + tick。 */
    private BlockPos recentLavaCell;
    private long recentLavaTick = -100000L;
    private long lastLavaGuardLogTick = -100000L;

    /** 生存层在岩浆里/脱困时调用：记住这口池的位置，防逃生后立刻又被带回去。 */
    public void rememberLava(BlockPos cell) {
        this.recentLavaCell = cell;
        this.recentLavaTick = this.tickCount;
    }

    /**
     * 移动目标是否正朝记忆中的岩浆格收敛（接触后 90 秒内）：目标点离岩浆 ≤3 格
     * 且比我当前更近——是"又往池里走"；目标在远离岩浆（上岸后离开池边）不拦。
     */
    private boolean headingIntoRecentLava(double tx, double tz) {
        if (recentLavaCell == null || this.tickCount - recentLavaTick > 1800) {
            return false;
        }
        double lx = recentLavaCell.getX() + 0.5D;
        double lz = recentLavaCell.getZ() + 0.5D;
        double targetDist = Math.hypot(tx - lx, tz - lz);
        if (targetDist > 3.0D) {
            return false;
        }
        double myDist = Math.hypot(this.getX() - lx, this.getZ() - lz);
        return targetDist < myDist;
    }

    private void logLavaGuard(String mode, double tx, double tz) {
        if (this.tickCount - lastLavaGuardLogTick >= 100) {
            lastLavaGuardLogTick = this.tickCount;
            log(npcName, "lava-memory-guard", "逃生后岩浆记忆拦截（" + mode + "）| lava="
                    + recentLavaCell.toShortString() + " target="
                    + String.format(Locale.ROOT, "%.1f,%.1f", tx, tz) + " " + this.describe());
        }
    }
    private double lastDistanceSq = Double.MAX_VALUE;
    private int noProgressTicks;
    /** 连续卡住自救次数：只有路径真正走完、或离开上次卡住的区域，才清零。 */
    private int stuckRescues;
    /**
     * 上次自救时所在的格子（自救计数不清零的依据）。
     * 两版旧逻辑都栽在"用距离变近判断有进展"上：先是自救后把基准重置成最大值、
     * 下一 tick 任何距离都算进展；改成"近 0.5 格才算"后，身体在墙角/树冠边的
     * 弹跳滑动仍会让距离信号反复抖过阈值。两次实测都是同一路径内自救 5~14 次、
     * 次数恒为 1，兜底永远攒不满。改为按区域判断：人还卡在老地方，
     * 计数就累加；真走完这条路、或挪到 4 格以外的新区域，才重新计数。
     * 计数攒满后的兜底动作（2026-10-25 Fong 拍板后）：放弃当前路径让技能失败收场、
     * 由规划层换路线换目标——不再有任何传送式脱困。
     */
    private BlockPos lastRescueCell;

    private FakePlayerBody(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation information) {
        super(server, level, profile, information);
    }

    /**
     * 生成伪玩家：构造 ServerPlayer -> 伪造连接 -> 走正常登录流程进入 PlayerList。
     */
    public static FakePlayerBody spawn(ServerLevel level, String name, Location at) {
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        GameProfile profile = new GameProfile(offlineUuid(name), name);

        FakePlayerBody body = new FakePlayerBody(server, level, profile, ClientInformation.createDefault());
        // 先进世界再走 placeNewPlayer，避免客户端首包看到 0,0 坐标。
        body.setPos(at.getX(), at.getY(), at.getZ());
        body.setYRot(at.getYaw());
        body.setXRot(at.getPitch());
        body.setYHeadRot(at.getYaw());

        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        server.getPlayerList().placeNewPlayer(new FakeConnection(), body, cookie);

        body.unsetRemoved();
        body.teleportTo(level, at.getX(), at.getY(), at.getZ(), Set.of(), at.getYaw(), at.getPitch(), true);

        body.setHealth(20.0F);
        body.getFoodData().setFoodLevel(20);
        body.getFoodData().setSaturation(5.0F);
        body.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        body.getAbilities().flying = false;
        body.setSprinting(false);
        // 玩家默认能自动上 0.6 格台阶；直接构造的 ServerPlayer 不会自动获得这个属性修正。
        var stepHeight = body.getAttribute(Attributes.STEP_HEIGHT);
        if (stepHeight != null) {
            stepHeight.setBaseValue(0.6F);
        }

        body.npcName = name;
        // 立刻把玩家加入区块跟踪，否则附近客户端可能收不到生成包。
        level.getChunkSource().move(body);
        log(name, "spawn", body.describe());
        return body;
    }

    /** 离线模式 UUID，与 Vanilla 离线服一致：OfflinePlayer:<name> 的 v3 UUID。 */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void tick() {
        applyMovementInput();
        super.tick();
        // 1.21.11 的 ServerPlayer.tick() 不再调用 doTick()，需要手动补上末刻逻辑（参考 Carpet）。
        this.doTick();

        // 每 10 tick 重新同步一次位置，让 Moonrise/ChunkMap 知道伪玩家移动了。
        if (this.level().getServer().getTickCount() % 10 == 0) {
            if (this.connection != null) {
                this.connection.resetPosition();
            }
            this.level().getChunkSource().move(this);
        }
    }

    /**
     * 把"想怎么走"翻译成原版移动输入。Player#aiStep -> travel 会消费这两个字段，
     * 因此伪玩家走的是完整的原版物理（惯性、台阶、落地、碰撞）。
     */
    private void applyMovementInput() {
        this.xxa = 0.0F;
        this.zza = 0.0F;

        // 游泳脱困优先于一切行走：泡在水/岩浆里时路径和直线走都只会贴着底沉，
        // 必须先游到岸边上岸，技能层会在上岸后按新位置重新规划。
        if (this.swimGoal != null) {
            applySwimInput();
            return;
        }

        // A* 路径优先：技能层设置路径后，哪怕之前的 walkTo 还没结束也按路径走。
        if (this.path != null) {
            applyPathInput();
            return;
        }

        if (this.walkTarget == null) {
            this.setSprinting(false);
            return;
        }

        double dx = this.walkTarget.x - this.getX();
        double dz = this.walkTarget.z - this.getZ();
        double distanceSq = dx * dx + dz * dz;

        if (distanceSq <= ARRIVE_DISTANCE_SQ) {
            Vec3 arrived = this.walkTarget;
            Runnable callback = this.walkCallback;
            this.walkTarget = null;
            this.walkCallback = null;
            this.stuckRescues = 0;
            this.lastRescueCell = null;
            this.setSprinting(false);
            log(npcName, "walk-arrive", String.format(Locale.ROOT, "到达 (%.2f, %.2f, %.2f)", arrived.x, arrived.y, arrived.z));
            if (callback != null) {
                callback.run();
            }
            return;
        }

        // 【第四十五轮】逃生后岩浆记忆：直线行走不经 A*，同样的收敛拦截——
        // 取消本次行走，交上层按新位置重新决策（口径同 walk-stuck 的放弃）。
        if (headingIntoRecentLava(this.walkTarget.x, this.walkTarget.z)) {
            logLavaGuard("walk", this.walkTarget.x, this.walkTarget.z);
            this.walkTarget = null;
            this.walkCallback = null;
            this.setSprinting(false);
            return;
        }

        // 面朝目标；MC 的 yaw 0 指向 +Z，需要减 90 度。
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        this.setYRot(yaw);
        this.setYHeadRot(yaw);
        this.zza = 1.0F;
        this.setSprinting(this.walkSprint);

        if (this.jumpCooldown > 0) {
            this.jumpCooldown--;
        } else if (this.onGround && (this.horizontalCollision
                || (this.walkTarget.y - this.getY() > 0.5D && distanceSq < 4.0D))) {
            // 撞到一格高的障碍就起跳，模拟玩家自动跳跃。
            // 【fix61】补路径模式早有的主动起跳：目标就在高一格处且已贴近时不等撞墙先跳——
            // ascend/travel 搭桥后靠直线行走上台阶，纯被动撞墙跳时机太晚，身体贴着台阶块
            // 磨蹭期间脚部格=台阶块本身，会被生存层实心自救当成"卡在方块里"挖掉（farm18 同格同秒实锤）。
            this.jumpFromGround();
            this.jumpCooldown = 5;
        }

        // 卡住检测：持续没有靠近目标就放弃，避免 spike 无限等待。
        // 进展门槛不能太小：贴墙磨蹭/碰撞挤压每 tick 都能蹭出 1e-4 级的距离变化，
        // 旧门槛被这种假进展反复重置，身体对着墙"走"上几分钟也不判卡住
        // （fix10 长跑 descend 单级干等 3 分钟的根源之一）。要求一次实打实的
        // 接近（平方距离至少减小 0.02，约合贴近 0.01~0.07 格）才算进展。
        if (distanceSq < this.lastDistanceSq - 0.02D) {
            this.lastDistanceSq = distanceSq;
            this.noProgressTicks = 0;
        } else if (++this.noProgressTicks > STUCK_TICKS) {
            Vec3 goal = this.walkTarget;
            this.walkTarget = null;
            this.walkCallback = null;
            this.setSprinting(false);
            log(npcName, "walk-stuck", "80 tick 内没有靠近目标，放弃行走 " + this.describe());
            if (goal != null) {
                // 直线行走没有路径可以绕，卡住时先做一次自救：挖开挡路的方块脱困；
                // 连续挖不开就放弃本次行走（上面已经清掉目标），由上层换路线。
                rescueFromStuck("walk", goal.x, goal.z);
            }
        }
    }

    /**
     * 游泳脱困：朝岸边格子游过去。只用真玩家在水里能做的事——
     * 朝向目标前进、持续上浮（原版按住跳跃的上浮效果，与 dryland 技能给向上速度同一做法）、
     * 贴到岸边且脚下有地时起跳上岸。全程走原版流体物理，碰撞照常生效，没有任何位置瞬移。
     *
     * <p>结束条件：离开流体并落地（上岸成功）、或约 10 秒超时（放弃，日志留痕，
     * 由生存层/技能层按现状重新决策；岩浆里游不出去就可能烧死，按正常死亡处理）。
     */
    private void applySwimInput() {
        double tx = this.swimGoal.getX() + 0.5D;
        double tz = this.swimGoal.getZ() + 0.5D;
        double dx = tx - this.getX();
        double dz = tz - this.getZ();
        double distanceSq = dx * dx + dz * dz;
        boolean inFluid = this.isInWater() || this.isInLava();

        if (!inFluid && (this.onGround() || distanceSq <= 0.25D)) {
            log(npcName, "swim-arrive", "已上岸 " + this.describe());
            this.swimGoal = null;
            this.setSprinting(false);
            return;
        }
        if (--this.swimTicksLeft <= 0) {
            log(npcName, "swim-timeout", "游泳约 10 秒仍未上岸，放弃本次脱困 " + this.describe());
            this.swimGoal = null;
            this.setSprinting(false);
            return;
        }

        if (distanceSq > 0.04D) {
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
            this.setYRot(yaw);
            this.setYHeadRot(yaw);
        }
        // 已经浮到目标正上方/正下方时不用再往前蹭，只上浮。
        this.zza = distanceSq > 0.09D ? 1.0F : 0.0F;
        this.setSprinting(false);

        if (inFluid) {
            // 上浮：和原版玩家在水里按住跳跃键等价的持续向上速度；
            // 与 SkillRunner.dryland 的上浮处理是同一个做法（给速度，不动位置）。
            this.jumping = true;
            Vec3 movement = this.getDeltaMovement();
            this.setDeltaMovement(movement.x, Math.max(movement.y, 0.07D), movement.z);
        }
        // 贴着岸边且脚下踩到水底/浅滩时起跳，像玩家扒着岸沿爬上去。
        if (this.jumpCooldown > 0) {
            this.jumpCooldown--;
        } else if (this.onGround() && this.horizontalCollision) {
            this.jumpFromGround();
            this.jumpCooldown = 5;
        }
    }

    /**
     * 进入游泳脱困模式，游向岸边格子（该格必须可站立且不在流体里，由调用方挑选）。
     * 会清掉当前路径/直线行走：水里继续按原路径走只会沉底，上岸后技能层会重新规划。
     */
    public void swimTo(BlockPos shore) {
        this.path = null;
        this.pathIndex = 0;
        this.pathArrive = null;
        this.pathAbandon = null;
        this.walkTarget = null;
        this.walkCallback = null;
        this.swimGoal = shore;
        // 超时按距离放宽（第四轮修复 B）：沿岸找缓坡时岸可能在十几格外，
        // 固定约 10 秒游不到就放弃、反复重发只会原地打转。上限 600 tick（约 30 秒），
        // 再久就该由生存层的挖岸脱困接管，而不是继续干游。
        double swimDist = Math.sqrt(this.blockPosition().distSqr(shore));
        this.swimTicksLeft = (int) Math.min(600, 200 + 20 * Math.ceil(swimDist));
        log(npcName, "swim-start", "游泳脱困 → 岸边 " + shore.toShortString() + " " + this.describe());
    }

    /** 附近没有岸边时原地上浮：先浮到水面，别沉在底下，之后再由技能层想办法靠岸。 */
    public void swimUp() {
        swimTo(this.blockPosition());
    }

    /**
     * 挖开自己头顶的方块（头格与头上一格）：淹水洞穴/被盖住的小坑里浮不出去时，
     * 玩家会凿开头顶继续往上游/爬。只挖硬度可挖的普通方块（基岩等挖不动的返回 false）。
     *
     * <p>【fix133】两格都没挖开时（全淹竖井：头顶两格全是水、流体挖不动），
     * 沿列继续向上扫描，凿穿水层上方的第一格实心顶盖——旧版到此恒失败，
     * 生存层 fluid-escape 全淹分支武装了也挖不出（farm99 实证 escape-dug 全局 0 次、
     * NPC 浮在淹井里 15 分钟）。tryDigBlocking 自会跳过流体与挖不动的块，
     * 全列无可挖仍诚实返回 false。
     *
     * <p>【fix137】续扫上限 +5 → +16：farm104 实证淹水洞腔比 5 格深——NPC 在
     * (550,48,-166) 挖开脚位上方数格后，上方 +1..+5 全是水，扫描恒空转且失败
     * 无日志，困约 1 小时（地表在 +16）。玩家在淹水洞里会贴着自己这列一直往上
     * 凿到空气为止，不会因为头顶 5 格全是水就停手；16 格仍是有界扫描，
     * 全水/全挖不动时照旧诚实 false。
     *
     * @return 是否挖开了至少一块
     */
    public boolean digOutAbove() {
        BlockPos feet = this.blockPosition();
        boolean dug = tryDigBlocking(feet.above(2));
        dug |= tryDigBlocking(feet.above());
        if (!dug) {
            for (int dy = 3; dy <= 16; dy++) {
                if (tryDigBlocking(feet.above(dy))) {
                    dug = true;
                    break;
                }
            }
        }
        return dug;
    }

    /**
     * 【fix137】只读探针：报告头顶列 +1..+16 内第一格实心块（跳过空气与流体），
     * 供生存层在挖顶失败时打诊断行——旧版 digOutAbove 失败无声，淹水洞里空转
     * 1 小时日志零证据（farm104）。不改世界、不挖块。
     *
     * @return 形如 "dy=9 block=diorite diggable=true"，全列无实心块返回 "none"
     */
    public String aboveColumnProbe() {
        BlockPos feet = this.blockPosition();
        for (int dy = 1; dy <= 16; dy++) {
            BlockPos pos = feet.above(dy);
            BlockState state = this.level().getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                continue;
            }
            float hardness = state.getDestroySpeed(this.level(), pos);
            boolean diggable = hardness >= 0.0F && hardness <= 3.0F;
            String blockName = state.getBlock().getName().getString();
            return "dy=" + dy + " block=" + blockName + " diggable=" + diggable;
        }
        return "none";
    }

    public boolean isSwimmingEscape() {
        return this.swimGoal != null;
    }

    /**
     * 按 A* 路径行走：每 tick 只朝当前 waypoint 给输入，到达后自动推进到下一格。
     *
     * <p>关键边界：
     * <ul>
     *   <li>到达判定必须同时满足水平 0.5 格和垂直 1.2 格，否则站在崖顶也会被当成"到了"。</li>
     *   <li>路径第一格通常就是脚下这一格，所以先跳过所有已经到达的 waypoint。</li>
     *   <li>水平对得很准但还高着一层时，改朝下一个 waypoint 走，逼自己从崖边走出去。</li>
     * </ul>
     */
    private void applyPathInput() {
        BlockPos waypoint;
        double tx;
        double ty;
        double tz;
        double distanceSq;

        while (true) {
            waypoint = this.path.get(this.pathIndex);
            tx = waypoint.getX() + 0.5D;
            ty = waypoint.getY();
            tz = waypoint.getZ() + 0.5D;
            double dx = tx - this.getX();
            double dz = tz - this.getZ();
            distanceSq = dx * dx + dz * dz;

            boolean reached = distanceSq <= ARRIVE_DISTANCE_SQ
                    && Math.abs(this.getY() - ty) <= ARRIVE_Y_TOLERANCE;
            if (!reached) {
                break;
            }

            this.pathIndex++;
            if (this.pathIndex >= this.path.size()) {
                finishPath(true);
                return;
            }
            log(npcName, "path-waypoint", "到达 " + (this.pathIndex - 1) + "/" + this.path.size()
                    + " " + waypoint.toShortString());
        }

        // 水平已经对准但人还在上一层（崖边、台阶上方）：直接朝下一格走，避免原地打转。
        // 只在"人比 waypoint 高"时这样做：如果人在下面，朝下一格走会背离台阶，
        // 应该继续对准头上那一格、靠跳跃上去。
        if (distanceSq < 1.0D
                && this.getY() - ty > ARRIVE_Y_TOLERANCE
                && this.pathIndex + 1 < this.path.size()) {
            BlockPos next = this.path.get(this.pathIndex + 1);
            tx = next.getX() + 0.5D;
            tz = next.getZ() + 0.5D;
        }

        double dx = tx - this.getX();
        double dz = tz - this.getZ();
        distanceSq = dx * dx + dz * dz;

        // 【第四十五轮】逃生后岩浆记忆：这条路径的当前航点正朝刚逃出的池收敛，
        // 取消路径交技能层重规划（A* 已会绕开贴岩浆格），别再同点复入。
        if (headingIntoRecentLava(tx, tz)) {
            logLavaGuard("path", tx, tz);
            finishPath(false);
            return;
        }

        // 面朝目标；MC 的 yaw 0 指向 +Z，需要减 90 度。
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        this.setYRot(yaw);
        this.setYHeadRot(yaw);
        this.zza = 1.0F;
        this.setSprinting(this.pathSprint);

        boolean waypointAbove = ty - this.getY() > 0.5D;
        // 掉下路径（例如从台阶边滑落、被怪推下坡）后，当前 waypoint 可能高于头顶 2 格。
        // 玩家也跳不上 2 格，继续在这里顶墙只会站满 2 秒；立即放弃，让技能层按当前位置重新规划。
        if (ty - this.getY() > 1.5D) {
            log(npcName, "path-abort", "goal 高出 " + String.format(Locale.ROOT, "%.1f", ty - this.getY())
                    + " 格，超出跳跃高度，重新规划 " + this.describe());
            finishPath(false);
            return;
        }
        if (this.jumpCooldown > 0) {
            this.jumpCooldown--;
        } else if (this.onGround && (this.horizontalCollision
                || (waypointAbove && distanceSq < 4.0D))) {
            // 台阶/1 格高的障碍：靠原版跳跃 + 前进惯性上去。
            // 除了"撞墙就跳"，还要在目标格就在头顶时主动起跳：
            // 斜着贴边靠近时 horizontalCollision 可能一直是 false，
            // 只靠撞墙判定就永远跳不上台阶，NPC 会原地顶墙 100 tick。
            this.jumpFromGround();
            this.jumpCooldown = 5;
        }

        // 卡住检测：A* 路径已经算好，走不动通常是中途被方块挡住（玩家放方块/门关了）。
        if (distanceSq < this.lastDistanceSq - 1.0E-4D) {
            this.lastDistanceSq = distanceSq;
            this.noProgressTicks = 0;
        } else if (++this.noProgressTicks > PATH_STUCK_TICKS) {
            BlockPos wp = this.path.get(this.pathIndex);
            // 带上 waypoint 和脚/头所在的方块：下次再卡住能直接看出是"跳不上去"还是"被方块挡住"。
            log(npcName, "path-stuck", "连续 " + PATH_STUCK_TICKS + " tick 没有靠近 waypoint "
                    + this.pathIndex + "/" + this.path.size() + " " + wp.toShortString()
                    + " " + this.describe()
                    + " 脚=" + this.level().getBlockState(this.blockPosition()).getBlock()
                    + " 头=" + this.level().getBlockState(this.blockPosition().above()).getBlock()
                    + " wp脚=" + this.level().getBlockState(wp).getBlock()
                    + " wp头=" + this.level().getBlockState(wp.above()).getBlock());
            // 先自救再决定是否放弃：玩家撞上两格高的墙会直接把墙挖开，
            // 伪玩家如果只会每 2 秒报一次"无法到达"，就会在同一个坑里无限重试。
            boolean gaveUp = rescueFromStuck("path", wp.getX() + 0.5D, wp.getZ() + 0.5D);
            if (gaveUp) {
                // 挖也挖不开：放弃这条路径，交给技能层按当前位置重新规划/换目标，
                // 不再有"传送到附近可站格"这种玩家做不到的脱困（Fong 定）。
                finishPath(false);
            }
        }
    }

    /**
     * 卡住自救：像玩家一样挖开挡路的方块脱困；连续挖不开就放弃当前行走/路径，
     * 让技能以失败收场、规划层换路线换目标。不许传送（Fong 定：只许真玩家手段）。
     *
     * <p>为什么需要它：A* 只保证一格"能站人"，不保证 1.8 格高的身体能从当前格挤过去。
     * 丛林里竹子、树叶、藤蔓的组合经常把一格宽的缝堵成实际过不去的墙——
     * 玩家会直接把方块挖掉，伪玩家如果只会继续顶墙，就会在同一个位置无限重试。
     *
     * @param phase 触发阶段（path/walk），只用于日志
     * @param tx    当前想去的水平坐标，用来决定往哪个方向挖
     * @return 是否已经放弃（计数攒满仍脱困失败）：路径调用方据此 finishPath(false)
     */
    private boolean rescueFromStuck(String phase, double tx, double tz) {
        this.stuckRescues++;
        BlockPos feet = this.blockPosition();
        this.lastRescueCell = feet;
        int dirX = (int) Math.signum(tx - this.getX());
        int dirZ = (int) Math.signum(tz - this.getZ());
        if (dirX == 0 && dirZ == 0) {
            // 水平已经对齐（例如只需要往下掉）：按当前朝向取前方一格。
            double yawRad = Math.toRadians(this.getYRot() + 90.0D);
            dirX = (int) Math.round(Math.cos(yawRad));
            dirZ = (int) Math.round(Math.sin(yawRad));
        }
        BlockPos frontFeet = feet.offset(dirX, 0, dirZ);
        BlockPos frontHead = frontFeet.above();
        boolean dugFeet = tryDigBlocking(frontFeet);
        boolean dugHead = tryDigBlocking(frontHead);
        // 斜着走被卡住时，真正挡路的往往不是斜前格（是空气），
        // 而是左右两侧正交格的边角（树冠、墙角）：身体 0.6 格宽，斜穿时两边都会剐蹭。
        // 实测 NPC 卡在树冠角落里，斜前全是空气、挖脚/挖头恒为 false，自救等于空转。
        // 斜向移动且斜前没挖到东西时，把两个正交侧格也试着挖开。
        boolean dugSide = false;
        if (!dugFeet && !dugHead && dirX != 0 && dirZ != 0) {
            BlockPos sideA = feet.offset(dirX, 0, 0);
            BlockPos sideB = feet.offset(0, 0, dirZ);
            dugSide = tryDigBlocking(sideA) | tryDigBlocking(sideA.above())
                    | tryDigBlocking(sideB) | tryDigBlocking(sideB.above());
        }
        // 前方都挖不到时再试自己头顶：被盖住的小坑/低矮洞口里，挡路的是头上的方块，
        // 挖掉才能起跳出去（玩家在 1×2 洞里也是先挖头顶）。
        boolean dugCeiling = false;
        if (!dugFeet && !dugHead && !dugSide) {
            dugCeiling = tryDigBlocking(feet.above(2)) | tryDigBlocking(feet.above());
        }
        String detail = "阶段=" + phase + " 次数=" + this.stuckRescues
                + " 方向=" + dirX + "," + dirZ
                + " 前脚=" + this.level().getBlockState(frontFeet).getBlock()
                + " 前头=" + this.level().getBlockState(frontHead).getBlock()
                + " 脚下=" + this.level().getBlockState(feet.below()).getBlock()
                + " 挖脚=" + dugFeet + " 挖头=" + dugHead + " 侧挖=" + dugSide + " 挖顶=" + dugCeiling;

        boolean gaveUp = false;
        if (this.stuckRescues >= MAX_DIG_RESCUES) {
            // 连续几轮都挖不开：不是被普通方块挡住（半格碰撞/基岩/挖不动的组合）。
            // 老实放弃——旧做法是传送到最近可站格，已按 Fong 的决定拆除：
            // 假人只能靠自己，失败收场后由规划层换路线、换目标，总好过原地无限重试。
            log(npcName, "stuck-giveup", detail + " 放弃当前行走，等待上层换路线");
            this.stuckRescues = 0;
            this.lastRescueCell = null;
            gaveUp = true;
        } else {
            log(npcName, "stuck-rescue", detail);
        }
        // 重新给一个完整的观察窗口；刚挖掉的方块要等下一 tick 才会让碰撞消失。
        // 基线设成"自救这一刻离目标的真实距离"，下一轮卡住判定从这里重新计时。
        this.noProgressTicks = 0;
        double markDx = tx - this.getX();
        double markDz = tz - this.getZ();
        this.lastDistanceSq = markDx * markDx + markDz * markDz;
        return gaveUp;
    }

    /**
     * 自救计数清零的唯一常规出口：人已经离开上次卡住的格子 4 格以外，
     * 说明早就脱困了，之前的卡住不该再连累新路段。
     * 技能换目标重新寻路时调用——新路径只要还在这片区域里，计数保留，
     * 这样"卡住→技能重试→又卡住"的循环能攒满 3 次、触发放弃兜底（失败换路线）。
     */
    private void resetRescuesIfLeftArea() {
        if (this.lastRescueCell == null) {
            this.stuckRescues = 0;
            return;
        }
        double dx = this.getX() - (this.lastRescueCell.getX() + 0.5D);
        double dz = this.getZ() - (this.lastRescueCell.getZ() + 0.5D);
        if (dx * dx + dz * dz > 16.0D) {
            this.stuckRescues = 0;
            this.lastRescueCell = null;
        }
    }

    /** 只挖"确实挡路"的方块：空气/液体不碰，基岩和黑曜石这种挖不动的也不碰。 */
    private boolean tryDigBlocking(BlockPos pos) {
        BlockState state = this.level().getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        float hardness = state.getDestroySpeed(this.level(), pos);
        if (hardness < 0.0F || hardness > 3.0F) {
            return false;
        }
        return this.mineWithBestTool(pos);
    }

    /**
     * 跟随一条 A* 路径。回调都在主线程 tick 内执行，调用方要用 runId 自校验，
     * 避免技能被取消后旧回调还在操作身体。
     *
     * @param path      格子序列（由 PathFinder 返回）
     * @param onArrive  到达最后一格时触发
     * @param onAbandon 卡住/无法继续时触发
     */
    public void followPath(List<BlockPos> path, boolean sprint, Runnable onArrive, Runnable onAbandon) {
        if (path == null || path.isEmpty()) {
            if (onAbandon != null) {
                onAbandon.run();
            }
            return;
        }
        BlockPos start = path.get(0);
        // A* 的起点会向下/向上吸附到最近的能站格；身体可能正站在半格方块或树冠边缘（例如 y=81.17）。
        // 旧做法是把身体小传送对齐到起点格——已按 Fong 的决定拆除（不许任何传送）。
        // 现在靠身体自己走/跳/落到起点那一层：人在上面就走过去掉下来，在下面就靠跳跃
        // 上一格；落差超过跳跃高度（1.5 格）时 applyPathInput 会自己放弃这条路径、
        // 让技能层按当前位置重新规划。水平本来就在 2.5 格内，走过去花不了一秒。
        double verticalGap = Math.abs(start.getY() - this.getY());
        double horizontalGap = Math.hypot(start.getX() + 0.5D - this.getX(), start.getZ() + 0.5D - this.getZ());
        if (verticalGap > 0.8D && horizontalGap < 2.5D) {
            log(npcName, "path-align-walk", String.format(Locale.ROOT,
                    "身体 y=%.2f 与路径起点 %s 相差 %.2f，靠走/跳对齐（不传送）",
                    this.getY(), start.toShortString(), verticalGap));
        }
        this.swimGoal = null;
        this.walkTarget = null;
        this.walkCallback = null;
        this.path = path;
        this.pathIndex = 0;
        this.pathSprint = sprint;
        this.pathArrive = onArrive;
        this.pathAbandon = onAbandon;
        this.lastDistanceSq = Double.MAX_VALUE;
        this.noProgressTicks = 0;
        resetRescuesIfLeftArea();
        log(npcName, "path-start", "节点=" + path.size() + " 终点=" + path.get(path.size() - 1).toShortString());
    }

    public boolean isFollowingPath() {
        return this.path != null;
    }

    /**
     * 结束路径。先清空状态再执行回调，因为回调里可能立刻开始下一个动作（比如挖掉目标方块）。
     */
    private void finishPath(boolean arrived) {
        String end = this.path == null ? "?" : this.path.get(this.path.size() - 1).toShortString();
        Runnable callback = arrived ? this.pathArrive : this.pathAbandon;
        this.path = null;
        this.pathIndex = 0;
        this.pathArrive = null;
        this.pathAbandon = null;
        if (arrived) {
            // 整条路走完了：之前的卡住已经翻篇，计数清零。
            this.stuckRescues = 0;
            this.lastRescueCell = null;
        }
        this.xxa = 0.0F;
        this.zza = 0.0F;
        this.setSprinting(false);
        log(npcName, arrived ? "path-done" : "path-abort", "终点=" + end + " " + this.describe());
        if (callback != null) {
            callback.run();
        }
    }

    /** 走到指定坐标；到达后执行 callback（在主线程 tick 内触发）。 */
    public void walkTo(Vec3 target, boolean sprint, Runnable callback) {
        // 直线行走和 A* 路径互斥，手动 walkTo 视为放弃当前路径；游泳脱困也一并取消。
        this.swimGoal = null;
        this.path = null;
        this.pathIndex = 0;
        this.pathArrive = null;
        this.pathAbandon = null;
        this.walkTarget = target;
        this.walkSprint = sprint;
        this.walkCallback = callback;
        this.lastDistanceSq = Double.MAX_VALUE;
        this.noProgressTicks = 0;
        resetRescuesIfLeftArea();
        log(npcName, "walk-start", String.format(Locale.ROOT, "目标 (%.2f, %.2f, %.2f)", target.x, target.y, target.z));
    }

    public void stopWalking() {
        this.swimGoal = null;
        this.walkTarget = null;
        this.walkCallback = null;
        this.path = null;
        this.pathIndex = 0;
        this.pathArrive = null;
        this.pathAbandon = null;
        this.xxa = 0.0F;
        this.zza = 0.0F;
        this.setSprinting(false);
    }

    public boolean isWalking() {
        return this.walkTarget != null || this.path != null;
    }

    /**
     * 原地起跳一次（跳搭垫柱用）：站在地面上才跳，走的还是原版跳跃物理
     * （初速、重力、落地全由 travel 接管），技能层负责在腾空时往脚下放方块。
     */
    public void jumpNow() {
        if (this.onGround()) {
            this.jumpFromGround();
        }
    }

    /** 挖掘方块，返回是否成功。Survival 模式下会走正常掉落流程。 */
    public boolean mine(BlockPos pos) {
        // 【fix99】床块永不挖：本方法是全插件唯一的 destroyBlock 包装，
        // 身体层卡住自救、生存层窒息/卡块自救、技能层全部挖掘最终都过这里，
        // 一处守卫全覆盖。farm59 实证：sleep phase 0 去锚点站位的直线路径穿过
        // 自家床格，walk-stuck 自救把床头挖掉，随后 startSleepInBed 对空气格
        // 抛 IAE、目标卡死刷屏。真玩家不会挖穿自家床，他家床同理——直接拒挖，
        // 调用方按"挖不开"诚实改道/放弃。
        BlockState targetState = this.level().getBlockState(pos);
        // 【fix135b】纯液体格不可挖且必须诚实报失败：destroyBlock 对水格返回
        // true 却不移除（邻格水源立刻回灌，before/after 全是 water）——fix135
        // staging 实证 digBankStep 对着水格"挖开成功"刷屏、真墙一格没动。
        // 含水方块（水渍台阶等）不是纯液体、不在此列，照常可挖。
        if (targetState.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
            return false;
        }
        if (targetState.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
            if (this.tickCount - lastBedGuardLogTick >= 200) {
                lastBedGuardLogTick = this.tickCount;
                log(npcName, "mine-bed-guard", "拒绝挖床块 " + targetState.getBlock()
                        + " " + pos.toShortString());
            }
            return false;
        }
        String before = this.level().getBlockState(pos).toString();
        // 【第二十三轮】工具损坏取证：动作前先记下主手工具，挖完比对（标准第 4 条）。
        ItemStack handBefore = this.getMainHandItem();
        String handPath = handBefore.isEmpty() ? "" : itemPathOf(handBefore);
        boolean handDamageable = !handBefore.isEmpty() && handBefore.isDamageableItem();
        int handDamage = handDamageable ? handBefore.getDamageValue() : 0;
        int handMax = handDamageable ? handBefore.getMaxDamage() : 0;
        boolean success = this.gameMode.destroyBlock(pos);
        String after = this.level().getBlockState(pos).toString();
        log(npcName, "mine", "pos=" + pos.toShortString() + " success=" + success
                + " before=" + before + " after=" + after);
        if (handDamageable) {
            checkToolBreak(handPath, handDamage, handMax, "mine", before);
        }
        return success;
    }

    /**
     * 【第二十三轮】工具损坏专行（日志标准第 4 条：资源流水，损坏必须有着落）：
     * 动作前主手是可损坏工具、动作后主手变空或换成了别的物品 → 这件工具碎了，
     * 打一条 [AIV][TOOL-BREAK]：哪件、碎时耐久（已耗/总）、当时在干什么。
     * 注：本插件工具普遍"留最后 1 点耐久不用"，真碎多发生在战斗武器/护甲上。
     */
    private void checkToolBreak(String path, int damageBefore, int maxDamage,
                                String action, String targetDesc) {
        ItemStack after = this.getMainHandItem();
        boolean gone = after.isEmpty() || !path.equals(itemPathOf(after));
        if (!gone) {
            return;
        }
        java.util.logging.Logger.getLogger("AIVillager").info("[AIV][TOOL-BREAK] " + npcName
                + " item=" + path + " durability=" + damageBefore + "/" + maxDamage
                + " action=" + action + " target=" + targetDesc
                + " pos=" + getBlockX() + "," + getBlockY() + "," + getBlockZ());
    }

    private static String itemPathOf(ItemStack stack) {
        net.minecraft.resources.Identifier id =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.getPath();
    }

    /**
     * 自动换工具后挖掘（Wurst AutoTool 的服务端版）。
     *
     * <p>原版掉落判定看的是主手工具：空手挖石头只会打碎方块、不掉圆石，
     * 所以"像玩家一样发展"必须先把手里的镐换上去。伪玩家没有客户端物品栏操作，
     * 这里直接按方块属性挑背包里最合适的工具并切到快捷栏，再走原版 destroyBlock。
     *
     * @return 是否挖掉；缺少必需工具时返回 false（不白挖）
     */
    /** 脚上穿的是不是皮靴（第十九轮）：原版只有皮靴能防细雪下陷。 */
    public boolean wearingLeatherBoots() {
        return this.getItemBySlot(EquipmentSlot.FEET).is(Items.LEATHER_BOOTS);
    }

    /** 当前所在群系的路径名（如 snowy_plains）；取不到返回空串。 */
    public String biomePath() {
        return this.level().getBiome(this.blockPosition()).unwrapKey()
                .map(key -> key.identifier().getPath()).orElse("");
    }

    /**
     * 是否身处"细雪地带"（第十九轮，Fong 拍板）：雪原/针叶林及山地细雪群系。
     * 皮靴的优先制作、护甲层"不许把皮靴换成铁靴"都按这个判定走；
     * 沙漠/平原等暖区返回 false，那里的 NPC 不会被逼着先找牛做靴子。
     */
    public boolean inPowderSnowCountry() {
        String biome = biomePath();
        return biome.contains("snow") || biome.contains("taiga")
                || biome.equals("grove") || biome.equals("jagged_peaks")
                || biome.equals("frozen_peaks") || biome.equals("ice_spikes");
    }

    public boolean mineWithBestTool(BlockPos pos) {
        return mineWithBestTool(pos, true);
    }

    /**
     * @param allowLastResort 是否允许动用"剩最后 1 点耐久"的退役工具兜底。一般挖掘
     *                        允许（总比卡死强）；ascend 这类"设计上就该素手挖"的脱困
     *                        路径传 false——长跑#10 实测兜底在 ascend 里把退役壳一格
     *                        烧一把、烧完照样得素手挖，壳白白烧光。
     */
    public boolean mineWithBestTool(BlockPos pos, boolean allowLastResort) {
        BlockState state = this.level().getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        Inventory inventory = this.getInventory();
        int selected = inventory.getSelectedSlot();
        // 不需要正确工具的方块（泥土/沙/木头）：直接空手挖。
        // destroyBlock 是瞬时的，换工具不会更快，只会白白磨损耐久——
        // 这正是"木镐挖 20 层楼梯就报废"的根因，而玩家会空手挖泥土。
        // 【第三十四轮】雪层/雪块并入素手类：Wiki 核实——雪硬度 0.1、徒手 0.5 秒
        // 可挖，只是没有锹不掉落（雪球靠锹采集）。旧版按 requiresCorrectToolForDrops
        // 把雪挡进"必须工具"分支、包里没锹就 mine-no-tool 跳过：长跑#11 实证
        // 84 次 mine-no-tool 里 71 次是雪层，descend 在雪原地表"挖不动"6 次、
        // 挖煤目标 193 秒零进展放弃、寻路 waypoint 卡雪层——雪原/雪针叶林开局
        // 整条下矿链被一层雪堵死。玩家清路时直接用手砸开雪（不要掉落），
        // 这里同理：素手挖掉、不给掉落，与原版徒手行为一致。
        if (!state.requiresCorrectToolForDrops()
                || state.is(net.minecraft.world.level.block.Blocks.SNOW)
                || state.is(net.minecraft.world.level.block.Blocks.SNOW_BLOCK)) {
            // 挖泥土/沙/木头不掉耐久，但主手拿着可损坏工具时先换成空格再挖。
            // 挖完必须还原选中格：之前漏了这一步，选中格一直停在空格上，
            // 生存层每 1.5 秒又把剑切回来，日志里就出现"切换武器"刷屏，
            // 快捷栏也在两格之间来回跳。
            int previous = selected;
            int swappedSlot = -1;
            if (inventory.getItem(selected).isDamageableItem()) {
                boolean moved = false;
                for (int slot = 0; slot < 9; slot++) {
                    if (inventory.getItem(slot).isEmpty()) {
                        inventory.setSelectedSlot(slot);
                        moved = true;
                        break;
                    }
                }
                if (!moved) {
                    // 【第三十二轮】快捷栏没有空格时的兜底：旧版直接拿着剑挖土，
                    // 长跑#9 实测铁剑就是这样被一锄一锄烧碎的（[TOOL-BREAK] 挖 dirt 时碎）。
                    // 改成把主手的可损坏工具与背包里任意一个不可损坏的堆（圆石/泥土等）
                    // 整组对调，挖完再换回来——等价于玩家把剑先塞回包里空手挖。
                    for (int slot = 0; slot < 36; slot++) {
                        if (slot == selected) {
                            continue;
                        }
                        ItemStack other = inventory.getItem(slot);
                        if (!other.isEmpty() && !other.isDamageableItem()) {
                            ItemStack hand = inventory.getItem(selected);
                            inventory.setItem(selected, other);
                            inventory.setItem(slot, hand);
                            swappedSlot = slot;
                            break;
                        }
                    }
                }
            }
            boolean mined = mine(pos);
            if (swappedSlot >= 0) {
                ItemStack hand = inventory.getItem(selected);
                ItemStack bag = inventory.getItem(swappedSlot);
                inventory.setItem(selected, bag);
                inventory.setItem(swappedSlot, hand);
            }
            inventory.setSelectedSlot(previous);
            return mined;
        }
        // 【第三十二轮】石镐主力钉成结构规则（Fong 工具分级令）：包里只要有能用的
        // 石镐、且这块方块石镐就能采（石头/煤/铁矿等 isCorrectToolForDrops 为真），
        // 就只准用石镐，不走下面的评分——长跑#9 实测石镐有货时铁镐仍被选去挖普通
        // 石头，3 分钟烧穿 202 耐久。石镐采不了的（钻石/金/红石等）自然落回评分选铁镐。
        int stoneSlot = -1;
        int stoneRemaining = -1;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !stack.is(Items.STONE_PICKAXE)) {
                continue;
            }
            if (stack.getDamageValue() >= stack.getMaxDamage() - 1) {
                continue; // 退役规则与全插件一致：留最后 1 点不用
            }
            if (!stack.isCorrectToolForDrops(state)) {
                continue;
            }
            int remaining = stack.getMaxDamage() - stack.getDamageValue();
            if (remaining > stoneRemaining) {
                stoneRemaining = remaining;
                stoneSlot = slot;
            }
        }
        // 【fix101】兜底解除：石镐回包（补造/挖到/补给到）即切回石镐主力，
        // 专行打日志——兜底是偏好失效时的降级路径，不是把铁镐扶正。
        if (stoneSlot >= 0 && pickFallbackActive) {
            pickFallbackActive = false;
            log(npcName, "pick-fallback", "解除 | reason=石镐回包，回归石镐主力");
        }
        // 【第三十六轮】石镐断供时不许拿高级镐顶普通石头（allowLastResort=false
        // 的脱困类路径）：长跑#12 两度实证——石镐在长掘进里烧成退役壳后，
        // ascend 回地表一路用铁镐挖普通石头（铁镐 250→60、60→16 的主烧段）。
        // fix33 的 false 只挡"烧退役壳"，没挡"烧好镐"：评分里铁镐是唯一
        // 未退役的合法候选。这类路径（ascend）设计上就该素手：方块石镐能采、
        // 包里又没可用石镐时，直接走 mineBareHand（无掉落脱困、零耐久），
        // 把"补石镐"留给规划器在决策点处理。需要高级镐的方块（钻石矿等，
        // 石镐探针 isCorrectToolForDrops=false）不受影响，照常落回评分。
        if (stoneSlot < 0 && !allowLastResort
                && new ItemStack(Items.STONE_PICKAXE).isCorrectToolForDrops(state)) {
            log(npcName, "mine-barehand-fallback", "无可用石镐，素手挖 "
                    + state.getBlock() + "（不烧高级镐） " + pos.toShortString());
            return mineBareHand(pos);
        }
        // 【第四十四轮】技能中段分级守卫（雨林/沙漠两局定量成案）：上面的结构规则
        // 只防"有石镐时不用铁镐"；石镐在长掘进中途退役后，默认路径
        // （allowLastResort=true，descend/strip/mine 都走它）落回评分，唯一合法
        // 候选就是铁镐——技能不中断、规划层补镐保障步只在目标边界 decide 时
        // 才跑，够不着技能中段，铁镐就这样一格格烧穿（雨林 250→34、抽样 45 条
        // 里 7 条铁镐挖石档块≈16%；沙漠 250→141 同型）。守卫：方块石镐能采、
        // 包里又没有可用石镐/木镐（评分必然顺位选到铁镐以上）时不许顺位烧——
        // 圆石 ≥3（够做一把石镐）直接返回 false 让技能收场，规划层保障步下一
        // 秒就补造石镐再续挖；圆石 <3 只放行当块（落回下面评分挖这一块补圆石，
        // 玩家也会先花 1 点高级镐耐久凑出做石镐的圆石），防"没圆石→做不了石镐
        // →又不许挖石"的死角。需高级镐的方块（钻石/金等，石镐探针为假）不受
        // 影响、照常评分。每次触发都打 mine-tier-guard 流水可审计。
        if (stoneSlot < 0 && allowLastResort
                && new ItemStack(Items.STONE_PICKAXE).isCorrectToolForDrops(state)
                && !hasUsableCheapPick(inventory, state)) {
            // 【fix101·Fong 2026-10-28 通则：守卫必须配兜底】fix44 旧口径只看
            // 圆石 ≥3 就一律拒挖——但圆石够 ≠ 合得出（缺木棍料/没工作台时
            // 合成路径同样不通），守卫只有「不许」没有出路：浇铸三趟里
            // descend 1 秒速败、规划层补镐也变不出来，就是这处无兜底守卫
            // 堵死的（fix100 只治了目标层热循环）。新链：先判能否立刻合
            // 石镐——合得出照旧拒挖、交规划层当场补造（省镐偏好不变）；
            // 合不出就降级兜底：不返回 false，落回下面评分顺位开挖——
            // toolScore 低 tier 分高，铁镐（tier3）天然排在钻石镐（tier4）
            // 之前，即 Fong 定的「铁先、钻最后」；全没镐时评分无可选，
            // 由 mine-no-tool 收场交规划层补镐步。兜底启用只打一行专行
            // 日志（写明缺什么料合不出），石镐回包时另有解除行（见上）。
            StringBuilder missing = new StringBuilder();
            if (stonePickCraftableNow(missing)) {
                if (this.tickCount - lastTierGuardLogTick >= 200) {
                    lastTierGuardLogTick = this.tickCount;
                    log(npcName, "mine-tier-guard", "无可用石镐/木镐但可立即合石镐，拒绝用高级镐挖 "
                            + state.getBlock() + "（交规划层补造石镐） "
                            + pos.toShortString());
                }
                return false;
            }
            if (!pickFallbackActive) {
                pickFallbackActive = true;
                log(npcName, "pick-fallback", "启用 | reason=无可用石镐且合不出石镐 missing="
                        + missing.toString().trim()
                        + " | tool=评分顺位(铁镐>钻石镐) block=" + state.getBlock()
                        + " pos=" + pos.toShortString());
            }
        }
        int bestSlot = stoneSlot;
        int bestScore = stoneSlot >= 0 ? Integer.MAX_VALUE : -1;
        // 第一遍：优先选耐久充足的（留最后 1 点不用，避免工具瞬间碎掉后无可用工具）。
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            // 留最后 1 点耐久不用：工具彻底碎掉后 NPC 就再也挖不动石头，
            // 只能卡在矿道里空转（实测两把木镐就是这样一起消失的）。
            if (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
                continue;
            }
            int score = toolScore(stack, state);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        // 兜底：所有工具都只剩最后 1 耐久时，还是要用一下——总比全空转好。
        // 这种情况只会在多条矿道挖穿、工具群灭时发生（descend 140 级用掉 400+ 耐久）。
        // 但"本就该素手挖"的路径不走这条（allowLastResort=false），理由见方法注释。
        if (bestSlot < 0 && allowLastResort) {
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                int score = toolScore(stack, state);
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = slot;
                }
            }
        }
        // 需要正确工具才有掉落的方块（石头/矿石/金属块），没有对应镐就别挖了。
        if (bestSlot < 0 || !inventory.getItem(bestSlot).isCorrectToolForDrops(state)) {
            log(npcName, "mine-no-tool", "缺少可采集 " + state.getBlock() + " 的工具，跳过 "
                    + pos.toShortString());
            return false;
        }
        // 【第十八轮】工具名先抓：下面的换格会把 bestSlot 的内容换走，
        // 换完再读就成了换工具前的手持物（第一版短验把手里的剑误报成
        // 挖石头的工具，当场逮到）。取证日志见换格后。
        String toolName = String.valueOf(inventory.getItem(bestSlot).getItem());
        if (bestSlot < 9) {
            inventory.setSelectedSlot(bestSlot);
        } else {
            // 背包深处：和当前选中格整组交换，等价于玩家把镐拖到快捷栏。
            ItemStack old = inventory.getItem(selected);
            inventory.setItem(selected, inventory.getItem(bestSlot));
            inventory.setItem(bestSlot, old);
            inventory.setSelectedSlot(selected);
        }
        // 【第十八轮】工具使用取证日志：Fong 工具分级口径（石镐主力、铁镐只挖
        // 必需矿）需要掘进段实证——换工具时打一行、每 100 次抽样一行，
        // 事后能按日志核对"这段深板岩/石头/煤/铁到底是哪把镐挖的"。
        if (!toolName.equals(lastToolLogName) || ++toolLogCounter >= 100) {
            log(npcName, "tool-use", "tool=" + toolName + " block=" + state.getBlock());
            lastToolLogName = toolName;
            toolLogCounter = 0;
        }
        boolean mined = mine(pos);
        // 挖完把选中格还原，理由同上面空手挖的分支：否则生存层每次检查都会切回武器。
        inventory.setSelectedSlot(selected);
        return mined;
    }

    /** 是否有可用的低档镐（木/金镐，未退役且能采当前方块）——fix44 守卫判定用。 */
    private static boolean hasUsableCheapPick(Inventory inventory, BlockState state) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()
                    || (!stack.is(Items.WOODEN_PICKAXE) && !stack.is(Items.GOLDEN_PICKAXE))) {
                continue;
            }
            if (stack.getDamageValue() >= stack.getMaxDamage() - 1) {
                continue; // 退役规则同全插件：留最后 1 点不用
            }
            if (stack.isCorrectToolForDrops(state)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 【fix101】能否立刻合出一把石镐（兜底链判定，身体层与目标层共用同一口径）：
     * 圆石（含深板岩圆石，fix57 口径）≥3、木棍料够（存货木棍 ≥2，或木板 ≥2，
     * 或原木 ≥1——1 原木出 4 木板、2 木板出 4 木棍）、工作台够得着（包里有，
     * 或身边 4 格内有一台）。缺哪件写进 missing（日志取证用）。
     *
     * <p>【fix123】补「产物放得下」：石镐不可叠加，背包 0 空槽时就算材料
     * 全齐也做不出来（合成产物无处放，farm86 实证：craft 连败「背包已满」
     * +fix116 退避 600 秒，而本判定仍报「可立即合」→守卫拒挖铁镐、主线
     * 死锁）。放得下的口径：有空槽，或消耗本身会腾出一格（圆石总量恰好
     * 3、或存货木棍恰好 2 根——合成吃光该堆、产物落回原格）。
     */
    public boolean stonePickCraftableNow(StringBuilder missing) {
        Inventory inventory = this.getInventory();
        int cobble = countInventoryItem(inventory, Items.COBBLESTONE)
                + countInventoryItem(inventory, Items.COBBLED_DEEPSLATE);
        int sticks = countInventoryItem(inventory, Items.STICK);
        int planks = 0;
        int logs = 0;
        int emptySlots = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                emptySlots++;
                continue;
            }
            String path = itemPathOf(stack);
            if (path.endsWith("_planks")) {
                planks += stack.getCount();
            } else if (path.endsWith("_log") || path.endsWith("_stem")) {
                logs += stack.getCount();
            }
        }
        boolean stickMaterial = sticks >= 2 || planks >= 2 || logs >= 1;
        boolean bench = countInventoryItem(inventory, Items.CRAFTING_TABLE) > 0
                || nearbyCraftingTable();
        boolean placeable = emptySlots >= 1 || cobble == 3 || sticks == 2;
        if (cobble < 3) {
            missing.append("圆石=").append(cobble).append("/3 ");
        }
        if (!stickMaterial) {
            missing.append("木棍料(棍=").append(sticks).append(",板=").append(planks)
                    .append(",原木=").append(logs).append(") ");
        }
        if (!bench) {
            missing.append("工作台=无 ");
        }
        if (!placeable) {
            missing.append("空槽=0(产物无处放) ");
        }
        return cobble >= 3 && stickMaterial && bench && placeable;
    }

    /** 身边 4 格内有没有放着的工作台（包里没有时合成路径要靠它）。 */
    private boolean nearbyCraftingTable() {
        BlockPos feet = this.blockPosition();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    if (this.level().getBlockState(feet.offset(dx, dy, dz))
                            .is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 背包 36 格内某物品总数——fix44 守卫数圆石用。 */
    private static int countInventoryItem(Inventory inventory,
                                          net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 【fix152】流体脱困挖岸专用入口：生存层 digBankStep 的第三路。
     *
     * <p>病根（farm120 live 死局）：挖岸旧链只调默认口径 mineWithBestTool
     * （allowLastResort=true），无石镐时命中 fix101 分级守卫——「合得出石镐」
     * 只判材料不判空槽，直接拒挖「交规划层补造」；背包已满时规划层合成恒
     * 失败，委托落空，NPC 困在水浸口袋里挖岸永久被拒（checks 冲过 300 仍
     * 恒 miss）。ascend 脱困口径（allowLastResort=false）本有素手挖兜底，
     * 挖岸唯独没接上——守卫无兜底，违 Fong 兜底通则。
     *
     * <p>口径：矿石格（注册名含 _ore 或 ancient_debris）不走本路——素手挖矿
     * 无掉落等于毁矿，有可用镐时前两路（mineWithBestTool/mine）已处理；其余
     * 格走脱困口径 mineWithBestTool(pos,false)：有石镐用石镐、无石镐素手挖
     * （无掉落脱困、零耐久）、绝不顺位烧铁/钻镐，与 ascend 同款、合省镐规矩。
     */
    public boolean mineEscapeBank(BlockPos pos) {
        BlockState state = this.level().getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).getPath();
        if (path.contains("_ore") || path.equals("ancient_debris")) {
            return false;
        }
        return mineWithBestTool(pos, false);
    }

    /**
     * 真·素手挖（第三十三轮）：destroyBlock 挖的是主手物品，生存层又总把武器/工具
     * 切回主手——长跑#10 实测 ascend 名义"用手挖阶梯"，实际拿石剑挖石头：剑 44 格
     * 烧掉 88 耐久当场碎，接着被换上手的退役镐壳一格碎一把，工具全灭。这里先把
     * 主手的可损坏物品腾开（选空格/与不可损坏的堆整组对调），挖完原样还原，
     * 保证一根耐久都不掉。腾不开（快捷栏全满且包里没有不可损坏的堆）才照旧拿着挖。
     */
    public boolean mineBareHand(BlockPos pos) {
        BlockState state = this.level().getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        Inventory inventory = this.getInventory();
        int selected = inventory.getSelectedSlot();
        int swappedSlot = -1;
        if (inventory.getItem(selected).isDamageableItem()) {
            boolean moved = false;
            for (int slot = 0; slot < 9; slot++) {
                if (inventory.getItem(slot).isEmpty()) {
                    inventory.setSelectedSlot(slot);
                    moved = true;
                    break;
                }
            }
            if (!moved) {
                for (int slot = 0; slot < 36; slot++) {
                    if (slot == selected) {
                        continue;
                    }
                    ItemStack other = inventory.getItem(slot);
                    if (!other.isEmpty() && !other.isDamageableItem()) {
                        ItemStack hand = inventory.getItem(selected);
                        inventory.setItem(selected, other);
                        inventory.setItem(slot, hand);
                        swappedSlot = slot;
                        break;
                    }
                }
            }
        }
        boolean mined = mine(pos);
        if (swappedSlot >= 0) {
            ItemStack hand = inventory.getItem(selected);
            ItemStack bag = inventory.getItem(swappedSlot);
            inventory.setItem(selected, bag);
            inventory.setItem(swappedSlot, hand);
        }
        inventory.setSelectedSlot(selected);
        return mined;
    }

    /**
     * 工具打分（第十七轮）：够用的工具里挑最便宜的，别再按“挖得最快”选。
     *
     * <p>旧打分只看对不对 + 速度，铁镐比石镐快，于是所有普通石头/深板岩掘进
     * 都优先烧铁镐，石镐全程闲置——长跑#6d 实测：补满 2 把铁镐不到 6 分钟
     * 全碎，正好赶上找到钻石矿时只剩石镐（钻石矿必须铁镐以上才有掉落），
     * 只能 mine-no-tool 跳过。真人玩家也是石镐挖石头、铁镐只挖必须铁镐的矿。
     * 非镐工具（斧/锹）只对自己能收的方块 correct，等级记 0、不受影响。
     */
    private static int toolScore(ItemStack stack, BlockState state) {
        if (!stack.isCorrectToolForDrops(state)) {
            return Math.round(stack.getDestroySpeed(state) * 100.0F);
        }
        return 10000 - pickaxeTier(stack) * 100
                + Math.round(stack.getDestroySpeed(state) * 10.0F);
    }

    private static int pickaxeTier(ItemStack stack) {
        if (stack.is(Items.WOODEN_PICKAXE) || stack.is(Items.GOLDEN_PICKAXE)) {
            return 1;
        }
        if (stack.is(Items.STONE_PICKAXE)) {
            return 2;
        }
        if (stack.is(Items.IRON_PICKAXE)) {
            return 3;
        }
        if (stack.is(Items.DIAMOND_PICKAXE)) {
            return 4;
        }
        if (stack.is(Items.NETHERITE_PICKAXE)) {
            return 5;
        }
        return 0;
    }

    /** 攻击实体，返回造成的伤害（目标不是 LivingEntity 时为 -1）。 */
    public float attackTarget(Entity target) {
        // 视线对准目标胸口，保证命中判定和原版一致。
        this.lookAt(EntityAnchorArgument.Anchor.EYES, target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D));
        // 【第二十三轮】武器损坏取证（同 mine 的口径，标准第 4 条）。
        ItemStack handBefore = this.getMainHandItem();
        String handPath = handBefore.isEmpty() ? "" : itemPathOf(handBefore);
        boolean handDamageable = !handBefore.isEmpty() && handBefore.isDamageableItem();
        int handDamage = handDamageable ? handBefore.getDamageValue() : 0;
        int handMax = handDamageable ? handBefore.getMaxDamage() : 0;
        float before = target instanceof LivingEntity living ? living.getHealth() : -1.0F;
        this.attack(target);
        this.swing(InteractionHand.MAIN_HAND);
        float after = target instanceof LivingEntity living ? living.getHealth() : -1.0F;
        float delta = before >= 0.0F ? before - after : -1.0F;
        log(npcName, "attack", "target=" + target.getType() + " health " + before + " -> " + after + " (damage=" + delta + ")");
        if (handDamageable) {
            var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
            checkToolBreak(handPath, handDamage, handMax, "attack",
                    key == null ? "?" : key.getPath());
        }
        return delta;
    }

    public String describe() {
        return String.format(Locale.ROOT,
                "name=%s pos=(%.2f, %.2f, %.2f) yaw=%.1f hp=%.1f food=%d armor=%d gameMode=%s walking=%s",
                npcName, this.getX(), this.getY(), this.getZ(), this.getYRot(), this.getHealth(),
                this.getFoodData().getFoodLevel(), this.getArmorValue(),
                this.gameMode.getGameModeForPlayer(), this.isWalking());
    }

    public String npcName() {
        return npcName;
    }

    public CraftPlayer bukkitPlayer() {
        return this.getBukkitEntity();
    }

    /** 从 PlayerList 正常移除：会广播离开、卸载实体并保存 playerdata。 */
    public void despawn() {
        log(npcName, "despawn", describe());
        this.level().getServer().getPlayerList().remove(this);
    }

    private static void log(String name, String action, String message) {
        LOG.info("[AIV][" + name + "] " + action + " | " + message);
    }
}

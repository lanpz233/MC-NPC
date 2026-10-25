package com.example.aivillager.npc;

import com.example.aivillager.pathfinding.PathFinder;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
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

    /** A* 路径；非 null 时优先级高于 walkTarget。 */
    private List<BlockPos> path;
    private int pathIndex;
    private boolean pathSprint;
    private Runnable pathArrive;
    private Runnable pathAbandon;

    /** 自救最多连续挖几次方块；超过说明不是"被方块挡住"而是卡在碰撞/半格地形里。 */
    private static final int MAX_DIG_RESCUES = 3;

    private int jumpCooldown;
    private double lastDistanceSq = Double.MAX_VALUE;
    private int noProgressTicks;
    /** 连续卡住自救次数：真正朝目标移动一次才清零。 */
    private int stuckRescues;

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
            this.setSprinting(false);
            log(npcName, "walk-arrive", String.format(Locale.ROOT, "到达 (%.2f, %.2f, %.2f)", arrived.x, arrived.y, arrived.z));
            if (callback != null) {
                callback.run();
            }
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
        } else if (this.onGround && this.horizontalCollision) {
            // 撞到一格高的障碍就起跳，模拟玩家自动跳跃。
            this.jumpFromGround();
            this.jumpCooldown = 5;
        }

        // 卡住检测：持续没有靠近目标就放弃，避免 spike 无限等待。
        if (distanceSq < this.lastDistanceSq - 1.0E-4D) {
            this.lastDistanceSq = distanceSq;
            this.noProgressTicks = 0;
            this.stuckRescues = 0;
        } else if (++this.noProgressTicks > STUCK_TICKS) {
            Vec3 goal = this.walkTarget;
            this.walkTarget = null;
            this.walkCallback = null;
            this.setSprinting(false);
            log(npcName, "walk-stuck", "80 tick 内没有靠近目标，放弃行走 " + this.describe());
            if (goal != null) {
                // 直线行走没有路径可以绕，卡住时先做一次自救：挖开挡路的方块，
                // 连续挖不开就把身体对齐到最近的可站立格，避免一直顶墙。
                rescueFromStuck("walk", goal.x, goal.z);
            }
        }
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
            this.stuckRescues = 0;
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
            rescueFromStuck("path", wp.getX() + 0.5D, wp.getZ() + 0.5D);
            if (this.stuckRescues == 0) {
                // 自救过程中发生了传送：旧路径已经不可信，交给技能层按新位置重新规划。
                finishPath(false);
            }
        }
    }

    /**
     * 卡住自救：像玩家一样挖开挡路的方块，连续挖不开就把身体对齐到最近的可站立格。
     *
     * <p>为什么需要它：A* 只保证一格"能站人"，不保证 1.8 格高的身体能从当前格挤过去。
     * 丛林里竹子、树叶、藤蔓的组合经常把一格宽的缝堵成实际过不去的墙——
     * 玩家会直接把方块挖掉，伪玩家如果只会继续顶墙，就会在同一个位置无限重试。
     *
     * @param phase 触发阶段（path/walk），只用于日志
     * @param tx    当前想去的水平坐标，用来决定往哪个方向挖
     */
    private void rescueFromStuck(String phase, double tx, double tz) {
        this.stuckRescues++;
        BlockPos feet = this.blockPosition();
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
        String detail = "阶段=" + phase + " 次数=" + this.stuckRescues
                + " 方向=" + dirX + "," + dirZ
                + " 前脚=" + this.level().getBlockState(frontFeet).getBlock()
                + " 前头=" + this.level().getBlockState(frontHead).getBlock()
                + " 脚下=" + this.level().getBlockState(feet.below()).getBlock()
                + " 挖脚=" + dugFeet + " 挖头=" + dugHead;

        if (this.stuckRescues >= MAX_DIG_RESCUES) {
            // 连续几轮都挖不开，说明不是被方块挡住，而是身体卡在半格方块的碰撞里（比如站在 81.17）。
            // 直接挪到最近的可站立格，等价于玩家被卡住后退出重进。
            BlockPos safe = this.nearestStandableCell(feet, tx, tz);
            if (safe != null) {
                this.teleportTo(this.level(), safe.getX() + 0.5D, safe.getY() + 0.001D, safe.getZ() + 0.5D,
                        Set.of(), this.getYRot(), this.getXRot(), true);
                log(npcName, "stuck-teleport", detail + " → " + safe.toShortString());
                this.stuckRescues = 0;
            } else {
                log(npcName, "stuck-teleport-fail", detail);
            }
        } else {
            log(npcName, "stuck-rescue", detail);
        }
        // 重新给一个完整的观察窗口；刚挖掉的方块要等下一 tick 才会让碰撞消失。
        this.noProgressTicks = 0;
        this.lastDistanceSq = Double.MAX_VALUE;
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

    /** 在身体周围（水平 ±2、垂直 ±2）找最近的可站立格，倾向靠近想去的方向。 */
    private BlockPos nearestStandableCell(BlockPos around, double tx, double tz) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos candidate = around.offset(dx, dy, dz);
                    if (!PathFinder.isStandable(this.level(), candidate)) {
                        continue;
                    }
                    double score = Math.sqrt(dx * dx + dz * dz) * 1.5D + Math.abs(dy)
                            + Math.hypot(candidate.getX() + 0.5D - tx, candidate.getZ() + 0.5D - tz) * 0.2D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        return best;
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
        // 这种"身体不在路径起点那一层"的情况下继续走，会一直朝一个自己并不在的高度靠近，
        // 40 tick 后判定卡住。先对齐到起点格，等价于玩家挪一下脚站到方块上。
        double verticalGap = Math.abs(start.getY() - this.getY());
        double horizontalGap = Math.hypot(start.getX() + 0.5D - this.getX(), start.getZ() + 0.5D - this.getZ());
        if (verticalGap > 0.8D && horizontalGap < 2.5D) {
            log(npcName, "path-align", String.format(Locale.ROOT,
                    "身体 y=%.2f 与路径起点 %s 相差 %.2f，先对齐",
                    this.getY(), start.toShortString(), verticalGap));
            this.teleportTo(this.level(), start.getX() + 0.5D, start.getY() + 0.001D, start.getZ() + 0.5D,
                    Set.of(), this.getYRot(), this.getXRot(), true);
        }
        this.walkTarget = null;
        this.walkCallback = null;
        this.path = path;
        this.pathIndex = 0;
        this.pathSprint = sprint;
        this.pathArrive = onArrive;
        this.pathAbandon = onAbandon;
        this.lastDistanceSq = Double.MAX_VALUE;
        this.noProgressTicks = 0;
        this.stuckRescues = 0;
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
        // 直线行走和 A* 路径互斥，手动 walkTo 视为放弃当前路径。
        this.path = null;
        this.pathIndex = 0;
        this.pathArrive = null;
        this.pathAbandon = null;
        this.walkTarget = target;
        this.walkSprint = sprint;
        this.walkCallback = callback;
        this.lastDistanceSq = Double.MAX_VALUE;
        this.noProgressTicks = 0;
        log(npcName, "walk-start", String.format(Locale.ROOT, "目标 (%.2f, %.2f, %.2f)", target.x, target.y, target.z));
    }

    public void stopWalking() {
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

    /** 挖掘方块，返回是否成功。Survival 模式下会走正常掉落流程。 */
    public boolean mine(BlockPos pos) {
        String before = this.level().getBlockState(pos).toString();
        boolean success = this.gameMode.destroyBlock(pos);
        String after = this.level().getBlockState(pos).toString();
        log(npcName, "mine", "pos=" + pos.toShortString() + " success=" + success
                + " before=" + before + " after=" + after);
        return success;
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
    public boolean mineWithBestTool(BlockPos pos) {
        BlockState state = this.level().getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        Inventory inventory = this.getInventory();
        int selected = inventory.getSelectedSlot();
        // 不需要正确工具的方块（泥土/沙/木头）：直接空手挖。
        // destroyBlock 是瞬时的，换工具不会更快，只会白白磨损耐久——
        // 这正是"木镐挖 20 层楼梯就报废"的根因，而玩家会空手挖泥土。
        if (!state.requiresCorrectToolForDrops()) {
            // 挖泥土/沙/木头不掉耐久，但主手拿着可损坏工具时先换成空格再挖。
            // 挖完必须还原选中格：之前漏了这一步，选中格一直停在空格上，
            // 生存层每 1.5 秒又把剑切回来，日志里就出现"切换武器"刷屏，
            // 快捷栏也在两格之间来回跳。
            int previous = selected;
            if (inventory.getItem(selected).isDamageableItem()) {
                for (int slot = 0; slot < 9; slot++) {
                    if (inventory.getItem(slot).isEmpty()) {
                        inventory.setSelectedSlot(slot);
                        break;
                    }
                }
            }
            boolean mined = mine(pos);
            inventory.setSelectedSlot(previous);
            return mined;
        }
        int bestSlot = -1;
        int bestScore = -1;
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
            int score = (stack.isCorrectToolForDrops(state) ? 10000 : 0)
                    + Math.round(stack.getDestroySpeed(state) * 100.0F);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        // 兜底：所有工具都只剩最后 1 耐久时，还是要用一下——总比全空转好。
        // 这种情况只会在多条矿道挖穿、工具群灭时发生（descend 140 级用掉 400+ 耐久）。
        if (bestSlot < 0) {
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                int score = (stack.isCorrectToolForDrops(state) ? 10000 : 0)
                        + Math.round(stack.getDestroySpeed(state) * 100.0F);
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
        if (bestSlot < 9) {
            inventory.setSelectedSlot(bestSlot);
        } else {
            // 背包深处：和当前选中格整组交换，等价于玩家把镐拖到快捷栏。
            ItemStack old = inventory.getItem(selected);
            inventory.setItem(selected, inventory.getItem(bestSlot));
            inventory.setItem(bestSlot, old);
            inventory.setSelectedSlot(selected);
        }
        boolean mined = mine(pos);
        // 挖完把选中格还原，理由同上面空手挖的分支：否则生存层每次检查都会切回武器。
        inventory.setSelectedSlot(selected);
        return mined;
    }

    /** 攻击实体，返回造成的伤害（目标不是 LivingEntity 时为 -1）。 */
    public float attackTarget(Entity target) {
        // 视线对准目标胸口，保证命中判定和原版一致。
        this.lookAt(EntityAnchorArgument.Anchor.EYES, target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D));
        float before = target instanceof LivingEntity living ? living.getHealth() : -1.0F;
        this.attack(target);
        this.swing(InteractionHand.MAIN_HAND);
        float after = target instanceof LivingEntity living ? living.getHealth() : -1.0F;
        float delta = before >= 0.0F ? before - after : -1.0F;
        log(npcName, "attack", "target=" + target.getType() + " health " + before + " -> " + after + " (damage=" + delta + ")");
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

package com.example.aivillager.npc;

import com.mojang.authlib.GameProfile;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
    // 【fix237】直线行走的放弃回调：walk-stuck 自救攒满（gaveUp）时通知技能层，
    // 与 followPath 的 onAbandon 同义。3 参 walkTo 不传放弃回调时为 null。
    private Runnable walkAbandon;

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
    /** 【fix352】上次因航点高出跳跃高度而 abort 的服务端 tick（-1 表示从未发生）。 */
    private int lastHeightAbortTick = -1;
    /** 【fix352】上次高差 abort 那条路的终点（腿目标），供技能层记失败腿记忆。 */
    private BlockPos lastHeightAbortEndpoint;
    /** 【fix205】高台悬空航点脱困专行的节流记账：同一航点只打一次。 */
    private int lastLedgeSkipIndex = -1;
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
    // 【fix196】最近一次被分级守卫礼让拒挖的 tick（技能层据此区分"礼让"与真挖不动）。
    private long lastTierGuardRefusalTick = -100000L;
    /** 【fix101】镐子兜底链状态：无可用石镐且合不出石镐时，降级用铁/钻镐
     * 顶上干活（Fong 2026-10-28 通则：守卫必须配兜底，只堵不疏不许出厂）。
     * 启用/解除各打一条 pick-fallback 专行日志；石镐回包即解除。 */
    private boolean pickFallbackActive;
    /** fix99 床块守卫日志节流：卡住自救会反复试挖床格，10 秒最多打一行。 */
    private long goldBootsProtectUntilTick = -100000L;
    private long lastBedGuardLogTick = -100000L;
    /** fix198 农田守卫日志节流：同 fix99 口径，10 秒最多打一行。 */
    private long lastFarmGuardLogTick = -100000L;
    /** fix199 基岩守卫日志节流：同 fix99 口径，10 秒最多打一行。 */
    private long lastBedrockGuardLogTick = -100000L;
    /** 【fix349】传送门守卫日志节流：同 fix99 口径，10 秒最多打一行。 */
    private long lastPortalGuardLogTick = -100000L;
    /** 【fix198】最近一次被自家农田守卫拒挖的 tick（技能层据此区分"避让"与真挖不动，同 fix196 探针）。 */
    private long lastFarmGuardRefusalTick = -100000L;
    /** 【fix239】存储/功能块守卫日志节流：同 fix99 口径，10 秒最多打一行。 */
    private long lastStorageGuardLogTick = -100000L;
    /** 【fix239】定点存储挖掘许可：仅工作站回收（挖自家登记的那台熔炉）与
     * 围死兜底放行时，在单次挖掘前后设定/清除；其余任何挖掘一律拒挖存储块。 */
    private BlockPos storageDigPermitPos;
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

    /**
     * 【fix322】该目标点是否会被岩浆记忆守卫拦截（供技能层迈步前预判）：
     * 与身体层 walk/path 分支同一口径。技能层下发 walkTo 前先问一句，
     * 免得下发一个必被静默取消的行走、再由 travel 计时器 0.5 秒一轮重发
     * 成 2 Hz 热循环（正式服 200 棒实证：walk-start 664 行/5.5 分钟）。
     * 只读判断，不改记忆、不改拦截口径。
     */
    public boolean lavaGuardBlocks(double tx, double tz) {
        return headingIntoRecentLava(tx, tz);
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
    /** 【fix323】农田守卫挡路导致救援跳过侧挖的累计次数（目标层轮换／爬升口径用）。 */
    private int farmGuardRescueSkips;
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

        // 【fix379→fix380】定时挖掘由身体 tick 自行推进：技能层普遍是"派一次挖
        // 一下就收尾"的口径（mine 技能到达站位只调一次 mineWithBestTool 就 finish），
        // 靠调用续命的会话永远等不到下一次调用——Fong 实测砍树只碎最下面一块。
        // 改为会话一旦开始就由 tick 驱动挖到底（够取距离/主手被换走才取消），
        // 调用只负责"开始挖哪一格"，与真人按住左键不松手同理。
        if (timedPos != null) {
            tickTimedMining();
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
            this.walkAbandon = null;
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
            this.walkAbandon = null;
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
            Runnable abandon = this.walkAbandon;
            this.walkAbandon = null;
            this.setSprinting(false);
            log(npcName, "walk-stuck", "80 tick 内没有靠近目标，放弃行走 " + this.describe());
            if (goal != null) {
                // 直线行走没有路径可以绕，卡住时先做一次自救：挖开挡路的方块脱困；
                // 连续挖不开就放弃本次行走（上面已经清掉目标），由上层换路线。
                // 【fix237】旧代码把 gaveUp 直接丢掉：path 分支（约 585 行）会调
                // finishPath(false) 通知技能层，walk 分支却静默——69 棒实证：
                // travel 腿在 (218.50,64,-19.70) 贴石墙打转 6 分钟，石镐烧光后
                // 分级守卫拒挖（mine-tier-guard ×33“交规划层补造石镐”），自救
                // 全程挖不到东西，stuck-giveup 照打、walk 却立刻重发同一目标，
                // 规划层“补一把备用石镐”保障步一次 decide 都拿不到。放弃信号
                // 必须像 path 分支一样交上去：gaveUp 时跑 onAbandon，技能层收
                // 失败重 decide，保障步当场补造石镐再续行。
                boolean gaveUp = rescueFromStuck("walk", goal.x, goal.z);
                if (gaveUp && abandon != null) {
                    abandon.run();
                }
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

        // 【fix255＋255c】到达还须脚格、脚下一格都不是流体：上浮自救（swimUp，
        // 目标即自身格）在浆面浮动时 isInLava 会瞬间翻假、distSq≈0，旧判据
        // 连续假宣告「已上岸」又沉回去（实验场 15×15 浆池两度实证 1 秒一轮
        // 的假上岸循环）。255 首版只查脚下一格，被浅池证伪：池深 1 格时脚
        // 下是池底石头、身体实际还泡在脚格的岩浆里（boot-lab105d 实证假上
        // 岸照旧两连）。正判据：真上岸时脚格是岸上空气格、脚下是实心支撑，
        // 两格流体皆空；泡在任何深度的流体里脚格必是流体本身——两格同查。
        boolean feetFluid = !this.level().getFluidState(this.blockPosition()).isEmpty();
        boolean belowFluid = !this.level().getFluidState(this.blockPosition().below()).isEmpty();
        if (!inFluid && (this.onGround() || distanceSq <= 0.25D) && !feetFluid && !belowFluid) {
            log(npcName, "swim-arrive", "已上岸 " + this.describe());
            this.swimGoal = null;
            // 【fix344】上岸即还跳跃输入：applySwimInput 在流体里把 this.jumping
            // 置真（下方），全仓无处清零——残留的 jumping 使原版 travel 在每次落地
            // 后再次弹起 NPC，waitingBody 的落定窗（Y 幅度 ≤1.05 连稳 60 轮）与严格
            // 判定（!isWalking && onGround）双双永不到，树基边 bobbing 6 次连环超时。
            // 251 棒实验场实证：平地草方块照样 bobbing，末次上岸约 70 秒后首现。
            this.jumping = false;
            this.setSprinting(false);
            return;
        }
        if (--this.swimTicksLeft <= 0) {
            log(npcName, "swim-timeout", "游泳约 10 秒仍未上岸，放弃本次脱困 " + this.describe());
            this.swimGoal = null;
            // 【fix344】同上：超时放弃游泳亦须还跳跃输入，否则同款残留跳跃。
            this.jumping = false;
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
        this.walkAbandon = null;
        this.swimGoal = shore;
        // 超时按距离放宽（第四轮修复 B）：沿岸找缓坡时岸可能在十几格外，
        // 固定约 10 秒游不到就放弃、反复重发只会原地打转。上限 600 tick（约 30 秒），
        // 再久就该由生存层的挖岸脱困接管，而不是继续干游。
        double swimDist = Math.sqrt(this.blockPosition().distSqr(shore));
        int swimTicks = (int) Math.min(600, 200 + 20 * Math.ceil(swimDist));
        // 【fix260】岩浆里游速远慢于水：同距离超时 ×2（上限 1200 tick≈60 秒），
        // 否则远岸长游（fix260 远岸定向）每约 10–30 秒就被超时截断重发、
        // 徒增抖动；生存层重发守卫本就不打断进行中的游泳，此项只降噪。
        if (this.isInLava()) {
            swimTicks = (int) Math.min(1200, swimTicks * 2L);
        }
        this.swimTicksLeft = swimTicks;
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

            // 【fix353】上行到达须真正站上台阶：fix352 的 onGround 要求有两处漏判——
            // (1) 起跳 apex 时 getY() 超过航点 Y，above=false 使 onGround 要求整个失效，
            // 半空即判“到达”（第五簇原机制；第六簇同型：航点 (11,62,-90) 在 apex 62.25
            // 即判到达，落回 Y=61.23–61.43 后下一航点 y≈63 高出 1.57–1.77 格 abort，
            // 265 第六簇 360 次同型 abort 实证）；(2) 站在一格之下、容差 0.6 内且
            // onGround 时亦判到达。要求：到达须 onGround，且航点高于 NPC 时脚须已到
            // 航点高度（getY() ≥ ty−0.1）。平地/下行口径不变（落地后 |dy|=0 到达；
            // 半 slab 脚 ty+0.5、above=false、|dy|=0.5≤0.6 照旧到达）。
            boolean above = ty > this.getY();
            boolean reached = distanceSq <= ARRIVE_DISTANCE_SQ
                    && Math.abs(this.getY() - ty) <= ARRIVE_Y_TOLERANCE
                    && this.onGround
                    && (!above || this.getY() >= ty - 0.1D);
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
        // 【fix205】水平阈值由 1.0 放宽到 2.25（1.5 格）：#53 实证卡点水平距²
        // 1.37、高 2 格、四周全 air——挖解无物可挖、原阈值差一线未触发脱困支，
        // 身体对着航点中心原地滑动被僵尸磨死。放宽带（1.0–2.25）加落差上限
        // 3.0 格（3 格内坠落无伤），原带（<1.0）行为不变。
        if (distanceSq < 2.25D
                && this.getY() - ty > ARRIVE_Y_TOLERANCE
                && (distanceSq < 1.0D || this.getY() - ty <= 3.0D)
                && this.pathIndex + 1 < this.path.size()) {
            if (distanceSq >= 1.0D && this.lastLedgeSkipIndex != this.pathIndex) {
                this.lastLedgeSkipIndex = this.pathIndex;
                log(npcName, "path-ledge-skip", "高台悬空航点改朝下一格 | idx=" + this.pathIndex
                        + " distSq=" + String.format(java.util.Locale.ROOT, "%.2f", distanceSq)
                        + " dy=" + String.format(java.util.Locale.ROOT, "%.1f", this.getY() - ty)
                        + " " + this.describe());
            }
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
            // 【fix352】记一笔高差 abort：技能层据此把本腿目标记入失败记忆、横向重取，
            // 不再原样重喂同一条路（264 第五簇 675 次同型 abort 实证）。
            this.lastHeightAbortTick = this.level().getServer().getTickCount();
            this.lastHeightAbortEndpoint = this.path.get(this.path.size() - 1);
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
        // 【fix323】头顶被自家农田守卫拒挖时不转斜向侧挖：头顶是自家耕地
        // （挖不动是设计），侧挖挖的是邻格、清不掉头顶的耕地，只会拆自
        // 家桥格／垫脚（202 棒生产实证：桥格 (-12,61,-113) 被挖 40 次、
        // travel 反复重搭、微进展反复清 stuck、400 秒原地打转）。跳过
        // 侧挖、直接给上层失败出口（换站位／跳过），不再原地挖。
        boolean guardBlocked323 = !dugFeet && !dugHead && mineRefusedByFarmGuardRecently();
        if (guardBlocked323) {
            farmGuardRescueSkips++;
        }
        // 斜着走被卡住时，真正挡路的往往不是斜前格（是空气），
        // 而是左右两侧正交格的边角（树冠、墙角）：身体 0.6 格宽，斜穿时两边都会剐蹭。
        // 实测 NPC 卡在树冠角落里，斜前全是空气、挖脚/挖头恒为 false，自救等于空转。
        // 斜向移动且斜前没挖到东西时，把两个正交侧格也试着挖开。
        boolean dugSide = false;
        if (!guardBlocked323 && !dugFeet && !dugHead && dirX != 0 && dirZ != 0) {
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
        // 【fix239】四路全空才问围死兜底：四周若还有普通可挖块，兜底内部会
        // 先挖普通块；只有全是存储块可挖时才放行挖最快的那一个（Fong
        // 2026-11-06 20:43 口径：先避让、围死才兜底，只堵不疏不许出厂）。
        boolean dugFallback = false;
        if (!dugFeet && !dugHead && !dugSide && !dugCeiling) {
            dugFallback = digStorageEscapeFallback();
        }
        String detail = "阶段=" + phase + " 次数=" + this.stuckRescues
                + " 方向=" + dirX + "," + dirZ
                + " 前脚=" + this.level().getBlockState(frontFeet).getBlock()
                + " 前头=" + this.level().getBlockState(frontHead).getBlock()
                + " 脚下=" + this.level().getBlockState(feet.below()).getBlock()
                + " 挖脚=" + dugFeet + " 挖头=" + dugHead + " 守卫挡路=" + guardBlocked323 + " 侧挖=" + dugSide + " 挖顶=" + dugCeiling
                + " 兜底=" + dugFallback;

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

    /**
     * 【fix231→fix239】存储/功能块判定（全插件统一口径）：带方块实体的
     * （箱子/熔炉/漏斗/桶等）与床/工作台一族。fix231 只在自救挖路径
     * （tryDigBlocking）查它；81 棒实验场实证 travel 清障挖路
     * （travelStepToward）无此守卫、照样挖穿工作台与熔炉——fix239 把
     * 判定下沉为身体层统一谓词，mine()/mineWithBestTool() 默认拒挖，
     * 只有定点许可（工作站回收、围死兜底）才放行。
     */
    public static boolean isStorageBlock(BlockState state) {
        return state.hasBlockEntity()
                || state.getBlock() instanceof net.minecraft.world.level.block.BedBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.CraftingTableBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.SmithingTableBlock
                || state.is(net.minecraft.world.level.block.Blocks.FLETCHING_TABLE)
                || state.getBlock() instanceof net.minecraft.world.level.block.CartographyTableBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.LoomBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.GrindstoneBlock;
    }

    /** 【fix239】定点存储挖掘许可：只认这一格、只在设定期间有效（同 fix198 许可式）。 */
    public void setStorageDigPermit(BlockPos pos) {
        this.storageDigPermitPos = pos;
    }

    /** 该格是否命中存储守卫（无定点许可时为真）：守卫触发时节流打一行专行。 */
    private boolean storageGuardBlocks(BlockPos pos, BlockState state) {
        if (!isStorageBlock(state) || pos.equals(this.storageDigPermitPos)) {
            return false;
        }
        if (this.tickCount - lastStorageGuardLogTick >= 200) {
            lastStorageGuardLogTick = this.tickCount;
            log(npcName, "storage-guard", "拒绝挖存储/功能块 " + state.getBlock()
                    + " " + pos.toShortString() + "（fix239：挖路/自救先避让，围死才走兜底）");
        }
        return true;
    }

    /** 只挖"确实挡路"的方块：空气/液体不碰，基岩和黑曜石这种挖不动的也不碰。 */
    private boolean tryDigBlocking(BlockPos pos) {
        BlockState state = this.level().getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        // 【fix231】自救不许挖存储/功能块：2026-11-05 farm209 基址边沿
        // 卡住自救连挖 4 格，顺手挖掉基址贵重箱 (733,65,-131)（箱内钻石
        // 8→10、红石 27→64 拾回，余物 despawn 灭失）；同一自救还挖掉
        // (732,65,-131) 压顶圆石、误打误撞通了床边一格。玩家被卡住也不
        // 会把自家箱子/床/工作台挖掉脱困——判定已下沉为身体层统一谓词
        // isStorageBlock（fix239），本路径与 mine 层双重拒挖。
        if (isStorageBlock(state)) {
            return false;
        }
        float hardness = state.getDestroySpeed(this.level(), pos);
        if (hardness < 0.0F || hardness > 3.0F) {
            return false;
        }
        // 【fix226】行走层挖穿挡路块=自救/通行语义，钻镐兜底放行（见
        // mineWithBestTool 三参版注释）：玩家被埋住时只剩钻镐也会挖出来，
        // 不可能守着省镐规矩在石盒里干等。
        return this.mineWithBestTool(pos, true, true);
    }

    /**
     * 【fix239】围死兜底（Fong 2026-11-06 20:43 口径，与 fix101 兜底通则同源）：
     * 存储块平时不许挖（fix231），但若被箱子/床/工作台一族四面围死、
     * 没有任何其他可挖方块，只堵不疏就是死路——此时放行：候选格里
     * 随机选一个"脱困最快"（destroy speed 最小、同速随机）的存储块挖掉。
     *
     * <p>触发门（缺一不可）：①围死判定——四个水平方向无一方向脚/头
     * 两格同时可通行（单格开洞不算脱困：1.8 格高的身体过不去）；②候选
     * 集（水平 8 格 + 头顶格）里先找普通可挖块——有就挖普通块（避让
     * 优先，本方法顺带覆盖自救窄扫描漏掉的后向格）；③只剩存储块可挖
     * 时才轮到存储兜底。每次真挖存储块必打 storage-fallback-dig 专行。
     *
     * @return 是否挖开了至少一块
     */
    public boolean digStorageEscapeFallback() {
        BlockPos feet = this.blockPosition();
        java.util.List<BlockPos> ring = new java.util.ArrayList<>();
        boolean sealed = true;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos dirFeet = feet.relative(dir);
            BlockPos dirHead = dirFeet.above();
            ring.add(dirFeet);
            ring.add(dirHead);
            BlockState fs = this.level().getBlockState(dirFeet);
            BlockState hs = this.level().getBlockState(dirHead);
            if (fs.getFluidState().isEmpty()
                    && fs.getCollisionShape(this.level(), dirFeet).isEmpty()
                    && hs.getFluidState().isEmpty()
                    && hs.getCollisionShape(this.level(), dirHead).isEmpty()) {
                sealed = false; // 有一向整个人能通过，不算围死
            }
        }
        if (!sealed) {
            return false;
        }
        java.util.List<BlockPos> candidates = new java.util.ArrayList<>(ring);
        candidates.add(feet.above(2));
        BlockPos normalPick = null;
        java.util.List<BlockPos> storagePicks = new java.util.ArrayList<>();
        float storageBestSpeed = Float.MAX_VALUE;
        for (BlockPos cell : candidates) {
            BlockState s = this.level().getBlockState(cell);
            if (s.isAir() || !s.getFluidState().isEmpty()) {
                continue;
            }
            float speed = s.getDestroySpeed(this.level(), cell);
            if (speed < 0.0F || speed > 3.0F) {
                continue; // 基岩/黑曜石一类挖不动，不入候选
            }
            if (isStorageBlock(s)) {
                if (speed < storageBestSpeed - 1.0E-4F) {
                    storageBestSpeed = speed;
                    storagePicks.clear();
                    storagePicks.add(cell);
                } else if (Math.abs(speed - storageBestSpeed) <= 1.0E-4F) {
                    storagePicks.add(cell);
                }
            } else if (normalPick == null) {
                normalPick = cell;
            }
        }
        if (normalPick != null) {
            BlockState normalState = this.level().getBlockState(normalPick);
            boolean dug = this.mineWithBestTool(normalPick, true, true);
            if (dug) {
                log(npcName, "storage-fallback", "围死候选里有普通可挖块，先挖普通块避让存储块 | "
                        + normalState.getBlock()
                        + " " + normalPick.toShortString());
            }
            return dug;
        }
        if (storagePicks.isEmpty()) {
            return false;
        }
        BlockPos pick = storagePicks.get(this.random.nextInt(storagePicks.size()));
        BlockState pickState = this.level().getBlockState(pick);
        this.storageDigPermitPos = pick;
        boolean dug;
        try {
            dug = this.mineWithBestTool(pick, true, true);
        } finally {
            this.storageDigPermitPos = null;
        }
        log(npcName, "storage-fallback-dig", "四面围死且只剩存储块可挖，兜底挖最快一块脱困 | "
                + pickState.getBlock() + " " + pick.toShortString()
                + " speed=" + String.format(Locale.ROOT, "%.1f", storageBestSpeed)
                + " 候选=" + storagePicks.size() + " success=" + dug);
        return dug;
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
        // 【fix248＋fix351】流体游泳护栏（与 walkTo 同口径）：人在流体里且游泳脱困
        // 进行中时不受理路径行走——受理即清 swimGoal，等于把人按回水/浆里
        // （岩浆 #92/#93 定谳；水域 263 棒新图生产实证：行走约 1 次/秒顶掉游泳、
        // 游泳约 2 秒一轮来回换岸、11 分钟零位移）。放弃回调照常跑，让技能层当场收场重派。
        if (this.swimGoal != null && (this.isInLava() || this.isInWater())) {
            log(npcName, "lava-swim-guard", "岩浆游泳中，拒绝路径抢身 " + this.describe());
            if (onAbandon != null) {
                onAbandon.run();
            }
            return;
        }
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
        // 【fix245】起点可达性守卫：起点比身体高出跳跃高度（>1.5 格，与
        // applyPathInput 的 path-abort 同判据）时，这条路径的第一步永远
        // 执行不了——硬启只会每 tick abort、技能层同秒重派成热循环
        // （86 棒新图 246 次零位移实证；根因在寻路起点解析，已在
        // PathFinder 侧收紧到身体可执行窗口，这里是不变量守卫，防任何
        // 来源的幻影路径再烧一遍）。不启路径，直接按放弃收场交技能层
        // 重新规划/换法。
        if (start.getY() - this.getY() > 1.5D) {
            log(npcName, "path-start-blocked", String.format(Locale.ROOT,
                    "路径起点 %s 高出身体 %.2f 格（>1.5 跳跃高度），拒绝启程交重规划 %s",
                    start.toShortString(), start.getY() - this.getY(), this.describe()));
            if (onAbandon != null) {
                onAbandon.run();
            }
            return;
        }
        this.swimGoal = null;
        this.walkTarget = null;
        this.walkCallback = null;
        this.walkAbandon = null;
        this.path = path;
        this.pathIndex = 0;
        this.lastLedgeSkipIndex = -1;
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

    /** 【fix352】上次高差 abort 的服务端 tick（-1 表示从未发生）。 */
    public int lastHeightAbortTick() {
        return this.lastHeightAbortTick;
    }

    /** 【fix352】上次高差 abort 那条路的终点（null 表示从未发生）。 */
    public BlockPos lastHeightAbortEndpoint() {
        return this.lastHeightAbortEndpoint;
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
        walkTo(target, sprint, callback, null);
    }

    /**
     * 走到指定坐标；到达后执行 callback、自救攒满放弃时执行 onAbandon
     * （在主线程 tick 内触发）。【fix237】与 followPath 的 onArrive/onAbandon 同义。
     */
    public void walkTo(Vec3 target, boolean sprint, Runnable callback, Runnable onAbandon) {
        // 【fix248＋fix351】流体游泳护栏：人在流体里且游泳脱困进行中时，行走请求
        // 不受理——岩浆 #92/#93 定谳：落浆后目标层派 ascend，其 walkTo 把
        // swimGoal 清掉、生存层重发又被冷却挡住，数秒烧死；水域 263 棒新图
        // 生产实证同款：行走约 1 次/秒顶掉游泳、11 分钟零位移。先游出水/浆面；
        // 技能层靠自身走位看门狗收场，脱困后自会重派（兜底不缺位）。
        if (this.swimGoal != null && (this.isInLava() || this.isInWater())) {
            log(npcName, "lava-swim-guard", "岩浆游泳中，拒绝行走抢身 target=" + target
                    + " " + this.describe());
            return;
        }
        // 直线行走和 A* 路径互斥，手动 walkTo 视为放弃当前路径；游泳脱困也一并取消。
        this.swimGoal = null;
        this.path = null;
        this.pathIndex = 0;
        this.pathArrive = null;
        this.pathAbandon = null;
        this.walkTarget = target;
        this.walkSprint = sprint;
        this.walkCallback = callback;
        this.walkAbandon = onAbandon;
        this.lastDistanceSq = Double.MAX_VALUE;
        this.noProgressTicks = 0;
        resetRescuesIfLeftArea();
        log(npcName, "walk-start", String.format(Locale.ROOT, "目标 (%.2f, %.2f, %.2f)", target.x, target.y, target.z));
    }

    public void stopWalking() {
        this.swimGoal = null;
        this.walkTarget = null;
        this.walkCallback = null;
        this.walkAbandon = null;
        this.path = null;
        this.pathIndex = 0;
        this.pathArrive = null;
        this.pathAbandon = null;
        this.xxa = 0.0F;
        this.zza = 0.0F;
        // 【fix344】停走即停跳：phaseApproach 在 waitingBody 之前必调 stopWalking，
        // 残留的 jumping（见 applySwimInput）会使 NPC 在落定门前继续原地弹跳。
        // 行走／路径自动跳与 pillar 跳皆走 jumpFromGround() 直接调用、不读此标志，
        // 清零不影响它们。
        this.jumping = false;
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
    /**
     * 【fix349】点亮门框的边框黑曜石判定：该格是黑曜石且任一相邻格是传送门方块。
     * 门框边框（底边/顶边/侧边）必与传送门方块相邻；四角黑曜石不相邻、挖角不灭门。
     */
    private boolean isLitPortalFrameObsidian(BlockPos pos) {
        if (!this.level().getBlockState(pos).is(net.minecraft.world.level.block.Blocks.OBSIDIAN)) {
            return false;
        }
        for (Direction dir : Direction.values()) {
            if (this.level().getBlockState(pos.relative(dir)).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) {
                return true;
            }
        }
        return false;
    }

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
        if (targetState.getBlock() instanceof net.minecraft.world.level.block.BedBlock
                && !pos.equals(this.storageDigPermitPos)) {
            // 【fix239】唯一例外：围死兜底的定点许可（四面被存储块围死、
            // 只剩床可挖时，Fong 20:43 口径放行挖最快一块脱困）；许可只由
            // digStorageEscapeFallback 在单次挖掘期间设定，fix99 防线其余
            // 情形一律不让。
            if (this.tickCount - lastBedGuardLogTick >= 200) {
                lastBedGuardLogTick = this.tickCount;
                log(npcName, "mine-bed-guard", "拒绝挖床块 " + targetState.getBlock()
                        + " " + pos.toShortString());
            }
            return false;
        }
        // 【fix239】存储/功能块默认拒挖下沉到本方法：fix231 当年只堵了
        // 自救挖路径，81 棒实验场实证 travel 清障挖路（travelStepToward
        // 直调 mineWithBestTool）照样挖穿工作台/熔炉，与 67 棒贵重箱被
        // 自救挖掉是同类风险。mine() 是全插件唯一 destroyBlock 包装，
        // 在此拒挖即全覆盖：箱子/熔炉/工作台一族（isStorageBlock）无
        // 定点许可一律不挖。定点许可只有两个来源——工作站回收挖自家
        // 登记熔炉（GoalExecutor 设定）、围死兜底（digStorageEscapeFallback
        // 设定）；调用方按"挖不开"改道/收场，围死情形由兜底专路解脱，
        // 不是只堵不疏。
        if (storageGuardBlocks(pos, targetState)) {
            return false;
        }
        // 【fix199】基岩永不挖：本体 destroyBlock 对假人真移除基岩（staging
        // 直读铁证），与移动层多处"基岩禁入"口径不一致——真玩家挖不动基岩。
        // 当前危害低（-59 封底与基岩交错层错开一层），仍按玩家口径钉死。
        if (targetState.is(net.minecraft.world.level.block.Blocks.BEDROCK)) {
            if (this.tickCount - lastBedrockGuardLogTick >= 200) {
                lastBedrockGuardLogTick = this.tickCount;
                log(npcName, "mine-bedrock-guard", "拒绝挖基岩 " + pos.toShortString());
            }
            return false;
        }
        // 【fix349】已点亮的传送门永不挖：传送门方块本身与点亮门框的边框黑曜石
        // （与传送门方块相邻）一律拒挖——新图 260 棒实证：NPC 行走清路
        // （tryStepInto→mineWithBestTool→mine）把自家门框黑曜石挖穿，门灭后
        // 账本仍记旧位、fix333 循环派往幻影点。废墟传送门无点亮方块，不受此
        // 守卫拦（挖废墟黑曜石造门照常）；门框四角黑曜石不与传送门方块相邻、
        // 挖角不灭门，不在此列。调用方按"挖不开"诚实改道/放弃（兜底：tryStepInto
        // 副轴避让／travel 12 轮收场，规划层重派）。
        if (targetState.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)
                || isLitPortalFrameObsidian(pos)) {
            if (this.tickCount - lastPortalGuardLogTick >= 200) {
                lastPortalGuardLogTick = this.tickCount;
                log(npcName, "mine-portal-guard", "拒绝挖已点亮传送门／门框黑曜石 " + targetState.getBlock()
                        + " " + pos.toShortString());
            }
            return false;
        }
        // 【fix198】自家农田永不挖：全插件挖掘都过本方法，一处守卫全覆盖。
        // farm164/166/167 三度实证 descend 在田上开井把耕地连土带苗挖成
        // 空气、田格 8→2 缩水至全毁。【fix309】旧口径「田间作业许可期间
        // 除外」是洞：GoalExecutor 每 tick 把 farmWorkPermit 置真，tend
        // 全程许可敞开，流体脱困挖头顶（digOutAbove）与 stuck-rescue
        // 侧挖趁 NPC 田边落水之机挖掉自家耕地——159 棒生产实证 tend-1
        // 扩 2 格毁 2 格、田 5 格原地踏步。许可已撤：守卫从此无条件。
        // 【fix311 修正】fix309 原句「收获挖作物格、本就不是田格、不受
        // 守卫拦」是错的：isFarmCell 含 y+1 作物位，且收获走 mineBareHand
        // →mine()、必经本守卫——159 棒实验场实证 fix309 boot 内收获 0 行、
        // 小麦拒挖 5 次，tend 颗粒无收。守卫改只拦土格本身
        // （isFarmSoilCell 精确匹配）：土是稀缺资源、作物可弃可补种；
        // till/plant/place 走 setBlockAndUpdate，不走 mine。无任何 farm
        // 动作需要挖土格本身。
        // 兜底（Fong 通则）：descend 有井口避让挪位+避让收场两条疏路，
        // 脱困挖不动则换向/续游，不是只堵不疏。
        com.example.aivillager.goals.FarmMemory.bindWorld(this.level());
        if (com.example.aivillager.goals.FarmMemory.isFarmSoilCell(npcName, pos)) {
            lastFarmGuardRefusalTick = this.tickCount;
            if (this.tickCount - lastFarmGuardLogTick >= 200) {
                lastFarmGuardLogTick = this.tickCount;
                log(npcName, "mine-farm-guard", "拒绝挖自家农田格 " + targetState.getBlock()
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

    /**
     * 【fix261】进门金靴保护窗：目标层进门前强制换上金靴（fix93·D8）后置此
     * 窗（60 秒，覆盖一轮 enterportal 30 秒＋重试余量，每轮进门尝试刷新）。
     * 窗内生存层 AutoArmor 不许把脚上金靴按防御分换下——fix93 的下界锁只管
     * 界内，主世界进门窗口曾无保护：换上约 1 秒即被铁/钻靴换回，传送瞬间
     * 从来没穿着金装（111/112 棒生产＋实验场双实证）。窗外恢复纯评分，
     * 回主世界照常换回好靴，兜底不堵死。
     */
    public void protectGoldBoots() {
        this.goldBootsProtectUntilTick = this.tickCount + 1200L;
    }

    /** 【fix261】当前是否在金靴保护窗内（生存层 FEET 换装守卫用）。 */
    public boolean goldBootsProtected() {
        return this.tickCount <= this.goldBootsProtectUntilTick;
    }

    // ===== 【fix379】定时挖掘（Fong 个人版开关 mining.timed）：像真人一样逐格耗时挖 =====
    // 瞬时版（测试线默认）：mineWithBestTool 换上工具→destroyBlock 一击碎→立刻还原，
    // 工具只在那一瞬间在手里，旁观者永远只看到保命层握着的剑，且一秒能碎好几块。
    // 定时版：一次只挖一个方块，按原版公式（方块硬度÷主手工具速率）逐 tick 推进，
    // 期间工具实实在在握在手里、带裂纹进度、带挥手动作，进度满才真正 destroyBlock。
    // 速率直接用原版 BlockState.getDestroyProgress（与真人客户端同式），不自创数值。
    private static boolean timedMiningEnabled = false;

    public static void setTimedMiningEnabled(boolean enabled) {
        timedMiningEnabled = enabled;
    }

    public static boolean timedMiningEnabled() {
        return timedMiningEnabled;
    }

    private BlockPos timedPos;          // 正在挖的格（null=无会话）
    private long timedStartTick;        // 会话开始 tick
    private long timedLastCallTick;     // 最近一次推进调用 tick（过期判定）
    private float timedPerTick;         // 每 tick 进度（会话开始时按当时主手算定）
    private String timedToolPath = "";  // 会话开始时主手物品路径（空串=空手）
    private Runnable timedRestore;      // 会话结束（挖完/取消/过期）时还原手部装备的动作
    private int timedCrackStage = -1;   // 已广播的裂纹阶段（-1=未广播）
    private long timedLastSwingTick;    // 上次挥手 tick

    /** 定时挖掘会话是否活跃（生存层换武器守卫用：挖完这一格之前不许把镐换走）。 */
    public boolean isActivelyMining() {
        return timedPos != null && this.tickCount - timedLastCallTick <= 20;
    }

    /** 别的格子正在挖 → 本次让行（会话由 tick 驱动挖完/取消，不靠调用续命）。 */
    private boolean timedBusyOther(BlockPos pos) {
        return timedPos != null && !timedPos.equals(pos);
    }

    /** 结束会话：清裂纹；restore=true 时执行还原动作（主手被外部接管时传 false）。 */
    private void cancelTimedSession(boolean restore) {
        if (timedPos != null && timedCrackStage >= 0) {
            this.level().destroyBlockProgress(this.getId(), timedPos, -1);
        }
        Runnable r = timedRestore;
        timedPos = null;
        timedRestore = null;
        timedCrackStage = -1;
        if (restore && r != null) {
            r.run();
        }
    }

    /**
     * 开始/推进一次定时挖掘。调用方须已完成换工具（含素手腾手），并把"还原手部
     * 装备"的动作以 restore 传入（仅建会话时采纳，推进时传 null 沿用已存的）。
     *
     * @return true=这一格刚刚挖掉；false=还在挖（或让行/失败），调用方按"未挖掉"处理
     */
    private boolean timedDig(BlockPos pos, Runnable restore) {
        BlockState state = this.level().getBlockState(pos);
        if (state.isAir()) {
            cancelTimedSession(true);
            return false;
        }
        if (timedPos == null || !timedPos.equals(pos)) {
            float perTick = state.getDestroyProgress(this, this.level(), pos);
            if (perTick <= 0.0F) {
                // 硬度 -1 等真挖不动：不建会话，照旧失败（基岩等另有守卫在前）。
                if (restore != null) {
                    restore.run();
                }
                return false;
            }
            if (perTick >= 1.0F) {
                // 一击即碎（草/花/火把等零硬度）：不建会话，直接挖。
                boolean ok = mine(pos);
                if (restore != null) {
                    restore.run();
                }
                return ok;
            }
            timedPos = pos.immutable();
            timedStartTick = this.tickCount;
            timedLastCallTick = this.tickCount;
            timedPerTick = perTick;
            timedToolPath = itemPathOf(this.getMainHandItem());
            timedRestore = restore;
            timedCrackStage = -1;
            timedLastSwingTick = this.tickCount - 4;
            log(npcName, "timed-mine", "开始逐格挖 " + state.getBlock()
                    + " tool=" + (timedToolPath.isEmpty() ? "空手" : timedToolPath)
                    + " 约需=" + Math.round(1.0D / perTick) + "tick " + pos.toShortString());
        } else {
            timedLastCallTick = this.tickCount;
            // 主手被外部换走（进食/战斗换武器接管）：取消会话、进度作废，手归外部不还原。
            if (!itemPathOf(this.getMainHandItem()).equals(timedToolPath)) {
                cancelTimedSession(false);
                return false;
            }
        }
        return advanceTimedMining();
    }

    /** 推进当前会话一格进度（挖掘调用与身体 tick 共用）：进度满则真挖并收尾。 */
    private boolean advanceTimedMining() {
        double progress = (this.tickCount - timedStartTick) * (double) timedPerTick;
        if (progress >= 1.0D) {
            boolean ok = mine(timedPos);
            cancelTimedSession(true);
            return ok;
        }
        if (this.tickCount - timedLastSwingTick >= 4) {
            this.swing(InteractionHand.MAIN_HAND);
            timedLastSwingTick = this.tickCount;
        }
        int stage = Math.min(9, (int) (progress * 10.0D));
        if (stage != timedCrackStage) {
            this.level().destroyBlockProgress(this.getId(), timedPos, stage);
            timedCrackStage = stage;
        }
        return false;
    }

    /**
     * 【fix380】身体 tick 驱动会话：目标格没了/主手被外部换走/走出够取距离
     * → 取消会话；否则刷新活跃时戳（保生存层换武器守卫全程有效）并推进一格。
     */
    private void tickTimedMining() {
        BlockState state = this.level().getBlockState(timedPos);
        if (state.isAir()) {
            cancelTimedSession(true);
            return;
        }
        // 主手被外部换走（进食/战斗换武器接管）：取消会话、进度作废，手归外部不还原。
        if (!itemPathOf(this.getMainHandItem()).equals(timedToolPath)) {
            cancelTimedSession(false);
            return;
        }
        // 原版生存够取 4.5 格，留 0.5 格余量防边界抖动误取消。
        if (this.getEyePosition().distanceToSqr(Vec3.atCenterOf(timedPos)) > 5.0D * 5.0D) {
            cancelTimedSession(true);
            return;
        }
        timedLastCallTick = this.tickCount;
        advanceTimedMining();
    }

    public boolean mineWithBestTool(BlockPos pos) {
        return mineWithBestTool(pos, true);
    }

    /**
     * 【fix196】最近 2 tick 内是否有挖掘被分级守卫礼让拒挖（石镐可合、拒用高级镐）。
     * descend/strip 在同 tick 挖后查格调用：礼让是偏好事件不是工具缺失，
     * 技能层应立即礼让收场交规划层补造石镐，不许累计"挖不动"连败。
     */
    public boolean mineRefusedByTierGuardRecently() {
        return this.tickCount - lastTierGuardRefusalTick <= 2;
    }

    /**
     * 【fix251】宽窗探针：最近 ticks tick 内是否有挖掘被分级守卫拒挖。
     * travel 的 followPath 挖墙发生在身体层、技能层只能在重规划结算时
     * 回看整段——2 tick 窗（fix196、为同 tick 同步查格设计）够不着，
     * 故另开宽窗版；fix196 版语义不动，descend/strip 照旧。
     */
    public boolean mineRefusedByTierGuardWithin(int ticks) {
        return this.tickCount - lastTierGuardRefusalTick <= ticks;
    }

    /** 【fix198】最近 2 tick 内是否被自家农田守卫拒挖（技能层避让收场判定，同 fix196 探针）。 */
    public boolean mineRefusedByFarmGuardRecently() {
        return this.tickCount - lastFarmGuardRefusalTick <= 2;
    }

    /** 【fix323】农田守卫挡路导致救援跳过侧挖的累计次数（目标层读）。 */
    public int farmGuardRescueSkips() {
        return farmGuardRescueSkips;
    }

    // 【fix309】farmWorkPermit 已删除：许可制在 tend 全程敞开，流体脱困/
    // stuck-rescue 趁机挖田（159 棒生产实证）；收获挖作物格本就不受
    // 守卫拦，无任何 farm 动作需要挖土格本身，守卫现无条件。

    /**
     * @param allowLastResort 是否允许动用"剩最后 1 点耐久"的退役工具兜底。一般挖掘
     *                        允许（总比卡死强）；ascend 这类"设计上就该素手挖"的脱困
     *                        路径传 false——长跑#10 实测兜底在 ascend 里把退役壳一格
     *                        烧一把、烧完照样得素手挖，壳白白烧光。
     */
    public boolean mineWithBestTool(BlockPos pos, boolean allowLastResort) {
        return mineWithBestTool(pos, allowLastResort, false);
    }

    /**
     * @param allowDiamondFallback 【fix226】是否放行钻石/下界镐作兜底工具。
     *        fix225 的钻镐排除只应覆盖技能层常规挖掘（省镐经济面）；行走层
     *        挖穿挡路块（tryDigBlocking）是自救/通行——farm203 live 实证：
     *        峡谷石盒里铁镐烧至退役后只剩钻镐，被排除→mine-no-tool 永冻。
     *        玩家被埋只剩钻镐照样挖出，故行走层传 true 放行。
     */
    public boolean mineWithBestTool(BlockPos pos, boolean allowLastResort, boolean allowDiamondFallback) {
        BlockState state = this.level().getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        // 【fix379】定时挖掘会话进行中：同格直接推进（工具已在手，不重跑选工具），
        // 异格让行（一次只挖一个方块）；会话过期时 timedBusyOther 顺手清理并放行。
        if (timedMiningEnabled && timedPos != null) {
            if (timedPos.equals(pos)) {
                return timedDig(pos, null);
            }
            if (timedBusyOther(pos)) {
                return false;
            }
        }
        // 【fix239】存储/功能块在选工具之前先拒：免得为一块箱子白跑
        // 分级守卫/兜底链的日志与状态（mine() 层另有同口径拒挖兜底）。
        if (storageGuardBlocks(pos, state)) {
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
            if (timedMiningEnabled) {
                // 【fix379】定时版：腾手状态在整个挖掘会话期间保持，挖完/取消才还原。
                final int swapSlotF = swappedSlot;
                Runnable restore = () -> {
                    if (swapSlotF >= 0) {
                        ItemStack handNow = inventory.getItem(selected);
                        ItemStack bagNow = inventory.getItem(swapSlotF);
                        inventory.setItem(selected, bagNow);
                        inventory.setItem(swapSlotF, handNow);
                    }
                    inventory.setSelectedSlot(previous);
                };
                return timedDig(pos, restore);
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
        // 【fix225】本调用是否进入了分级守卫面（石镐能采的方块、无可用石镐）：
        // 进入后若走到评分兜底，钻石/下界镐不入候选——farm201 实证：满包
        // 「合不出」窗口里兜底链先烧铁镐再烧钻镐，单 boot 钻镐 527→73，
        // 而钻镐是黑曜石唯一工具、属保命级资产。Fong 兜底口径（fix101）
        // 点名的是「铁镐顶上」，钻镐只许挖石镐/铁镐采不了的方块（黑曜石、
        // 钻石矿等——那些方块石镐探针为假、根本不进本守卫面，不受影响）。
        boolean diamondExcludedFromFallback = false;
        if (stoneSlot < 0 && allowLastResort
                && new ItemStack(Items.STONE_PICKAXE).isCorrectToolForDrops(state)
                && !hasUsableCheapPick(inventory, state)) {
            // 【fix226】行走层自救挖掘（allowDiamondFallback）不排除钻镐。
            diamondExcludedFromFallback = !allowDiamondFallback;
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
                // 【fix196】记下礼让时点：技能层（descend/strip）挖后查格仍实心时
                // 先问本探针，礼让不许记成"挖不动/缺少镐"（staging fix195e 实证：
                // descend 同格连试 6 次速败、误喂 fix109 缺镐速断把连败顶满）。
                lastTierGuardRefusalTick = this.tickCount;
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
            // 【fix225】守卫面内钻石/下界镐不入兜底候选（见上方 diamondExcludedFromFallback）。
            if (diamondExcludedFromFallback
                    && (stack.is(Items.DIAMOND_PICKAXE) || stack.is(Items.NETHERITE_PICKAXE))) {
                continue;
            }
            int score = toolScore(stack, state);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        // 【fix234】非正确工具不得占位遮挡退役壳兜底。
        // 68 棒实测：包里只剩两把 1 耐久石镐（第一遍跳过）+ 被 fix225 排除的钻镐时，
        // 剑/弓等非正确工具以 toolScore≈100“胜出”当 bestSlot，终检即拒 → mine-no-tool；
        // 而“只剩 1 耐久还是要用一下”的兜底遍只在 bestSlot<0 时跑，剑占位致其永不点火。
        // 非正确工具本就挖不出掉落，占位毫无意义：置回 -1 落回兜底遍（fix225 排除面不变）。
        if (bestSlot >= 0 && !inventory.getItem(bestSlot).isCorrectToolForDrops(state)) {
            bestSlot = -1;
            bestScore = -1;
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
                // 【fix225】退役壳兜底遍同样排除钻石/下界镐（守卫面内）。
                if (diamondExcludedFromFallback
                        && (stack.is(Items.DIAMOND_PICKAXE) || stack.is(Items.NETHERITE_PICKAXE))) {
                    continue;
                }
                // 【fix236】退役壳遍同样不烧只剩 1 耐久的钻石/下界镐（黑曜石种子）。
                // 69 棒实证：fix235 第三遍有此护栏、第一遍也有，但壳遍没有——
                // tryDigBlocking（allowDiamondFallback=true，fix226 自救放行钻镐）
                // 走壳遍选中 1 耐久钻镐，stuck-rescue 一格挖断（52→0）；而同一秒前
                // strip 路径的 mine-no-tool 刚正确拒挖（护栏在第三遍生效）。
                // 石/铁壳仍可烧到最后 1 点（壳遍本意），只保钻石/下界镐种子。
                if ((stack.is(Items.DIAMOND_PICKAXE) || stack.is(Items.NETHERITE_PICKAXE))
                        && stack.isDamageableItem()
                        && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
                    continue;
                }
                int score = toolScore(stack, state);
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = slot;
                }
            }
        }
        // 【fix234 追补】兜底遍本身也会把剑/弓等非正确工具以 toolScore≈100 选为
        // bestSlot（当包里已无任何正确工具时），终检即拒 → mine-no-tool，且会挡住
        // 下面的 fix235。非正确工具占位毫无意义：再置回 -1。
        if (bestSlot >= 0 && !inventory.getItem(bestSlot).isCorrectToolForDrops(state)) {
            bestSlot = -1;
            bestScore = -1;
        }
        // 【fix235】绝对最后手段：两遍扫完仍无可用工具、且钻镐被 fix225 排除时，
        // 若包里只剩钻石/下界镐这一把镐（无任何其他可用镐、也无圆石可合石镐），
        // 放行钻镐挖这一格——否则 NPC 在无镐无圆石时永冻（68 棒实测：两把 1 耐久
        // 石镐先后殉职、圆石耗尽、travel 换位在 fix186/fix150 里空转）。
        // 玩家口径：只剩钻镐时就拿它挖 3 块圆石合石镐，1 点耐久换脱困。专行计数
        // 防长烧：一旦合出石镐，第一遍即有候选、此处不再点火，钻镐重新被排除。
        // fix101 通则：fix225 的排除须配兜底，此处即该兜底。
        if (bestSlot < 0 && allowLastResort && diamondExcludedFromFallback) {
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                if (!(stack.is(Items.DIAMOND_PICKAXE) || stack.is(Items.NETHERITE_PICKAXE))) {
                    continue;
                }
                // 钻镐本身也只剩最后 1 点耐久时不烧（留着当黑曜石的种子）。
                if (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
                    continue;
                }
                int score = toolScore(stack, state);
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = slot;
                }
            }
            if (bestSlot >= 0) {
                log(npcName, "pick-last-resort-diamond", "只剩钻镐兜底掘进 block=" + state.getBlock()
                        + " pos=" + pos.toShortString());
            }
        }
        // 【fix225】守卫面内无合法工具（只剩钻镐）→ 按礼让收场处理（置
        // fix196 探针，技能层让位交规划层补镐/腾包），不许烧钻镐硬挖。
        // fix235 点火时 bestSlot 已 >= 0，此处不置探针。
        // 【fix357】探针只在包里真有一把钻石/下界镐被排除时才置：
        // 包里一把镐都没有时 bestSlot<0 与"只剩钻镐"同形，旧口径一律
        // 置探针，descend 误读为"分级守卫拒挖（石镐可合）"礼让收场——
        // 而石镐根本合不出（圆石 0）、亦无高级镐被拒，fix320 守卫
        // "合不出"分支空转返回、目标层原样重派，1.5 次/秒空转
        // （269 棒实验场实证）。无镐时不置探针，诚实走 mine-no-tool。
        if (bestSlot < 0 && diamondExcludedFromFallback
                && (countInventoryItem(inventory, Items.DIAMOND_PICKAXE) > 0
                        || countInventoryItem(inventory, Items.NETHERITE_PICKAXE) > 0)) {
            lastTierGuardRefusalTick = this.tickCount;
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
        if (timedMiningEnabled) {
            // 【fix379】定时版：工具在整个挖掘会话期间握在手里，挖完/取消才换回。
            final int bestSlotF = bestSlot;
            Runnable restore = () -> {
                if (bestSlotF >= 9) {
                    ItemStack curNow = inventory.getItem(selected);
                    inventory.setItem(selected, inventory.getItem(bestSlotF));
                    inventory.setItem(bestSlotF, curNow);
                }
                inventory.setSelectedSlot(selected);
            };
            return timedDig(pos, restore);
        }
        boolean mined = mine(pos);
        // 【fix348】深槽换镐后把物品换回去：只还原选中格、不还原物品时，
        // 原选中格的物品（可能是在吃的口粮）被埋进背包深槽，下一实体 tick
        // updatingUsingItem() 见手持物≠useItem 即取消进食。素手分支同款还原在上。
        if (bestSlot >= 9) {
            ItemStack cur = inventory.getItem(selected);
            inventory.setItem(selected, inventory.getItem(bestSlot));
            inventory.setItem(bestSlot, cur);
        }
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
     *
     * <p>【fix183】空槽口径再对齐：fix123 的「有空槽」按 ≥1 算，但合成链
     * 的真实门槛是 fix129 腾格 3 格；空槽 1–2 且丢弃腾不出格时合成仍恒
     * 败、本判定却报可合，守卫委托落空成死局（farm148）。现口径：空槽
     * ≥3，或消耗本身腾格（圆石恰好 3 / 木棍恰好 2）。
     *
     * <p>【fix369·310 棒】石料口径补黑石：圆石／深板岩圆石／黑石在原版
     * 石镐配方里完全等价（同属 stone_tool_materials 标签，Wiki 黑石条目：
     * “黑石可替代圆石来合成石制工具”）。旧口径只数前两者，下界里袋里
     * 躺着黑石也判「合不出石镐 missing=圆石=0/3」（309 正式服实证），
     * 分级守卫、pick-fallback、fix306 下界镐保障三路同堵。黑石计入后，
     * 判定与执行层（craftOne 走真实配方、标签候选天然含黑石）一致。
     * missing 文案的“圆石=”标签不动（保日志口径连续）；主世界 decide()
     * 的 fix57 口径（ProgressionPlanner:1680）不改——主世界无黑石，数了无用。
     */
    public boolean stonePickCraftableNow(StringBuilder missing) {
        Inventory inventory = this.getInventory();
        int cobble = countInventoryItem(inventory, Items.COBBLESTONE)
                + countInventoryItem(inventory, Items.COBBLED_DEEPSLATE)
                + countInventoryItem(inventory, Items.BLACKSTONE);
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
        // 【fix183】placeable 空槽门槛 ≥1→≥3：fix123 的 ≥1 只算了产物本身
        // 一格，但合成系统真实门槛是 fix129 的腾格 3 格（合木棍中间产物+
        // 石镐产物+腾格余量，且腾格靠丢弃、无受害者时腾不出）。farm148
        // 实证两口径打架死局：包剩 1–2 格、无可丢受害者时合成恒败「背包
        // 已满」，本判定却报「可立即合」→ 分级守卫持续拒挖高级镐「交规划
        // 层补造」、委托落空，NPC 钉死恢复栈台阶（descend 挖不动+travel
        // A* 耗尽双循环）。守卫必配兜底（fix101 通则）：紧包时如实报合不
        // 出 → pick-fallback 顺位开挖（铁>钻）脱困；空槽 ≥3 省镐口径不变。
        // 【fix232】fix183 的 cobble==3||sticks==2 逃生口仍谎报：它只算了
        // 产物镐本身的格，没算合成链中间产物。合石镐要 3 圆石 + 2 木棍：
        // 木棍现成（sticks>=2）时中间产物 0 格，产物镐需 1 格（空槽或圆
        // 石/木棍堆叠耗尽腾出的格）；木棍须由木板合（planks>=2）时中间产
        // 物木棍占 1 格（木板恰为 2 时其格腾出抵 1 格）；木棍须由原木现合
        // （logs>=1）时中间产物木板+木棍占 2 格。farm211 基址实证：
        // cobble=3、logs=19、sticks=0、空槽=1，旧口径报可合，实合木棍时
        // 「4 个 stick 放不下」恒败，分级守卫持续拒挖、NPC 钉死基址边沿
        // （fix186 连环 4 次）。按链算格，合不出时如实报 → pick-fallback。
        int freedSlots = (cobble == 3 ? 1 : 0) + (sticks == 2 ? 1 : 0);
        boolean placeable;
        if (sticks >= 2) {
            placeable = emptySlots + freedSlots >= 1;
        } else if (planks >= 2) {
            placeable = emptySlots + freedSlots + (planks == 2 ? 1 : 0) >= 2;
        } else { // logs >= 1（stickMaterial 已保证三者其一）
            placeable = emptySlots + freedSlots >= 3;
        }
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
        // 【fix379】定时挖掘会话进行中：同格推进、异格让行（同 mineWithBestTool 口径）。
        if (timedMiningEnabled && timedPos != null) {
            if (timedPos.equals(pos)) {
                return timedDig(pos, null);
            }
            if (timedBusyOther(pos)) {
                return false;
            }
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
        if (timedMiningEnabled) {
            // 【fix379】定时版：腾手状态在整个挖掘会话期间保持，挖完/取消才还原。
            final int swapSlotF = swappedSlot;
            Runnable restore = () -> {
                if (swapSlotF >= 0) {
                    ItemStack handNow = inventory.getItem(selected);
                    ItemStack bagNow = inventory.getItem(swapSlotF);
                    inventory.setItem(selected, bagNow);
                    inventory.setItem(swapSlotF, handNow);
                }
                inventory.setSelectedSlot(selected);
            };
            return timedDig(pos, restore);
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

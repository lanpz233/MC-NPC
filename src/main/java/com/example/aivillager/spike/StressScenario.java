package com.example.aivillager.spike;

import com.example.aivillager.AiVillagerPlugin;
import com.example.aivillager.npc.FakePlayerBody;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.logging.Logger;

/**
 * 规模压力测试：一次生成 N 个伪玩家，让它们各自绕圈走，采样服务端 tick 耗时。
 *
 * <p>目的不是做严谨 benchmark，而是回答一个架构问题：
 * "50+ 个 ServerPlayer 伪玩家同屏游走，服务端还撑得住吗？"
 * 这直接决定后续要不要上 LOD（只对附近 NPC 启用完整身体）。
 */
public final class StressScenario {

    private static final Logger LOG = Logger.getLogger("AIVillager");

    /** 每个伪玩家的游走半径（格）。 */
    private static final int WANDER_RADIUS = 6;
    /** tick 采样窗口大小，用于算平均/最差。 */
    private static final int WINDOW = 100;

    private final AiVillagerPlugin plugin;
    private final CommandSender sender;
    private final List<FakePlayerBody> bots = new ArrayList<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final Deque<Double> tickMillis = new ArrayDeque<>();
    private final Random random = new Random(20261024L);

    private boolean finished;
    private int spawnIndex;
    private int spawnCount;
    private BukkitTask spawnTask;
    private long lastTickNanos;
    private double worstTickMillis;
    private double totalTickMillis;
    private int tickSamples;

    public StressScenario(AiVillagerPlugin plugin, CommandSender sender) {
        this.plugin = plugin;
        this.sender = sender;
    }

    public void start(int count, int seconds) {
        this.spawnCount = count;
        LOG.info("[AIV][STRESS] ===== 开始压力测试: " + count + " 个伪玩家, " + seconds + " 秒 =====");
        say("压力测试开始: " + count + " 个伪玩家, " + seconds + " 秒");

        // 每 2 tick 生成一个，避免所有区块/实体在同一 tick 内同时创建造成假抖动。
        spawnTask = Bukkit.getScheduler().runTaskTimer(plugin, this::spawnNext, 1L, 2L);
        // 观察窗口结束后收尾。
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> finish(), 20L * seconds));
        // 全局看门狗：生成都可能很慢，兜底 3 倍时间。
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!finished) {
                LOG.warning("[AIV][STRESS] 超时收尾");
                finish();
            }
        }, 20L * seconds * 3L));
    }

    public void cancel() {
        finished = true;
        cancelTasks();
        despawnAll();
    }

    private void spawnNext() {
        if (finished) {
            return;
        }
        if (spawnIndex >= spawnCount) {
            if (spawnTask != null) {
                spawnTask.cancel();
                spawnTask = null;
            }
            startSampling();
            return;
        }
        try {
            World world = Bukkit.getWorlds().get(0);
            Location base = world.getSpawnLocation();
            // 8 列网格铺开，间距 3 格，避免伪玩家互相卡住。
            int column = spawnIndex % 8;
            int row = spawnIndex / 8;
            int x = base.getBlockX() + 6 + column * 3;
            int z = base.getBlockZ() + 6 + row * 3;
            int y = world.getHighestBlockYAt(x, z) + 1;

            String name = String.format(Locale.ROOT, "Bot_%02d", spawnIndex + 1);
            FakePlayerBody bot = FakePlayerBody.spawn(((CraftWorld) world).getHandle(), name,
                    new Location(world, x + 0.5D, y, z + 0.5D, 0.0F, 0.0F));
            plugin.registerNpc(bot);
            bots.add(bot);

            // 每个伪玩家在出生点附近随机游走，保证移动/碰撞/区块跟踪全程有负载。
            wanderFrom(bot, new Vec3(x + 0.5D, y, z + 0.5D));
            spawnIndex++;
        } catch (Throwable throwable) {
            LOG.severe("[AIV][STRESS] 生成第 " + (spawnIndex + 1) + " 个伪玩家失败: " + throwable);
            finish();
        }
    }

    private void wanderFrom(FakePlayerBody bot, Vec3 origin) {
        double targetX = origin.x + random.nextInt(WANDER_RADIUS * 2 + 1) - WANDER_RADIUS;
        double targetZ = origin.z + random.nextInt(WANDER_RADIUS * 2 + 1) - WANDER_RADIUS;
        // 用 y 不参与到达判定（水平距离），这里直接沿用当前高度。
        bot.walkTo(new Vec3(targetX, bot.getY(), targetZ), false, () -> {
            if (!finished) {
                wanderFrom(bot, origin);
            }
        });
    }

    private void startSampling() {
        if (finished) {
            return;
        }
        LOG.info("[AIV][STRESS] 生成完成: " + bots.size() + " 个伪玩家, 开始采样 tick 耗时");
        say("生成完成: " + bots.size() + " 个伪玩家, 开始采样");
        lastTickNanos = System.nanoTime();

        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::sampleTick, 0L, 1L));
        // 每 5 秒汇报一次。
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::report, 100L, 100L));
    }

    /**
     * 在 tick 任务里记录"两次调度之间的真实间隔"。
     * 服务端 20 TPS 时这个值约 50ms；超过 50ms 的部分就是 tick 变慢的证据。
     */
    private void sampleTick() {
        long now = System.nanoTime();
        if (lastTickNanos != 0L) {
            double millis = (now - lastTickNanos) / 1_000_000.0D;
            tickMillis.addLast(millis);
            if (tickMillis.size() > WINDOW) {
                tickMillis.removeFirst();
            }
            worstTickMillis = Math.max(worstTickMillis, millis);
            totalTickMillis += millis;
            tickSamples++;
        }
        lastTickNanos = now;
    }

    private void report() {
        if (finished || tickMillis.isEmpty()) {
            return;
        }
        double average = tickMillis.stream().mapToDouble(Double::doubleValue).average().orElse(0.0D);
        double tps = Math.min(20.0D, 1000.0D / Math.max(average, 1.0D));
        LOG.info(String.format(Locale.ROOT,
                "[AIV][STRESS] bots=%d 平均tick=%.2fms 估算TPS=%.2f 窗口最差=%.2fms 在线玩家=%d",
                bots.size(), average, tps, tickMillis.stream().mapToDouble(Double::doubleValue).max().orElse(0.0D),
                Bukkit.getOnlinePlayers().size()));
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        cancelTasks();

        if (tickSamples > 0) {
            double average = totalTickMillis / tickSamples;
            LOG.info(String.format(Locale.ROOT,
                    "[AIV][STRESS] ===== 结果: bots=%d 采样tick=%d 平均=%.2fms 最差=%.2fms 估算TPS=%.2f =====",
                    bots.size(), tickSamples, average, worstTickMillis,
                    Math.min(20.0D, 1000.0D / Math.max(average, 1.0D))));
        } else {
            LOG.info("[AIV][STRESS] ===== 结果: bots=" + bots.size() + " 没有采样到 tick 数据 =====");
        }

        long startedAt = System.nanoTime();
        despawnAll();
        LOG.info(String.format(Locale.ROOT, "[AIV][STRESS] 全部退出耗时=%.1fms 在线玩家=%d",
                (System.nanoTime() - startedAt) / 1_000_000.0D, Bukkit.getOnlinePlayers().size()));
        say("压力测试结束，详见控制台日志");
    }

    private void despawnAll() {
        for (FakePlayerBody bot : bots) {
            try {
                bot.stopWalking();
                bot.despawn();
            } catch (Throwable throwable) {
                LOG.warning("[AIV][STRESS] 清理伪玩家失败: " + bot.npcName() + " -> " + throwable);
            }
            plugin.unregisterNpc(bot);
        }
        bots.clear();
    }

    private void cancelTasks() {
        for (BukkitTask task : tasks) {
            if (!task.isCancelled()) {
                task.cancel();
            }
        }
        tasks.clear();
    }

    private void say(String message) {
        sender.sendMessage("[AIV] " + message);
    }
}

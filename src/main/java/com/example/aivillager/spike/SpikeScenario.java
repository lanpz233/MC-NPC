package com.example.aivillager.spike;

import com.example.aivillager.AiVillagerPlugin;
import com.example.aivillager.npc.FakePlayerBody;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 无人值守验收脚本：生成 -> 走 10 格 -> 挖方块 -> 攻击僵尸 -> 检查拾取 -> 退出。
 *
 * <p>每一步都有超时与异常兜底，最终会输出一行 SPIKE PASS/FAIL 结论，
 * 方便在本地 Paper 服务端控制台直接判断 spike 是否成立。
 */
public final class SpikeScenario {

    private static final Logger LOG = Logger.getLogger("AIVillager");

    private final AiVillagerPlugin plugin;
    private final CommandSender sender;
    private final List<BukkitTask> tasks = new ArrayList<>();

    private FakePlayerBody npc;
    private Zombie zombie;
    private boolean finished;
    private boolean miningStarted;

    public SpikeScenario(AiVillagerPlugin plugin, CommandSender sender) {
        this.plugin = plugin;
        this.sender = sender;
    }

    public void start() {
        LOG.info("[AIV][SPIKE] ===== 开始伪玩家 spike =====");
        schedule(1L, this::spawnNpc);
        // 全局看门狗：40 秒内没有跑完就判定失败并清理。
        schedule(20L * 40L, this::timeout);
    }

    /** 中途取消（/aivspike stop 或插件卸载），只停任务，不动已存在的 NPC。 */
    public void cancel() {
        finished = true;
        cancelTasks();
    }

    private void spawnNpc() {
        try {
            World world = Bukkit.getWorlds().get(0);
            Location base = world.getSpawnLocation();
            int x = base.getBlockX() + 2;
            int z = base.getBlockZ() + 2;
            int y = world.getHighestBlockYAt(x, z) + 1;
            Location at = new Location(world, x + 0.5D, y, z + 0.5D, -90.0F, 0.0F);

            npc = FakePlayerBody.spawn(((CraftWorld) world).getHandle(), "Villager_A1", at);
            plugin.registerNpc(npc);
            say("1/6 生成成功 | " + npc.describe());

            schedule(10L, this::startWalk);
        } catch (Throwable throwable) {
            fail("spawn", throwable);
        }
    }

    private void startWalk() {
        try {
            Vec3 target = new Vec3(npc.getX() + 10.0D, npc.getY(), npc.getZ());
            npc.walkTo(target, false, this::startMine);
            say(String.format("2/6 开始向东走 10 格 -> (%.2f, %.2f, %.2f)", target.x, target.y, target.z));

            // 行走兜底：10 秒还没到就停下来，继续验证挖矿/攻击。
            schedule(200L, () -> {
                if (finished) {
                    return;
                }
                if (npc != null && npc.isWalking()) {
                    LOG.warning("[AIV][SPIKE] 行走超时，停止行走并继续后续步骤 | " + npc.describe());
                    npc.stopWalking();
                    startMine();
                }
            });
        } catch (Throwable throwable) {
            fail("walk", throwable);
        }
    }

    private void startMine() {
        if (miningStarted || finished) {
            return;
        }
        miningStarted = true;
        try {
            World world = npc.bukkitPlayer().getWorld();
            // 刻意用泥土而不是石头：石头必须用镐才掉圆石，空手挖只会消失、不产生掉落物，
            // 那样就验证不到"挖矿 -> 掉落 -> 拾取"这条对劳动生产最关键的链路。
            BlockPos pos = BlockPos.containing(npc.getX(), npc.getY() - 0.5D, npc.getZ());
            Block block = world.getBlockAt(pos.getX(), pos.getY(), pos.getZ());
            block.setType(Material.DIRT);

            boolean success = npc.mine(pos);
            Material after = block.getType();
            say("3/6 挖矿 success=" + success + "，方块类型 " + Material.DIRT + " -> " + after);
            if (!success || after != Material.AIR) {
                fail("mine", new IllegalStateException("挖掘结果不符合预期"));
                return;
            }

            // 方块掉落物的默认拾取延迟是 10 tick，等 25 tick 再查背包。
            schedule(25L, this::verifyPickup);
        } catch (Throwable throwable) {
            fail("mine", throwable);
        }
    }

    /** 验证伪玩家能像玩家一样把掉落物吸进背包（依赖原版 Player.aiStep 的碰撞拾取）。 */
    private void verifyPickup() {
        try {
            boolean pickedUp = npc.bukkitPlayer().getInventory().contains(Material.DIRT);
            say("4/6 掉落物拾取 dirt=" + pickedUp + " | " + npc.describe());
            if (!pickedUp) {
                fail("pickup", new IllegalStateException("挖掉泥土后没有拾取到掉落物"));
                return;
            }
            spawnZombie();
        } catch (Throwable throwable) {
            fail("pickup", throwable);
        }
    }

    private void spawnZombie() {
        try {
            World world = npc.bukkitPlayer().getWorld();
            Location at = new Location(world, npc.getX() + 2.0D, npc.getY(), npc.getZ() + 0.5D);
            zombie = world.spawn(at, Zombie.class, spawned -> {
                spawned.setAI(false);
                spawned.setSilent(true);
                spawned.setPersistent(true);
                spawned.setShouldBurnInDay(false);
            });
            say("5/6 生成测试僵尸，等待攻击蓄力");
            schedule(30L, this::doAttack);
        } catch (Throwable throwable) {
            fail("spawn-zombie", throwable);
        }
    }

    private void doAttack() {
        try {
            float before = (float) zombie.getHealth();
            float damage = npc.attackTarget(((CraftLivingEntity) zombie).getHandle());
            float after = (float) zombie.getHealth();
            say("攻击伤害=" + damage + "，僵尸生命 " + before + " -> " + after);
            if (damage <= 0.0F) {
                fail("attack", new IllegalStateException("攻击没有造成伤害"));
                return;
            }
            schedule(10L, this::verifyAndFinish);
        } catch (Throwable throwable) {
            fail("attack", throwable);
        }
    }

    /**
     * 最后一步：确认伪玩家真的从 PlayerList 里消失了。
     * 这一步很关键——如果退出不干净，50+ 个 NPC 会持续占用玩家槽位、残留追踪状态。
     */
    private void verifyAndFinish() {
        try {
            int onlineBefore = Bukkit.getOnlinePlayers().size();
            boolean listedBefore = Bukkit.getPlayerExact(npc.npcName()) != null;

            npc.stopWalking();
            npc.despawn();
            int onlineAfter = Bukkit.getOnlinePlayers().size();
            boolean listedAfter = Bukkit.getPlayerExact(npc.npcName()) != null;

            boolean ok = listedBefore && onlineAfter == onlineBefore - 1 && !listedAfter;
            say("6/6 退出检查 列表前=" + listedBefore + " 在线数 " + onlineBefore + " -> " + onlineAfter
                    + " 残留=" + listedAfter);

            plugin.unregisterNpc(npc);
            npc = null;

            if (!ok) {
                finish("SPIKE FAIL (despawn)");
                return;
            }
            finish("SPIKE PASS");
        } catch (Throwable throwable) {
            fail("verify", throwable);
        }
    }

    private void timeout() {
        if (finished) {
            return;
        }
        LOG.warning("[AIV][SPIKE] 超时未完成 | " + (npc == null ? "npc=null" : npc.describe()));
        finish("SPIKE FAIL (timeout)");
    }

    private void fail(String step, Throwable throwable) {
        LOG.severe("[AIV][SPIKE] 步骤失败: " + step + " | " + throwable);
        throwable.printStackTrace();
        finish("SPIKE FAIL (" + step + ")");
    }

    private void finish(String result) {
        if (finished) {
            return;
        }
        finished = true;
        cancelTasks();
        say(result);
        LOG.info("[AIV][SPIKE] ===== " + result + " =====");

        if (zombie != null) {
            zombie.remove();
            zombie = null;
        }
        if (npc != null) {
            try {
                npc.stopWalking();
                npc.despawn();
            } catch (Throwable throwable) {
                LOG.warning("[AIV][SPIKE] 清理伪玩家异常: " + throwable);
            }
            plugin.unregisterNpc(npc);
            npc = null;
        }
    }

    private void schedule(long delayTicks, Runnable runnable) {
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!finished) {
                runnable.run();
            }
        }, delayTicks));
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

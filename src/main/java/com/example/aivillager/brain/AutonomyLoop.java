package com.example.aivillager.brain;

import com.example.aivillager.AiVillagerPlugin;
import com.example.aivillager.goals.GoalExecutor;
import com.example.aivillager.intent.Intent;
import com.example.aivillager.npc.FakePlayerBody;
import com.example.aivillager.skills.SkillRunner;
import com.example.aivillager.survival.SurvivalController;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * 自主决策循环：没有任何玩家说话时，NPC 也会定期"自省"并自己做决定。
 *
 * <p>三条约束决定了它的形状：
 * <ol>
 *   <li>本地模型一次推理 6-10 秒，50 个 NPC 同时问会直接打爆 Ollama，
 *       所以用 {@code maxConcurrent} 做全局并发闸门；抢不到配额就等下一轮。</li>
 *   <li>正在执行技能（走路/挖矿/攻击）或正在和玩家对话的 NPC 不参与自省，避免打断动作。</li>
 *   <li>每个 NPC 都有自己的最小间隔，避免一个 NPC 刚做完动作又立刻问模型。</li>
 * </ol>
 *
 * <p>所有方法都在主线程调用；HTTP 推理由 {@link VillagerBrain#think} 内部异步完成，
 * 回调再切回主线程释放配额。
 */
public final class AutonomyLoop {

    private static final Logger LOG = Logger.getLogger("AIVillager");
    /** 空闲走动间隔：比模型推理快得多，保证两次决策之间 NPC 一直有小动作。 */
    private static final long NUDGE_INTERVAL_MILLIS = 2500L;

    private final AiVillagerPlugin plugin;
    private final long intervalMillis;
    private final int maxConcurrent;
    /** 每个 NPC 上一次发起自省的墙钟时间，用来做 per-NPC 节流。 */
    private final Map<String, Long> lastThinkAt = new HashMap<>();
    /** 每个 NPC 上一次空闲走动的时间，避免每 tick 都重新规划路径。 */
    private final Map<String, Long> lastNudgeAt = new HashMap<>();

    private BukkitTask task;
    private int active;

    public AutonomyLoop(AiVillagerPlugin plugin, int intervalSeconds, int maxConcurrent) {
        this.plugin = plugin;
        this.intervalMillis = Math.max(1, intervalSeconds) * 1000L;
        this.maxConcurrent = Math.max(1, maxConcurrent);
    }

    public void start() {
        // 每秒 poll 一次；真正的每 NPC 频率由 intervalMillis 控制。
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::poll, 100L, 20L);
        LOG.info("[AIV][AUTO] 自主决策循环启动 | interval=" + intervalMillis + "ms maxConcurrent=" + maxConcurrent);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        lastThinkAt.clear();
        lastNudgeAt.clear();
        LOG.info("[AIV][AUTO] 自主决策循环停止");
    }

    private void poll() {
        // 包装层：项目里其他周期任务都有 safeTick 兜底，唯独这里原先裸奔。
        // poll 里要读每个 NPC 的背包/附近实体，任何一次异常抛出都会中断本轮
        // 后面所有 NPC 的处理；若异常恰好发生在配额借出之后，还会泄漏配额（见下方 think 保护）。
        try {
            pollInner();
        } catch (Throwable throwable) {
            LOG.log(java.util.logging.Level.WARNING, "[AIV][AUTO] 自主循环单轮异常（已兜住，循环继续）", throwable);
        }
    }

    private void pollInner() {
        long now = System.currentTimeMillis();
        // 第一遍：先争取让 NPC 问模型。
        // 顺序很重要：如果先空闲走动，skills 会被 goto 一直占着，
        // 决策检查每次都看到"技能非 idle"而跳过，NPC 就变成只会瞎晃、不再发展。
        //
        // 发展层（ProgressionPlanner）启用时不再问模型：本地模型 20-40 秒才回一次，
        // 还经常重复同一个目标，无法支撑"木头→石头→铁→钻石"这种长链条推进。
        // 模型仍负责玩家对话，自主路线由确定性科技树负责。
        if (!plugin.progressionEnabled()) {
            for (FakePlayerBody body : plugin.npcs()) {
                if (!body.isAlive() || active >= maxConcurrent) {
                    continue;
                }
                SkillRunner skills = plugin.skill(body.npcName());
                if (skills == null || !"idle".equals(skills.currentSkill())) {
                    // 正在走路/挖矿/攻击，自省会打断动作，跳过。
                    continue;
                }
                // 有中期目标在执行时不问模型：目标层自己会拆步骤，问到一半只会得到重复目标。
                GoalExecutor goals = plugin.goal(body.npcName());
                if (goals != null && goals.active()) {
                    continue;
                }
                VillagerBrain brain = plugin.brain(body.npcName());
                if (brain == null || brain.isBusy()) {
                    continue;
                }
                Long last = lastThinkAt.get(body.npcName());
                if (last != null && now - last < intervalMillis) {
                    continue;
                }

                lastThinkAt.put(body.npcName(), now);
                active++;
                boolean started;
                try {
                    started = brain.think(() -> active = Math.max(0, active - 1));
                } catch (Throwable throwable) {
                    // think() 会先在主线程同步组提示词（读背包/附近方块/实体），可能直接抛异常，
                    // 那样回调和 !started 分支都走不到，配额就永久泄漏了
                    // （默认 maxConcurrent=1，泄漏一次 = 全服 NPC 从此不再自省）。
                    active = Math.max(0, active - 1);
                    LOG.log(java.util.logging.Level.WARNING,
                            "[AIV][AUTO] " + body.npcName() + " think 同步异常，配额已归还", throwable);
                    continue;
                }
                if (!started) {
                    // 理论上 isBusy() 已经挡住，这里只是保证配额不会泄漏。
                    active = Math.max(0, active - 1);
                }
            }
        }

        // 第二遍：模型正在思考（或还在节流等待）的 NPC 用小范围走动填满空档。
        for (FakePlayerBody body : plugin.npcs()) {
            if (body.isAlive()) {
                nudgeIdle(body, now);
            }
        }
    }

    /**
     * 空闲走动：没有目标、没有技能、附近也没有敌人时，让 NPC 自己小范围挪几步。
     *
     * <p>为什么需要它：本地模型一次推理 10-25 秒，目标本身却常常 1-2 秒就做完，
     * 中间大段时间 NPC 会像木桩一样站着，看起来就是"很呆、不动"。
     * 这里只做 3-6 格的短距离移动，不碰背包、不挖方块；模型一旦返回目标，
     * 目标层会立刻 stop 掉这次走动去执行真正的任务。
     */
    private void nudgeIdle(FakePlayerBody body, long now) {
        SkillRunner skills = plugin.skill(body.npcName());
        if (skills == null || !"idle".equals(skills.currentSkill())) {
            return;
        }
        GoalExecutor goals = plugin.goal(body.npcName());
        if (goals != null && goals.active()) {
            return;
        }
        SurvivalController survival = plugin.survival(body.npcName());
        if (survival != null && survival.isFleeing()) {
            return;
        }
        // 吃东西/格挡时不要强行迈步。
        if (body.isUsingItem()) {
            return;
        }
        // 只在"确实还在等决策"时走动：模型正在推理、被并发配额挡住、或还在节流冷却。
        // 如果模型已不可用、也没有任何决策在等，就让 NPC 安静站着，避免无意义地一直绕圈。
        VillagerBrain brain = plugin.brain(body.npcName());
        if (brain == null) {
            return;
        }
        boolean waiting = brain.isBusy() || active >= maxConcurrent;
        Long lastThink = lastThinkAt.get(body.npcName());
        if (!waiting && lastThink != null && now - lastThink < intervalMillis) {
            waiting = true;
        }
        if (!waiting) {
            return;
        }
        Long last = lastNudgeAt.get(body.npcName());
        if (last != null && now - last < NUDGE_INTERVAL_MILLIS) {
            return;
        }
        // 附近有敌对生物时交给生存层，别自己往怪脸上走。
        List<Entity> threats = body.level().getEntities(body, body.getBoundingBox().inflate(8.0D),
                entity -> entity instanceof Enemy && entity.isAlive());
        if (!threats.isEmpty()) {
            return;
        }

        lastNudgeAt.put(body.npcName(), now);
        for (int attempt = 0; attempt < 3; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2.0D;
            double distance = 3.0D + ThreadLocalRandom.current().nextDouble() * 3.0D;
            JsonObject params = new JsonObject();
            params.addProperty("x", body.getX() + Math.cos(angle) * distance);
            params.addProperty("z", body.getZ() + Math.sin(angle) * distance);
            if (skills.run(new Intent("", "goto", params), "空闲")) {
                LOG.info("[AIV][IDLE] " + body.npcName() + " 空闲走动 | "
                        + String.format(Locale.ROOT, "距离=%.1f", distance));
                return;
            }
        }
    }
}

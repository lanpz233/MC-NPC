package com.example.aivillager.command;

import com.example.aivillager.AiVillagerPlugin;
import com.example.aivillager.brain.VillagerBrain;
import com.example.aivillager.goals.GoalExecutor;
import com.example.aivillager.intent.Intent;
import com.example.aivillager.llm.LlmProvider;
import com.example.aivillager.llm.LlmRouter;
import com.example.aivillager.npc.FakePlayerBody;
import com.example.aivillager.skills.SkillRunner;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * spike 阶段的调试命令，用来在没有 LLM 的情况下逐项验证伪玩家身体能力。
 */
public final class SpikeCommand implements CommandExecutor, TabCompleter {

    private static final String NPC_NAME_PATTERN = "^[A-Za-z0-9_]{1,16}$";

    private final AiVillagerPlugin plugin;

    public SpikeCommand(AiVillagerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("aivillager.admin")) {
            sender.sendMessage("[AIV] 没有权限");
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> spawn(sender, args);
            case "walk" -> walk(sender, args);
            case "mine" -> mine(sender);
            case "attack" -> attack(sender);
            case "status" -> status(sender, args);
            case "despawn" -> despawn(sender, args);
            case "run" -> plugin.startScenario(sender);
            case "stress" -> stress(sender, args);
            case "say" -> say(sender, args);
            case "act" -> act(sender, args);
            case "brain" -> brainStatus(sender, args);
            case "llm" -> llmStatus(sender);
            case "stop" -> {
                plugin.stopScenario();
                for (FakePlayerBody body : plugin.npcs()) {
                    body.stopWalking();
                }
                sender.sendMessage("[AIV] 已停止场景与所有行走");
            }
            default -> usage(sender);
        }
        return true;
    }

    private void spawn(CommandSender sender, String[] args) {
        String name = args.length > 1 ? args[1] : "Villager_A1";
        if (!name.matches(NPC_NAME_PATTERN)) {
            sender.sendMessage("[AIV] 名字只能是 1-16 位 ASCII 字母/数字/下划线");
            return;
        }
        if (plugin.npc(name) != null) {
            sender.sendMessage("[AIV] 已存在同名伪玩家: " + name);
            return;
        }

        World world = sender instanceof Player player ? player.getWorld() : Bukkit.getWorlds().get(0);
        Location base = sender instanceof Player player ? player.getLocation() : world.getSpawnLocation();
        int x = base.getBlockX() + 2;
        int z = base.getBlockZ() + 2;
        int y = world.getHighestBlockYAt(x, z) + 1;
        // yaw = -90 面向 +X（东）。
        Location at = new Location(world, x + 0.5D, y, z + 0.5D, -90.0F, 0.0F);

        try {
            FakePlayerBody body = FakePlayerBody.spawn(((CraftWorld) world).getHandle(), name, at);
            plugin.registerNpc(body);
            sender.sendMessage("[AIV] 已生成 " + body.describe());
        } catch (Throwable throwable) {
            plugin.getLogger().severe("[AIV] 生成伪玩家失败: " + throwable);
            throwable.printStackTrace();
            sender.sendMessage("[AIV] 生成失败，详见控制台");
        }
    }

    private void walk(CommandSender sender, String[] args) {
        FakePlayerBody body = plugin.lastNpc();
        if (body == null) {
            sender.sendMessage("[AIV] 还没有伪玩家，先执行 /aivspike spawn");
            return;
        }
        int blocks = 10;
        if (args.length > 1) {
            try {
                blocks = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                sender.sendMessage("[AIV] 距离必须是整数");
                return;
            }
        }
        Vec3 target = new Vec3(body.getX() + blocks, body.getY(), body.getZ());
        body.walkTo(target, false, null);
        sender.sendMessage("[AIV] " + body.npcName() + " 开始走向 " + target);
    }

    private void mine(CommandSender sender) {
        FakePlayerBody body = plugin.lastNpc();
        if (body == null) {
            sender.sendMessage("[AIV] 还没有伪玩家");
            return;
        }
        // 默认挖脚下方块（flat 世界里就是草方块下的泥土），保证一定有方块可挖。
        BlockPos pos = BlockPos.containing(body.getX(), body.getY() - 0.5D, body.getZ());
        boolean success = body.mine(pos);
        sender.sendMessage("[AIV] mine " + pos.toShortString() + " => " + success);
    }

    private void attack(CommandSender sender) {
        FakePlayerBody body = plugin.lastNpc();
        if (body == null) {
            sender.sendMessage("[AIV] 还没有伪玩家");
            return;
        }
        Entity target = findNearestTarget(body);
        if (target == null) {
            sender.sendMessage("[AIV] 附近 4 格内没有可攻击的非玩家生物");
            return;
        }
        float damage = body.attackTarget(target);
        sender.sendMessage("[AIV] 攻击 " + target.getType() + "，伤害 " + damage);
    }

    private Entity findNearestTarget(FakePlayerBody body) {
        ServerLevel level = body.level();
        List<Entity> candidates = level.getEntities(
                body,
                body.getBoundingBox().inflate(4.0D),
                entity -> entity instanceof LivingEntity
                        && entity != body
                        && !(entity instanceof net.minecraft.world.entity.player.Player)
        );
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private void status(CommandSender sender, String[] args) {
        if (args.length > 1) {
            FakePlayerBody body = plugin.npc(args[1]);
            if (body == null) {
                sender.sendMessage("[AIV] 找不到 " + args[1]);
                return;
            }
            sender.sendMessage("[AIV] " + body.describe());
            return;
        }
        if (plugin.npcs().isEmpty()) {
            sender.sendMessage("[AIV] 当前没有伪玩家");
            return;
        }
        for (FakePlayerBody body : plugin.npcs()) {
            sender.sendMessage("[AIV] " + body.describe());
        }
    }

    private void despawn(CommandSender sender, String[] args) {
        FakePlayerBody body = args.length > 1 ? plugin.npc(args[1]) : plugin.lastNpc();
        if (body == null) {
            sender.sendMessage("[AIV] 没有可移除的伪玩家");
            return;
        }
        body.despawn();
        plugin.unregisterNpc(body);
        sender.sendMessage("[AIV] 已移除 " + body.npcName());
    }

    /** 手动和 NPC 对话：/aivspike say <内容>。控制台可以用它做无人值守验证。 */
    private void say(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("[AIV] 用法: /aivspike say <内容>");
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String listenerName = sender instanceof Player player ? player.getName() : "Console";

        FakePlayerBody body;
        if (sender instanceof Player player) {
            body = plugin.nearestNpc(player.getLocation(), plugin.chatRadius());
            if (body == null) {
                body = plugin.lastNpc();
            }
        } else {
            body = plugin.lastNpc();
        }
        if (body == null) {
            sender.sendMessage("[AIV] 还没有伪玩家，先执行 /aivspike spawn");
            return;
        }

        VillagerBrain brain = plugin.brain(body.npcName());
        if (brain == null) {
            sender.sendMessage("[AIV] " + body.npcName() + " 没有大脑实例");
            return;
        }
        boolean accepted = brain.ask(listenerName, text,
                (npcName, reply) -> sender.sendMessage(npcName + ">" + listenerName + ": " + reply));
        sender.sendMessage("[AIV] 对话请求受理=" + accepted + "（模型在后台推理，回复稍后到）");
    }

    /**
     * 绕过模型直接执行技能：/aivspike act mine block=dirt，用来单独验证技能层。
     * 参数统一写成 key=value；能解析成数字就存数字，避免 Intent 里的 getAsInt/getAsDouble 失效。
     */
    private void act(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("[AIV] 用法: /aivspike act <goto|mine|attack|follow|idle> [key=value ...]");
            return;
        }
        FakePlayerBody body = plugin.lastNpc();
        if (body == null) {
            sender.sendMessage("[AIV] 还没有伪玩家，先执行 /aivspike spawn");
            return;
        }
        SkillRunner skill = plugin.skill(body.npcName());
        if (skill == null) {
            sender.sendMessage("[AIV] " + body.npcName() + " 没有技能执行器");
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        JsonObject params = new JsonObject();
        for (int index = 2; index < args.length; index++) {
            String token = args[index];
            int separator = token.indexOf('=');
            if (separator <= 0) {
                sender.sendMessage("[AIV] 参数格式应为 key=value，收到: " + token);
                return;
            }
            String key = token.substring(0, separator);
            String value = token.substring(separator + 1);
            if (value.matches("-?\\d+(\\.\\d+)?")) {
                params.addProperty(key, Double.parseDouble(value));
            } else {
                params.addProperty(key, value);
            }
        }

        // 目标层动作与 VillagerBrain 同口径路由到 GoalExecutor（【fix106】短验工具）：
        // 否则 spike 只能验技能层，目标层机制（如追杀时限）永远造不了景。
        if (GoalExecutor.isGoalAction(action)) {
            GoalExecutor goals = plugin.goal(body.npcName());
            if (goals == null) {
                sender.sendMessage("[AIV] " + body.npcName() + " 没有目标执行器");
                return;
            }
            boolean goalAccepted = goals.start(new Intent("", action, params));
            sender.sendMessage("[AIV] act(goal) " + action + " => " + goalAccepted + " params=" + params);
            plugin.getLogger().info("[AIV][ACT] " + body.npcName() + " goal=" + action
                    + " accepted=" + goalAccepted + " params=" + params);
            return;
        }

        String listenerName = sender instanceof Player player ? player.getName() : "Console";
        boolean accepted = skill.run(new Intent("", action, params), listenerName);
        sender.sendMessage("[AIV] act " + action + " => " + accepted + " params=" + params);
        plugin.getLogger().info("[AIV][ACT] " + body.npcName() + " action=" + action
                + " accepted=" + accepted + " params=" + params);
    }

    private void brainStatus(CommandSender sender, String[] args) {
        FakePlayerBody body = args.length > 1 ? plugin.npc(args[1]) : plugin.lastNpc();
        if (body == null) {
            sender.sendMessage("[AIV] 找不到对应的伪玩家");
            return;
        }
        VillagerBrain brain = plugin.brain(body.npcName());
        sender.sendMessage("[AIV] " + (brain == null ? "没有大脑实例" : brain.status()));
    }

    private void llmStatus(CommandSender sender) {
        LlmRouter router = plugin.router();
        if (router == null || !router.isAvailable()) {
            sender.sendMessage("[AIV] 没有可用的模型后端，请检查 config.yml 的 llm 配置");
            return;
        }
        for (LlmProvider provider : router.providers()) {
            sender.sendMessage("[AIV] " + provider.id() + " | " + provider.describe());
        }
    }

    private void stress(CommandSender sender, String[] args) {
        int count = 20;
        if (args.length > 1) {
            try {
                count = Math.max(1, Math.min(80, Integer.parseInt(args[1])));
            } catch (NumberFormatException exception) {
                sender.sendMessage("[AIV] 数量必须是整数（1-80）");
                return;
            }
        }
        int seconds = 30;
        if (args.length > 2) {
            try {
                seconds = Math.max(10, Math.min(300, Integer.parseInt(args[2])));
            } catch (NumberFormatException exception) {
                sender.sendMessage("[AIV] 时长必须是整数秒（10-300）");
                return;
            }
        }
        plugin.startStress(sender, count, seconds);
    }

    private void usage(CommandSender sender) {
        sender.sendMessage("[AIV] /aivspike spawn [name] | walk <blocks> | mine | attack | status [name] | despawn [name]"
                + " | act <action> [key=value ...] | run | stress [count] [seconds] | say <内容> | brain [name] | llm | stop");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("spawn", "walk", "mine", "attack", "status", "despawn", "act", "run", "stress",
                            "say", "brain", "llm", "stop")
                    .stream()
                    .filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("act")) {
            return List.of("goto", "mine", "attack", "follow", "idle")
                    .stream()
                    .filter(option -> option.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("status") || args[0].equalsIgnoreCase("despawn"))) {
            List<String> names = new ArrayList<>();
            for (FakePlayerBody body : plugin.npcs()) {
                names.add(body.npcName());
            }
            return names.stream().filter(name -> name.startsWith(args[1])).toList();
        }
        return List.of();
    }
}

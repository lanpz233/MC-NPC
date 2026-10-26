package com.example.aivillager;

import com.example.aivillager.brain.AutonomyLoop;
import com.example.aivillager.brain.VillagerBrain;
import com.example.aivillager.command.SpikeCommand;
import com.example.aivillager.goals.GoalExecutor;
import com.example.aivillager.listener.NpcChatListener;
import com.example.aivillager.llm.LlmRouter;
import com.example.aivillager.npc.FakePlayerBody;
import com.example.aivillager.progression.ProgressionMode;
import com.example.aivillager.progression.ProgressionPlanner;
import com.example.aivillager.skills.SkillRunner;
import com.example.aivillager.spike.SpikeScenario;
import com.example.aivillager.spike.StressScenario;
import com.example.aivillager.survival.SurvivalController;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 插件入口。
 *
 * <p>当前包含两层：
 * <ul>
 *   <li>身体层：{@link FakePlayerBody}，一个真正进入 PlayerList、走原版物理的 ServerPlayer。</li>
 *   <li>大脑层：{@link VillagerBrain} + {@link LlmRouter}，本地 Ollama 优先、云端兜底。</li>
 * </ul>
 *
 * <p>线程约定：所有实体/世界访问都在主线程；LLM 调用异步，结果回主线程后再写聊天。
 */
public final class AiVillagerPlugin extends JavaPlugin {

    private final Map<String, FakePlayerBody> npcs = new LinkedHashMap<>();
    private final Map<String, VillagerBrain> brains = new LinkedHashMap<>();
    private final Map<String, SkillRunner> skills = new LinkedHashMap<>();
    private final Map<String, GoalExecutor> goals = new LinkedHashMap<>();
    private final Map<String, ProgressionPlanner> progression = new LinkedHashMap<>();
    private final Map<String, SurvivalController> survival = new LinkedHashMap<>();

    private SpikeScenario scenario;
    private StressScenario stress;
    private AutonomyLoop autonomy;
    private LlmRouter router;
    private double chatRadius = 16.0D;
    private int memoryTurns = 8;
    private boolean survivalEnabled = true;
    private int survivalEatThreshold = 14;
    private double survivalDefendRadius = 8.0D;
    /** 发展层开关：开着时由确定性规划器推进科技树，LLM 只负责聊天。 */
    private boolean progressionEnabled = true;
    /** 阶段模式全局默认（config: progression.default-mode）。 */
    private ProgressionMode defaultProgressionMode = ProgressionMode.SPEEDRUN;
    /** 按 NPC 名覆盖的阶段模式（config: progression.modes.<NPC 名>），键统一小写匹配。 */
    private final Map<String, ProgressionMode> npcProgressionModes = new HashMap<>();
    /** 按 NPC 名的自定义阶段计划原文（config: progression.plans.<NPC 名>，逗号分隔的阶段 id），键统一小写匹配。 */
    private final Map<String, java.util.List<String>> npcCustomPlans = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // 【fix40】记矿账本落盘目录（ore-memory/<NPC>.json）。
        com.example.aivillager.goals.OreMemory.init(getDataFolder());

        router = LlmRouter.fromConfig(this);
        chatRadius = getConfig().getDouble("npc.chat-radius", 16.0D);
        memoryTurns = Math.max(1, getConfig().getInt("npc.memory-turns", 8));
        survivalEnabled = getConfig().getBoolean("survival.enabled", true);
        survivalEatThreshold = Math.max(1, Math.min(20, getConfig().getInt("survival.eat-threshold", 14)));
        survivalDefendRadius = Math.max(2.0D, Math.min(16.0D, getConfig().getDouble("survival.defend-radius", 8.0D)));
        // 默认开启：没有它 NPC 只会被 LLM 随机指挥，永远走不到钻石装备。
        progressionEnabled = getConfig().getBoolean("progression.enabled", true);
        // 阶段模式：全局默认 + 按 NPC 名覆盖。后续"玩家改某个 NPC 的目标"也走这张表：
        // 运行时改表并重建该 NPC 的规划器即可，规划器本身不关心模式是谁设的。
        defaultProgressionMode = ProgressionMode.parse(
                getConfig().getString("progression.default-mode", "speedrun"));
        org.bukkit.configuration.ConfigurationSection modesSection =
                getConfig().getConfigurationSection("progression.modes");
        if (modesSection != null) {
            for (String key : modesSection.getKeys(false)) {
                npcProgressionModes.put(key.toLowerCase(java.util.Locale.ROOT),
                        ProgressionMode.parse(modesSection.getString(key)));
            }
        }
        getLogger().info("阶段模式配置 | 默认=" + defaultProgressionMode.configKey()
                + "（" + defaultProgressionMode.displayName() + "） 按NPC覆盖=" + npcProgressionModes);
        // 阶段级自定义计划：progression.plans.<NPC 名> = "id,id,..."（逗号分隔的阶段 id）。
        // 这里只收原文，解析与前置校验在 planForNpc 里做（每个 NPC 注册时各打一次明确日志）。
        org.bukkit.configuration.ConfigurationSection plansSection =
                getConfig().getConfigurationSection("progression.plans");
        if (plansSection != null) {
            for (String key : plansSection.getKeys(false)) {
                String raw = plansSection.getString(key, "");
                java.util.List<String> ids = new java.util.ArrayList<>();
                for (String part : raw.split(",")) {
                    String id = part.trim();
                    if (!id.isEmpty()) {
                        ids.add(id);
                    }
                }
                npcCustomPlans.put(key.toLowerCase(java.util.Locale.ROOT), ids);
            }
        }
        getLogger().info("阶段自定义配置 | 按NPC计划=" + npcCustomPlans);

        PluginCommand command = getCommand("aivspike");
        if (command != null) {
            SpikeCommand executor = new SpikeCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().severe("plugin.yml 未注册 aivspike 命令");
        }

        Bukkit.getPluginManager().registerEvents(new NpcChatListener(this), this);
        getLogger().info("AIVillager enabled | server=" + Bukkit.getVersion() + " chatRadius=" + chatRadius);

        // 无人值守验证：身体层 spike。
        if (getConfig().getBoolean("auto-spike", false)) {
            int delay = Math.max(20, getConfig().getInt("auto-spike-delay-ticks", 60));
            Bukkit.getScheduler().runTaskLater(this, () -> startScenario(Bukkit.getConsoleSender()), delay);
        }

        // 规模验证：spike 之后单独起一次压力测试，回答"50+ 伪玩家是否可行"。
        int stressCount = getConfig().getInt("auto-stress-count", 0);
        if (stressCount > 0) {
            int delay = Math.max(20, getConfig().getInt("auto-stress-delay-ticks", 600));
            int seconds = Math.max(10, getConfig().getInt("auto-stress-seconds", 30));
            Bukkit.getScheduler().runTaskLater(this,
                    () -> startStress(Bukkit.getConsoleSender(), stressCount, seconds), delay);
        }

        // 对话闭环验证：常驻一个 NPC，可选地自动提一个问题。
        if (getConfig().getBoolean("auto-npc", false)) {
            int delay = Math.max(20, getConfig().getInt("auto-npc-delay-ticks", 60));
            Bukkit.getScheduler().runTaskLater(this, this::spawnDefaultNpc, delay);
        }
        String autoAskText = getConfig().getString("auto-ask-text", "");
        if (autoAskText != null && !autoAskText.isBlank()) {
            int delay = Math.max(20, getConfig().getInt("auto-ask-delay-ticks", 120));
            int repeat = Math.max(1, getConfig().getInt("auto-ask-repeat", 1));
            // 间隔必须大于一次推理耗时，否则第二次会被 inFlight 保护直接丢弃。
            int interval = Math.max(60, getConfig().getInt("auto-ask-interval-ticks", 240));
            for (int index = 0; index < repeat; index++) {
                long at = delay + (long) index * interval;
                Bukkit.getScheduler().runTaskLater(this, () -> autoAsk(autoAskText), at);
            }
        }

        // 自主决策：没人说话时 NPC 也会定期自省并行动。
        if (getConfig().getBoolean("autonomy.enabled", false)) {
            // 下限 2 秒：目标通常 1-2 秒结束，留太长的间隔只会让 NPC 干站着。
            int interval = Math.max(2, getConfig().getInt("autonomy.interval-seconds", 5));
            int maxConcurrent = Math.max(1, getConfig().getInt("autonomy.max-concurrent", 1));
            autonomy = new AutonomyLoop(this, interval, maxConcurrent);
            autonomy.start();
        }

        // 背包持久化：伪玩家不在离线玩家存档里，重启后 placeNewPlayer 拿不到背包，
        // 不存的话每次重启科技树都从零开始。30 秒一次 + 关服时各存一遍，崩溃最多丢 30 秒收入。
        Bukkit.getScheduler().runTaskTimer(this, this::saveAllNpcInventories, 600L, 600L);
    }

    @Override
    public void onDisable() {
        stopScenario();

        if (autonomy != null) {
            autonomy.stop();
            autonomy = null;
        }

        // 先停发展层：它会在目标结束后立刻下新目标，必须在目标层之前停。
        for (ProgressionPlanner planner : progression.values()) {
            planner.stop();
        }
        progression.clear();

        // 再停目标层：它的 tick 会驱动技能，必须在技能停之前停。
        for (GoalExecutor goal : goals.values()) {
            goal.stop();
        }
        goals.clear();

        // 先停生存层，再停技能：生存层会追着 skills 的状态做决策，反向可能出现空转。
        for (SurvivalController controller : survival.values()) {
            controller.stop();
        }
        survival.clear();

        // 先停技能，避免技能里的周期任务在实体移除后还继续跑。
        for (SkillRunner skill : skills.values()) {
            skill.stop();
        }
        skills.clear();

        for (FakePlayerBody body : new ArrayList<>(npcs.values())) {
            try {
                // 先存背包再下线：despawn 之后实体里的物品就再也读不到了。
                saveNpcInventory(body);
                body.stopWalking();
                body.despawn();
            } catch (Throwable throwable) {
                getLogger().warning("清理伪玩家失败: " + body.npcName() + " -> " + throwable);
            }
        }
        npcs.clear();
        brains.clear();
        getLogger().info("AIVillager disabled");
    }

    public void registerNpc(FakePlayerBody body) {
        // 同名重注册时先清掉旧的生存层/目标层，否则会残留一个操作已移除实体的周期任务。
        SurvivalController previous = survival.remove(body.npcName());
        if (previous != null) {
            previous.stop();
        }
        GoalExecutor previousGoal = goals.remove(body.npcName());
        if (previousGoal != null) {
            previousGoal.stop();
        }
        ProgressionPlanner previousPlanner = progression.remove(body.npcName());
        if (previousPlanner != null) {
            previousPlanner.stop();
        }
        npcs.remove(body.npcName());
        npcs.put(body.npcName(), body);

        // 目标层用已有的技能执行器；它在技能空闲时推进目标，不依赖模型。
        GoalExecutor goalExecutor = new GoalExecutor(this, body, skill(body.npcName()));
        goalExecutor.start();
        goals.put(body.npcName(), goalExecutor);

        // 发展层必须拿到同一个目标层实例：它只负责"下一步做什么"，执行仍归目标层。
        if (progressionEnabled) {
            ProgressionMode mode = modeForNpc(body.npcName());
            ProgressionPlanner planner = new ProgressionPlanner(this, body, skill(body.npcName()),
                    goalExecutor, mode, planForNpc(body.npcName(), mode));
            planner.start();
            progression.put(body.npcName(), planner);
        }

        if (survivalEnabled) {
            SurvivalController controller = new SurvivalController(
                    this, body, skill(body.npcName()), survivalEatThreshold, survivalDefendRadius);
            controller.start();
            survival.put(body.npcName(), controller);
        }
    }

    public void unregisterNpc(FakePlayerBody body) {
        npcs.remove(body.npcName());
        brains.remove(body.npcName());
        ProgressionPlanner planner = progression.remove(body.npcName());
        if (planner != null) {
            planner.stop();
        }
        GoalExecutor goalExecutor = goals.remove(body.npcName());
        if (goalExecutor != null) {
            goalExecutor.stop();
        }
        SurvivalController controller = survival.remove(body.npcName());
        if (controller != null) {
            controller.stop();
        }
        SkillRunner skill = skills.remove(body.npcName());
        if (skill != null) {
            skill.stop();
        }
    }

    public FakePlayerBody npc(String name) {
        return npcs.get(name);
    }

    public FakePlayerBody lastNpc() {
        FakePlayerBody last = null;
        for (FakePlayerBody body : npcs.values()) {
            last = body;
        }
        return last;
    }

    public Collection<FakePlayerBody> npcs() {
        return new ArrayList<>(npcs.values());
    }

    /** 取（并按需创建）某个 NPC 的大脑。 */
    public VillagerBrain brain(String name) {
        FakePlayerBody body = npcs.get(name);
        if (body == null) {
            return null;
        }
        return brains.computeIfAbsent(name, key ->
                new VillagerBrain(this, body, router, memoryTurns, skill(name), goal(name)));
    }

    /** 取（并按需创建）某个 NPC 的目标执行器。 */
    public GoalExecutor goal(String name) {
        FakePlayerBody body = npcs.get(name);
        if (body == null) {
            return null;
        }
        return goals.computeIfAbsent(name, key -> new GoalExecutor(this, body, skill(name)));
    }

    /** 取某个 NPC 的生存层（可能未启用），目标层用它判断是否正在逃跑。 */
    public SurvivalController survival(String name) {
        return survival.get(name);
    }

    /** 取某个 NPC 的发展层（可能未启用）。 */
    public ProgressionPlanner progression(String name) {
        return progression.get(name);
    }

    /** 发展层是否启用：启用时自主决策由规划器负责，LLM 不再下目标。 */
    /**
     * 查某个 NPC 的阶段模式：先按名字（不分大小写）查覆盖表，没有就用全局默认。
     * 这是"每个 NPC 目标"的唯一入口——config、将来的游戏内命令/GUI 都改这一层。
     */
    public ProgressionMode modeForNpc(String npcName) {
        if (npcName != null) {
            ProgressionMode mode = npcProgressionModes.get(npcName.toLowerCase(java.util.Locale.ROOT));
            if (mode != null) {
                return mode;
            }
        }
        return defaultProgressionMode;
    }

    /**
     * 查某个 NPC 的有效阶段计划：config 里配了自定义计划（progression.plans）就解析它
     * （含前置依赖校验，缺前置的阶段会被禁用并打 plan-invalid 日志），没配就返回 null
     * 表示用模式预设。这是"每个阶段都可以自定义"的唯一入口，与 modeForNpc 同层：
     * 将来游戏内改目标也改这两张表 + 重建规划器。
     */
    public java.util.List<com.example.aivillager.progression.StagePlan.StageDef> planForNpc(
            String npcName, ProgressionMode mode) {
        if (npcName == null) {
            return null;
        }
        java.util.List<String> raw = npcCustomPlans.get(npcName.toLowerCase(java.util.Locale.ROOT));
        if (raw == null) {
            return null;
        }
        return com.example.aivillager.progression.StagePlan.resolvePlan(raw, getLogger(), npcName);
    }

    public boolean progressionEnabled() {
        return progressionEnabled;
    }

    /** 取（并按需创建）某个 NPC 的技能执行器；每个 NPC 只有一个，保证动作互斥。 */
    public SkillRunner skill(String name) {
        FakePlayerBody body = npcs.get(name);
        if (body == null) {
            return null;
        }
        return skills.computeIfAbsent(name, key -> new SkillRunner(this, body));
    }

    public LlmRouter router() {
        return router;
    }

    public double chatRadius() {
        return chatRadius;
    }

    /** 找离某点最近、且在聊天半径内的 NPC。 */
    public FakePlayerBody nearestNpc(Location location, double radius) {
        double bestDistanceSq = radius * radius;
        FakePlayerBody best = null;
        for (FakePlayerBody body : npcs.values()) {
            if (body.isRemoved() || !body.isAlive()) {
                continue;
            }
            // 不同维度直接跳过，避免把坐标当成同世界距离。
            if (location.getWorld() == null || !body.level().dimension().equals(
                    ((CraftWorld) location.getWorld()).getHandle().dimension())) {
                continue;
            }
            double dx = body.getX() - location.getX();
            double dy = body.getY() - location.getY();
            double dz = body.getZ() - location.getZ();
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq <= bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = body;
            }
        }
        return best;
    }

    /** 玩家聊天：交给最近的 NPC，回复广播给所有玩家（和真人聊天一致）。 */
    public void handlePlayerChat(Player player, String text) {
        FakePlayerBody npc = nearestNpc(player.getLocation(), chatRadius);
        if (npc == null) {
            getLogger().info("[AIV][CHAT] 玩家 " + player.getName() + " 附近 " + chatRadius + " 格内没有 NPC，忽略");
            return;
        }
        VillagerBrain brain = brain(npc.npcName());
        if (brain == null) {
            return;
        }
        brain.ask(player.getName(), text,
                (npcName, reply) -> broadcastNpcChat(npcName, player.getName(), reply));
    }

    /** 统一格式：说话者>听者。控制台同一行由日志打出，游戏内广播带颜色。 */
    private void broadcastNpcChat(String npcName, String listenerName, String reply) {
        Component message = Component.text(npcName + ">" + listenerName + ": ", NamedTextColor.YELLOW)
                .append(Component.text(reply, NamedTextColor.WHITE));
        Bukkit.broadcast(message);
    }

    /** 在世界出生点附近生成一个常驻 NPC（auto-npc 与手动测试共用）。 */
    public FakePlayerBody spawnDefaultNpc() {
        World world = Bukkit.getWorlds().get(0);
        Location base = world.getSpawnLocation();
        int x = base.getBlockX() + 2;
        int z = base.getBlockZ() + 2;
        int y = world.getHighestBlockYAt(x, z) + 1;
        Location at = new Location(world, x + 0.5D, y, z + 0.5D, -90.0F, 0.0F);
        try {
            FakePlayerBody body = FakePlayerBody.spawn(((CraftWorld) world).getHandle(), "Villager_A1", at);
            // 先把上次关服时存下的背包塞回去，再启动各层：规划器第一眼就该看到真实资产，
            // 否则它会先下"砍木头做工作台"，等背包恢复又要重排一次。
            loadNpcInventory(body);
            registerNpc(body);
            getLogger().info("[AIV] auto-npc 生成完成 | " + body.describe());
            return body;
        } catch (Throwable throwable) {
            getLogger().severe("[AIV] auto-npc 生成失败: " + throwable);
            throwable.printStackTrace();
            return null;
        }
    }

    /** 无人值守对话验证：用 Console 身份提问，回复打到控制台。 */
    private void autoAsk(String text) {
        FakePlayerBody npc = lastNpc();
        if (npc == null) {
            getLogger().warning("[AIV][AUTO-ASK] 没有伪玩家，跳过自动提问");
            return;
        }
        VillagerBrain brain = brain(npc.npcName());
        if (brain == null) {
            return;
        }
        boolean accepted = brain.ask("Console", text,
                (npcName, reply) -> Bukkit.getConsoleSender().sendMessage(npcName + ">Console: " + reply));
        getLogger().info("[AIV][AUTO-ASK] 自动提问受理=" + accepted + " text=" + text);
    }

    /** 背包持久化用的世界 PDC 键；名字里的 UUID 是稳定的（离线 UUID 按名字算）。 */
    private NamespacedKey npcInventoryKey(String name) {
        return new NamespacedKey(this, "npc_inv_" + name.toLowerCase(Locale.ROOT));
    }

    private void saveAllNpcInventories() {
        for (FakePlayerBody body : npcs.values()) {
            saveNpcInventory(body);
        }
    }

    /**
     * 把伪玩家背包序列化进世界 PDC。
     *
     * <p>为什么不用原版 playerdata：伪玩家是用 {@code placeNewPlayer} 直接塞进 PlayerList 的，
     * 实测重启后拿不到之前的背包（新建的 ServerPlayer 是空的），科技树每次重启都从零开始。
     * 世界 PDC 会随 level.dat 一起保存，正好用来跨重启存这一份状态。
     */
    private void saveNpcInventory(FakePlayerBody body) {
        // 死亡状态下背包已经掉光了：这时候存会把空背包写进去，把之前的好档覆盖掉。
        if (!body.isAlive()) {
            return;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
                out.writeObject(body.getBukkitEntity().getInventory().getContents());
            }
            String encoded = Base64.getEncoder().encodeToString(bytes.toByteArray());
            body.getBukkitEntity().getWorld().getPersistentDataContainer()
                    .set(npcInventoryKey(body.npcName()), PersistentDataType.STRING, encoded);
        } catch (Throwable throwable) {
            getLogger().warning("[AIV] 背包保存失败 " + body.npcName() + " -> " + throwable);
        }
    }

    /** 读回上次保存的背包；没有存档时保持原版空背包。 */
    private void loadNpcInventory(FakePlayerBody body) {
        String encoded = body.getBukkitEntity().getWorld().getPersistentDataContainer()
                .get(npcInventoryKey(body.npcName()), PersistentDataType.STRING);
        if (encoded == null || encoded.isEmpty()) {
            getLogger().info("[AIV] " + body.npcName() + " 没有背包存档，从空背包开始");
            return;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encoded);
            try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(raw))) {
                ItemStack[] items = (ItemStack[]) in.readObject();
                body.getBukkitEntity().getInventory().setContents(items);
            }
            getLogger().info("[AIV] " + body.npcName() + " 已恢复背包存档");
        } catch (Throwable throwable) {
            getLogger().warning("[AIV] 背包恢复失败 " + body.npcName() + " -> " + throwable);
        }
    }

    public void startScenario(CommandSender sender) {
        stopScenario();
        scenario = new SpikeScenario(this, sender);
        scenario.start();
    }

    public void startStress(CommandSender sender, int count, int seconds) {
        stopScenario();
        stress = new StressScenario(this, sender);
        stress.start(count, seconds);
    }

    public void stopScenario() {
        if (scenario != null) {
            scenario.cancel();
            scenario = null;
        }
        if (stress != null) {
            stress.cancel();
            stress = null;
        }
    }
}

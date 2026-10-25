package com.example.aivillager.brain;

import com.example.aivillager.AiVillagerPlugin;
import com.example.aivillager.goals.GoalExecutor;
import com.example.aivillager.intent.Intent;
import com.example.aivillager.intent.IntentParser;
import com.example.aivillager.llm.LlmMessage;
import com.example.aivillager.llm.LlmResult;
import com.example.aivillager.llm.LlmRouter;
import com.example.aivillager.npc.FakePlayerBody;
import com.example.aivillager.skills.SkillRunner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * 单个 AI 村民的大脑：把身体状态 + 最近对话整理成提示词，交给 {@link LlmRouter} 异步推理。
 *
 * <p>线程模型：
 * <ol>
 *   <li>{@link #ask} 必须在主线程调用（要读实体/背包/周围环境）。</li>
 *   <li>HTTP 推理在异步线程完成。</li>
 *   <li>回调切回主线程后才写游戏聊天，保证不并发改世界状态。</li>
 * </ol>
 *
 * <p>每个 NPC 同时只处理一条输入：模型慢（本地 9B 约 3-8 秒），排队会越积越多，
 * 因此新输入在思考期间直接丢弃并记日志，而不是堆一个无上限的队列。
 */
public final class VillagerBrain {

    private static final Logger LOG = Logger.getLogger("AIVillager");

    /** 回复硬上限，防止模型输出长段落刷屏。 */
    private static final int MAX_REPLY_CHARS = 120;
    /** 附近环境最多列几个实体，避免提示词膨胀。 */
    private static final int MAX_NEARBY = 5;
    /** 附近方块统计半径，让模型知道"这里到底有什么可挖"，而不是凭空想象石头。 */
    private static final int NEARBY_BLOCK_RADIUS = 3;

    // 存具体插件类型而不是 Plugin：status 摘要要读发展层，Plugin 接口上没有这个方法。
    private final AiVillagerPlugin plugin;
    private final FakePlayerBody body;
    private final LlmRouter router;
    private final int memoryTurns;
    private final SkillRunner skills;
    private final GoalExecutor goals;
    private final Deque<LlmMessage> history = new ArrayDeque<>();

    private boolean inFlight;
    /** 当前这次推理是自主自省，而不是玩家对话。玩家开口时可以抢占它。 */
    private boolean autoInFlight;
    /** 回合号：被抢占的旧推理结果回来时直接丢弃，避免执行过时动作。 */
    private int turnId;
    private long lastLatencyMs = -1L;
    private String lastProvider = "-";
    /** 连续选择 idle 的次数：本地 9B 实测会反复"歇会儿"，需要一个打破循环的机制。 */
    private int idleStreak;

    public VillagerBrain(AiVillagerPlugin plugin, FakePlayerBody body, LlmRouter router,
                         int memoryTurns, SkillRunner skills, GoalExecutor goals) {
        this.plugin = plugin;
        this.body = body;
        this.router = router;
        this.memoryTurns = Math.max(1, memoryTurns);
        this.skills = skills;
        this.goals = goals;
    }

    public FakePlayerBody body() {
        return body;
    }

    /**
     * 处理一句话。
     *
     * @param listenerName 说话的人（玩家名或 Console）
     * @param text         原话
     * @param onReply      模型成功后的回调，参数为 (npcName, 回复文本)，在主线程执行
     * @return 是否受理（无可用模型 / 正在思考都会返回 false）
     */
    public boolean ask(String listenerName, String text, BiConsumer<String, String> onReply) {
        if (!router.isAvailable()) {
            LOG.warning("[AIV][BRAIN] " + body.npcName() + " 没有可用模型，忽略来自 " + listenerName + " 的话");
            return false;
        }
        // 玩家对话优先于自主自省：自省期间玩家开口，直接开始这一轮对话，
        // 并让 turnId 作废旧的自省结果，避免它稍后回来又执行一个过时的动作。
        if (inFlight && !autoInFlight) {
            LOG.info("[AIV][BRAIN] " + body.npcName() + " 正在思考，丢弃本次输入 | from=" + listenerName + " text=" + text);
            return false;
        }
        int myTurn = ++turnId;
        inFlight = true;
        autoInFlight = false;

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(buildSystemPrompt(listenerName, false)));
        messages.addAll(history);
        messages.add(LlmMessage.user("玩家 " + listenerName + " 对你说：" + text));

        LOG.info("[AIV][BRAIN] " + body.npcName() + " 受理输入 | from=" + listenerName
                + " text=" + text + " history=" + history.size() + " promptChars=" + promptLength(messages));

        router.complete(messages).whenComplete((result, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (myTurn != turnId) {
                        LOG.info("[AIV][BRAIN] " + body.npcName() + " 对话结果已被更新的回合取代，丢弃");
                        return;
                    }
                    inFlight = false;
                    if (error != null) {
                        // 不生成兜底台词：模型全链路失败时 NPC 保持沉默，只记日志。
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        LOG.severe("[AIV][BRAIN] " + body.npcName() + " 推理失败: "
                                + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                        return;
                    }
                    handleResult(listenerName, text, result, onReply);
                }));
        return true;
    }

    /**
     * 自主模式：没有玩家输入时，NPC 自己根据状态做一次决策。
     *
     * <p>和 {@link #ask} 的区别：不写对话历史、不广播台词，只把模型给的意图交给技能层。
     * {@code onFinished} 一定在主线程执行，供全局调度器释放并发配额。
     *
     * @return 是否真正发起了推理（已有请求在飞或模型不可用会返回 false）
     */
    public boolean think(Runnable onFinished) {
        if (!router.isAvailable() || inFlight) {
            return false;
        }
        int myTurn = ++turnId;
        inFlight = true;
        autoInFlight = true;

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(buildSystemPrompt("Steve", true)));
        messages.add(LlmMessage.user("现在没有人对你说话。根据当前状态给自己定一个下一步目标；有危险或确实没事可做时才用 idle。"));

        LOG.info("[AIV][AUTO] " + body.npcName() + " 自省开始 | promptChars=" + promptLength(messages));

        router.complete(messages).whenComplete((result, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (myTurn != turnId) {
                        LOG.info("[AIV][AUTO] " + body.npcName() + " 自省结果已被玩家对话抢占，丢弃");
                        onFinished.run();
                        return;
                    }
                    inFlight = false;
                    autoInFlight = false;
                    try {
                        if (error != null) {
                            Throwable cause = error.getCause() == null ? error : error.getCause();
                            LOG.severe("[AIV][AUTO] " + body.npcName() + " 自省失败: "
                                    + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                            return;
                        }
                        handleAutonomous(result);
                    } finally {
                        onFinished.run();
                    }
                }));
        return true;
    }

    private void handleResult(String listenerName, String text, LlmResult result, BiConsumer<String, String> onReply) {
        // 模型输出的是"决策"：say 是要说的话，action/params 是要做的事。
        // 解析失败会退化成纯说话，所以这里即使不是 JSON 也仍能正常聊天。
        Intent intent = IntentParser.parse(result.text());
        String reply = sanitize(intent.say());
        lastProvider = result.providerId();
        lastLatencyMs = result.latencyMs();

        if (reply.isEmpty()) {
            LOG.warning("[AIV][BRAIN] " + body.npcName() + " 回复清洗后为空 | raw=" + result.text());
        } else {
            remember(listenerName, text, reply);
            LOG.info("[AIV][CHAT] " + body.npcName() + ">" + listenerName + ": " + reply
                    + " | provider=" + result.providerId() + " 耗时=" + result.latencyMs() + "ms");
            onReply.accept(body.npcName(), reply);
        }

        // 说话和动作分离：即使台词被清洗为空，只要模型给了合法动作也照常执行。
        if (GoalExecutor.isGoalAction(intent.action())) {
            // gather/craft/build/explore 是中期目标，交给目标层拆成多步执行。
            boolean accepted = goals.start(intent);
            LOG.info("[AIV][GOAL] " + body.npcName() + " 目标执行 | action=" + intent.action()
                    + " accepted=" + accepted + " params=" + intent.params());
        } else if (!"none".equals(intent.action())) {
            boolean accepted = skills.run(intent, listenerName);
            LOG.info("[AIV][SKILL] " + body.npcName() + " 意图执行 | action=" + intent.action()
                    + " accepted=" + accepted + " params=" + intent.params());
        }
    }

    /** 自主决策的结果处理：话只进控制台，动作照常交给技能层。 */
    private void handleAutonomous(LlmResult result) {
        Intent intent = IntentParser.parse(result.text());
        lastProvider = result.providerId();
        lastLatencyMs = result.latencyMs();

        String say = sanitize(intent.say());
        if (!say.isEmpty()) {
            LOG.info("[AIV][AUTO] " + body.npcName() + " 自述: " + say
                    + " | provider=" + result.providerId() + " 耗时=" + result.latencyMs() + "ms");
        }
        if ("none".equals(intent.action()) || "idle".equals(intent.action())) {
            LOG.info("[AIV][AUTO] " + body.npcName() + " 自主决策: 暂不行动 | action=" + intent.action()
                    + " idleStreak=" + idleStreak);
            // 本地模型经常连续输出 idle，NPC 会一直站着不动。
            // 连续两次 idle 就自己挑个附近的点走一趟：既能立刻看到行为，也算探索周围。
            if (++idleStreak >= 2) {
                idleStreak = 0;
                exploreNearby();
            }
            return;
        }
        idleStreak = 0;
        if (GoalExecutor.isGoalAction(intent.action())) {
            // 目标层会自己拆步骤、统计进度，完成之前不再打扰模型。
            boolean accepted = goals.start(intent);
            LOG.info("[AIV][AUTO] " + body.npcName() + " 自主决策: 目标=" + intent.action()
                    + " accepted=" + accepted + " params=" + intent.params());
            return;
        }
        boolean accepted = skills.run(intent, "自主");
        LOG.info("[AIV][AUTO] " + body.npcName() + " 自主决策: action=" + intent.action()
                + " accepted=" + accepted + " params=" + intent.params());
    }

    /**
     * 连续 idle 时自己找个附近坐标走过去。
     * 距离控制在 6–12 格：太近看不出移动，太远容易一脚踩进洞穴/断崖导致路径中断。
     */
    private void exploreNearby() {
        double angle = java.util.concurrent.ThreadLocalRandom.current().nextDouble() * Math.PI * 2.0D;
        double distance = 6.0D + java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 6.0D;
        double x = body.getX() + Math.cos(angle) * distance;
        double z = body.getZ() + Math.sin(angle) * distance;
        com.google.gson.JsonObject params = new com.google.gson.JsonObject();
        params.addProperty("x", x);
        params.addProperty("z", z);
        boolean accepted = skills.run(new Intent("", "goto", params), "自主");
        LOG.info("[AIV][AUTO] " + body.npcName() + " 连续 idle，自己走走 | x=" + Math.round(x)
                + " z=" + Math.round(z) + " accepted=" + accepted);
    }

    private void remember(String listenerName, String text, String reply) {
        history.addLast(LlmMessage.user("玩家 " + listenerName + " 对你说：" + text));
        history.addLast(LlmMessage.assistant(reply));
        // 只保留最近 N 轮，控制提示词长度（本地 9B 上下文有限，长历史会拖慢首 token）。
        while (history.size() > memoryTurns * 2) {
            history.removeFirst();
        }
    }

    /** 把模型输出清洗成"能直接进聊天框"的一句话：去换行、去引号、去代码块、限长。 */
    private static String sanitize(String raw) {
        String text = raw.replace("\r", " ").replace("\n", " ").replace("`", "");
        text = text.replaceAll("\\s{2,}", " ").trim();
        // 模型有时会把名字当成前缀写出来，统一由聊天层拼 说话者>听者，这里去掉。
        text = text.replaceAll("^[\\p{L}\\p{N}_]{1,16}\\s*[：:]\\s*", "");
        text = text.replaceAll("^[\"'“”]+|[\"'“”]+$", "").trim();
        // 硬约束：玩家可见聊天框里绝不能出现代码/JSON 片段。发现就直接丢弃这条回复。
        if (looksLikeCode(text)) {
            LOG.warning("[AIV][BRAIN] " + "回复疑似包含代码/JSON，已丢弃: " + raw);
            return "";
        }
        if (text.length() > MAX_REPLY_CHARS) {
            text = text.substring(0, MAX_REPLY_CHARS);
        }
        return text;
    }

    private static boolean looksLikeCode(String text) {
        return text.indexOf('{') >= 0
                || text.indexOf('}') >= 0
                || text.contains("()")
                || text.contains("=>")
                || text.contains("</");
    }

    /**
     * 提示词按"决策协议"写：模型必须只吐一个 JSON，聊天层只取 say，技能层消费 action/params。
     * 示例里把真实名字和听者名填进去，小模型更容易照抄格式。
     */
    private String buildSystemPrompt(String listenerName, boolean autonomous) {
        StringBuilder prompt = new StringBuilder(640);
        prompt.append("你是 Minecraft 里的村民「").append(body.npcName())
                .append("」，像玩家一样生活：走动、采集、合成、建造、战斗。\n");
        prompt.append("只输出一个 JSON 对象，不要输出 JSON 以外的文字，不要用 markdown。格式：\n");
        prompt.append("{\"say\":\"台词\",\"action\":\"动作\",\"params\":{}}\n");
        // 9B 模型很容易“嘴上说要去砍树，action 却填 none”，这条硬规则必须放在动作列表之前。
        prompt.append("硬规则：say 里说了要做什么，action 就必须是对应动作，不能填 none。\n");
        prompt.append("动作：\n");
        prompt.append("- gather：采集，params={\"block\":\"*_log 或附近方块ID\",\"count\":8}。"
                + "block 写 *_log 表示任意树（推荐），技能层自动砍最近的树，不要指定 oak_log 之类的具体树种。\n");
        prompt.append("- craft：合成，params={\"item\":\"任意原版物品ID，如 planks|stick|crafting_table|"
                + "wooden_pickaxe|stone_pickaxe|bamboo_planks|furnace|torch\",\"count\":1}。\n");
        prompt.append("  配方来自服务端完整配方表，中间材料会自动补齐（原木→木板→木棍、竹子→竹块→竹木板），"
                + "不用自己先做中间产物。\n");
        prompt.append("- build：盖 3x3 小屋，params={\"structure\":\"shelter\"}（背包需 25 个建筑方块）。\n");
        prompt.append("- explore：远行，params={\"distance\":32}。\n");
        prompt.append("- mine：挖单个方块，params={\"block\":\"附近方块ID\"}；block 必须在「附近方块」里。\n");
        prompt.append("- attack：打敌对生物，params={\"hits\":3}。\n");
        prompt.append("- goto：走过去，params={\"x\":0,\"z\":0}。\n");
        prompt.append("- idle：没危险且没事可做时待命；none：只说话不行动。\n");
        if (autonomous) {
            prompt.append("自主模式：每轮必须推进发展，按 gather→craft→build/explore 走，不能只说不做；"
                    + "目标进行中不要重复下目标，进度看「当前目标」。只有生命危险或确实无事可做才 idle。\n");
            // 小模型不会自己算配方数量，经常拿着 2 块竹木板就要做工作台，白白浪费一轮。
            prompt.append("合成前先核对材料：工作台=4 木板，木棍=2 木板，木镐=3 木板+2 木棍，"
                    + "石镐=3 圆石+2 木棍；竹子要 9 个才够 1 个竹块→2 块竹木板。材料不够就先 gather。\n");
            prompt.append("示例：{\"say\":\"先去砍点木头。\",\"action\":\"gather\",\"params\":{\"block\":\"*_log\",\"count\":8}}\n");
            prompt.append("示例：{\"say\":\"木头够了，做把木镐。\",\"action\":\"craft\",\"params\":{\"item\":\"wooden_pickaxe\"}}\n");
        } else {
            prompt.append("对话模式：对方没要求你做事时 action 用 none；说了要做的事就立刻执行。\n");
            prompt.append("follow 动作：params={\"player\":\"").append(listenerName).append("\"}。\n");
        }
        prompt.append("说话规则：名字是「").append(body.npcName())
                .append("」；中文 1-2 句，最多 60 字；不要代码、JSON、旁白；不要说自己是 AI。\n");
        prompt.append("当前状态：").append(stateSummary()).append('\n');
        return prompt.toString();
    }

    /** 最小感知：生命、饥饿、昼夜、位置、背包、周围 8 格内的生物。 */
    private String stateSummary() {
        StringBuilder state = new StringBuilder(200);
        state.append(String.format(Locale.ROOT, "生命 %.0f/20，饥饿 %d/20",
                body.getHealth(), body.getFoodData().getFoodLevel()));

        long dayTime = body.level().getDayTime() % 24000L;
        state.append(dayTime < 12300L ? "，现在是白天" : "，现在是夜晚");
        state.append(String.format(Locale.ROOT, "，位置 (%.0f, %.0f, %.0f)", body.getX(), body.getY(), body.getZ()));

        String inventory = inventorySummary();
        state.append("，背包：").append(inventory);
        state.append("，当前动作：").append(skills.currentSkill());
        // 目标进度必须回灌：否则模型不知道 NPC 正在砍木头，会每隔一轮重新下一个相同目标。
        state.append("，当前目标：").append(goals.status());
        state.append("，上一目标：").append(goals.lastOutcome());
        // 发展层的阶段也回灌：玩家问"你在干嘛"时，模型能说出正在推进的科技树步骤。
        com.example.aivillager.progression.ProgressionPlanner planner = plugin.progression(body.npcName());
        if (planner != null) {
            state.append("，发展阶段：").append(planner.stage());
        }
        // 上一件事的结果必须回灌，否则模型会反复尝试"挖石头"这种当前环境做不到的动作。
        state.append("，上一件事：").append(skills.lastOutcome());
        String blocks = nearbyBlocksSummary();
        if (!blocks.isEmpty()) {
            state.append("，附近方块：").append(blocks);
        }

        String nearby = nearbySummary();
        if (!nearby.isEmpty()) {
            state.append("，附近：").append(nearby);
        }
        return state.toString();
    }

    private String inventorySummary() {
        StringBuilder summary = new StringBuilder();
        var inventory = body.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (summary.length() > 0) {
                summary.append('、');
            }
            summary.append(stack.getHoverName().getString()).append('x').append(stack.getCount());
            if (summary.length() > 60) {
                break;
            }
        }
        return summary.length() == 0 ? "空手" : summary.toString();
    }

    /** 统计脚边小范围里出现最多的方块 ID，作为挖矿的可选目标提示。 */
    private String nearbyBlocksSummary() {
        BlockPos center = body.blockPosition();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-NEARBY_BLOCK_RADIUS, -2, -NEARBY_BLOCK_RADIUS),
                center.offset(NEARBY_BLOCK_RADIUS, 2, NEARBY_BLOCK_RADIUS))) {
            BlockState state = body.level().getBlockState(pos);
            if (state.isAir() || !isExposed(pos)) {
                continue;
            }
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            counts.merge(id, 1, Integer::sum);
        }
        if (counts.isEmpty()) {
            return "";
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .map(entry -> entry.getKey() + "x" + entry.getValue())
                .collect(Collectors.joining("、"));
    }

    /** 只把至少一面贴空气的方块报给模型，和技能层"只挖暴露方块"的规则保持一致。 */
    private boolean isExposed(BlockPos pos) {
        return body.level().getBlockState(pos.above()).isAir()
                || body.level().getBlockState(pos.below()).isAir()
                || body.level().getBlockState(pos.north()).isAir()
                || body.level().getBlockState(pos.south()).isAir()
                || body.level().getBlockState(pos.east()).isAir()
                || body.level().getBlockState(pos.west()).isAir();
    }

    private String nearbySummary() {
        List<Entity> entities = body.level().getEntities(body, body.getBoundingBox().inflate(8.0D),
                entity -> entity != body && entity.isAlive());
        if (entities.isEmpty()) {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        int listed = 0;
        for (Entity entity : entities) {
            if (listed >= MAX_NEARBY) {
                break;
            }
            if (summary.length() > 0) {
                summary.append('、');
            }
            String name = entity.getType().toString().replace("entity.minecraft.", "");
            // 玩家要报名字而不是 "player"：模型的 follow 需要真实玩家名，光有类型它只能瞎编。
            if (entity instanceof net.minecraft.world.entity.player.Player player) {
                name = player.getName().getString();
            }
            summary.append(name).append(String.format(Locale.ROOT, "(%d格)", (int) entity.distanceTo(body)));
            listed++;
        }
        return summary.toString();
    }

    /** 附近 16 格内的真实玩家名，用于给模型一个可跟随的目标。 */
    private List<String> nearbyPlayerNames() {
        List<Entity> players = body.level().getEntities(body, body.getBoundingBox().inflate(16.0D),
                entity -> entity != body
                        && entity.isAlive()
                        && entity instanceof net.minecraft.world.entity.player.Player);
        List<String> names = new ArrayList<>(players.size());
        for (Entity entity : players) {
            names.add(((net.minecraft.world.entity.player.Player) entity).getName().getString());
        }
        return names;
    }

    private static int promptLength(List<LlmMessage> messages) {
        int total = 0;
        for (LlmMessage message : messages) {
            total += message.content().length();
        }
        return total;
    }

    /** 是否正在推理（玩家对话或自主自省），供自主调度器跳过忙碌的 NPC。 */
    public boolean isBusy() {
        return inFlight;
    }

    public String status() {
        return "npc=" + body.npcName() + " inFlight=" + inFlight + " auto=" + autoInFlight
                + " history=" + history.size()
                + " lastProvider=" + lastProvider + " lastLatency=" + lastLatencyMs + "ms"
                + " skill=" + skills.currentSkill();
    }
}

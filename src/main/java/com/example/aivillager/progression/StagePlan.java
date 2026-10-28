package com.example.aivillager.progression;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 阶段序列表：把"发展路线"集中定义在一处。
 *
 * <p>每个阶段 = 名字 + 它在 {@code ProgressionPlanner.decide()} 里对应的决策段
 * （完成判定 = 该段的条件不再触发、流程自然落到下一段；目标下发 = 该段返回的 Step）。
 * 以前这些阶段只以散落的中文字符串形式存在于 decide() 的条件链里，
 * 没人能一眼看出"整条路线长什么样、某个模式走到哪一段为止"。
 * 现在路线以这张表为准：
 *
 * <ul>
 *   <li>{@link ProgressionMode#SPEEDRUN 通关模式}：全表，终点是末地打龙；</li>
 *   <li>{@link ProgressionMode#STEADY 安稳发育模式}：走到钻石甲 + 食物储备为止，
 *       之后进入 {@link #SUSTAIN} 维持生存循环，不再往下界/末地推进。</li>
 * </ul>
 *
 * <p>后续"玩家改每个 NPC 的目标"的接入点就在这一层：
 * 玩家改目标 = 给某个 NPC 换一个 {@link ProgressionMode}（换路线），
 * 或者进一步给它一份自定义阶段序列（换路线里的阶段子集/顺序）。
 * 规划器只认"当前 NPC 的模式 → 阶段序列"，不关心模式是谁设的，
 * 所以将来不管是命令、GUI 还是配置文件改模式，都只需要改插件层那张
 * NPC 名 → 模式 的表，并让规划器重读一次即可（见 AiVillagerPlugin#modeForNpc）。
 */
public final class StagePlan {

    /** 一个阶段的定义：稳定 id（给程序/配置用）+ 显示名（给日志/玩家看）。 */
    public record StageDef(String id, String name) {
    }

    public static final StageDef HUNGER = new StageDef("hunger", "饥饿求生");
    public static final StageDef WORKBENCH = new StageDef("workbench", "工作台");
    public static final StageDef WOOD_TOOLS = new StageDef("wood_tools", "木工具（木镐/木剑）");
    public static final StageDef STONE_TOOLS = new StageDef("stone_tools", "石器工具");
    public static final StageDef FURNACE = new StageDef("furnace", "熔炉");
    public static final StageDef WOOD_RESERVE = new StageDef("wood_reserve", "木料安全库存");
    public static final StageDef COAL = new StageDef("coal", "煤");
    public static final StageDef IRON_ORE = new StageDef("iron_ore", "挖铁");
    public static final StageDef SMELT_IRON = new StageDef("smelt_iron", "冶铁");
    public static final StageDef IRON_GEAR = new StageDef("iron_gear", "铁装备");
    public static final StageDef SPARE_PICKS = new StageDef("spare_picks", "工具保障（备用镐）");
    public static final StageDef DIAMOND = new StageDef("diamond", "钻石工具（镐/剑）");
    /** 钻石剑单独成段：自定义计划可以只要钻石镐、跳过钻石剑（省 2 颗钻石直奔下界）。 */
    public static final StageDef DIAMOND_SWORD = new StageDef("diamond_sword", "钻石剑");
    public static final StageDef DIAMOND_ARMOR = new StageDef("diamond_armor", "钻石甲");
    public static final StageDef FOOD = new StageDef("food", "食物储备");
    /**
     * 种田（第四十九轮 fix49，Fong D11 拍板，P2 落脚点套装第一位）：靠水开田、
     * 循环种植——收割后立刻补种、种子/种苗留存永不吃光、只有富余进口粮
     * （小麦做面包、土豆烤熟）。定位是贫瘠群系断粮的根治项：五局群系合证
     * hunt 在动物稀疏/斑块分布下靠不住，田是 NPC 自己能控的口粮来源。
     * 与 hunt 的关系：hunt 管应急与肉食，田管细水长流的主粮。
     */
    public static final StageDef FARMING = new StageDef("farming", "种田（循环种植）");
    /**
     * 落脚点（第五十一轮 fix51，Fong D2 拍板，P2 落脚点套装第二位）：在有水+
     * 天然矿洞+树多处建火柴盒小屋，作全套锚点——D1 的床放屋里、箱子存贵重物、
     * D3 死后回程认它。建成位置落盘 BaseMemory，重启/远行都找得回。
     */
    public static final StageDef BASE = new StageDef("base", "落脚点（火柴盒小屋）");
    /**
     * 皮靴（第十九轮，Fong 拍板）：细雪地带的条件触发阶段——身处雪原/针叶林等
     * 有细雪的群系时优先攒皮革做皮靴穿上（没皮靴寻路避开细雪、陷住要挖出/垫出）。
     * 暖区出生不强制（只在打猎顺手攒够 4 张皮时顺带做）。做成独立阶段就是为了
     * 能被阶段自定义控制：自定义计划不写 leather_boots，这个驱动就不启用
     * （寻路避细雪的安全规则不受阶段控制，那是保命不是发展路线）。
     */
    public static final StageDef LEATHER_BOOTS = new StageDef("leather_boots", "皮靴（细雪地带）");
    public static final StageDef PORTAL_PREP = new StageDef("portal_prep", "下界门准备");
    public static final StageDef BOW_ARROWS = new StageDef("bow_arrows", "弓箭");
    public static final StageDef STRONGHOLD = new StageDef("stronghold", "要塞与末地门");
    public static final StageDef NETHER = new StageDef("nether", "下界（烈焰棒/珍珠/眼睛）");
    public static final StageDef END = new StageDef("end", "末地打龙");
    public static final StageDef SHELTER = new StageDef("shelter", "夜间小屋");
    public static final StageDef EXPLORE = new StageDef("explore", "探索兜底");
    /** 安稳发育模式的终点站：不是"完成"，是无限循环的维持生存。 */
    public static final StageDef SUSTAIN = new StageDef("sustain", "维持生存");

    /** 通关模式的完整阶段序列（顺序即推进顺序）。 */
    private static final List<StageDef> SPEEDRUN_PLAN = List.of(
            HUNGER, WORKBENCH, WOOD_TOOLS, STONE_TOOLS, FURNACE, WOOD_RESERVE,
            COAL, IRON_ORE, SMELT_IRON, IRON_GEAR, SPARE_PICKS,
            DIAMOND, DIAMOND_SWORD, DIAMOND_ARMOR, FOOD, FARMING, BASE, LEATHER_BOOTS,
            PORTAL_PREP, BOW_ARROWS, STRONGHOLD, NETHER, END,
            SHELTER, EXPLORE);

    /**
     * 安稳发育模式的阶段序列：与通关共用前半程（先活下来、发育到钻石套），
     * 到食物储备为止，之后进入维持生存，不再出现下界门准备及之后的任何阶段。
     */
    private static final List<StageDef> STEADY_PLAN = List.of(
            HUNGER, WORKBENCH, WOOD_TOOLS, STONE_TOOLS, FURNACE, WOOD_RESERVE,
            COAL, IRON_ORE, SMELT_IRON, IRON_GEAR, SPARE_PICKS,
            DIAMOND, DIAMOND_SWORD, DIAMOND_ARMOR, FOOD, FARMING, BASE, LEATHER_BOOTS,
            SUSTAIN);

    /**
     * 全部阶段的"标准顺序"（= decide() 里各段的依赖安全顺序）。
     * 自定义计划写什么顺序都按这张表归一化执行：阶段之间的先后本质是
     * 前置依赖（没有铁镐挖不了钻石），玩家改的是"要哪些阶段"，不是把
     * 依赖倒过来——倒过来的计划在解析时会被前置校验拦下（见下）。
     */
    private static final List<StageDef> CANONICAL = List.of(
            HUNGER, WORKBENCH, WOOD_TOOLS, STONE_TOOLS, FURNACE, WOOD_RESERVE,
            COAL, IRON_ORE, SMELT_IRON, IRON_GEAR, SPARE_PICKS,
            DIAMOND, DIAMOND_SWORD, DIAMOND_ARMOR, FOOD, FARMING, BASE, LEATHER_BOOTS,
            PORTAL_PREP, NETHER, BOW_ARROWS, STRONGHOLD, END,
            SHELTER, EXPLORE, SUSTAIN);

    /** 按 id 查阶段定义；找不到返回 null。 */
    public static StageDef byId(String id) {
        if (id == null) {
            return null;
        }
        String key = id.trim().toLowerCase(Locale.ROOT);
        for (StageDef def : CANONICAL) {
            if (def.id().equals(key)) {
                return def;
            }
        }
        return null;
    }

    /** 全部合法阶段 id（config 校验与日志用）。 */
    public static List<StageDef> allStages() {
        return CANONICAL;
    }

    /**
     * 阶段前置依赖：启用某阶段必须同时启用它的前置阶段，否则 NPC 会在
     * 运行时死等不存在的前置产物（比如启用了 diamond 却没启用 iron_gear，
     * 它永远拿不到铁镐）。解析自定义计划时按这张表级联禁用并打明确日志，
     * 把错误拦在配置加载期，而不是让 NPC 在游戏里空转。
     */
    private static final Map<String, List<String>> PREREQUISITES = Map.ofEntries(
            Map.entry("wood_tools", List.of("workbench")),
            Map.entry("stone_tools", List.of("wood_tools")),
            Map.entry("furnace", List.of("stone_tools")),
            Map.entry("coal", List.of("stone_tools")),
            Map.entry("iron_ore", List.of("stone_tools")),
            Map.entry("smelt_iron", List.of("furnace", "iron_ore")),
            Map.entry("iron_gear", List.of("smelt_iron")),
            Map.entry("spare_picks", List.of("smelt_iron")),
            Map.entry("diamond", List.of("iron_gear")),
            Map.entry("diamond_sword", List.of("diamond")),
            Map.entry("diamond_armor", List.of("diamond")),
            Map.entry("leather_boots", List.of("wood_tools")),
            Map.entry("farming", List.of("wood_tools")),
            Map.entry("base", List.of("wood_tools")),
            Map.entry("portal_prep", List.of("diamond")),
            Map.entry("nether", List.of("portal_prep")),
            Map.entry("stronghold", List.of("nether")),
            Map.entry("end", List.of("stronghold", "bow_arrows")));

    /**
     * 解析一份自定义阶段计划（第七轮：阶段级自定义）。
     *
     * @param requestedIds config 里写的阶段 id 列表（子集即可，顺序仅作展示）
     * @param log          校验日志出口：未知 id、缺前置被禁用、sustain 冲突都会打明确一行
     * @param npcName      日志里标注是哪个 NPC 的计划
     * @return 归一化后的有效计划（标准顺序、只含通过校验的阶段）；requestedIds 为空时返回 null（用模式预设）
     */
    public static List<StageDef> resolvePlan(List<String> requestedIds, Logger log, String npcName) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            return null;
        }
        Set<String> enabled = new LinkedHashSet<>();
        for (String raw : requestedIds) {
            StageDef def = byId(raw);
            if (def == null) {
                log.warning("[AIV][PLAN] plan-invalid | npc=" + npcName
                        + " 未知阶段 id=" + raw + "，已忽略（合法 id 见 StagePlan）");
                continue;
            }
            enabled.add(def.id());
        }
        // 前置校验（级联）：标准顺序里前置总在前面，一遍正向扫描即可连锁禁用。
        for (StageDef def : CANONICAL) {
            if (!enabled.contains(def.id())) {
                continue;
            }
            List<String> prereqs = PREREQUISITES.getOrDefault(def.id(), List.of());
            List<String> missing = new ArrayList<>();
            for (String prereq : prereqs) {
                if (!enabled.contains(prereq)) {
                    missing.add(prereq);
                }
            }
            if (!missing.isEmpty()) {
                enabled.remove(def.id());
                log.warning("[AIV][PLAN] plan-invalid | npc=" + npcName
                        + " 阶段 " + def.id() + "（" + def.name() + "）缺前置 " + missing
                        + "，已禁用——不补前置的话 NPC 会在运行时死等不存在的产物");
            }
        }
        // sustain 只在"没有启用任何后期阶段"时才会真正触发（规划器的终点站语义）；
        // 两边同时启用不是错误，但必须提醒配置者 sustain 会被后期阶段顶掉。
        if (enabled.contains(SUSTAIN.id())
                && (enabled.contains(PORTAL_PREP.id()) || enabled.contains(NETHER.id())
                        || enabled.contains(STRONGHOLD.id()) || enabled.contains(END.id()))) {
            log.warning("[AIV][PLAN] plan-note | npc=" + npcName
                    + " sustain 与后期阶段（portal_prep/nether/stronghold/end）同时启用，"
                    + "sustain 不会触发，NPC 会继续往通关推进");
        }
        List<StageDef> plan = new ArrayList<>();
        for (StageDef def : CANONICAL) {
            if (enabled.contains(def.id())) {
                plan.add(def);
            }
        }
        // 【fix102·兜底审计 #18】全部请求都被剔除（未知 id + 缺前置连锁）时
        // 旧口径返回空计划：规划层零阶段启用、NPC 只剩生存层原地待命，
        // 配置写错的人也看不到出路。空计划按「没写计划」处理——回落模式
        // 预设（resolvePlan 返回 null 的既有语义），并打警告说明原因。
        if (plan.isEmpty()) {
            log.warning("[AIV][PLAN] plan-invalid | npc=" + npcName
                    + " 自定义计划校验后为空，已回落模式预设计划——请检查 config 里的阶段 id 与前置");
            return null;
        }
        return plan;
    }

    private StagePlan() {
    }

    /** 某个模式对应的阶段序列。 */
    public static List<StageDef> forMode(ProgressionMode mode) {
        return mode == ProgressionMode.STEADY ? STEADY_PLAN : SPEEDRUN_PLAN;
    }

    /** 一行可读的序列摘要，启动日志用。 */
    public static String describe(List<StageDef> plan) {
        StringBuilder builder = new StringBuilder();
        for (StageDef def : plan) {
            if (builder.length() > 0) {
                builder.append(" → ");
            }
            builder.append(def.name());
        }
        return builder.toString();
    }
}

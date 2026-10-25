package com.example.aivillager.progression;

import java.util.Locale;

/**
 * 阶段模式：决定一个 NPC 沿哪一条阶段序列发展。
 *
 * <p>这个项目最初是奔着"通关"一条直线写的：木头→铁→钻石→下界→末地。
 * Fong 要求把它改成多个模式，后续还要让玩家能改每个 NPC 的目标。
 * 模式就是那条"玩家可改的目标"的第一层：先决定走哪条路线，
 * 路线内部的阶段序列见 {@link StagePlan}。
 *
 * <p>模式按 NPC 存储（插件层用 NPC 名查表），并可在 config.yml 里指定：
 * <pre>
 * progression:
 *   default-mode: speedrun        # 全局默认
 *   modes:
 *     Villager_A1: steady         # 按 NPC 名覆盖
 * </pre>
 */
public enum ProgressionMode {

    /** 通关模式（默认）：原科技树全程，含钻石甲阶段，终点是末地打龙。 */
    SPEEDRUN("speedrun", "通关模式"),

    /**
     * 安稳发育模式：走到钻石套齐 + 食物/物资有余就停，
     * 之后只维持生存（吃饱、补工具、补木料、附近巡视），不再推进下界/末地。
     */
    STEADY("steady", "安稳发育模式");

    private final String configKey;
    private final String displayName;

    ProgressionMode(String configKey, String displayName) {
        this.configKey = configKey;
        this.displayName = displayName;
    }

    /** config.yml 里写的名字。 */
    public String configKey() {
        return configKey;
    }

    /** 给人看的名字（日志/状态）。 */
    public String displayName() {
        return displayName;
    }

    /** 解析配置字符串；无法识别时回退到通关模式（默认行为不能被笔误打断）。 */
    public static ProgressionMode parse(String raw) {
        if (raw != null) {
            String key = raw.trim().toLowerCase(Locale.ROOT);
            for (ProgressionMode mode : values()) {
                if (mode.configKey.equals(key) || mode.name().equalsIgnoreCase(key)) {
                    return mode;
                }
            }
        }
        return SPEEDRUN;
    }
}

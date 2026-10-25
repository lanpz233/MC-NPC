package com.example.aivillager.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 把模型输出解析成 {@link Intent}。
 *
 * <p>小模型经常不老实：会加代码块、加解释、把参数写在顶层。所以这里做三件事：
 * <ol>
 *   <li>从任意文本里抓第一个"括号配平"的 JSON 对象，而不是要求整段就是 JSON。</li>
 *   <li>参数既支持嵌套 {@code params}，也支持平铺在顶层（小模型更常这么写）。</li>
 *   <li>解析失败时退化为"只说话"，并把原文里的 JSON 片段剥掉，避免代码进聊天框。</li>
 * </ol>
 */
public final class IntentParser {

    private static final Logger LOG = Logger.getLogger("AIVillager");
    /**
     * 合法动作白名单。
     * gather/craft/build/explore 是中期目标（交给目标层拆成多步技能），
     * 曾经漏在这里导致模型明明给了 explore 却被降级成 none，NPC 于是站着不动。
     */
    private static final Set<String> ACTIONS = Set.of(
            "none", "idle", "goto", "mine", "attack", "follow",
            "gather", "craft", "build", "explore");

    private IntentParser() {
    }

    public static Intent parse(String raw) {
        String json = extractJsonObject(raw);
        if (json == null) {
            return Intent.sayOnly(stripJson(raw));
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String say = root.has("say") && !root.get("say").isJsonNull() ? root.get("say").getAsString() : "";
            String action = root.has("action") ? root.get("action").getAsString().toLowerCase(Locale.ROOT) : "none";
            if (!ACTIONS.contains(action)) {
                LOG.warning("[AIV][INTENT] 未知动作 '" + action + "'，按 none 处理 | raw=" + raw);
                action = "none";
            }
            JsonObject params = root.has("params") && root.get("params").isJsonObject()
                    ? root.getAsJsonObject("params")
                    : root;
            return new Intent(say, action, params);
        } catch (RuntimeException exception) {
            LOG.warning("[AIV][INTENT] JSON 解析失败，退化为纯说话 | error=" + exception.getMessage() + " | raw=" + raw);
            return Intent.sayOnly(stripJson(raw));
        }
    }

    /** 从头扫描，找到第一个括号配平的 {...}；找不到返回 null。 */
    private static String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        while (start >= 0) {
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int index = start; index < raw.length(); index++) {
                char current = raw.charAt(index);
                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (current == '\\') {
                        escaped = true;
                    } else if (current == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (current == '"') {
                    inString = true;
                } else if (current == '{') {
                    depth++;
                } else if (current == '}') {
                    depth--;
                    if (depth == 0) {
                        return raw.substring(start, index + 1);
                    }
                }
            }
            start = raw.indexOf('{', start + 1);
        }
        return null;
    }

    /** 去掉可能残留的 JSON 片段与代码块标记，只留人能读的文本。 */
    private static String stripJson(String raw) {
        String text = raw.replace("```", " ").replace("`", " ");
        String json = extractJsonObject(text);
        while (json != null) {
            text = text.replace(json, " ");
            json = extractJsonObject(text);
        }
        return text.replaceAll("\\s{2,}", " ").trim();
    }
}

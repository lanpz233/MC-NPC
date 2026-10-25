package com.example.aivillager.intent;

import com.google.gson.JsonObject;

/**
 * 模型输出的一次决策：说什么 + 做什么。
 *
 * <p>参数放在 {@link JsonObject} 里而不是强类型字段，是因为动作集合后续会扩张
 * （砍树、种地、交易……），每加一个动作都改一次 record 不划算。
 */
public record Intent(String say, String action, JsonObject params) {

    public static Intent sayOnly(String say) {
        return new Intent(say, "none", new JsonObject());
    }

    public String param(String key, String fallback) {
        return params.has(key) && !params.get(key).isJsonNull() ? params.get(key).getAsString() : fallback;
    }

    public int intParam(String key, int fallback) {
        try {
            return params.has(key) ? params.get(key).getAsInt() : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    public double doubleParam(String key, double fallback) {
        try {
            return params.has(key) ? params.get(key).getAsDouble() : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}

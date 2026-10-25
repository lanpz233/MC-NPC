package com.example.aivillager.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * 本地 Ollama 后端（/api/chat，非流式）。
 *
 * <p>关键实现细节：
 * <ul>
 *   <li>{@code think=false}：Ollama 的思考型模型（Qwen3.x 等）默认会把 token 全花在 thinking 上，
 *       content 返回空字符串。关掉思考后 content 才会正常输出，同时延迟明显下降。</li>
 *   <li>{@code stream=false}：一次返回整段文本，处理逻辑简单；50+ NPC 时用队列控制并发即可。</li>
 *   <li>HTTP 非 200、content 为空都视为失败，抛给 Router 去切云端。</li>
 * </ul>
 */
public final class OllamaProvider implements LlmProvider {

    private static final Logger LOG = Logger.getLogger("AIVillager");

    private final HttpClient http;
    private final String baseUrl;
    private final String model;
    private final int timeoutSeconds;
    private final int maxTokens;
    private final double temperature;
    private final boolean think;

    public OllamaProvider(String baseUrl, String model, int timeoutSeconds, int maxTokens,
                          double temperature, boolean think) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.think = think;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(10, timeoutSeconds)))
                .build();
    }

    @Override
    public String id() {
        return "local-ollama";
    }

    @Override
    public String describe() {
        return "local-ollama url=" + baseUrl + " model=" + model + " think=" + think + " timeout=" + timeoutSeconds + "s";
    }

    @Override
    public CompletableFuture<String> chat(List<LlmMessage> messages) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("stream", false);
        root.addProperty("think", think);

        JsonArray array = new JsonArray();
        for (LlmMessage message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            item.addProperty("content", message.content());
            array.add(item);
        }
        root.add("messages", array);

        JsonObject options = new JsonObject();
        options.addProperty("temperature", temperature);
        options.addProperty("num_predict", maxTokens);
        root.add("options", options);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(root.toString(), StandardCharsets.UTF_8))
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    int status = response.statusCode();
                    String body = response.body();
                    if (status != 200) {
                        throw new IllegalStateException("Ollama HTTP " + status + " body=" + truncate(body));
                    }
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    JsonObject message = json.has("message") ? json.getAsJsonObject("message") : null;
                    String content = message != null && message.has("content")
                            ? message.get("content").getAsString() : "";
                    if (content.isBlank()) {
                        // 这种情况通常意味着模型仍在 thinking，或者 num_predict 太小被截断。
                        throw new IllegalStateException("Ollama 返回空 content done_reason="
                                + (json.has("done_reason") ? json.get("done_reason").getAsString() : "?"));
                    }
                    LOG.fine("[AIV][LLM] ollama prompt_tokens=" + field(json, "prompt_eval_count")
                            + " eval_tokens=" + field(json, "eval_count"));
                    return content.trim();
                });
    }

    private static String field(JsonObject json, String key) {
        return json.has(key) ? json.get(key).getAsString() : "?";
    }

    private static String truncate(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}

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

/**
 * 云端后端：OpenAI 兼容的 /v1/chat/completions。
 * 适用于 OpenAI、DeepSeek、月之暗面、硅基流动等任何兼容接口的服务。
 */
public final class OpenAiCompatibleProvider implements LlmProvider {

    private final HttpClient http;
    private final String id;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private final int maxTokens;
    private final double temperature;

    public OpenAiCompatibleProvider(String id, String baseUrl, String apiKey, String model,
                                    int timeoutSeconds, int maxTokens, double temperature) {
        this.id = id;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(10, timeoutSeconds)))
                .build();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String describe() {
        return id + " url=" + baseUrl + " model=" + model + " timeout=" + timeoutSeconds + "s";
    }

    @Override
    public CompletableFuture<String> chat(List<LlmMessage> messages) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("temperature", temperature);
        root.addProperty("max_tokens", maxTokens);
        root.addProperty("stream", false);

        JsonArray array = new JsonArray();
        for (LlmMessage message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            item.addProperty("content", message.content());
            array.add(item);
        }
        root.add("messages", array);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(root.toString(), StandardCharsets.UTF_8))
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    int status = response.statusCode();
                    String body = response.body();
                    if (status != 200) {
                        throw new IllegalStateException(id + " HTTP " + status + " body=" + truncate(body));
                    }
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    JsonArray choices = json.getAsJsonArray("choices");
                    if (choices == null || choices.isEmpty()) {
                        throw new IllegalStateException(id + " 返回没有 choices");
                    }
                    JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                    String content = message != null && message.has("content")
                            ? message.get("content").getAsString() : "";
                    if (content.isBlank()) {
                        throw new IllegalStateException(id + " 返回空 content");
                    }
                    return content.trim();
                });
    }

    private static String truncate(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}

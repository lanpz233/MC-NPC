package com.example.aivillager.llm;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * 模型路由：按顺序尝试 local（Ollama）-> cloud（OpenAI 兼容），前一个失败才切下一个。
 *
 * <p>设计约束：
 * <ul>
 *   <li>全部异步，不占用主线程；结果由调用方切回主线程再写入游戏。</li>
 *   <li>失败不静默：每一跳都写控制台，便于判断是网络、模型名还是 token 上限问题。</li>
 *   <li>不做兜底台词：模型彻底失败时只记日志，不编造 NPC 回复。</li>
 * </ul>
 */
public final class LlmRouter {

    private static final Logger LOG = Logger.getLogger("AIVillager");

    private final List<LlmProvider> providers;
    private final boolean fallback;

    private LlmRouter(List<LlmProvider> providers, boolean fallback) {
        this.providers = providers;
        this.fallback = fallback;
    }

    public static LlmRouter fromConfig(Plugin plugin) {
        FileConfiguration config = plugin.getConfig();
        boolean fallback = config.getBoolean("llm.fallback", true);
        int maxTokens = config.getInt("llm.max-tokens", 256);
        double temperature = config.getDouble("llm.temperature", 0.7D);

        List<LlmProvider> providers = new ArrayList<>();

        if (config.getBoolean("llm.local.enabled", true)) {
            String baseUrl = config.getString("llm.local.base-url", "http://127.0.0.1:11434");
            String model = config.getString("llm.local.model", "qwen3.5-9b-uncensored-mc");
            int timeout = Math.max(5, config.getInt("llm.local.timeout-seconds", 90));
            // 思考型模型必须关掉 think，否则 content 会是空串（见 OllamaProvider 注释）。
            boolean think = config.getBoolean("llm.local.think", false);
            providers.add(new OllamaProvider(baseUrl, model, timeout, maxTokens, temperature, think));
        } else {
            LOG.info("[AIV][LLM] 本地后端已禁用");
        }

        if (config.getBoolean("llm.cloud.enabled", false)) {
            String apiKey = config.getString("llm.cloud.api-key", "");
            if (apiKey == null || apiKey.isBlank()) {
                LOG.warning("[AIV][LLM] 云端已启用但 api-key 为空，跳过云端后端");
            } else {
                String baseUrl = config.getString("llm.cloud.base-url", "https://api.deepseek.com");
                String model = config.getString("llm.cloud.model", "deepseek-chat");
                int timeout = Math.max(5, config.getInt("llm.cloud.timeout-seconds", 45));
                String id = config.getString("llm.cloud.id", "cloud");
                providers.add(new OpenAiCompatibleProvider(id, baseUrl, apiKey, model, timeout, maxTokens, temperature));
            }
        }

        if (providers.isEmpty()) {
            LOG.severe("[AIV][LLM] 没有任何可用的模型后端，对话功能将不可用");
        }
        for (LlmProvider provider : providers) {
            LOG.info("[AIV][LLM] 注册后端 | " + provider.describe());
        }
        LOG.info("[AIV][LLM] 路由顺序=" + providers.stream().map(LlmProvider::id).toList() + " fallback=" + fallback);
        return new LlmRouter(List.copyOf(providers), fallback);
    }

    public List<LlmProvider> providers() {
        return providers;
    }

    public boolean isAvailable() {
        return !providers.isEmpty();
    }

    public CompletableFuture<LlmResult> complete(List<LlmMessage> messages) {
        return attempt(0, messages);
    }

    private CompletableFuture<LlmResult> attempt(int index, List<LlmMessage> messages) {
        if (index >= providers.size()) {
            return CompletableFuture.failedFuture(new IllegalStateException("所有模型后端均失败"));
        }
        LlmProvider provider = providers.get(index);
        long startedAt = System.nanoTime();
        return provider.chat(messages)
                .thenApply(text -> {
                    long latency = (System.nanoTime() - startedAt) / 1_000_000L;
                    LOG.info("[AIV][LLM] provider=" + provider.id() + " ok 耗时=" + latency + "ms 字数=" + text.length());
                    return new LlmResult(provider.id(), text, latency);
                })
                .exceptionallyCompose(error -> {
                    long latency = (System.nanoTime() - startedAt) / 1_000_000L;
                    Throwable cause = unwrap(error);
                    LOG.warning("[AIV][LLM] provider=" + provider.id() + " failed 耗时=" + latency
                            + "ms error=" + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                    if (!fallback) {
                        return CompletableFuture.failedFuture(cause);
                    }
                    return attempt(index + 1, messages);
                });
    }

    private static Throwable unwrap(Throwable error) {
        // CompletableFuture 会把异常包一层 CompletionException，日志里去掉它更可读。
        Throwable current = error;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }
}

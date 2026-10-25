package com.example.aivillager.llm;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 一个模型后端。实现必须完全异步：绝不能阻塞服务端主线程。
 */
public interface LlmProvider {

    /** 日志里用来区分后端，例如 local-ollama / cloud-deepseek。 */
    String id();

    /** 人类可读的描述，用于 /aivspike llm 输出。 */
    String describe();

    /**
     * 发起一次对话。返回的 future 在 HTTP 完成时结束；
     * 失败时必须以异常完成，交给 {@link LlmRouter} 决定是否切换下一个后端。
     */
    CompletableFuture<String> chat(List<LlmMessage> messages);
}

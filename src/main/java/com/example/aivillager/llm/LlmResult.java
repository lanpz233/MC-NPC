package com.example.aivillager.llm;

/**
 * 一次成功的模型调用结果，带上后端与耗时，方便在控制台追踪路由决策。
 */
public record LlmResult(String providerId, String text, long latencyMs) {
}

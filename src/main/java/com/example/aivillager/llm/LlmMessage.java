package com.example.aivillager.llm;

/**
 * 一条对话消息。role 取值与 OpenAI/Ollama 一致：system / user / assistant。
 */
public record LlmMessage(String role, String content) {

    public static LlmMessage system(String content) {
        return new LlmMessage("system", content);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage("assistant", content);
    }
}

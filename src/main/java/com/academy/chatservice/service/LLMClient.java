package com.academy.chatservice.service;

import java.util.function.Consumer;

/**
 * Abstraction over any LLM backend.
 * Swap MockLLMClient → OllamaLLMClient or OpenAILLMClient without changing business logic.
 */
public interface LLMClient {
    String generate(String prompt);

    default void generateStream(String prompt, Consumer<String> onChunk) {
        onChunk.accept(generate(prompt));
    }
}

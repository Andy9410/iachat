package com.academy.chatservice.service;

import com.academy.chatservice.model.tools.LLMToolResponse;
import com.academy.chatservice.model.tools.ToolDefinition;

import java.util.List;
import java.util.function.Consumer;

/**
 * Abstraction over any LLM backend.
 * Swap MockLLMClient → OllamaLLMClient or OpenAILLMClient without changing business logic.
 */
public interface LLMClient {
    String generate(String prompt);

    default String modelName() { return "unknown"; }

    default boolean supportsToolCalling() { return false; }

    default LLMToolResponse generateWithTools(String prompt, List<ToolDefinition> tools) {
        return new LLMToolResponse(generate(prompt), List.of());
    }

    default void generateStream(String prompt, Consumer<String> chunkConsumer) {
        chunkConsumer.accept(generate(prompt));
    }
}

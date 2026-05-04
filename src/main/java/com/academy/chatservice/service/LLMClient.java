package com.academy.chatservice.service;

/**
 * Abstraction over any LLM backend.
 * Swap MockLLMClient → OllamaLLMClient or OpenAILLMClient without changing business logic.
 */
public interface LLMClient {
    String generate(String prompt);
}

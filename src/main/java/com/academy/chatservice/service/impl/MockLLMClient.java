    package com.academy.chatservice.service.impl;

    import com.academy.chatservice.service.LLMClient;
    import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
    import org.springframework.stereotype.Component;

    /**
     * Active when llm.provider=mock (default).
     *
     * Future Ollama swap:
     *   POST http://localhost:11434/api/generate
     *   { "model": "llama3", "prompt": "...", "stream": false }
     */
    @Component
    @ConditionalOnProperty(name = "llm.provider", havingValue = "mock", matchIfMissing = true)
    public class MockLLMClient implements LLMClient {

        @Override
        public String generate(String prompt) {
            return "Respuesta simulada para: \"%s\". Integra Ollama para respuestas reales.".formatted(prompt);
        }
    }

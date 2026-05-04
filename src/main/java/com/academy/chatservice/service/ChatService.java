package com.academy.chatservice.service;

import com.academy.chatservice.model.ChatRequest;
import com.academy.chatservice.model.ChatResponse;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private final LLMClient llmClient;

    public ChatService(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    public ChatResponse process(ChatRequest request) {
        var message = request.message().trim();

        if (message.isBlank()) {
            throw new IllegalArgumentException("El mensaje no puede estar vacío");
        }

        // TODO: enriquecer con contexto RAG (pgvector) antes de llamar al LLM
        var prompt = buildPrompt(message);
        var llmResponse = llmClient.generate(prompt);

        return new ChatResponse(llmResponse);
    }

    private String buildPrompt(String userMessage) {
        // TODO: inyectar fragmentos recuperados por similitud semántica
        return """
                Eres un tutor inteligente de una academia de programación.
                Responde de forma clara, precisa y pedagógica.

                Pregunta del estudiante: %s
                """.formatted(userMessage);
    }
}

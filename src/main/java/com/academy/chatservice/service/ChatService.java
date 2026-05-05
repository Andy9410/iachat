package com.academy.chatservice.service;

import com.academy.chatservice.model.ChatRequest;
import com.academy.chatservice.model.ChatResponse;
import com.academy.chatservice.model.Conversation;
import com.academy.chatservice.model.Message;
import com.academy.chatservice.repository.ConversationRepository;
import com.academy.chatservice.repository.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatService {

    private final LLMClient llmClient;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public ChatService(LLMClient llmClient,
                       ConversationRepository conversationRepository,
                       MessageRepository messageRepository) {
        this.llmClient = llmClient;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public ChatResponse process(ChatRequest request) {
        var text = request.message().trim();

        if (text.isBlank()) {
            throw new IllegalArgumentException("El mensaje no puede estar vacío");
        }

        var conversation = resolveConversation(request.conversationId(), text);

        saveMessage(conversation, Message.Role.user, text);

        // TODO: enriquecer con contexto RAG (pgvector) antes de llamar al LLM
        var llmResponse = llmClient.generate(buildPrompt(text));

        saveMessage(conversation, Message.Role.assistant, llmResponse);

        return new ChatResponse(llmResponse, conversation.getId());
    }

    private Conversation resolveConversation(Long conversationId, String firstMessage) {
        if (conversationId != null) {
            return conversationRepository.findById(conversationId)
                    .orElseThrow(() -> new IllegalArgumentException("Conversación no encontrada: " + conversationId));
        }
        var conv = new Conversation();
        conv.setTitle(firstMessage.length() > 50 ? firstMessage.substring(0, 50) : firstMessage);
        return conversationRepository.save(conv);
    }

    private void saveMessage(Conversation conversation, Message.Role role, String content) {
        var msg = new Message();
        msg.setConversation(conversation);
        msg.setRole(role);
        msg.setContent(content);
        messageRepository.save(msg);
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

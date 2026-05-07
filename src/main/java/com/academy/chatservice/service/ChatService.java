package com.academy.chatservice.service;

import com.academy.chatservice.config.ChatContextProperties;
import com.academy.chatservice.model.*;
import com.academy.chatservice.repository.ConversationRepository;
import com.academy.chatservice.repository.MessageEmbeddingRepository;
import com.academy.chatservice.repository.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final LLMClient llmClient;
    private final EmbeddingClient embeddingClient;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MessageEmbeddingRepository messageEmbeddingRepository;
    private final ChatContextProperties contextProps;

    public ChatService(LLMClient llmClient,
                       EmbeddingClient embeddingClient,
                       ConversationRepository conversationRepository,
                       MessageRepository messageRepository,
                       MessageEmbeddingRepository messageEmbeddingRepository,
                       ChatContextProperties contextProps) {
        this.llmClient = llmClient;
        this.embeddingClient = embeddingClient;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.messageEmbeddingRepository = messageEmbeddingRepository;
        this.contextProps = contextProps;
    }

    @Transactional
    public ChatResponse process(ChatRequest request) {
        var text = request.message().trim();

        if (text.isBlank()) {
            throw new IllegalArgumentException("El mensaje no puede estar vacío");
        }

        var conversation = resolveConversation(request.conversationId(), text);

        long messageCount = messageRepository.countByConversationId(conversation.getId());
        compactIfNeeded(conversation, messageCount);

        String vectorStr = toVectorString(embeddingClient.embed(text));
        saveUserMessage(conversation, text, vectorStr);

        var similar = messageEmbeddingRepository.findSimilar(vectorStr, conversation.getId(), contextProps.ragTopK());
        var window = getWindow(conversation.getId(), contextProps.windowSize());
        var llmResponse = llmClient.generate(buildPrompt(text, conversation.getSummary(), window, similar));

        saveMessage(conversation, Message.Role.assistant, llmResponse);

        return new ChatResponse(llmResponse, conversation.getId());
    }

    private void compactIfNeeded(Conversation conversation, long messageCount) {
        int threshold = contextProps.compactionThreshold();
        int window = contextProps.windowSize();
        if (messageCount <= threshold) return;

        long toSummarize = messageCount - window;
        List<Message> oldMessages = messageRepository.findFirstN(conversation.getId(), (int) toSummarize);

        String summaryPrompt = buildSummaryPrompt(conversation.getSummary(), oldMessages);
        String newSummary = llmClient.generate(summaryPrompt);

        conversation.setSummary(newSummary);
        conversationRepository.save(conversation);
    }

    private List<Message> getWindow(Long conversationId, int windowSize) {
        List<Message> last = messageRepository.findLastN(conversationId, windowSize);
        Collections.reverse(last);
        return last;
    }

    private Conversation resolveConversation(Long conversationId, String firstMessage) {
        if (conversationId != null) {
            return conversationRepository.findById(conversationId)
                    .orElseThrow(() -> new IllegalArgumentException("Conversación no encontrada: " + conversationId));
        }
        var conv = new Conversation();
        int max = contextProps.titleMaxLength();
        conv.setTitle(firstMessage.length() > max ? firstMessage.substring(0, max) : firstMessage);
        return conversationRepository.save(conv);
    }

    private void saveUserMessage(Conversation conversation, String content, String vectorStr) {
        var msg = new Message();
        msg.setConversation(conversation);
        msg.setRole(Message.Role.user);
        msg.setContent(content);
        messageRepository.save(msg);
        messageEmbeddingRepository.insertEmbedding(msg.getId(), vectorStr);
    }

    private void saveMessage(Conversation conversation, Message.Role role, String content) {
        var msg = new Message();
        msg.setConversation(conversation);
        msg.setRole(role);
        msg.setContent(content);
        messageRepository.save(msg);
    }

    private String toVectorString(List<Float> vector) {
        return vector.stream().map(Object::toString).collect(Collectors.joining(",", "[", "]"));
    }

    private String buildPrompt(String userMessage, String summary, List<Message> window,
                               List<SimilarMessageProjection> similar) {
        var sb = new StringBuilder();
        sb.append(contextProps.systemPrompt()).append("\n");

        if (!similar.isEmpty()) {
            sb.append("\nContexto relevante de otras conversaciones:\n");
            for (var s : similar) {
                String role = "user".equals(s.getRole()) ? "Estudiante" : "Tutor";
                sb.append(role).append(": ").append(s.getContent()).append("\n");
            }
        }

        if (summary != null && !summary.isBlank()) {
            sb.append("\nResumen de esta conversación:\n").append(summary).append("\n");
        }

        if (!window.isEmpty()) {
            sb.append("\nHistorial reciente:\n");
            for (Message m : window) {
                String role = m.getRole() == Message.Role.user ? "Estudiante" : "Tutor";
                sb.append(role).append(": ").append(m.getContent()).append("\n");
            }
        }

        sb.append("\nPregunta del estudiante: ").append(userMessage);
        return sb.toString();
    }

    private String buildSummaryPrompt(String existingSummary, List<Message> messages) {
        var sb = new StringBuilder();
        sb.append("Resume de forma concisa la siguiente conversación entre un estudiante y un tutor de programación.\n");

        if (existingSummary != null && !existingSummary.isBlank()) {
            sb.append("Resumen previo: ").append(existingSummary).append("\n\n");
        }

        sb.append("Conversación a resumir:\n");
        for (Message m : messages) {
            String role = m.getRole() == Message.Role.user ? "Estudiante" : "Tutor";
            sb.append(role).append(": ").append(m.getContent()).append("\n");
        }

        sb.append("\nResumen:");
        return sb.toString();
    }
}

package com.academy.chatservice.service;

import com.academy.chatservice.config.ChatContextProperties;
import com.academy.chatservice.model.ChatRequest;
import com.academy.chatservice.model.ChatResponse;
import com.academy.chatservice.model.Conversation;
import com.academy.chatservice.model.Message;
import com.academy.chatservice.repository.ConversationRepository;
import com.academy.chatservice.repository.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Service
public class ChatService {

    private final LLMClient llmClient;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ChatContextProperties contextProps;

    public ChatService(LLMClient llmClient,
                       ConversationRepository conversationRepository,
                       MessageRepository messageRepository,
                       ChatContextProperties contextProps) {
        this.llmClient = llmClient;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
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

        saveMessage(conversation, Message.Role.user, text);

        var window = getWindow(conversation.getId(), contextProps.windowSize());
        var llmResponse = llmClient.generate(buildPrompt(text, conversation.getSummary(), window));

        saveMessage(conversation, Message.Role.assistant, llmResponse);

        return new ChatResponse(llmResponse, conversation.getId());
    }

    private void compactIfNeeded(Conversation conversation, long messageCount) {
        int threshold = contextProps.compactionThreshold();
        int window = contextProps.windowSize();
        if (messageCount <= threshold) return;
        if ((messageCount - threshold) % window != 0) return;

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

    private String buildPrompt(String userMessage, String summary, List<Message> window) {
        var sb = new StringBuilder();
        sb.append("""
                Eres un tutor inteligente de una academia de programación.
                Responde de forma clara, precisa y pedagógica.
                """);

        if (summary != null && !summary.isBlank()) {
            sb.append("\nResumen de la conversación anterior:\n").append(summary).append("\n");
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

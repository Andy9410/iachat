package com.academy.chatservice.service;

import com.academy.chatservice.config.ChatContextProperties;
import com.academy.chatservice.model.*;
import com.academy.chatservice.repository.ConversationRepository;
import com.academy.chatservice.repository.MessageEmbeddingRepository;
import com.academy.chatservice.repository.MessageRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

    public record StreamPrep(Long conversationId, String prompt) {}

    @Transactional
    public StreamPrep prepareStream(ChatRequest request, String userEmail) {
        var text = request.message().trim();
        if (text.isBlank()) {
            throw new IllegalArgumentException("El mensaje no puede estar vacío");
        }

        var conversation = resolveConversation(request.conversationId(), text, userEmail);

        long messageCount = messageRepository.countByConversationId(conversation.getId());
        compactIfNeeded(conversation, messageCount);

        String vectorStr = toVectorString(embeddingClient.embed(text));
        saveUserMessage(conversation, text, vectorStr);

        var similar = messageEmbeddingRepository.findSimilar(vectorStr, userEmail, conversation.getId(), contextProps.ragTopK());
        var window = getWindow(conversation.getId(), contextProps.windowSize());
        String prompt = buildPrompt(text, conversation.getSummary(), window, similar);

        return new StreamPrep(conversation.getId(), prompt);
    }

    @Transactional
    public void finalizeStream(Long conversationId, String assistantResponse) {
        var conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversación no encontrada"));
        saveMessage(conversation, Message.Role.assistant, assistantResponse);
    }

    @Transactional
    public ChatResponse process(ChatRequest request, String userEmail) {
        var text = request.message().trim();

        if (text.isBlank()) {
            throw new IllegalArgumentException("El mensaje no puede estar vacío");
        }

        var conversation = resolveConversation(request.conversationId(), text, userEmail);

        long messageCount = messageRepository.countByConversationId(conversation.getId());
        compactIfNeeded(conversation, messageCount);

        String vectorStr = toVectorString(embeddingClient.embed(text));
        saveUserMessage(conversation, text, vectorStr);

        var similar = messageEmbeddingRepository.findSimilar(vectorStr, userEmail, conversation.getId(), contextProps.ragTopK());
        var window = getWindow(conversation.getId(), contextProps.windowSize());
        var llmResponse = llmClient.generate(buildPrompt(text, conversation.getSummary(), window, similar));

        saveMessage(conversation, Message.Role.assistant, llmResponse);

        return new ChatResponse(llmResponse, conversation.getId());
    }

    public record StreamPreparation(Long conversationId, String prompt) {}

    @Transactional
    public StreamPreparation prepareStream(ChatRequest request, String userEmail) {
        var text = request.message().trim();

        if (text.isBlank()) {
            throw new IllegalArgumentException("El mensaje no puede estar vacío");
        }

        var conversation = resolveConversation(request.conversationId(), text, userEmail);

        long messageCount = messageRepository.countByConversationId(conversation.getId());
        compactIfNeeded(conversation, messageCount);

        String vectorStr = toVectorString(embeddingClient.embed(text));
        saveUserMessage(conversation, text, vectorStr);

        var similar = messageEmbeddingRepository.findSimilar(vectorStr, userEmail, conversation.getId(), contextProps.ragTopK());
        var window = getWindow(conversation.getId(), contextProps.windowSize());
        String prompt = buildPrompt(text, conversation.getSummary(), window, similar);

        return new StreamPreparation(conversation.getId(), prompt);
    }

    @Transactional
    public void finalizeStream(Long conversationId, String assistantResponse) {
        var conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversación no encontrada"));
        saveMessage(conv, Message.Role.assistant, assistantResponse);
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryDto> getMyConversations(String userEmail) {
        return conversationRepository.findByUserEmailAndHiddenFalseOrderByCreatedAtDesc(userEmail)
                .stream()
                .map(c -> new ConversationSummaryDto(
                        c.getId(),
                        c.getTitle(),
                        c.getCreatedAt(),
                        (int) messageRepository.countByConversationId(c.getId())
                ))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<MessageDto> getConversationMessages(Long conversationId, String userEmail) {
        conversationRepository.findByIdAndUserEmail(conversationId, userEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversación no encontrada"));

        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)
                .stream()
                .map(m -> new MessageDto(m.getId(), m.getRole().name(), m.getContent(), m.getCreatedAt()))
                .collect(Collectors.toList());
    }

    @Transactional
    public String generateTitle(Long conversationId, String userEmail) {
        Conversation conversation = conversationRepository.findByIdAndUserEmail(conversationId, userEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversación no encontrada"));

        String firstMessage = messageRepository.findFirstN(conversationId, 1).stream()
                .filter(m -> m.getRole() == Message.Role.user)
                .findFirst()
                .map(Message::getContent)
                .orElse("");

        if (firstMessage.isBlank()) return conversation.getTitle();

        String prompt = "Genera un título de 2 a 6 palabras en español para una conversación que empieza con: \""
                + firstMessage + "\". Responde solo con el título, sin comillas ni puntuación final.";

        String raw = llmClient.generate(prompt).trim().replaceAll("[\"'.,;!?]", "");
        String[] words = raw.split("\\s+");
        String title = words.length > 6
                ? String.join(" ", java.util.Arrays.copyOfRange(words, 0, 6))
                : raw;

        conversation.setTitle(title);
        conversationRepository.save(conversation);
        return title;
    }

    @Transactional
    public void deleteConversation(Long conversationId, String userEmail) {
        Conversation conv = conversationRepository.findByIdAndUserEmail(conversationId, userEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversación no encontrada"));
        conv.setHidden(true);
        conversationRepository.save(conv);
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

    private Conversation resolveConversation(Long conversationId, String firstMessage, String userEmail) {
        if (conversationId != null) {
            return conversationRepository.findByIdAndUserEmail(conversationId, userEmail)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversación no encontrada"));
        }
        var conv = new Conversation();
        conv.setUserEmail(userEmail);
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

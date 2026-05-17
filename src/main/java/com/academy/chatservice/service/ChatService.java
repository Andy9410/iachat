package com.academy.chatservice.service;

import com.academy.chatservice.config.ChatContextProperties;
import com.academy.chatservice.model.*;
import com.academy.chatservice.repository.ConversationRepository;
import com.academy.chatservice.repository.MessageEmbeddingRepository;
import com.academy.chatservice.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final LLMClient llmClient;
    private final EmbeddingClient embeddingClient;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MessageEmbeddingRepository messageEmbeddingRepository;
    private final ChatContextProperties contextProps;
    private final DocumentSearchClient documentSearchClient;

    public ChatService(LLMClient llmClient,
                       EmbeddingClient embeddingClient,
                       ConversationRepository conversationRepository,
                       MessageRepository messageRepository,
                       MessageEmbeddingRepository messageEmbeddingRepository,
                       ChatContextProperties contextProps,
                       DocumentSearchClient documentSearchClient) {
        this.llmClient = llmClient;
        this.embeddingClient = embeddingClient;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.messageEmbeddingRepository = messageEmbeddingRepository;
        this.contextProps = contextProps;
        this.documentSearchClient = documentSearchClient;
    }

    public record StreamPrep(Long conversationId, String prompt, List<DocumentSearchClient.DocumentChunk> docChunks, String clarificationMessage) {}

    @Transactional
    public StreamPrep prepareStream(ChatRequest request, String userEmail) {
        var text = request.message().trim();
        if (text.isBlank()) {
            throw new IllegalArgumentException("El mensaje no puede estar vacío");
        }

        var conversation = resolveConversation(request.conversationId(), text, userEmail);

        long messageCount = messageRepository.countByConversationId(conversation.getId());
        compactIfNeeded(conversation, messageCount);

        var priorWindow = getWindow(conversation.getId(), 2);
        String vectorStr = toVectorString(embeddingClient.embed(text));
        saveUserMessage(conversation, text, vectorStr);

        var similar = messageEmbeddingRepository.findSimilar(vectorStr, userEmail, conversation.getId(), contextProps.ragTopK());
        var window = getWindow(conversation.getId(), contextProps.windowSize());
        var searchResult = request.preferredDocumentId() != null
                ? documentSearchClient.search(buildHydeQuery(text, priorWindow), userEmail, request.preferredDocumentId())
                : DocumentSearchClient.SearchResult.empty();
        if (searchResult.ambiguous()) {
            String msg = buildAmbiguityMessage(searchResult.exerciseRef(), searchResult.ambiguousDocuments());
            saveMessage(conversation, Message.Role.assistant, msg);
            return new StreamPrep(conversation.getId(), null, Collections.emptyList(), msg);
        }
        var docChunks = searchResult.chunks();
        String prompt = buildPrompt(text, conversation.getSummary(), window, similar, docChunks, userEmail);

        return new StreamPrep(conversation.getId(), prompt, docChunks, null);
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

        var priorWindow = getWindow(conversation.getId(), 2);
        String vectorStr = toVectorString(embeddingClient.embed(text));
        saveUserMessage(conversation, text, vectorStr);

        var similar = messageEmbeddingRepository.findSimilar(vectorStr, userEmail, conversation.getId(), contextProps.ragTopK());
        var window = getWindow(conversation.getId(), contextProps.windowSize());
        var searchResult = request.preferredDocumentId() != null
                ? documentSearchClient.search(buildHydeQuery(text, priorWindow), userEmail, request.preferredDocumentId())
                : DocumentSearchClient.SearchResult.empty();
        if (searchResult.ambiguous()) {
            String msg = buildAmbiguityMessage(searchResult.exerciseRef(), searchResult.ambiguousDocuments());
            saveMessage(conversation, Message.Role.assistant, msg);
            return new ChatResponse(msg, conversation.getId());
        }
        var docChunks = searchResult.chunks();
        var llmResponse = llmClient.generate(buildPrompt(text, conversation.getSummary(), window, similar, docChunks, userEmail));

        saveMessage(conversation, Message.Role.assistant, llmResponse);

        return new ChatResponse(llmResponse, conversation.getId());
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
                               List<SimilarMessageProjection> similar,
                               List<DocumentSearchClient.DocumentChunk> docChunks,
                               String userEmail) {
        var sb = new StringBuilder();
        sb.append(contextProps.systemPrompt()).append("\n");

        if (!docChunks.isEmpty()) {
            sb.append("\nMaterial de estudio relevante del estudiante:\n");
            for (var chunk : docChunks) {
                sb.append("— [").append(chunk.filename());
                if (chunk.pageNumber() != null) sb.append(", p.").append(chunk.pageNumber());
                sb.append("] ").append(chunk.chunkText()).append("\n");
            }
        }

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

    private String buildAmbiguityMessage(String exerciseRef, List<String> documentNames) {
        var sb = new StringBuilder();
        if (exerciseRef != null) {
            sb.append("Encontré \"").append(exerciseRef).append("\" en múltiples documentos.");
        } else {
            sb.append("Encontré el ejercicio en múltiples documentos.");
        }
        sb.append(" ¿De cuál querés que lo resuelva?\n\n");
        for (String name : documentNames) {
            sb.append("- ").append(name).append("\n");
        }
        return sb.toString();
    }

    private String buildHydeQuery(String text, List<Message> priorWindow) {
        String base = buildSearchQuery(text, priorWindow);
        try {
            String hydePrompt = "Responde en 2 oraciones técnicas y concisas a: \"" + base
                    + "\". Solo el contenido técnico, sin saludos ni explicaciones adicionales.";
            String hypothetical = llmClient.generate(hydePrompt).trim();
            if (!hypothetical.isBlank()) {
                log.info("[HyDE] query='{}' → hypothetical='{}'", base, hypothetical.substring(0, Math.min(80, hypothetical.length())));
                return hypothetical;
            }
        } catch (Exception e) {
            log.warn("[HyDE] LLM call failed, using original query: {}", e.getMessage());
        }
        return base;
    }

    private String buildSearchQuery(String text, List<Message> priorWindow) {
        if (text.length() >= 40) return text;
        String lastUserMsg = priorWindow.stream()
                .filter(m -> m.getRole() == Message.Role.user)
                .reduce((a, b) -> b)
                .map(Message::getContent)
                .orElse("");
        return lastUserMsg.isBlank() ? text : lastUserMsg + " " + text;
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

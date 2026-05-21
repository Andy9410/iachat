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

        var similar = messageEmbeddingRepository.findSimilar(vectorStr, conversation.getId(), contextProps.ragTopK());
        var window = getWindow(conversation.getId(), contextProps.windowSize());

        // Persist the active document on the conversation so it survives frontend session resets
        if (request.preferredDocumentId() != null
                && !request.preferredDocumentId().equals(conversation.getActiveDocumentId())) {
            conversation.setActiveDocumentId(request.preferredDocumentId());
            conversationRepository.save(conversation);
        }

        Long docId = request.preferredDocumentId() != null
                ? request.preferredDocumentId()
                : conversation.getActiveDocumentId();

        var searchResult = docId != null
                ? documentSearchClient.search(buildHydeQuery(text, priorWindow), userEmail, docId)
                : DocumentSearchClient.SearchResult.empty();

        if (searchResult.ambiguous()) {
            String msg = buildAmbiguityMessage(searchResult.exerciseRef(), searchResult.ambiguousDocuments());
            saveMessage(conversation, Message.Role.assistant, msg);
            return new StreamPrep(conversation.getId(), null, Collections.emptyList(), msg);
        }

        var docChunks = searchResult.chunks();
        log.info("[RAG DEBUG] conversation_id={} user={} active_doc_id={} retrieved_chunks={} documents_used=[{}] scores=[{}]",
                conversation.getId(), userEmail, docId, docChunks.size(),
                docChunks.stream().map(DocumentSearchClient.DocumentChunk::filename).distinct().collect(Collectors.joining(", ")),
                docChunks.stream().map(c -> String.format("%.3f", c.similarity())).collect(Collectors.joining(", ")));

        String prompt = buildPrompt(text, conversation.getSummary(), window, similar, docChunks, docId, userEmail, request.explanationLevel());

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

        var similar = messageEmbeddingRepository.findSimilar(vectorStr, conversation.getId(), contextProps.ragTopK());
        var window = getWindow(conversation.getId(), contextProps.windowSize());

        if (request.preferredDocumentId() != null
                && !request.preferredDocumentId().equals(conversation.getActiveDocumentId())) {
            conversation.setActiveDocumentId(request.preferredDocumentId());
            conversationRepository.save(conversation);
        }

        Long docId = request.preferredDocumentId() != null
                ? request.preferredDocumentId()
                : conversation.getActiveDocumentId();

        var searchResult = docId != null
                ? documentSearchClient.search(buildHydeQuery(text, priorWindow), userEmail, docId)
                : DocumentSearchClient.SearchResult.empty();

        if (searchResult.ambiguous()) {
            String msg = buildAmbiguityMessage(searchResult.exerciseRef(), searchResult.ambiguousDocuments());
            saveMessage(conversation, Message.Role.assistant, msg);
            return new ChatResponse(msg, conversation.getId());
        }

        var docChunks = searchResult.chunks();
        log.info("[RAG DEBUG] conversation_id={} user={} active_doc_id={} retrieved_chunks={} documents_used=[{}] scores=[{}]",
                conversation.getId(), userEmail, docId, docChunks.size(),
                docChunks.stream().map(DocumentSearchClient.DocumentChunk::filename).distinct().collect(Collectors.joining(", ")),
                docChunks.stream().map(c -> String.format("%.3f", c.similarity())).collect(Collectors.joining(", ")));

        var llmResponse = llmClient.generate(buildPrompt(text, conversation.getSummary(), window, similar, docChunks, docId, userEmail, request.explanationLevel()));

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
    public MessagePageDto getConversationMessages(Long conversationId, String userEmail, int limit, Long before) {
        conversationRepository.findByIdAndUserEmail(conversationId, userEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversación no encontrada"));

        int fetchLimit = limit + 1;
        List<Message> raw = before != null
                ? messageRepository.findBeforeId(conversationId, before, fetchLimit)
                : messageRepository.findLastN(conversationId, fetchLimit);

        boolean hasMore = raw.size() > limit;
        List<Message> page = hasMore ? raw.subList(0, limit) : raw;

        // findLastN / findBeforeId return DESC — reverse to chronological ASC
        List<MessageDto> dtos = page.stream()
                .map(m -> new MessageDto(m.getId(), m.getRole().name(), m.getContent(), m.getCreatedAt()))
                .collect(Collectors.toList());
        Collections.reverse(dtos);

        return new MessagePageDto(dtos, hasMore);
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

    private String levelInstruction(Integer level) {
        return switch (level != null ? level : 3) {
            case 1 -> """
                    [INSTRUCCIÓN DE NIVEL — PRIORIDAD MÁXIMA, ANULA CUALQUIER REGLA DE CONCISIÓN]:
                    Nivel BÁSICO. Respondé como si le explicaras a alguien que nunca vio el tema.
                    - Respuesta corta: 2 a 4 oraciones como máximo.
                    - Usá analogías del mundo cotidiano, sin jerga técnica.
                    - Si hay código, mostrá solo la parte mínima e indispensable.
                    - Priorizá que se entienda sobre que sea completo.""";
            case 2 -> """
                    [INSTRUCCIÓN DE NIVEL — PRIORIDAD MÁXIMA, ANULA CUALQUIER REGLA DE CONCISIÓN]:
                    Nivel SIMPLE. Respondé de forma accesible para alguien con nociones básicas.
                    - Respuesta moderada: 1 a 2 párrafos.
                    - Introducí cada término técnico con una breve explicación entre paréntesis.
                    - Incluí un ejemplo concreto y simple.""";
            case 4 -> """
                    [INSTRUCCIÓN DE NIVEL — PRIORIDAD MÁXIMA, ANULA CUALQUIER REGLA DE CONCISIÓN]:
                    Nivel AVANZADO. Respondé con profundidad técnica real.
                    - Respuesta extensa: 3 a 5 párrafos o bloques de código detallados.
                    - Explicá el "por qué" detrás de cada decisión técnica.
                    - Incluí casos de uso, variantes y posibles problemas.
                    - Asumí que el lector conoce los fundamentos.""";
            case 5 -> """
                    [INSTRUCCIÓN DE NIVEL — PRIORIDAD MÁXIMA, ANULA CUALQUIER REGLA DE CONCISIÓN]:
                    Nivel EXPERTO. Respondé como lo haría un senior a otro senior.
                    - Respuesta muy extensa y completa: sin límite de longitud, cubrí todo lo relevante.
                    - Usá terminología técnica precisa sin explicarla.
                    - Incluí implementaciones completas, optimizaciones, trade-offs y edge cases.
                    - Referenciá patrones, estándares o literatura técnica si corresponde.""";
            default -> """
                    [INSTRUCCIÓN DE NIVEL]:
                    Nivel INTERMEDIO. Respondé con un balance entre claridad y profundidad.
                    - Respuesta de longitud media: 2 a 3 párrafos.
                    - Usá terminología técnica estándar con contexto cuando sea necesario.
                    - Incluí un ejemplo práctico.""";
        };
    }

    private String buildPrompt(String userMessage, String summary, List<Message> window,
                               List<SimilarMessageProjection> similar,
                               List<DocumentSearchClient.DocumentChunk> docChunks,
                               Long activeDocId,
                               String userEmail,
                               Integer explanationLevel) {
        var sb = new StringBuilder();
        sb.append(contextProps.systemPrompt()).append("\n");

        if (!docChunks.isEmpty()) {
            sb.append("\nMaterial de estudio del documento activo:\n");
            for (var chunk : docChunks) {
                sb.append("— [").append(chunk.filename());
                if (chunk.pageNumber() != null) sb.append(", p.").append(chunk.pageNumber());
                sb.append("] ").append(chunk.chunkText()).append("\n");
            }
        } else if (activeDocId != null) {
            // Document was set but retrieval returned nothing — tell the model explicitly
            sb.append("\n[SISTEMA: El usuario tiene un documento activo pero no se encontró " +
                      "información relevante en él para esta pregunta. Si la respuesta requiere " +
                      "contenido específico del documento, indicá: " +
                      "\"No encontré información sobre esto en los documentos actuales.\" " +
                      "No uses contexto de otros documentos.]\n");
        }

        if (!similar.isEmpty()) {
            sb.append("\nContexto relevante de esta conversación:\n");
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

        sb.append("\n").append(levelInstruction(explanationLevel));
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

    @Transactional
    public void setActiveDocument(Long conversationId, Long documentId, String userEmail) {
        var conv = conversationRepository.findByIdAndUserEmail(conversationId, userEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        conv.setActiveDocumentId(documentId);
        conversationRepository.save(conv);
    }
}

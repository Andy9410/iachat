package com.academy.chatservice.service;

import com.academy.chatservice.config.ChatContextProperties;
import com.academy.chatservice.model.*;
import com.academy.chatservice.model.tools.LLMToolResponse;
import com.academy.chatservice.model.tools.ToolCall;
import com.academy.chatservice.repository.ConversationRepository;
import com.academy.chatservice.repository.MessageEmbeddingRepository;
import com.academy.chatservice.repository.MessageRepository;
import com.academy.chatservice.service.tools.ToolExecutionContext;
import com.academy.chatservice.service.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final LLMClient llmClient;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MessageEmbeddingRepository messageEmbeddingRepository;
    private final ChatContextProperties contextProps;
    private final DocumentSearchClient documentSearchClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionContext toolExecutionContext;
    private final WhiteboardService whiteboardService;
    private final ReasoningNodeService reasoningNodeService;

    public ChatService(LLMClient llmClient,
                       EmbeddingClient embeddingClient,
                       ConversationRepository conversationRepository,
                       MessageRepository messageRepository,
                       MessageEmbeddingRepository messageEmbeddingRepository,
                       ChatContextProperties contextProps,
                       DocumentSearchClient documentSearchClient,
                       ToolRegistry toolRegistry,
                       ToolExecutionContext toolExecutionContext,
                       WhiteboardService whiteboardService,
                       ReasoningNodeService reasoningNodeService,
                       ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.embeddingClient = embeddingClient;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.messageEmbeddingRepository = messageEmbeddingRepository;
        this.contextProps = contextProps;
        this.documentSearchClient = documentSearchClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutionContext = toolExecutionContext;
        this.whiteboardService = whiteboardService;
        this.reasoningNodeService = reasoningNodeService;
        this.objectMapper = objectMapper;
    }

    public record StreamPrep(
            Long conversationId,
            String prompt,
            List<DocumentSearchClient.DocumentChunk> docChunks,
            String clarificationMessage,
            String userMessage,
            Integer explanationLevel,
            String activeWhiteboardId,
            WhiteboardInterpretationResponse whiteboardInterpretation
    ) {}

    @Transactional
    public StreamPrep prepareStream(ChatRequest request, String userEmail, String firstName) {
        var text = request.message().trim();
        if (text.isBlank()) {
            throw new IllegalArgumentException("El mensaje no puede estar vacío");
        }

        var conversation = resolveConversation(request.conversationId(), text, userEmail);

        long messageCount = messageRepository.countByConversationId(conversation.getId());
        archiveOldMessagesIfNeeded(conversation, messageCount);

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
            return new StreamPrep(conversation.getId(), null, Collections.emptyList(), msg, text,
                    request.explanationLevel(), request.activeWhiteboardId(), request.whiteboardInterpretation());
        }
        var docChunks = searchResult.chunks();
        log.info("[RAG DEBUG] conversation_id={} user={} active_doc_id={} retrieved_chunks={} documents_used=[{}] scores=[{}]",
                conversation.getId(), userEmail, docId, docChunks.size(),
                docChunks.stream().map(DocumentSearchClient.DocumentChunk::filename).distinct().collect(Collectors.joining(", ")),
                docChunks.stream().map(c -> String.format("%.3f", c.similarity())).collect(Collectors.joining(", ")));

        boolean includeArchived = Boolean.TRUE.equals(request.includeFullHistory());
        String prompt = buildPrompt(text, conversation.getSummary(), window, similar, docChunks, docId, userEmail,
                request.explanationLevel(), firstName, includeArchived ? conversation.getArchivedContext() : null,
                request.visiblePage(), request.activeWhiteboardId(), request.whiteboardInterpretation(),
                conversation.getId());

        return new StreamPrep(conversation.getId(), prompt, docChunks, null, text,
                request.explanationLevel(), request.activeWhiteboardId(), request.whiteboardInterpretation());
    }

    @Transactional
    public void finalizeStream(Long conversationId, String assistantResponse, List<String> suggestions) {
        var conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversación no encontrada"));
        saveMessage(conversation, Message.Role.assistant, assistantResponse, suggestions);
    }

    public LLMToolResponse generateWithRegisteredTools(String prompt) {
        if (!llmClient.supportsToolCalling()) {
            return new LLMToolResponse(llmClient.generate(prompt), List.of());
        }
        return llmClient.generateWithTools(prompt, toolRegistry.definitions());
    }

    public boolean shouldUseRegisteredTools(StreamPrep prep) {
        if (prep == null) return false;
        // Whiteboard active: tools always available
        if (shouldUseRegisteredTools(prep.activeWhiteboardId(), prep.whiteboardInterpretation())) return true;
        // No active whiteboard: enable tools when the user explicitly requests one
        // or when the message is a complex problem that benefits from structured reasoning
        String msg = prep.userMessage() != null ? prep.userMessage().toLowerCase(java.util.Locale.ROOT) : "";
        return msg.contains("pizarra") || msg.contains("whiteboard")
                || msg.contains("explicame en") || msg.contains("explicá en")
                || msg.contains("mostralo en") || msg.contains("razonamiento")
                || msg.contains("paso a paso en") || msg.contains("dibuja")
                || msg.contains("abrí la") || msg.contains("abri la");
    }

    public boolean shouldUseRegisteredTools(String activeWhiteboardId, WhiteboardInterpretationResponse whiteboardInterpretation) {
        return whiteboardInterpretation == null
                && activeWhiteboardId != null
                && !activeWhiteboardId.isBlank();
    }

    public Object executeToolCall(ToolCall toolCall, String userEmail, Long conversationId) {
        return toolExecutionContext.withContext(
                userEmail,
                conversationId,
                () -> toolRegistry.execute(toolCall.name(), toolCall.arguments())
        );
    }

    public boolean shouldForceExerciseBreakdown(StreamPrep prep) {
        String text = prep.userMessage() == null ? "" : prep.userMessage().toLowerCase();
        boolean asksForSteps = text.contains("paso a paso")
                || text.contains("desglos")
                || text.contains("guía")
                || text.contains("guia")
                || text.contains("ayudame con el ejercicio")
                || text.contains("ayúdame con el ejercicio")
                || text.contains("explicame el ejercicio")
                || text.contains("explícame el ejercicio");
        boolean mentionsExercise = text.contains("ejercicio") || text.contains("exercise");
        return asksForSteps && mentionsExercise;
    }

    public Object executeExerciseBreakdownFallback(StreamPrep prep) {
        String exerciseTitle = extractExerciseTitle(prep.userMessage());
        String exerciseText = buildExerciseText(prep);
        String userLevel = mapUserLevel(prep.explanationLevel());
        boolean showFullSolution = asksForFullSolution(prep.userMessage());

        try {
            String args = objectMapper.writeValueAsString(Map.of(
                    "exerciseText", exerciseText,
                    "exerciseTitle", exerciseTitle,
                    "userLevel", userLevel,
                    "showFullSolution", showFullSolution
            ));
            return toolRegistry.execute("break_down_exercise", args);
        } catch (Exception e) {
            throw new RuntimeException("No se pudo ejecutar fallback de break_down_exercise", e);
        }
    }

    @Transactional
    public ChatResponse process(ChatRequest request, String userEmail, String firstName) {
        var text = request.message().trim();

        if (text.isBlank()) {
            throw new IllegalArgumentException("El mensaje no puede estar vacío");
        }

        var conversation = resolveConversation(request.conversationId(), text, userEmail);

        long messageCount = messageRepository.countByConversationId(conversation.getId());
        archiveOldMessagesIfNeeded(conversation, messageCount);

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

        boolean includeArchived = Boolean.TRUE.equals(request.includeFullHistory());

        String prompt = buildPrompt(text, conversation.getSummary(), window, similar, docChunks, docId, userEmail,
                request.explanationLevel(), firstName, includeArchived ? conversation.getArchivedContext() : null,
                request.visiblePage(), request.activeWhiteboardId(), request.whiteboardInterpretation(),
                conversation.getId());

        String llmResponse;
        boolean useRegisteredTools = llmClient.supportsToolCalling()
                && shouldUseRegisteredTools(request.activeWhiteboardId(), request.whiteboardInterpretation());
        log.info("[LLM] provider={} model={} tools={} use_registered_tools={}",
                llmClient.getClass().getSimpleName(), llmClient.modelName(), llmClient.supportsToolCalling(), useRegisteredTools);
        if (useRegisteredTools) {
            var toolAwareResponse = generateWithRegisteredTools(prompt);
            if (toolAwareResponse.hasToolCalls()) {
                Object toolResult = executeToolCall(toolAwareResponse.toolCalls().get(0), userEmail, conversation.getId());
                try {
                    llmResponse = objectMapper.writeValueAsString(toolResult);
                } catch (Exception e) {
                    throw new RuntimeException("No se pudo serializar el resultado de la tool", e);
                }
            } else {
                llmResponse = toolAwareResponse.content();
            }
        } else {
            llmResponse = llmClient.generate(prompt);
        }

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

    @Transactional
    public ConversationSummaryDto createConversation(String userEmail, String title) {
        var conversation = new Conversation();
        conversation.setUserEmail(userEmail);
        conversation.setTitle(title == null || title.isBlank() ? "Nueva conversación" : title.trim());
        var saved = conversationRepository.save(conversation);
        return new ConversationSummaryDto(saved.getId(), saved.getTitle(), saved.getCreatedAt(), 0);
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
                .map(m -> new MessageDto(m.getId(), m.getRole().name(), m.getContent(), m.getCreatedAt(), parseSuggestions(m.getSuggestions())))
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

        log.info("[LLM] provider={} model={}", llmClient.getClass().getSimpleName(), llmClient.modelName());
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

    private void archiveOldMessagesIfNeeded(Conversation conversation, long messageCount) {
        int threshold = contextProps.archiveThreshold();
        if (messageCount <= threshold) return;
        if (conversation.getArchivedMessageCount() > 0) return; // already archived

        List<Message> toArchive = messageRepository.findFirstN(conversation.getId(), threshold);

        var sb = new StringBuilder();
        for (Message m : toArchive) {
            String role = m.getRole() == Message.Role.user ? "Estudiante" : "Tutor";
            sb.append(role).append(": ").append(m.getContent()).append("\n");
        }

        conversation.setArchivedContext(sb.toString().stripTrailing());
        conversation.setArchivedMessageCount(threshold);
        conversationRepository.save(conversation);
        log.info("[ARCHIVE] conversation_id={} archived {} messages", conversation.getId(), threshold);
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
        saveMessage(conversation, role, content, List.of());
    }

    private void saveMessage(Conversation conversation, Message.Role role, String content, List<String> suggestions) {
        var msg = new Message();
        msg.setConversation(conversation);
        msg.setRole(role);
        msg.setContent(content);
        if (suggestions != null && !suggestions.isEmpty()) {
            try { msg.setSuggestions(objectMapper.writeValueAsString(suggestions)); } catch (Exception ignored) {}
        }
        messageRepository.save(msg);
    }

    private List<String> parseSuggestions(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); } catch (Exception e) { return List.of(); }
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
                               Integer explanationLevel, String firstName,
                               String archivedContext, Integer visiblePage,
                               String activeWhiteboardId,
                               WhiteboardInterpretationResponse whiteboardInterpretation,
                               Long conversationId) {
        String personalization = """
            [SISTEMA]
            El nombre del estudiante es %s.
            
            - Usá su nombre ocasionalmente.
            - Especialmente en saludos o explicaciones importantes.
            - No uses el nombre en todas las respuestas.
            - Mantené un tono natural y humano.
            """.formatted(firstName);


        var sb = new StringBuilder();
        sb.append(contextProps.systemPrompt()).append("\n");
        sb.append(personalization).append("\n");

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
                      "información relevante en él para esta pregunta. Antes de indicar falta de " +
                      "información, buscá exclusivamente en la memoria conversacional provista abajo " +
                      "(contexto relevante, contexto archivado, resumen e historial reciente). " +
                      "Si la memoria contiene información útil, respondé solo con esa memoria e indicá " +
                      "de forma natural: \"No encontré esta información en los documentos adjuntos, " +
                      "pero anteriormente en esta conversación se mencionó que...\". " +
                      "No consideres la pregunta actual como memoria útil. Si tampoco hay información " +
                      "útil en la memoria, indicá que no disponés de información suficiente para responder. " +
                      "No inventes información y no uses contexto de otros documentos.]\n");
        }

        if (!similar.isEmpty()) {
            sb.append("\nContexto relevante de esta conversación:\n");
            for (var s : similar) {
                String role = "user".equals(s.getRole()) ? "Estudiante" : "Tutor";
                sb.append(role).append(": ").append(s.getContent()).append("\n");
            }
        }

        if (archivedContext != null && !archivedContext.isBlank()) {
            sb.append("\nContexto archivado (mensajes iniciales de esta conversación):\n")
              .append(archivedContext).append("\n");
        }

        if (summary != null && !summary.isBlank()) {
            sb.append("\nResumen de esta conversación:\n").append(summary).append("\n");
        }

        if (whiteboardInterpretation != null) {
            sb.append(buildWhiteboardContext(whiteboardInterpretation, activeWhiteboardId));
        } else if (activeWhiteboardId != null && !activeWhiteboardId.isBlank()) {
            sb.append(buildWhiteboardContext(activeWhiteboardId, userEmail));
        }

        if (conversationId != null) {
            String entriesCtx = whiteboardService.buildEntriesContext(conversationId);
            if (!entriesCtx.isBlank()) sb.append(entriesCtx);

            String reasoningCtx = reasoningNodeService.buildReasoningContext(conversationId);
            if (!reasoningCtx.isBlank()) sb.append(reasoningCtx);
        }

        if (!window.isEmpty()) {
            sb.append("\nHistorial reciente:\n");
            for (Message m : window) {
                String role = m.getRole() == Message.Role.user ? "Estudiante" : "Tutor";
                sb.append(role).append(": ").append(m.getContent()).append("\n");
            }
        }

        sb.append("\n").append(levelInstruction(explanationLevel));
        sb.append("""

            [TOOLS DISPONIBLES]
            Si el estudiante pide ayuda paso a paso, guía progresiva, desglose de un ejercicio,
            explicación por etapas, o solicita resolver un ejercicio identificado del PDF, usá la tool
            break_down_exercise en lugar de responder como texto normal.

            REGLA CRÍTICA — Si el estudiante menciona "pizarra", "ejemplo en la pizarra",
              "dame un ejemplo en la pizarra", "explicame en la pizarra", "mostralo en la pizarra"
              o cualquier variante que pida contenido visual:
              1. NUNCA respondas el contenido como texto en el chat.
              2. Llamá PRIMERO open_whiteboard.
              3. Luego llamá inject_whiteboard_content con TODO el contenido (título, pasos, fórmulas).
              4. En el chat solo respondé UNA oración corta como "Ya lo escribí en la pizarra →".
            - inject_whiteboard_content fragmenta el contenido en bloques pequeños y los persiste.
              Tipos de bloque: TITLE (título), TEXT (párrafo), STEP (paso numerado), FORMULA (expresión),
              EXAMPLE (ejemplo), WARNING (advertencia), QUESTION (pregunta al estudiante), SYSTEM_NOTE.
              Cada bloque lleva type, content y orderIndex (1, 2, 3...).
              El LLM puede razonar sobre lo que ya inyectó en la pizarra en mensajes futuros.
            - update_whiteboard sigue disponible para agregar entradas simples sin metadata.

            Razonamiento estructurado (Reasoning Graph):
            - Cuando el usuario plantea un problema complejo (asignación, optimización, algoritmo,
              demostración matemática), usá create_reasoning_node ANTES de resolver.
            - Secuencia recomendada: PROBLEM → PLAN → un SUBPROBLEM por cada parte → FINAL_ANSWER.
            - Si ya hay nodos en el Reasoning Graph, consultá su estado y continuá desde cualquier nodo.
            - Después de resolver cada SUBPROBLEM, creá un SUBPROBLEM_SOLUTION como hijo.
            - El FINAL_ANSWER siempre tiene parentNodeId apuntando al nodo PLAN o raíz del árbol.
            - exerciseText debe contener el enunciado más completo disponible a partir del mensaje y del material de estudio.
            - exerciseTitle debe ser el identificador más claro disponible, por ejemplo "Ejercicio 2".
            - userLevel debe mapearse a basico, intermedio o avanzado según el nivel de explicación actual.
            - showFullSolution debe ser false salvo que el estudiante pida explícitamente la solución completa, respuesta final o resolución completa.
            """);

        if (activeWhiteboardId != null && !activeWhiteboardId.isBlank()) {
            sb.append("""
            Pizarra Inteligente:
            - Si el estudiante pregunta "¿Está bien?", "¿Qué me falta?", "Revisalo", "Continuemos",
              "Ayudame con este algoritmo" o pide revisar lo que hizo visualmente, usá tools de pizarra.
            - Si el bloque [PIZARRA INTERPRETADA] está presente, NO llames interpret_whiteboard; respondé directamente usando su Tipo, Texto OCR, Ecuación detectada y Resumen semántico.
            - Si el bloque [PIZARRA ACTIVA] incluye whiteboardId pero no hay interpretación, usá interpret_whiteboard con ese ID.
            - Si no hay whiteboardId visible, usá get_active_whiteboard para consultar la pizarra activa.
            - No respondas sobre nodos de inicio/fin si la interpretación indica que la pizarra es matemática o texto libre.
            - Para resumir lo dibujado, usá summarize_whiteboard.
            - Para sugerir cambios visuales o paso a paso, usá propose_whiteboard_change.
            - Nunca modifiques la pizarra directamente. propose_whiteboard_change solo devuelve una sugerencia; el estudiante decide si aplicarla.

            REGLA CRÍTICA — Paso a paso en la pizarra:
            - Si la pizarra está activa (hay whiteboardId) Y el estudiante pide una explicación paso a paso,
              resolución de un ejercicio, o el desarrollo de un problema, NO respondas con texto en el chat.
            - En cambio, llamá propose_whiteboard_change con el parámetro "steps": un array donde cada elemento
              es un paso de la solución (string). Ejemplo:
              steps: ["Identificar la ecuación: 2x + 3 = 7", "Despejar x: 2x = 4", "Resultado: x = 2"]
            - El título (instruction) debe ser un resumen breve de lo que se resuelve.
            - En el chat respondé solo con una frase corta como:
              "Escribí el paso a paso en la pizarra. ¿Querés que sigamos con algún paso en particular?"
            """);
        }
        sb.append("\nPregunta del estudiante: ").append(userMessage);
        sb.append("""

            \n\nAl terminar tu respuesta añadí una línea de sugerencias con este formato exacto:
            |||["sugerencia real 1","sugerencia real 2","sugerencia real 3"]
            Reemplazá esos textos por 3 preguntas o acciones concretas en español, entre 2 y 8 palabras cada una,
            relacionadas con el tema respondido. No copies las palabras "sugerencia real" ni uses placeholders.
            No agregues markdown, etiquetas, títulos, bloques de código ni texto extra después de esa línea.""");

        return sb.toString();
    }

    private String buildWhiteboardContext(String activeWhiteboardId, String userEmail) {
        try {
            WhiteboardInterpretationResponse interpretation = whiteboardService.interpret(activeWhiteboardId, userEmail);
            return buildWhiteboardContext(interpretation, activeWhiteboardId);
        } catch (Exception e) {
            log.warn("No se pudo agregar contexto de pizarra activa id={}", activeWhiteboardId, e);
            return "\n[PIZARRA ACTIVA]\n"
                    + "whiteboardId: " + activeWhiteboardId + "\n"
                    + "No se pudo recuperar el contenido guardado de la pizarra. "
                    + "Podés usar las tools de pizarra con este ID si la consulta lo requiere.\n";
        }
    }

    private String buildWhiteboardContext(WhiteboardInterpretationResponse interpretation, String fallbackWhiteboardId) {
        String whiteboardId = interpretation.whiteboardId() != null ? interpretation.whiteboardId() : fallbackWhiteboardId;
        String type = interpretation.type() != null ? interpretation.type() : "unknown";
        String ocrText = interpretation.ocrText() != null ? interpretation.ocrText() : "";
        String equation = interpretation.equation();
        double confidence = interpretation.confidence();
        boolean hasEquation = equation != null && !equation.isBlank();
        boolean hasOcrText = !ocrText.isBlank();

        StringBuilder sb = new StringBuilder();
        sb.append("\n[PIZARRA ACTIVA]\n");
        sb.append("whiteboardId: ").append(whiteboardId != null ? whiteboardId : "").append("\n");
        sb.append("título: ").append(interpretation.title() != null ? interpretation.title() : "").append("\n");
        sb.append("ejercicio: ").append(interpretation.exerciseLabel() != null ? interpretation.exerciseLabel() : "").append("\n");
        sb.append("documentId: ").append(interpretation.documentId() != null ? interpretation.documentId() : "").append("\n");
        sb.append("\n[PIZARRA INTERPRETADA]\n");
        sb.append("Tipo: ").append(type).append("\n");
        if (hasEquation) {
            sb.append("Ecuación detectada:\n").append(equation).append("\n");
        }
        sb.append("Texto OCR: ").append(ocrText).append("\n");
        sb.append("Elementos estructurados: ").append(interpretation.structuredElements()).append("\n");
        sb.append("Resumen semántico: ").append(interpretation.semanticSummary()).append("\n");
        sb.append("Confianza: ").append(confidence).append("\n");

        // ── Instrucciones condicionales según calidad de interpretación ────────────
        sb.append("\n[INSTRUCCIONES ESPECÍFICAS SEGÚN INTERPRETACIÓN]\n");

        if ("unknown".equals(type)) {
            sb.append("PRIORIDAD MÁXIMA — type=unknown: No se pudo interpretar la pizarra.\n");
            sb.append("- NO generes párrafos largos ni análisis.\n");
            sb.append("- Respondé breve: \"No pude interpretar la pizarra con claridad. ");
            sb.append("Probá escribir la ecuación con la herramienta de texto o seleccioná modo Matemática.\"\n");

        } else if (confidence < 0.75) {
            sb.append("PRIORIDAD MÁXIMA — Confianza BAJA (").append(confidence).append(" < 0.75):\n");
            sb.append("- NO generes explicaciones largas ni análisis detallados.\n");
            sb.append("- NO inventes contenido ni des recomendaciones genéricas.\n");
            sb.append("- NO uses frases como \"sería útil tener más información\", ");
            sb.append("\"la calidad de la imagen...\", \"podría deberse a varios factores...\" o similares.\n");
            if (hasEquation) {
                sb.append("- Mostrá claramente la ecuación detectada y preguntá al estudiante si es correcta.\n");
                sb.append("- Ejemplo: \"Detecté posiblemente: `").append(equation).append("` ¿Está bien?\"\n");
                sb.append("- Si el estudiante confirma la ecuación, ahí sí podés resolverla. ");
                sb.append("Si no, pedí que la escriba con la herramienta de texto.\n");
            } else if (hasOcrText) {
                sb.append("- Preguntá al estudiante si el texto detectado en la pizarra es correcto.\n");
                sb.append("- No analices ni resuelvas hasta tener confirmación.\n");
            } else if ("graph".equals(type)) {
                sb.append("- La pizarra parece contener una gráfica en ejes cartesianos, aunque la lectura no es perfecta.\n");
                sb.append("- NO le pidas al estudiante que escriba una ecuación: el contenido es una gráfica, no una expresión.\n");
                sb.append("- Ofrecé analizar la gráfica: pendiente, puntos de corte, crecimiento/decrecimiento, comportamiento de la función.\n");
                sb.append("- Ejemplo: \"Parece que dibujaste una gráfica con ejes cartesianos. ¿Querés que analice pendiente, puntos de corte o comportamiento de la función?\"\n");
            } else {
                sb.append("- El Texto OCR está vacío. Indicá que no se pudo leer con claridad.\n");
                sb.append("- Sugerí usar la herramienta de texto para escribir la ecuación.\n");
            }
            sb.append("- Sé breve: 2 a 3 oraciones como máximo. Pedí confirmación al estudiante.\n");

        } else {
            // confidence >= 0.75
            sb.append("Confianza ALTA (").append(confidence).append(" ≥ 0.75): podés proceder con el análisis normalmente.\n");
            if ("math".equals(type) && hasEquation) {
                sb.append("- Podés resolver o analizar la ecuación matemática detectada.\n");
            }
            if ("graph".equals(type)) {
                sb.append("- La pizarra contiene una gráfica sobre ejes cartesianos.\n");
                sb.append("- No le pidas al estudiante que escriba la ecuación.\n");
                sb.append("- Ofrecé analizar: pendiente, puntos de corte, máximos y mínimos, comportamiento de la función.\n");
                sb.append("- Ejemplo: \"Detecté una posible gráfica sobre ejes cartesianos. Parece haber una curva creciente. ¿Querés que analice pendiente, puntos de corte, máximos y mínimos, o comportamiento de la función?\"\n");
            }
            if ("geometry".equals(type)) {
                sb.append("- La pizarra contiene figuras geométricas.\n");
                sb.append("- Ofrecé analizar: propiedades, área, perímetro, ángulos, relaciones entre figuras.\n");
            }
            if ("text".equals(type)) {
                sb.append("- Respondé basándote en el texto detectado en la pizarra.\n");
            }
        }

        // Reglas generales siempre presentes
        sb.append("- Nunca modifiques la pizarra directamente.\n");
        sb.append("- Si necesitás una propuesta visual, usá propose_whiteboard_change con este whiteboardId.\n");

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

    private String extractExerciseTitle(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return "Ejercicio";
        var matcher = java.util.regex.Pattern
                .compile("(?i)ejercicio\\s+([\\w.-]+)")
                .matcher(userMessage);
        if (matcher.find()) {
            return "Ejercicio " + matcher.group(1);
        }
        return "Ejercicio";
    }

    private String buildExerciseText(StreamPrep prep) {
        if (prep.docChunks() != null && !prep.docChunks().isEmpty()) {
            return prep.docChunks().stream()
                    .map(DocumentSearchClient.DocumentChunk::chunkText)
                    .filter(text -> text != null && !text.isBlank())
                    .limit(4)
                    .collect(Collectors.joining("\n\n"));
        }
        return prep.userMessage();
    }

    private String mapUserLevel(Integer explanationLevel) {
        return switch (explanationLevel != null ? explanationLevel : 3) {
            case 1, 2 -> "basico";
            case 4, 5 -> "avanzado";
            default -> "intermedio";
        };
    }

    private boolean asksForFullSolution(String userMessage) {
        String text = userMessage == null ? "" : userMessage.toLowerCase();
        return text.contains("solución completa")
                || text.contains("solucion completa")
                || text.contains("respuesta final")
                || text.contains("resolvelo completo")
                || text.contains("resuélvelo completo")
                || text.contains("resolver completo");
    }

    private String buildHydeQuery(String text, List<Message> priorWindow) {
        String base = buildSearchQuery(text, priorWindow);
        try {
            String hydePrompt = "Responde en 2 oraciones técnicas y concisas sobre PROGRAMACIÓN/DESARROLLO DE SOFTWARE a: \"" + base
                    + "\". Solo el contenido técnico, sin saludos ni explicaciones adicionales.";
            log.info("[LLM] provider={} model={}", llmClient.getClass().getSimpleName(), llmClient.modelName());
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

    @Transactional(readOnly = true)
    public Map<String, Object> getArchivedContext(Long conversationId, String userEmail) {
        var conv = conversationRepository.findByIdAndUserEmail(conversationId, userEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversación no encontrada"));
        return java.util.Map.of(
                "archivedMessageCount", conv.getArchivedMessageCount(),
                "hasArchivedContext", conv.getArchivedContext() != null,
                "archivedContext", conv.getArchivedContext() != null ? conv.getArchivedContext() : ""
        );
    }

    @Transactional
    public void setActiveDocument(Long conversationId, Long documentId, String userEmail) {
        var conv = conversationRepository.findByIdAndUserEmail(conversationId, userEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        conv.setActiveDocumentId(documentId);
        conversationRepository.save(conv);
    }
}

package com.academy.chatservice.service;

import com.academy.chatservice.model.*;
import com.academy.chatservice.model.InjectWhiteboardRequest;
import com.academy.chatservice.model.tools.UpdateWhiteboardArgs;
import com.academy.chatservice.repository.ConversationRepository;
import com.academy.chatservice.repository.WhiteboardEntryRepository;
import com.academy.chatservice.repository.WhiteboardRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class WhiteboardService {

    private static final Map<String, Object> EMPTY_DATA = Map.of("version", 1, "elements", List.of());
    private static final Set<String> TEACH_BLOCK_TYPES = Set.of(
            "TITLE", "TEXT", "STEP", "FORMULA", "EXAMPLE", "WARNING", "QUESTION", "NOTE"
    );
    private static final Pattern JSON_STRING_FIELD = Pattern.compile("\"%s\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private final ConversationRepository conversationRepository;
    private final WhiteboardRepository whiteboardRepository;
    private final WhiteboardEntryRepository entryRepository;
    private final ObjectMapper objectMapper;
    private final WhiteboardImageRenderer imageRenderer;
    private final WhiteboardInterpretService interpretService;

    public WhiteboardService(
            ConversationRepository conversationRepository,
            WhiteboardRepository whiteboardRepository,
            WhiteboardEntryRepository entryRepository,
            WhiteboardImageRenderer imageRenderer,
            WhiteboardInterpretService interpretService,
            ObjectMapper objectMapper
    ) {
        this.conversationRepository = conversationRepository;
        this.whiteboardRepository = whiteboardRepository;
        this.entryRepository = entryRepository;
        this.imageRenderer = imageRenderer;
        this.interpretService = interpretService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<WhiteboardDto> list(Long conversationId, String userEmail) {
        requireConversation(conversationId, userEmail);
        return whiteboardRepository.findByConversationIdOrderByUpdatedAtDesc(conversationId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public WhiteboardDto active(Long conversationId, String userEmail) {
        Conversation conversation = requireConversation(conversationId, userEmail);
        return whiteboardRepository.findFirstByConversationIdOrderByUpdatedAtDesc(conversationId)
                .map(this::toDto)
                .orElseGet(() -> {
                    Whiteboard whiteboard = new Whiteboard();
                    whiteboard.setConversation(conversation);
                    whiteboard.setTitle("Resolución guiada");
                    whiteboard.setData(writeData(EMPTY_DATA));
                    return toDto(whiteboardRepository.save(whiteboard));
                });
    }

    @Transactional
    public WhiteboardDto createOrGet(Long conversationId, String userEmail, WhiteboardRequest request) {
        Conversation conversation = requireConversation(conversationId, userEmail);
        String exerciseLabel = normalize(request.exerciseLabel());
        if (exerciseLabel != null) {
            Optional<Whiteboard> existing = whiteboardRepository
                    .findFirstByConversationIdAndExerciseLabelIgnoreCaseOrderByUpdatedAtDesc(conversationId, exerciseLabel);
            if (existing.isPresent()) return toDto(existing.get());
        }

        Whiteboard whiteboard = new Whiteboard();
        whiteboard.setConversation(conversation);
        whiteboard.setDocumentId(request.documentId());
        whiteboard.setExerciseLabel(exerciseLabel);
        whiteboard.setTitle(normalize(request.title()) != null
                ? request.title().trim()
                : exerciseLabel != null ? "Resolución guiada - " + exerciseLabel : "Resolución guiada");
        whiteboard.setData(writeData(request.data() != null ? request.data() : EMPTY_DATA));
        return toDto(whiteboardRepository.save(whiteboard));
    }

    @Transactional
    public WhiteboardDto update(String id, String userEmail, WhiteboardRequest request) {
        Whiteboard whiteboard = requireWhiteboard(id, userEmail);
        if (request.title() != null && !request.title().isBlank()) whiteboard.setTitle(request.title().trim());
        if (request.documentId() != null) whiteboard.setDocumentId(request.documentId());
        if (request.exerciseLabel() != null) whiteboard.setExerciseLabel(normalize(request.exerciseLabel()));
        if (request.data() != null) whiteboard.setData(writeData(request.data()));
        return toDto(whiteboardRepository.save(whiteboard));
    }

    @Transactional
    public void delete(String id, String userEmail) {
        Whiteboard whiteboard = requireWhiteboard(id, userEmail);
        whiteboardRepository.delete(whiteboard);
    }

    @Transactional(readOnly = true)
    public WhiteboardDto getExerciseWhiteboard(Long conversationId, String exerciseLabel, String userEmail) {
        requireConversation(conversationId, userEmail);
        return whiteboardRepository
                .findFirstByConversationIdAndExerciseLabelIgnoreCaseOrderByUpdatedAtDesc(conversationId, exerciseLabel)
                .map(this::toDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pizarra no encontrada"));
    }

    @Transactional(readOnly = true)
    public WhiteboardSummaryResponse summarize(String id, String userEmail) {
        Whiteboard whiteboard = requireWhiteboard(id, userEmail);
        return new WhiteboardSummaryResponse(
                "whiteboard_summary",
                toPublicId(whiteboard.getId()),
                whiteboard.getTitle(),
                summarizeElements(elements(readData(whiteboard.getData())))
        );
    }

    @Transactional(readOnly = true)
    public WhiteboardAnalysisResponse analyze(String id, String userEmail) {
        Whiteboard whiteboard = requireWhiteboard(id, userEmail);
        List<Map<String, Object>> elements = elements(readData(whiteboard.getData()));
        String summary = summarizeElements(elements);
        List<String> observations = new ArrayList<>();

        if (elements.isEmpty()) {
            observations.add("La pizarra está vacía; falta representar el planteo inicial.");
        }
        boolean hasStart = containsText(elements, "inicio") || containsText(elements, "start");
        boolean hasEnd = containsText(elements, "fin") || containsText(elements, "final") || containsText(elements, "end");
        boolean hasDecision = elements.stream().anyMatch(el -> "diamond".equals(String.valueOf(el.get("type"))));
        boolean hasArrow = elements.stream().anyMatch(el -> "arrow".equals(String.valueOf(el.get("type"))));
        boolean hasNegative = containsText(elements, "no") || containsText(elements, "falso") || containsText(elements, "negativo");

        if (!hasStart) observations.add("Falta un nodo o texto de inicio claro.");
        if (!hasEnd) observations.add("No existe nodo final visible.");
        if (hasDecision && !hasNegative) observations.add("Falta representar el caso negativo de la decisión.");
        if (elements.size() > 1 && !hasArrow) observations.add("Los elementos no muestran flujo; agregá flechas entre pasos.");
        if (observations.isEmpty()) observations.add("La pizarra tiene una estructura básica coherente.");

        return new WhiteboardAnalysisResponse(
                "whiteboard_analysis",
                toPublicId(whiteboard.getId()),
                whiteboard.getTitle(),
                summary,
                observations
        );
    }

    @Transactional(readOnly = true)
    public WhiteboardSuggestionResponse proposeChange(String id, String instruction, List<String> steps, String userEmail) {
        Whiteboard whiteboard = requireWhiteboard(id, userEmail);
        List<Map<String, Object>> proposed = new ArrayList<>();

        if (steps != null && !steps.isEmpty()) {
            // Modo paso a paso: un elemento de texto por paso, apilados verticalmente
            int y = 40;
            for (int i = 0; i < steps.size(); i++) {
                String stepText = steps.get(i);
                if (stepText == null || stepText.isBlank()) continue;
                var element = new LinkedHashMap<String, Object>();
                element.put("id", "sug_" + UUID.randomUUID());
                element.put("type", "text");
                element.put("x", 40);
                element.put("y", y);
                element.put("text", (i + 1) + ". " + stepText.trim());
                proposed.add(element);
                y += 72;
            }
        } else {
            // Modo elemento único
            List<Map<String, Object>> existing = elements(readData(whiteboard.getData()));
            int offset = Math.min(420, 80 + existing.size() * 24);
            String text = instruction == null || instruction.isBlank() ? "Completar el siguiente paso" : instruction.trim();
            var element = new LinkedHashMap<String, Object>();
            element.put("id", "sug_" + UUID.randomUUID());
            element.put("type", inferType(text));
            element.put("x", offset);
            element.put("y", 120);
            element.put("width", 150);
            element.put("height", 72);
            element.put("text", cleanSuggestionText(text));
            proposed.add(element);
        }

        String title = instruction == null || instruction.isBlank() ? "Paso a paso" : cleanSuggestionTitle(instruction);
        return new WhiteboardSuggestionResponse(
                "whiteboard_suggestion",
                toPublicId(whiteboard.getId()),
                title,
                proposed
        );
    }

    @Transactional(readOnly = true)
    public WhiteboardSuggestionResponse proposeChange(String id, String instruction, String userEmail) {
        return proposeChange(id, instruction, null, userEmail);
    }

    @Transactional(readOnly = true)
    public WhiteboardInterpretationResponse interpret(String id, String userEmail) {
        Whiteboard whiteboard = requireWhiteboard(id, userEmail);
        return interpretWhiteboard(whiteboard, null);
    }

    @Transactional(readOnly = true)
    public WhiteboardInterpretationResponse interpret(WhiteboardInterpretRequest request, String userEmail) {
        if (request.conversationId() != null) {
            requireConversation(request.conversationId(), userEmail);
        }
        Whiteboard whiteboard = requireWhiteboard(request.whiteboardId(), userEmail);
        if (request.conversationId() != null
                && !request.conversationId().equals(whiteboard.getConversation().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pizarra no encontrada");
        }
        return interpretWhiteboard(whiteboard, request.imageBase64(), request.interpretMode());
    }

    private WhiteboardInterpretationResponse interpretWhiteboard(Whiteboard whiteboard, String imageBase64) {
        return interpretWhiteboard(whiteboard, imageBase64, "auto");
    }

    private WhiteboardInterpretationResponse interpretWhiteboard(Whiteboard whiteboard, String imageBase64, String interpretMode) {
        List<Map<String, Object>> elements = elements(readData(whiteboard.getData()));
        String structured = summarizeStructured(elements);
        return interpretService.interpret(
                toPublicId(whiteboard.getId()),
                whiteboard.getTitle(),
                whiteboard.getExerciseLabel(),
                whiteboard.getDocumentId(),
                elements,
                structured,
                resolveImage(imageBase64, elements),
                interpretMode
        );
    }

    private String resolveImage(String imageBase64, List<Map<String, Object>> elements) {
        String image = normalize(imageBase64);
        if (image != null) return image;
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(imageRenderer.renderPng(elements));
    }

    // ─── Teaching whiteboard methods ────────────────────────────────────────

    @Transactional
    public WhiteboardDto openForTeaching(Long conversationId, String title, String mode, String userEmail) {
        Conversation conversation = requireConversation(conversationId, userEmail);
        // Reutilizar pizarra activa de enseñanza si existe
        var existing = whiteboardRepository
                .findFirstByConversationIdOrderByUpdatedAtDesc(conversationId)
                .filter(w -> "teaching".equals(w.getMode()) && "ACTIVE".equals(w.getStatus()));
        if (existing.isPresent()) return toDto(existing.get());

        Whiteboard wb = new Whiteboard();
        wb.setConversation(conversation);
        String resolvedTitle = title != null && !title.isBlank() ? title.trim() : "Resolución guiada";
        wb.setTitle(resolvedTitle);
        wb.setIntent(resolvedTitle);
        wb.setMode(mode != null ? mode : "teaching");
        wb.setStatus("ACTIVE");
        wb.setData(writeData(EMPTY_DATA));
        return toDto(whiteboardRepository.save(wb));
    }

    /** Actualiza la intención educativa actual del workspace (al cambiar de tarea). */
    @Transactional
    public void updateIntent(String whiteboardId, String intent, String userEmail) {
        Whiteboard whiteboard = requireWhiteboard(whiteboardId, userEmail);
        whiteboard.setIntent(intent != null && !intent.isBlank() ? intent.trim() : whiteboard.getIntent());
        whiteboardRepository.save(whiteboard);
    }

    @Transactional
    public List<WhiteboardEntryDto> addEntries(String whiteboardId, Long conversationId, List<UpdateWhiteboardArgs.StepArg> entries, String userEmail) {
        Whiteboard whiteboard = requireWhiteboard(whiteboardId, userEmail);
        requireConversation(conversationId, userEmail);
        if (!whiteboard.getConversation().getId().equals(conversationId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "La pizarra no pertenece a esta conversación");
        }
        if (entries == null || entries.isEmpty()) return List.of();

        List<WhiteboardEntry> saved = new ArrayList<>();
        for (var e : entries) {
            WhiteboardEntry entry = new WhiteboardEntry();
            entry.setWhiteboard(whiteboard);
            entry.setConversationId(conversationId);
            entry.setType(normalizeBlockType(e.type()));
            entry.setAuthor(e.author() != null && !e.author().isBlank() ? e.author() : "assistant");
            entry.setContent(e.content() != null ? e.content() : "");
            entry.setOrderIndex(e.orderIndex());
            saved.add(entryRepository.save(entry));
        }
        return saved.stream().map(this::toEntryDto).toList();
    }

    @Transactional(readOnly = true)
    public List<WhiteboardEntryDto> getEntries(String whiteboardId, Long conversationId, String userEmail) {
        Whiteboard whiteboard = requireWhiteboard(whiteboardId, userEmail);
        requireConversation(conversationId, userEmail);
        // La pizarra debe pertenecer a esta conversación: las conversaciones no comparten pizarra.
        if (!whiteboard.getConversation().getId().equals(conversationId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "La pizarra no pertenece a esta conversación");
        }
        return entryRepository.findByWhiteboard_IdOrderByOrderIndexAsc(whiteboard.getId())
                .stream().map(this::toEntryDto).toList();
    }

    @Transactional
    public List<WhiteboardEntryDto> injectBlocks(String whiteboardId, Long conversationId,
                                                  List<InjectWhiteboardRequest.BlockRequest> blocks,
                                                  String userEmail) {
        Whiteboard whiteboard = requireWhiteboard(whiteboardId, userEmail);
        requireConversation(conversationId, userEmail);
        if (!whiteboard.getConversation().getId().equals(conversationId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "La pizarra no pertenece a esta conversación");
        }
        if (blocks == null || blocks.isEmpty()) return List.of();

        // Auto-append: determine the next orderIndex from what's already saved
        int baseIndex = entryRepository.findMaxOrderIndexByWhiteboardId(whiteboard.getId()) + 1;

        List<WhiteboardEntry> saved = new ArrayList<>();
        for (var block : blocks) {
            WhiteboardEntry entry = new WhiteboardEntry();
            entry.setWhiteboard(whiteboard);
            entry.setConversationId(conversationId);
            entry.setType(normalizeBlockType(block.type()));
            entry.setAuthor(block.author() != null ? block.author() : "assistant");
            entry.setContent(block.content() != null ? block.content() : "");
            entry.setOrderIndex(block.orderIndex() > 0 ? baseIndex + block.orderIndex() - 1 : baseIndex + saved.size());
            if (block.metadata() != null && !block.metadata().isEmpty()) {
                entry.setMetadata(writeMetadata(block.metadata()));
            }
            saved.add(entryRepository.save(entry));
        }
        return saved.stream().map(this::toEntryDto).toList();
    }

    public String buildEntriesContext(Long conversationId) {
        var whiteboard = whiteboardRepository.findFirstByConversationIdOrderByUpdatedAtDesc(conversationId).orElse(null);
        var entries = entryRepository.findByConversationIdOrderByOrderIndexAsc(conversationId);
        if (whiteboard == null && entries.isEmpty()) return "";

        var sb = new StringBuilder("\n[RESOLUCIÓN GUIADA — workspace de esta conversación]\n");
        if (whiteboard != null) {
            sb.append("whiteboardId: ").append(whiteboard.getId()).append("\n");
            if (whiteboard.getIntent() != null && !whiteboard.getIntent().isBlank()) {
                sb.append("intención actual: ").append(whiteboard.getIntent()).append("\n");
            } else if (whiteboard.getTitle() != null && !whiteboard.getTitle().isBlank()) {
                sb.append("intención actual: ").append(whiteboard.getTitle()).append("\n");
            }
            sb.append("estado: ").append(whiteboard.getStatus()).append("\n");
        }
        sb.append("Reutilizá este workspace (no crees uno nuevo) para agregar/continuar contenido.\n\n");

        if (entries.isEmpty()) {
            sb.append("(El workspace todavía no tiene bloques.)\n");
            return sb.toString();
        }

        int blockNum = 1;
        for (var e : entries) {
            String who = "user".equals(e.getAuthor()) ? "Alumno" : "IA";
            sb.append("Bloque ").append(blockNum++).append(" [").append(e.getType())
              .append(" — ").append(who).append("]:\n");
            sb.append(e.getContent()).append("\n\n");
        }

        var last = entries.get(entries.size() - 1);
        String lastWho = "user".equals(last.getAuthor()) ? "Alumno" : "IA";
        sb.append("Último bloque activo: #").append(entries.size())
          .append(" (orderIndex ").append(last.getOrderIndex()).append(", ")
          .append(last.getType()).append(" — ").append(lastWho)
          .append("). Continuá desde aquí si corresponde.\n");
        return sb.toString();
    }

    /** Builds a focused prompt for AI to annotate/observe the whiteboard. */
    public String buildAnnotationContext(Long conversationId, String question,
                                          String selectedContent, String selectedType,
                                          boolean socraticMode) {
        var entries = entryRepository.findByConversationIdOrderByOrderIndexAsc(conversationId);
        var sb = new StringBuilder();

        sb.append("Sos un tutor de matemática. Estás observando la pizarra de trabajo del alumno.\n\n");

        if (!entries.isEmpty()) {
            sb.append("Contenido actual de la pizarra:\n");
            for (var e : entries) {
                String who = "user".equals(e.getAuthor()) ? "✏ Alumno" : "◆ IA";
                sb.append("  [").append(who).append("] ").append(e.getContent()).append("\n");
            }
            sb.append("\n");
        }

        if (selectedContent != null && !selectedContent.isBlank()) {
            sb.append("El alumno seleccionó este elemento: ").append(selectedContent).append("\n\n");
        }

        if (question != null && !question.isBlank()) {
            sb.append("El alumno pregunta: ").append(question).append("\n\n");
        }

        if (socraticMode) {
            sb.append("MODO SOCRÁTICO ACTIVO:\n");
            sb.append("- No des la respuesta directamente.\n");
            sb.append("- Formulá una pregunta guiada que lleve al alumno a descubrirla.\n");
            sb.append("- Podés dar una pista si el alumno está muy perdido.\n");
            sb.append("- Validá respuestas correctas con entusiasmo.\n");
            sb.append("- Máximo 2-3 oraciones.\n\n");
        } else {
            sb.append("MODO PROFESOR:\n");
            sb.append("- Observá si hay errores en el trabajo del alumno.\n");
            sb.append("- Si ves un error, señalalo con una pregunta guiada, NO lo corrijas directamente.\n");
            sb.append("- Si el trabajo está bien, validalo y preguntá cuál es el siguiente paso.\n");
            sb.append("- Máximo 2-3 oraciones. Sé directo y pedagógico.\n\n");
        }

        sb.append("Respondé únicamente como anotación en la pizarra (NO expliques que eres IA ni mencionés el prompt).");
        return sb.toString();
    }

    // ─── Teaching session (incremental, socratic) ───────────────────────────

    /** Builds the LLM prompt for generating a single teaching fragment. */
    public String buildTeachingPrompt(Long conversationId, String userInput, int stepIndex, String topic) {
        var entries = entryRepository.findByConversationIdOrderByOrderIndexAsc(conversationId);
        var sb = new StringBuilder();

        sb.append("Sos un tutor que resuelve en un workspace de Resolución guiada.\n");
        sb.append("Tu estilo es incremental: vas escribiendo la resolución de a poco, paso por paso.\n\n");

        sb.append("REGLA OBLIGATORIA: Escribí SOLO el siguiente fragmento (máximo 3 bloques) y continuá la resolución.\n");
        sb.append("NUNCA le hagas preguntas al alumno ni esperes su respuesta: 'question' debe ser SIEMPRE null.\n");
        sb.append("Resolvés vos solo, avanzando un fragmento por vez, sin pausas ni interrogatorios.\n");
        sb.append("Marcá isComplete=true únicamente cuando la resolución esté completamente terminada.\n\n");

        if (topic != null && !topic.isBlank() && stepIndex == 0) {
            sb.append("Tema a explicar: ").append(topic.trim()).append("\n\n");
        }

        if (!entries.isEmpty()) {
            sb.append("Pizarra hasta ahora:\n");
            for (var e : entries) {
                String who = "user".equals(e.getAuthor()) ? "✏ Alumno" : "◆ IA";
                sb.append("  [").append(who).append("][").append(e.getType()).append("] ")
                  .append(e.getContent()).append("\n");
            }
            sb.append("\n");
        }

        if (stepIndex == 0) {
            sb.append("Es el INICIO de la resolución. Comenzá con el primer concepto fundamental (título + 1-2 bloques máximo).\n\n");
        } else {
            sb.append("Continuá con el siguiente fragmento de la resolución, retomando desde donde quedó.\n\n");
        }

        sb.append("Respondé ÚNICAMENTE con un JSON válido (sin texto extra, sin markdown, sin backticks):\n");
        sb.append("Tipos permitidos para cada bloque: TITLE, TEXT, STEP, FORMULA, EXAMPLE, WARNING.\n");
        sb.append("No combines tipos con '|'. No uses claves duplicadas. Las fórmulas van en content, sin prefijo '$'.\n");
        sb.append("'question' debe ser SIEMPRE null (no preguntes nada). isComplete=true solo cuando la resolución terminó.\n");
        sb.append("Ejemplo válido:\n");
        sb.append("{\"blocks\":[{\"type\":\"TITLE\",\"content\":\"Resolver una ecuación lineal\"},");
        sb.append("{\"type\":\"FORMULA\",\"content\":\"2x + 6 = 9\"},");
        sb.append("{\"type\":\"STEP\",\"content\":\"Restamos 6 en ambos lados para aislar el término con x.\"}]");
        sb.append(",\"question\":null,\"isComplete\":false}\n");

        return sb.toString();
    }

    /** Record usado internamente para parsear la respuesta del LLM. */
    public record TeachFragment(List<Map<String, String>> blocks, String question, boolean isComplete) {}

    /** Extrae bloques + pregunta del JSON que devuelve el LLM. */
    public TeachFragment parseTeachFragment(String raw) {
        try {
            int start = raw.indexOf('{');
            int end   = raw.lastIndexOf('}');
            if (start < 0 || end < 0) throw new IllegalArgumentException("No JSON found");
            String json = raw.substring(start, end + 1);

            var node = objectMapper.readTree(json);

            List<Map<String, String>> blocks = new ArrayList<>();
            var blocksNode = node.get("blocks");
            if (blocksNode != null && blocksNode.isArray()) {
                for (var b : blocksNode) {
                    String type    = b.has("type")    ? b.get("type").asText("TEXT")    : "TEXT";
                    String content = b.has("content") ? b.get("content").asText("")     : "";
                    blocks.addAll(sanitizeTeachBlock(type, content));
                }
            }

            String question = null;
            if (node.has("question") && !node.get("question").isNull()) {
                String q = node.get("question").asText("").trim();
                if (!q.isBlank()) question = q;
            }
            boolean isComplete = node.has("isComplete") && node.get("isComplete").asBoolean(false);

            if (blocks.isEmpty()) blocks.addAll(salvageTeachBlocks(raw));
            // 'question' siempre es null en el modo no interactivo; la finalización depende solo de isComplete.
            return new TeachFragment(blocks, question, isComplete);
        } catch (Exception e) {
            String question = extractJsonStringField(raw, "question");
            List<Map<String, String>> blocks = salvageTeachBlocks(raw);
            return new TeachFragment(
                blocks,
                question,
                question == null
            );
        }
    }

    private List<Map<String, String>> sanitizeTeachBlock(String rawType, String rawContent) {
        List<Map<String, String>> blocks = new ArrayList<>();
        String content = cleanTeachContent(rawContent);

        if (!content.isBlank()) {
            addTeachBlock(blocks, normalizeTeachBlockType(rawType, content), content);
        }

        if (rawType != null && rawType.contains("|")) {
            for (String token : rawType.split("\\|")) {
                String extracted = cleanTeachContent(token);
                if (extracted.isBlank() || TEACH_BLOCK_TYPES.contains(extracted.toUpperCase(Locale.ROOT))) {
                    continue;
                }
                addTeachBlock(blocks, inferTeachBlockType(extracted), extracted);
            }
        }

        return blocks;
    }

    private List<Map<String, String>> salvageTeachBlocks(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of(Map.of("type", "TEXT", "content", "No se pudo generar el siguiente paso."));
        }

        List<Map<String, String>> blocks = new ArrayList<>();
        List<String> types = extractJsonStringFields(raw, "type");
        List<String> contents = extractJsonStringFields(raw, "content");

        int max = Math.max(types.size(), contents.size());
        for (int i = 0; i < max; i++) {
            String type = i < types.size() ? types.get(i) : "TEXT";
            String content = i < contents.size() ? contents.get(i) : "";
            blocks.addAll(sanitizeTeachBlock(type, content));
        }

        if (blocks.isEmpty()) {
            String cleaned = cleanTeachContent(raw);
            if (!cleaned.isBlank() && !looksLikeRawJson(cleaned)) {
                addTeachBlock(blocks, "TEXT", cleaned);
            }
        }

        if (blocks.isEmpty()) {
            addTeachBlock(blocks, "TEXT", "No se pudo estructurar el siguiente paso.");
        }
        return blocks;
    }

    private String normalizeTeachBlockType(String rawType, String content) {
        if (rawType == null || rawType.isBlank() || rawType.contains("|")) {
            return inferTeachBlockType(content);
        }
        String candidate = rawType.trim().toUpperCase(Locale.ROOT);
        return TEACH_BLOCK_TYPES.contains(candidate) ? candidate : inferTeachBlockType(content);
    }

    private String inferTeachBlockType(String content) {
        return isSimpleMathExpression(content) ? "FORMULA" : "TEXT";
    }

    private void addTeachBlock(List<Map<String, String>> blocks, String type, String content) {
        String cleaned = cleanTeachContent(content);
        if (cleaned.isBlank()) return;

        String normalizedType = TEACH_BLOCK_TYPES.contains(type) ? type : "TEXT";
        boolean duplicate = blocks.stream().anyMatch(existing ->
                cleaned.equals(existing.get("content")) && normalizedType.equals(existing.get("type")));
        if (!duplicate) {
            blocks.add(Map.of("type", normalizedType, "content", cleaned));
        }
    }

    private String cleanTeachContent(String value) {
        if (value == null) return "";
        String cleaned = value.trim();
        while (cleaned.startsWith("$")) {
            cleaned = cleaned.substring(1).trim();
        }
        return cleaned;
    }

    private boolean looksLikeRawJson(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.contains("\"blocks\"");
    }

    private String extractJsonStringField(String raw, String field) {
        List<String> values = extractJsonStringFields(raw, field);
        return values.isEmpty() ? null : values.get(0);
    }

    private List<String> extractJsonStringFields(String raw, String field) {
        if (raw == null || raw.isBlank()) return List.of();
        Pattern pattern = Pattern.compile(String.format(JSON_STRING_FIELD.pattern(), Pattern.quote(field)));
        Matcher matcher = pattern.matcher(raw);
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            String value = unescapeJsonString(matcher.group(1));
            if (!value.isBlank()) values.add(value.trim());
        }
        return values;
    }

    private String unescapeJsonString(String value) {
        try {
            return objectMapper.readValue("\"" + value + "\"", String.class);
        } catch (Exception e) {
            return value.replace("\\\"", "\"").replace("\\\\", "\\");
        }
    }

    private String normalizeBlockType(String type) {
        if (type == null) return "TEXT";
        return switch (type.toUpperCase()) {
            case "TITLE", "TEXT", "STEP", "FORMULA", "EXAMPLE", "WARNING",
                 "QUESTION", "NOTE", "DRAWING_INSTRUCTION", "SYSTEM_NOTE", "HIGHLIGHT", "DRAWING",
                 "AI_NOTE", "AI_QUESTION", "AI_CORRECTION" -> type.toUpperCase();
            default -> "TEXT";
        };
    }

    private String writeMetadata(java.util.Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return null;
        }
    }

    private WhiteboardEntryDto toEntryDto(WhiteboardEntry e) {
        return new WhiteboardEntryDto(
                e.getId(),
                toPublicId(e.getWhiteboard().getId()),
                e.getConversationId(),
                e.getType(),
                e.getAuthor(),
                e.getContent(),
                e.getOrderIndex(),
                e.getMetadata()
        );
    }

    public WhiteboardDto toDto(Whiteboard whiteboard) {
        return new WhiteboardDto(
                toPublicId(whiteboard.getId()),
                whiteboard.getConversation().getId(),
                whiteboard.getDocumentId(),
                whiteboard.getExerciseLabel(),
                whiteboard.getTitle(),
                readData(whiteboard.getData()),
                whiteboard.getMode(),
                whiteboard.getStatus(),
                whiteboard.getCreatedAt(),
                whiteboard.getUpdatedAt()
        );
    }

    private Conversation requireConversation(Long conversationId, String userEmail) {
        return conversationRepository.findByIdAndUserEmail(conversationId, userEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversación no encontrada"));
    }

    private Whiteboard requireWhiteboard(String id, String userEmail) {
        Long numericId = parsePublicId(id);
        Whiteboard whiteboard = whiteboardRepository.findById(numericId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pizarra no encontrada"));
        if (!userEmail.equals(whiteboard.getConversation().getUserEmail())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pizarra no encontrada");
        }
        return whiteboard;
    }

    private Map<String, Object> readData(String raw) {
        if (raw == null || raw.isBlank()) return EMPTY_DATA;
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            return EMPTY_DATA;
        }
    }

    private String writeData(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datos de pizarra inválidos");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> elements(Map<String, Object> data) {
        Object value = data.get("elements");
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private String summarizeElements(List<Map<String, Object>> elements) {
        if (elements.isEmpty()) return "Pizarra vacía.";
        return elements.stream()
                .map(this::describeElement)
                .collect(Collectors.joining(" → "));
    }

    private String summarizeStructured(List<Map<String, Object>> elements) {
        if (elements.isEmpty()) return "Pizarra vacía.";
        long arrows = elements.stream().filter(el -> "arrow".equals(String.valueOf(el.get("type")))).count();
        long paths = elements.stream().filter(el -> "path".equals(String.valueOf(el.get("type")))).count();
        List<String> nodes = elements.stream()
                .filter(el -> !"path".equals(String.valueOf(el.get("type"))))
                .map(this::describeStructuredElement)
                .filter(text -> !text.isBlank())
                .toList();
        List<String> parts = new ArrayList<>();
        if (!nodes.isEmpty()) parts.add(String.join(" → ", nodes));
        if (arrows > 0) parts.add(arrows + " flecha(s)");
        if (paths > 0) parts.add(paths + " trazo(s) manuscrito(s)");
        return parts.isEmpty() ? "Trazos manuscritos sin estructura reconocible." : String.join("; ", parts);
    }

    private String describeStructuredElement(Map<String, Object> el) {
        String type = String.valueOf(el.getOrDefault("type", "elemento"));
        String text = String.valueOf(el.getOrDefault("text", "")).trim();
        String label = switch (type) {
            case "rect" -> "caja";
            case "circle" -> "círculo";
            case "diamond" -> "decisión";
            case "arrow" -> "flecha";
            case "text" -> "texto";
            case "equation" -> "ecuación";
            default -> "";
        };
        if (label.isBlank()) return "";
        return text.isBlank() ? label : label + " \"" + text + "\"";
    }

    private String normalizeOcr(String text) {
        if (text == null) return "";
        return text
                .replace("²", "^2")
                .replace("³", "^3")
                .replace("¹", "^1")
                .replace("ⁿ", "^n")
                .replace("⁺", "^+")
                .replace("⁻", "^-")
                .replace("−", "-")
                .replace("–", "-")
                .replace("—", "-")
                .replace("÷", "/")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean hasUsefulOcr(String text) {
        return text != null && text.matches(".*[\\p{L}\\p{N}].*") && text.length() >= 2;
    }

    private String structuredText(List<Map<String, Object>> elements) {
        return elements.stream()
                .map(el -> String.valueOf(el.getOrDefault("text", "")).trim())
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private Optional<String> firstEquationElementText(List<Map<String, Object>> elements) {
        return elements.stream()
                .filter(el -> "equation".equals(String.valueOf(el.get("type"))))
                .map(el -> String.valueOf(el.getOrDefault("text", "")).trim())
                .filter(text -> !text.isBlank())
                .findFirst();
    }

    private String inferWhiteboardType(String ocrText, List<Map<String, Object>> elements) {
        String lower = ocrText == null ? "" : ocrText.toLowerCase(Locale.ROOT);
        boolean hasMath = extractMathExpression(ocrText) != null
                || lower.matches(".*\\d+\\s*[+\\-x×*/=<>]\\s*\\d+.*")
                || lower.contains("sqrt")
                || lower.contains("^");
        if (hasMath) return "math";

        boolean hasDiamond = elements.stream().anyMatch(el -> "diamond".equals(String.valueOf(el.get("type"))));
        boolean hasArrow = elements.stream().anyMatch(el -> "arrow".equals(String.valueOf(el.get("type"))));
        boolean hasBox = elements.stream().anyMatch(el -> {
            String type = String.valueOf(el.get("type"));
            return "rect".equals(type) || "circle".equals(type) || "diamond".equals(type);
        });
        if (hasDiamond || (hasArrow && hasBox)) return "flowchart";
        if (lower.matches(".*\\b(if|while|for|leer|inicio|fin|retornar|función|funcion)\\b.*")) return "algorithm";
        if (hasUsefulOcr(ocrText)) return "text";
        return "unknown";
    }

    private String buildSemanticSummary(
            String type,
            String equation,
            String ocrText,
            String structured,
            List<Map<String, Object>> elements,
            boolean hasUsefulOcr
    ) {
        if ("math".equals(type) && equation != null) {
            return "La pizarra contiene una ecuación matemática: " + equation;
        }
        if (hasUsefulOcr) {
            String prefix = switch (type) {
                case "math" -> "La pizarra contiene una operación o expresión matemática";
                case "flowchart" -> "La pizarra combina texto reconocido con una estructura de diagrama";
                case "algorithm" -> "La pizarra contiene una idea de algoritmo o pseudocódigo";
                default -> "La pizarra contiene texto reconocido";
            };
            String structure = structured.isBlank() ? "" : " Estructura visible: " + structured + ".";
            return prefix + ": \"" + ocrText + "\"." + structure;
        }
        if (elements.isEmpty()) {
            return "La pizarra está vacía; no hay contenido para interpretar.";
        }
        if (structured.contains("trazo(s) manuscrito(s)") && !structured.contains("caja") && !structured.contains("flecha")) {
            return "La interpretación es incierta: hay trazos manuscritos, pero el OCR no reconoció texto claro.";
        }
        return "No hay texto OCR claro. Estructura visible: " + structured + ".";
    }

    private double confidence(double ocrConfidence, boolean hasUsefulOcr, List<Map<String, Object>> elements) {
        if (hasUsefulOcr) return Math.max(0.45, Math.min(0.95, ocrConfidence));
        if (!elements.isEmpty()) return 0.35;
        return 0.0;
    }

    private String extractMathExpression(String text) {
        if (text == null || text.isBlank()) return null;
        String normalized = normalizeMathExpressionText(text);
        var matcher = java.util.regex.Pattern
                .compile("(?i)([a-z0-9][a-z0-9\\s+\\-*/×xX^().]*\\s*[=<>]\\s*[a-z0-9][a-z0-9\\s+\\-*/×xX^().]*)")
                .matcher(normalized);
        while (matcher.find()) {
            String candidate = cleanMathCandidate(matcher.group(1));
            if (isSimpleMathExpression(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private String normalizeMathExpressionText(String text) {
        return normalizeOcr(text)
                .replaceAll("(?i)\\bpor\\b", "x")
                .replaceAll("(?i)\\bmas\\b", "+")
                .replaceAll("(?i)\\bmás\\b", "+")
                .replaceAll("(?i)\\bmenos\\b", "-")
                .replaceAll("(?i)\\bigual\\b", "=")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String cleanMathCandidate(String candidate) {
        return candidate
                .replaceAll("\\s*([+\\-*/×=<>^()])\\s*", " $1 ")
                .replaceAll("\\s+", " ")
                .replaceAll("(?i)^\\W+|\\W+$", "")
                .trim();
    }

    private boolean isSimpleMathExpression(String candidate) {
        if (candidate == null || candidate.length() < 3 || candidate.length() > 80) return false;
        if (!candidate.matches(".*[=<>].*")) return false;
        if (!candidate.matches(".*[0-9].*")) return false;
        return candidate.matches("(?i)[a-z0-9\\s+\\-*/×xX=<>^().]+");
    }

    private String describeElement(Map<String, Object> el) {
        String type = String.valueOf(el.getOrDefault("type", "elemento"));
        String text = String.valueOf(el.getOrDefault("text", "")).trim();
        if (!text.isBlank()) return text;
        return switch (type) {
            case "rect" -> "rectángulo";
            case "circle" -> "círculo";
            case "diamond" -> "decisión";
            case "arrow" -> "flecha";
            case "path" -> "trazo";
            case "equation" -> "ecuación";
            default -> type;
        };
    }

    private boolean containsText(List<Map<String, Object>> elements, String needle) {
        String lower = needle.toLowerCase(Locale.ROOT);
        return elements.stream()
                .map(el -> String.valueOf(el.getOrDefault("text", "")).toLowerCase(Locale.ROOT))
                .anyMatch(text -> text.contains(lower));
    }

    private String inferType(String instruction) {
        String lower = instruction.toLowerCase(Locale.ROOT);
        if (lower.contains("condición") || lower.contains("condicion") || lower.contains(">") || lower.contains("<")) return "diamond";
        if (lower.contains("flecha")) return "arrow";
        if (lower.contains("círculo") || lower.contains("circulo") || lower.contains("nodo final")) return "circle";
        return "rect";
    }

    private String cleanSuggestionText(String instruction) {
        return instruction.replaceFirst("(?i)^agregar\\s+", "").trim();
    }

    private String cleanSuggestionTitle(String instruction) {
        String text = cleanSuggestionText(instruction);
        return text.length() > 60 ? text.substring(0, 60) : text;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String toPublicId(Long id) {
        return "wb_" + id;
    }

    private Long parsePublicId(String id) {
        if (id == null || id.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID inválido");
        String raw = id.startsWith("wb_") ? id.substring(3) : id;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID inválido");
        }
    }
}

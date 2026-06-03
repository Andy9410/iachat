package com.academy.chatservice.service;

import com.academy.chatservice.model.*;
import com.academy.chatservice.repository.ConversationRepository;
import com.academy.chatservice.repository.WhiteboardRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class WhiteboardService {

    private static final Map<String, Object> EMPTY_DATA = Map.of("version", 1, "elements", List.of());

    private final ConversationRepository conversationRepository;
    private final WhiteboardRepository whiteboardRepository;
    private final ObjectMapper objectMapper;
    private final WhiteboardImageRenderer imageRenderer;
    private final WhiteboardInterpretService interpretService;

    public WhiteboardService(
            ConversationRepository conversationRepository,
            WhiteboardRepository whiteboardRepository,
            WhiteboardImageRenderer imageRenderer,
            WhiteboardInterpretService interpretService,
            ObjectMapper objectMapper
    ) {
        this.conversationRepository = conversationRepository;
        this.whiteboardRepository = whiteboardRepository;
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

    @Transactional(readOnly = true)
    public WhiteboardDto active(Long conversationId, String userEmail) {
        requireConversation(conversationId, userEmail);
        return whiteboardRepository.findFirstByConversationIdOrderByUpdatedAtDesc(conversationId)
                .map(this::toDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pizarra no encontrada"));
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
                : exerciseLabel != null ? "Pizarra - " + exerciseLabel : "Pizarra inteligente");
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

    public WhiteboardDto toDto(Whiteboard whiteboard) {
        return new WhiteboardDto(
                toPublicId(whiteboard.getId()),
                whiteboard.getConversation().getId(),
                whiteboard.getDocumentId(),
                whiteboard.getExerciseLabel(),
                whiteboard.getTitle(),
                readData(whiteboard.getData()),
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

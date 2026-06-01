package com.academy.chatservice.service;

import com.academy.chatservice.model.MathOcrResult;
import com.academy.chatservice.model.WhiteboardInterpretationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class WhiteboardInterpretService {

    private static final Logger log = LoggerFactory.getLogger(WhiteboardInterpretService.class);

    private static final int MAX_IMAGE_DIMENSION = 2048;
    private static final int PREPROCESS_SCALE = 2;
    private static final int CROP_PADDING = 40;

    private final ObjectProvider<VisionModelClient> visionModelClient;
    private final ObjectProvider<MathOcrClient> mathOcrClient;
    private final Environment environment;

    public WhiteboardInterpretService(
            ObjectProvider<VisionModelClient> visionModelClient,
            ObjectProvider<MathOcrClient> mathOcrClient,
            Environment environment
    ) {
        this.visionModelClient = visionModelClient;
        this.mathOcrClient = mathOcrClient;
        this.environment = environment;
    }

    public WhiteboardInterpretationResponse interpret(
            String whiteboardId,
            String title,
            String exerciseLabel,
            Long documentId,
            List<Map<String, Object>> elements,
            String structuredElements,
            String imageBase64
    ) {
        return interpret(whiteboardId, title, exerciseLabel, documentId, elements, structuredElements, imageBase64, "auto");
    }

    public WhiteboardInterpretationResponse interpret(
            String whiteboardId,
            String title,
            String exerciseLabel,
            Long documentId,
            List<Map<String, Object>> elements,
            String structuredElements,
            String imageBase64,
            String interpretMode
    ) {
        String normalizedMode = normalizeMode(interpretMode);
        int imgSize = imageBase64 != null ? imageBase64.length() : 0;
        log.debug("[WHITEBOARD_INTERPRET] whiteboardId={} mode={} hasStructuredElements={} imageSize={} elementsCount={}",
                whiteboardId, normalizedMode,
                structuredElements != null && !structuredElements.isBlank(),
                imgSize, elements.size());

        // Validar imagen antes de continuar
        String imageValidationError = validateImage(imageBase64);
        if (imageValidationError != null) {
            log.warn("[WHITEBOARD_INTERPRET] whiteboardId={} reason={} Imagen inválida: prefix={}",
                    whiteboardId, imageValidationError,
                    imageBase64 != null ? imageBase64.substring(0, Math.min(50, imageBase64.length())) : "null");
            if (!elements.isEmpty()) {
                return classifyFallback(whiteboardId, title, exerciseLabel, documentId, elements, structuredElements, "", imageValidationError);
            }
            return complete("unknown", whiteboardId, title, exerciseLabel, documentId,
                    null, "", structuredElements,
                    "La imagen de la pizarra no es válida.", 0.0, imageValidationError);
        }

        // Preprocesar imagen antes de enviar a OpenRouter
        String processedImage = preprocessImage(imageBase64, whiteboardId);
        log.debug("[WHITEBOARD_INTERPRET] whiteboardId={} Imagen preprocesada: original={}bytes → {}bytes",
                whiteboardId, imgSize, processedImage != null ? processedImage.length() : 0);

        // Guardar imagen preprocesada para debug (solo en perfil dev)
        // La imagen enviada a OpenRouter es la preprocesada
        saveDebugImage(processedImage, whiteboardId);

        String structuredText = structuredText(elements);
        boolean hasStructuredText = hasUsefulText(structuredText);

        // Priority 1: Modo forzado "math"
        if ("math".equals(normalizedMode)) {
            log.info("[WHITEBOARD_INTERPRET] whiteboardId={} reason=MATH_MODE_FORCED Modo forzado: matemática", whiteboardId);
            return interpretMathMode(whiteboardId, title, exerciseLabel, documentId, elements, structuredText, structuredElements, processedImage);
        }

        // Priority 2: Structured text from elements
        if (hasStructuredText) {
            String equation = firstEquationElementText(elements).orElseGet(() -> extractMathExpression(structuredText));
            String type = equation != null ? "math" : inferType(structuredText, elements);
            String summary = semanticSummary(type, equation != null ? equation : null, structuredText, structuredElements);
            double confidence = type.equals("math") ? 0.95 : 0.90;
            log.info("[WHITEBOARD_INTERPRET] whiteboardId={} reason=STRUCTURED_TEXT type={} confidence={} equation={}",
                    whiteboardId, type, confidence, equation);
            return complete(type, whiteboardId, title, exerciseLabel, documentId,
                    equation, structuredText, structuredElements, summary, confidence, "STRUCTURED_TEXT");
        }

        // Priority 3: Math OCR especializado (pix2tex) — solo cuando la imagen tiene alta probabilidad
        // de contener matemática. Si tiene confianza alta, evita enviar al modelo vision más costoso.
        if (processedImage != null && !processedImage.isBlank()) {
            MathOcrResult mathOcr = tryMathOcr(processedImage, whiteboardId);
            if (mathOcr != null && mathOcr.isUsable() && mathOcr.confidence() >= 0.5) {
                String latex = mathOcr.latex();
                log.info("[WHITEBOARD_INTERPRET] whiteboardId={} reason=MATH_OCR Math OCR detectó: latex='{}' confidence={}",
                        whiteboardId, latex.length() > 80 ? latex.substring(0, 80) + "..." : latex, mathOcr.confidence());
                return complete("math", whiteboardId, title, exerciseLabel, documentId,
                        latex, latex, structuredElements,
                        "La pizarra contiene una ecuación matemática: " + latex + ".",
                        mathOcr.confidence(), "MATH_OCR");
            }
        }

        // Priority 4: Vision model
        if (processedImage != null && !processedImage.isBlank()) {
            try {
                VisionModelClient client = visionModelClient.getIfAvailable();
                if (client == null) {
                    log.warn("[WHITEBOARD_INTERPRET] whiteboardId={} reason=NO_VISION_CLIENT VisionModelClient no disponible", whiteboardId);
                    return classifyFallback(whiteboardId, title, exerciseLabel, documentId, elements, structuredElements, "", "NO_VISION_CLIENT");
                }

                log.info("[WHITEBOARD_INTERPRET] whiteboardId={} Enviando a modelo vision client={} mode={} processedSize={}",
                        whiteboardId, client.getClass().getSimpleName(), normalizedMode, processedImage.length());
                long startTime = System.currentTimeMillis();
                WhiteboardInterpretationResponse vision = client.interpretWhiteboardImage(processedImage, whiteboardId);
                long elapsed = System.currentTimeMillis() - startTime;

                log.info("[WHITEBOARD_INTERPRET] whiteboardId={} Respuesta cruda vision: type={} confidence={} ocrText='{}' semanticSummary='{}' reason='{}' elapsedMs={}",
                        whiteboardId, vision.type(), vision.confidence(), vision.ocrText(), vision.semanticSummary(), vision.reason(), elapsed);

                String ocrText = vision.ocrText() != null ? vision.ocrText() : "";
                String equation = vision.equation();
                String visionReason = vision.reason() != null ? vision.reason() : "";

                // Priority 5: Heurísticas sobre texto detectado
                if (("unknown".equals(vision.type()) || vision.confidence() < 0.3)
                        && hasUsefulText(ocrText)) {
                    String heuristicEquation = extractMathExpression(ocrText);
                    if (heuristicEquation != null) {
                        log.info("[WHITEBOARD_INTERPRET] whiteboardId={} reason=MATH_EQUATION_DETECTED Heurística override: vision={} conf={} → math (ecuación='{}')",
                                whiteboardId, vision.type(), vision.confidence(), heuristicEquation);
                        return complete("math", whiteboardId, title, exerciseLabel, documentId,
                                heuristicEquation, ocrText, structuredElements,
                                "La pizarra contiene una ecuación matemática: " + heuristicEquation + ".",
                                0.85, "MATH_EQUATION_DETECTED");
                    }
                    if (isLikelyMathExpression(ocrText)) {
                        log.info("[WHITEBOARD_INTERPRET] whiteboardId={} reason=MATH_HEURISTIC Heurística override: vision={} conf={} → math (texto='{}')",
                                whiteboardId, vision.type(), vision.confidence(), ocrText);
                        return complete("math", whiteboardId, title, exerciseLabel, documentId,
                                ocrText, ocrText, structuredElements,
                                "La pizarra contiene una expresión matemática: " + ocrText + ".",
                                0.6, "MATH_HEURISTIC");
                    }
                }

                // Si el modelo devolvió unknown pero hay texto OCR genérico, clasificar como text
                if ("unknown".equals(vision.type()) && hasUsefulText(ocrText)) {
                    log.info("[WHITEBOARD_INTERPRET] whiteboardId={} reason=OCR_TEXT_DETECTED Heurística override: vision={} → text (ocr='{}')",
                            whiteboardId, vision.type(), ocrText);
                    return complete("text", whiteboardId, title, exerciseLabel, documentId,
                            null, ocrText, structuredElements,
                            "La pizarra contiene texto: " + ocrText + ".", 0.6, "OCR_TEXT_DETECTED");
                }

                // Priority 6: Vision returned unknown — classify by element structure
                if ("unknown".equals(vision.type()) || vision.confidence() < 0.3) {
                    String fallbackReason = visionReason.isEmpty() ? "LOW_CONFIDENCE" : visionReason;
                    log.info("[WHITEBOARD_INTERPRET] whiteboardId={} reason=FALLBACK_ELEMENT_CLASSIFICATION Vision no reconoció (type={} conf={}), clasificando por estructura. visionReason={}",
                            whiteboardId, vision.type(), vision.confidence(), fallbackReason);
                    return classifyFallback(whiteboardId, title, exerciseLabel, documentId, elements, structuredElements, ocrText, fallbackReason);
                }

                // El modelo devolvió algo útil
                String finalEquation = equation != null ? equation : extractMathExpression(ocrText);
                String type = finalEquation != null ? "math" : vision.type();
                log.info("[WHITEBOARD_INTERPRET] whiteboardId={} reason=VISION_MODEL type={} confidence={}",
                        whiteboardId, type, vision.confidence());
                return complete(type, whiteboardId, title, exerciseLabel, documentId,
                        finalEquation, ocrText, structuredElements, vision.semanticSummary(), vision.confidence(), "VISION_MODEL");

            } catch (Exception e) {
                log.error("[WHITEBOARD_INTERPRET] whiteboardId={} reason=VISION_ERROR Error en interpretación vision: {}", whiteboardId, e.getMessage(), e);
                return classifyFallback(whiteboardId, title, exerciseLabel, documentId, elements, structuredElements, "", "VISION_ERROR");
            }
        }

        // No hay imagen — clasificar por estructura de elementos
        if (!elements.isEmpty()) {
            log.info("[WHITEBOARD_INTERPRET] whiteboardId={} reason=ELEMENT_STRUCTURE No hay imagen, clasificando por elementos", whiteboardId);
            return classifyFallback(whiteboardId, title, exerciseLabel, documentId, elements, structuredElements, "", "ELEMENT_STRUCTURE");
        }

        log.info("[WHITEBOARD_INTERPRET] whiteboardId={} reason=EMPTY No hay texto estructurado, imagen ni elementos", whiteboardId);
        return unknown(whiteboardId, title, exerciseLabel, documentId, structuredElements);
    }

    /**
     * Valida que la imagen base64 tenga el formato esperado.
     */
    private String validateImage(String imageBase64) {
        if (imageBase64 == null || imageBase64.isBlank()) {
            return "EMPTY_IMAGE";
        }
        if (!imageBase64.contains("data:image") || !imageBase64.contains("base64,")) {
            return "INVALID_IMAGE";
        }
        String payload = imageBase64.substring(imageBase64.indexOf("base64,") + 7);
        if (payload.isBlank()) {
            return "EMPTY_IMAGE";
        }
        return null; // válida
    }

    /**
     * Interpretación especializada para modo matemática.
     * Usa prompt especializado e intenta extraer ecuación sí o sí.
     */
    private WhiteboardInterpretationResponse interpretMathMode(
            String whiteboardId,
            String title,
            String exerciseLabel,
            Long documentId,
            List<Map<String, Object>> elements,
            String structuredText,
            String structuredElements,
            String imageBase64
    ) {
        // Primero buscar elementos de tipo "equation"
        String equation = firstEquationElementText(elements).orElse(null);
        if (equation != null) {
            log.info("[WHITEBOARD_INTERPRET] whiteboardId={} math-mode: ecuación encontrada en elementos: {}", whiteboardId, equation);
            return complete("math", whiteboardId, title, exerciseLabel, documentId,
                    equation, structuredText, structuredElements,
                    "La pizarra contiene una ecuación matemática: " + equation + ".",
                    0.95);
        }

        // Buscar expresión matemática en textos de elementos
        if (hasUsefulText(structuredText)) {
            equation = extractMathExpression(structuredText);
            if (equation != null) {
                log.info("[WHITEBOARD_INTERPRET] whiteboardId={} math-mode: ecuación extraída de texto estructurado: {}", whiteboardId, equation);
                return complete("math", whiteboardId, title, exerciseLabel, documentId,
                        equation, structuredText, structuredElements,
                        "La pizarra contiene una ecuación matemática: " + equation + ".",
                        0.90);
            }
            // Si el texto no es matemático, igual forzamos type=math para que el LLM lo interprete
            log.info("[WHITEBOARD_INTERPRET] whiteboardId={} math-mode: no se encontró ecuación pero modo es math, forzando type=math", whiteboardId);
        }

        // Intentar Math OCR especializado primero
        if (imageBase64 != null && !imageBase64.isBlank()) {
            MathOcrResult mathOcr = tryMathOcr(imageBase64, whiteboardId);
            if (mathOcr != null && mathOcr.isUsable()) {
                String latex = mathOcr.latex();
                log.info("[WHITEBOARD_INTERPRET] whiteboardId={} math-mode: Math OCR exitoso: latex='{}' confidence={}",
                        whiteboardId, latex.length() > 80 ? latex.substring(0, 80) + "..." : latex, mathOcr.confidence());
                return complete("math", whiteboardId, title, exerciseLabel, documentId,
                        latex, latex, structuredElements,
                        "La pizarra contiene una ecuación matemática: " + latex + ".",
                        mathOcr.confidence(), "MATH_OCR_MODE");
            }
        }

        // Usar modelo vision con prompt especializado
        if (imageBase64 != null && !imageBase64.isBlank()) {
            try {
                VisionModelClient client = visionModelClient.getIfAvailable();
                if (client != null) {
                    log.info("[WHITEBOARD_INTERPRET] whiteboardId={} math-mode: enviando a vision client={}",
                            whiteboardId, client.getClass().getSimpleName());
                    WhiteboardInterpretationResponse vision = client.interpretWhiteboardImage(imageBase64, whiteboardId);
                    log.info("[WHITEBOARD_INTERPRET] whiteboardId={} math-mode: respuesta vision type={} ocrText='{}' confidence={}",
                            whiteboardId, vision.type(), vision.ocrText(), vision.confidence());

                    String ocrText = vision.ocrText() != null ? vision.ocrText() : "";
                    equation = vision.equation();

                    // Si la visión detectó una gráfica, respetarla aunque el modo sea "math".
                    // Modo Matemática incluye gráficas cartesianas — no forzar a ecuación.
                    if ("graph".equals(vision.type())) {
                        String graphSummary = vision.semanticSummary() != null && !vision.semanticSummary().isBlank()
                                ? vision.semanticSummary()
                                : "La pizarra muestra una gráfica en ejes cartesianos.";
                        log.info("[WHITEBOARD_INTERPRET] whiteboardId={} math-mode: visión detectó gráfica (conf={}), respetando type=graph",
                                whiteboardId, vision.confidence());
                        return complete("graph", whiteboardId, title, exerciseLabel, documentId,
                                null, ocrText, structuredElements, graphSummary, vision.confidence(), "GRAPH_IN_MATH_MODE");
                    }

                    // Intentar extraer ecuación del OCR
                    if (equation == null && hasUsefulText(ocrText)) {
                        equation = extractMathExpression(ocrText);
                    }

                    // Incluso si el modelo devolvió unknown, si hay texto que parece matemática, clasificar como math
                    if (equation != null) {
                        return complete("math", whiteboardId, title, exerciseLabel, documentId,
                                equation, ocrText, structuredElements,
                                "La pizarra contiene una ecuación matemática: " + equation + ".",
                                0.85);
                    }

                    if (hasUsefulText(ocrText) && isLikelyMathExpression(ocrText)) {
                        return complete("math", whiteboardId, title, exerciseLabel, documentId,
                                ocrText, ocrText, structuredElements,
                                "La pizarra contiene una expresión matemática: " + ocrText + ".",
                                0.75);
                    }

                    // Forzar type=math aunque el modelo no haya detectado, porque el usuario eligió modo math
                    if (hasUsefulText(ocrText)) {
                        return complete("math", whiteboardId, title, exerciseLabel, documentId,
                                null, ocrText, structuredElements,
                                "La pizarra podría contener una expresión matemática. Texto detectado: " + ocrText + ".",
                                0.5);
                    }

                    return complete("math", whiteboardId, title, exerciseLabel, documentId,
                            null, ocrText, structuredElements,
                            "No se pudo identificar una ecuación clara en la pizarra.",
                            0.3);
                }
            } catch (Exception e) {
                log.error("[WHITEBOARD_INTERPRET] whiteboardId={} math-mode: error en vision: {}", whiteboardId, e.getMessage(), e);
            }
        }

        // Fallback: devolver math aunque no tengamos ecuación, para que el LLM principal pueda ayudar
        return complete("math", whiteboardId, title, exerciseLabel, documentId,
                null, "", structuredElements,
                "No se pudo extraer una ecuación clara de la pizarra en modo matemática.",
                0.3);
    }

    /**
     * Clasificación de fallback basada en estructura de elementos.
     * Se usa cuando el modelo vision no pudo interpretar o no hay imagen.
     * NO devuelve unknown si hay elementos con contenido visible.
     */
    private WhiteboardInterpretationResponse classifyFallback(
            String whiteboardId,
            String title,
            String exerciseLabel,
            Long documentId,
            List<Map<String, Object>> elements,
            String structuredElements,
            String ocrText,
            String reason
    ) {
        if (elements.isEmpty()) {
            return unknown(whiteboardId, title, exerciseLabel, documentId, structuredElements);
        }

        boolean hasStrokes = elements.stream().anyMatch(el -> "path".equals(String.valueOf(el.get("type"))));
        boolean hasShapes = elements.stream().anyMatch(el -> {
            String type = String.valueOf(el.get("type"));
            return "rect".equals(type) || "circle".equals(type) || "diamond".equals(type);
        });
        boolean hasArrows = elements.stream().anyMatch(el -> "arrow".equals(String.valueOf(el.get("type"))));
        boolean hasText = elements.stream().anyMatch(el -> {
            String t = String.valueOf(el.getOrDefault("text", ""));
            return !t.isBlank() && !"path".equals(String.valueOf(el.get("type")));
        });

        // ── Detectar posibles ejes cartesianos (graph) ──────────────────────────
        if (hasStrokes && !hasShapes && !hasArrows) {
            boolean likelyAxes = isLikelyAxes(elements);
            if (likelyAxes) {
                log.info("[WHITEBOARD_INTERPRET] whiteboardId={} classification_reason=GRAPH_AXES_DETECTED type=graph (ejes cartesianos detectados por heurística)",
                        whiteboardId);
                return complete("graph", whiteboardId, title, exerciseLabel, documentId,
                        null, ocrText, structuredElements,
                        "La pizarra parece contener un sistema de ejes cartesianos.",
                        0.75, "GRAPH_AXES_DETECTED");
            }
        }

        // Si hay texto útil en los elementos (aunque no se haya extraído antes), clasificar
        String elementText = structuredText(elements);
        if (hasUsefulText(elementText)) {
            String equation = extractMathExpression(elementText);
            if (equation != null) {
                log.info("[WHITEBOARD_INTERPRET] whiteboardId={} classification_reason=ELEMENT_TEXT_MATH type=math equation='{}'",
                        whiteboardId, equation);
                return complete("math", whiteboardId, title, exerciseLabel, documentId,
                        equation, ocrText, structuredElements,
                        "La pizarra contiene una ecuación matemática: " + equation + ".",
                        0.8);
            }
            if (isLikelyMathExpression(elementText)) {
                log.info("[WHITEBOARD_INTERPRET] whiteboardId={} classification_reason=ELEMENT_TEXT_MATH_LIKE type=math text='{}'",
                        whiteboardId, elementText);
                return complete("math", whiteboardId, title, exerciseLabel, documentId,
                        elementText, ocrText, structuredElements,
                        "La pizarra contiene una expresión matemática: " + elementText + ".",
                        0.7);
            }
        }

        // Si hay trazos manuscritos sin texto — NO asumir math automáticamente
        // Podría ser una gráfica, figura geométrica, diagrama, etc.
        if (hasStrokes && !hasShapes && !hasArrows && !hasUsefulText(elementText)) {
            log.info("[WHITEBOARD_INTERPRET] whiteboardId={} classification_reason=HANDWRITTEN_STROKES type=unknown (trazos manuscritos sin texto, contenido no clasificable sin visión)",
                    whiteboardId);
            return complete("unknown", whiteboardId, title, exerciseLabel, documentId,
                    null, ocrText, structuredElements,
                    "La pizarra tiene trazos manuscritos pero no se pudo clasificar el contenido sin la imagen.",
                    0.3, "HANDWRITTEN_UNKNOWN");
        }

        // Si hay elementos estructurados con texto (cajas, círculos, etc.), clasificar por tipo
        if (hasShapes || hasArrows) {
            log.info("[WHITEBOARD_INTERPRET] whiteboardId={} classification_reason=STRUCTURED_DIAGRAM type=flowchart (elementos de diagrama detectados)",
                    whiteboardId);
            return complete("flowchart", whiteboardId, title, exerciseLabel, documentId,
                    null, ocrText, structuredElements,
                    "La pizarra contiene elementos de diagrama o flujo.",
                    0.7);
        }

        // Si hay texto (aunque no matemático)
        if (hasText) {
            log.info("[WHITEBOARD_INTERPRET] whiteboardId={} classification_reason=TEXT_ELEMENTS type=text (elementos de texto detectados)",
                    whiteboardId);
            return complete("text", whiteboardId, title, exerciseLabel, documentId,
                    null, ocrText, structuredElements,
                    "La pizarra contiene elementos de texto.",
                    0.6);
        }

        // Fallback final: unknown con confianza baja — ya no asumimos math por defecto
        log.info("[WHITEBOARD_INTERPRET] whiteboardId={} classification_reason=UNKNOWN_ELEMENTS type=unknown (contenido detectado pero no clasificable)",
                whiteboardId);
        return complete("unknown", whiteboardId, title, exerciseLabel, documentId,
                null, ocrText, structuredElements,
                "La pizarra tiene contenido visible pero no se pudo clasificar con precisión.",
                0.2, "UNKNOWN_ELEMENTS");
    }

    private WhiteboardInterpretationResponse classifyFallback(
            String whiteboardId,
            String title,
            String exerciseLabel,
            Long documentId,
            List<Map<String, Object>> elements,
            String structuredElements,
            String ocrText
    ) {
        return classifyFallback(whiteboardId, title, exerciseLabel, documentId, elements, structuredElements, ocrText, "ELEMENT_STRUCTURE");
    }

    /**
     * Heurística robusta para detectar expresiones matemáticas en texto.
     * Detecta:
     * - números y variables mezclados
     * - símbolos matemáticos: = + - * / x √ ^
     * - dy/dx, derivadas
     * - expresiones SIN = pero con clara notación matemática: "2x + 1", "x + y"
     */
    private boolean isLikelyMathExpression(String text) {
        if (text == null || text.isBlank()) return false;

        String normalized = text.toLowerCase(Locale.ROOT).trim()
                .replaceAll("\\s+", " ")
                .replace("×", "x")
                .replace("⋅", "*")
                .replace("÷", "/")
                .replace("−", "-")
                .replace("—", "-")
                .replace("–", "-")
                // Símbolos matemáticos unicode comunes
                .replace("→", "->")
                .replace("△", "triangle")
                .replace("π", "pi")
                .replace("θ", "theta")
                .replace("α", "alpha")
                .replace("β", "beta")
                .replace("∑", "sum")
                .replace("∏", "product")
                .replace("∞", "infinity");

        // Detectar dy/dx, derivadas
        if (normalized.matches(".*\\bd[yz]\\s*/\\s*d[xz].*")) return true;
        if (normalized.matches(".*\\bd\\w+\\s*/\\s*d\\w+.*")) return true;
        if (normalized.matches(".*\\bf['′]?\\(x\\).*")) return true;
        if (normalized.matches(".*\\bint\\s*\\w+\\s*d\\w+.*")) return true;
        if (normalized.matches(".*\\b∫.*")) return true;
        if (normalized.matches(".*\\bsum\\b.*")) return true;
        if (normalized.matches(".*\\blím\\b|.*\\blim\\b.*")) return true;

        // Detectar ecuaciones con = (mayor prioridad)
        boolean hasEquals = normalized.contains("=");
        boolean hasInequality = normalized.contains("<") || normalized.contains(">") || normalized.contains("≤") || normalized.contains("≥");

        // Patrones con = o < >
        if (hasEquals || hasInequality) {
            boolean hasNumber = normalized.matches(".*\\d+.*");
            boolean hasVariable = normalized.matches(".*[a-zA-Z].*");
            boolean hasMathOp = normalized.matches(".*[+\\-*/^√].*");

            // Casos: "2x + 3 = 4", "x + 5 = 10", "y = 2x + 1"
            if (hasNumber && hasVariable) return true;
            // Casos con solo números y operadores: "2 + 3 = 4"
            if (hasNumber && hasMathOp) return true;
            // Casos con solo variables: "x = y + 1"
            if (hasVariable && hasMathOp) return true;
            // Incluso solo un número antes de = : "2x + 1 → 1 + = △" → tiene = y números
            if (hasNumber) return true;
        }

        // ── Patrones SIN = ────────────────────────────────────────────────────
        // Ejemplos: "2x + 1", "x + y", "3x - 2", "dy/dx", "x^2 + 1"

        // Número seguido de variable (implícita multiplicación): "2x", "3y", "5x"
        boolean hasImplicitMultiply = normalized.matches(".*\\d+\\s*[a-zA-Z].*") // "2x"
                || normalized.matches(".*[a-zA-Z]\\s*\\d+.*"); // "x2"

        // Variable con potencia: "x^2", "y^3"
        boolean hasPower = normalized.matches(".*[a-zA-Z]\\s*\\^\\s*\\d+.*");

        // Operador binario entre números/variables: "x + y", "3 + 4", "x - 1"
        boolean hasBinaryMathOp = normalized.matches(".*[a-zA-Z0-9]\\s*[+\\-]\\s*[a-zA-Z0-9].*");

        // Símbolo de raíz cuadrada
        boolean hasSqrt = normalized.matches(".*\\b√.*") || normalized.matches(".*\\bsqrt\\b.*");

        // Fracción simple tipo "a/b" o "1/2"
        boolean hasSimpleFraction = normalized.matches(".*[a-zA-Z0-9]\\s*/\\s*[a-zA-Z0-9].*");

        // Potencia con ^ : "x^2", "x^n"
        boolean hasCaret = normalized.matches(".*\\^\\s*[a-zA-Z0-9].*");

        // Signo menos/operador al inicio: "-2x + 1", "-x"
        boolean hasLeadingSign = normalized.matches("^\\s*[-+]\\s*[a-zA-Z0-9].*");

        // Número seguido de operador: "2+", "3-"
        boolean hasNumberAndOp = normalized.matches(".*\\d\\s*[+\\-*/]\\s*\\d.*");

        // Múltiples números separados por espacios o operadores: "1 2 3" con + o -
        boolean hasMultipleNumbers = normalized.matches(".*\\d+.*\\d+.*") && hasBinaryMathOp;

        // Flecha (→) entre expresiones: "2x + 1 → 3"
        boolean hasArrow = normalized.contains("->");

        if (hasImplicitMultiply) return true;
        if (hasPower) return true;
        if (hasSqrt) return true;
        if (hasCaret) return true;

        // Si tiene operador binario entre números/variables, es probablemente math
        if (hasBinaryMathOp && (normalized.matches(".*\\d+.*") || normalized.matches(".*[a-zA-Z].*"))) return true;

        // Fracción con al menos un número
        if (hasSimpleFraction && normalized.matches(".*\\d+.*")) return true;

        // Signo leading seguido de variable/número
        if (hasLeadingSign) return true;

        // Múltiples números con operación
        if (hasMultipleNumbers) return true;

        // Flecha (transformación) entre expresiones
        if (hasArrow && (normalized.matches(".*\\d+.*") || normalized.matches(".*[a-zA-Z].*"))) return true;

        // Si tiene número con signo y operador
        if (hasNumberAndOp) return true;

        return false;
    }

    private WhiteboardInterpretationResponse complete(
            String type,
            String whiteboardId,
            String title,
            String exerciseLabel,
            Long documentId,
            String equation,
            String ocrText,
            String structuredElements,
            String semanticSummary,
            double confidence
    ) {
        String normalizedType = normalizeType(equation != null ? "math" : type);
        String finalSummary = semanticSummary == null || semanticSummary.isBlank()
                ? "No se pudo interpretar claramente la pizarra."
                : semanticSummary;

        log.info("[WHITEBOARD_INTERPRET] whiteboardId={} RESULTADO FINAL: type={} confidence={} equation='{}' ocrText='{}' summary='{}'",
                whiteboardId, normalizedType, Math.max(0.0, Math.min(1.0, confidence)),
                equation != null ? equation : "", ocrText != null ? ocrText : "",
                finalSummary);

        return new WhiteboardInterpretationResponse(
                normalizedType,
                whiteboardId,
                title,
                exerciseLabel,
                documentId,
                equation,
                ocrText == null ? "" : ocrText,
                structuredElements == null ? "" : structuredElements,
                finalSummary,
                Math.max(0.0, Math.min(1.0, confidence))
        );
    }

    private WhiteboardInterpretationResponse complete(
            String type,
            String whiteboardId,
            String title,
            String exerciseLabel,
            Long documentId,
            String equation,
            String ocrText,
            String structuredElements,
            String semanticSummary,
            double confidence,
            String reason
    ) {
        String normalizedType = normalizeType(equation != null ? "math" : type);
        String finalSummary = semanticSummary == null || semanticSummary.isBlank()
                ? "No se pudo interpretar claramente la pizarra."
                : semanticSummary;

        log.info("[WHITEBOARD_INTERPRET] whiteboardId={} RESULTADO FINAL: type={} confidence={} reason={} equation='{}' ocrText='{}' summary='{}'",
                whiteboardId, normalizedType, Math.max(0.0, Math.min(1.0, confidence)), reason,
                equation != null ? equation : "", ocrText != null ? ocrText : "",
                finalSummary);

        return new WhiteboardInterpretationResponse(
                normalizedType,
                whiteboardId,
                title,
                exerciseLabel,
                documentId,
                equation,
                ocrText == null ? "" : ocrText,
                structuredElements == null ? "" : structuredElements,
                finalSummary,
                Math.max(0.0, Math.min(1.0, confidence)),
                reason
        );
    }

    private WhiteboardInterpretationResponse unknown(
            String whiteboardId,
            String title,
            String exerciseLabel,
            Long documentId,
            String structuredElements
    ) {
        log.info("[WHITEBOARD_INTERPRET] whiteboardId={} RESULTADO FINAL: type=unknown confidence=0.0 (no se pudo interpretar)", whiteboardId);
        return complete(
                "unknown",
                whiteboardId,
                title,
                exerciseLabel,
                documentId,
                null,
                "",
                structuredElements,
                "No se pudo interpretar claramente la pizarra.",
                0.0
        );
    }

    /**
     * Preprocesa la imagen antes de enviarla al modelo vision:
     * - Fondo blanco
     * - Trazos negros
     * - Recorta al área con contenido
     * - Agrega margen
     * - Escala 2x
     * - Limita tamaño máximo
     */
    private String preprocessImage(String imageBase64, String whiteboardId) {
        if (imageBase64 == null || !imageBase64.contains("base64,")) return imageBase64;
        try {
            String payload = imageBase64.substring(imageBase64.indexOf("base64,") + 7);
            byte[] rawBytes = Base64.getDecoder().decode(payload);
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(rawBytes));
            if (original == null) {
                log.warn("[WHITEBOARD_INTERPRET] whiteboardId={} No se pudo decodificar la imagen para preprocesar", whiteboardId);
                return imageBase64;
            }

            int w = original.getWidth();
            int h = original.getHeight();
            if (w == 0 || h == 0) return imageBase64;

            // 1. Fondo blanco si la imagen original tiene transparencia
            BufferedImage whiteBg = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = whiteBg.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, w, h);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.drawImage(original, 0, 0, w, h, null);
            } finally {
                g.dispose();
            }

            // 2. Detectar área con contenido (crop)
            int minX = w, minY = h, maxX = 0, maxY = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = whiteBg.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int gv = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    // No es blanco puro (o muy cercano)
                    if (r < 240 || gv < 240 || b < 240) {
                        if (x < minX) minX = x;
                        if (y < minY) minY = y;
                        if (x > maxX) maxX = x;
                        if (y > maxY) maxY = y;
                    }
                }
            }

            // Si no se encontró contenido, devolver la imagen original centrada
            if (minX > maxX || minY > maxY) {
                log.debug("[WHITEBOARD_INTERPRET] whiteboardId={} No se detectó contenido en la imagen para crop", whiteboardId);
                return imageBase64;
            }

            // 3. Agregar margen
            int cropX = Math.max(0, minX - CROP_PADDING);
            int cropY = Math.max(0, minY - CROP_PADDING);
            int cropW = Math.min(w - cropX, maxX - minX + 2 * CROP_PADDING);
            int cropH = Math.min(h - cropY, maxY - minY + 2 * CROP_PADDING);

            BufferedImage cropped = whiteBg.getSubimage(cropX, cropY, cropW, cropH);

            // 4. Escalar 2x
            int scaledW = Math.min(cropW * PREPROCESS_SCALE, MAX_IMAGE_DIMENSION);
            int scaledH = Math.min(cropH * PREPROCESS_SCALE, MAX_IMAGE_DIMENSION);

            BufferedImage scaled = new BufferedImage(scaledW, scaledH, BufferedImage.TYPE_INT_RGB);
            Graphics2D sg = scaled.createGraphics();
            try {
                sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                sg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                sg.drawImage(cropped, 0, 0, scaledW, scaledH, null);
            } finally {
                sg.dispose();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(scaled, "png", out);
            byte[] processedBytes = out.toByteArray();
            String processedBase64 = Base64.getEncoder().encodeToString(processedBytes);

            log.debug("[WHITEBOARD_INTERPRET] whiteboardId={} Imagen preprocesada: original={}x{} → crop=({},{}){}x{} → escalada={}x{} total={}bytes",
                    whiteboardId, w, h, cropX, cropY, cropW, cropH, scaledW, scaledH, processedBytes.length);

            return "data:image/png;base64," + processedBase64;

        } catch (Exception e) {
            log.warn("[WHITEBOARD_INTERPRET] whiteboardId={} Error en preprocesamiento de imagen: {}", whiteboardId, e.getMessage());
            return imageBase64;
        }
    }

    /**
     * Guarda la imagen original en /tmp para debug.
     * Solo en perfil dev o local.
     */
    private void saveDebugImage(String imageBase64, String whiteboardId) {
        boolean isDev = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "dev".equals(p) || "local".equals(p) || "development".equals(p));
        if (!isDev) return;

        if (imageBase64 == null || !imageBase64.contains("base64,")) return;
        try {
            String payload = imageBase64.substring(imageBase64.indexOf("base64,") + 7);
            byte[] rawBytes = Base64.getDecoder().decode(payload);
            File debugFile = new File("/tmp/whiteboard-" + whiteboardId.replaceAll("[^a-zA-Z0-9_-]", "_") + ".png");
                    java.nio.file.Files.write(debugFile.toPath(), rawBytes);
            log.info("[WHITEBOARD_INTERPRET] whiteboardId={} Imagen guardada para debug en {}", whiteboardId, debugFile.getAbsolutePath());
        } catch (IOException e) {
            log.warn("[WHITEBOARD_INTERPRET] whiteboardId={} No se pudo guardar imagen debug: {}", whiteboardId, e.getMessage());
        }
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

    private String inferType(String text, List<Map<String, Object>> elements) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);

        // Primero detectar ejes cartesianos (graph) — antes que math
        if (isLikelyAxes(elements)) return "graph";

        if (extractMathExpression(text) != null
                || lower.matches(".*\\d+\\s*[+\\-x×*/=<>]\\s*\\d+.*")
                || isLikelyMathExpression(text)) return "math";
        if (lower.matches(".*\\b(if|while|for|leer|inicio|fin|retornar|función|funcion|mostrar|mientras|si)\\b.*")) return "algorithm";
        boolean hasDiagram = elements.stream().anyMatch(el -> {
            String type = String.valueOf(el.get("type"));
            return "rect".equals(type) || "circle".equals(type) || "diamond".equals(type) || "arrow".equals(type);
        });
        if (hasDiagram) return "flowchart";
        return hasUsefulText(text) ? "text" : "unknown";
    }

    private String semanticSummary(String type, String equation, String text, String structuredElements) {
        if ("math".equals(type) && equation != null) {
            return "La pizarra contiene una ecuación matemática: " + equation + ".";
        }
        if ("graph".equals(type)) {
            return "La pizarra contiene un sistema de ejes cartesianos con curvas o funciones.";
        }
        if ("geometry".equals(type)) {
            return "La pizarra contiene figuras geométricas.";
        }
        if (hasUsefulText(text)) {
            return "La pizarra contiene texto estructurado: " + text.replace("\n", " ") + ".";
        }
        return "No hay texto claro. Estructura visible: " + structuredElements + ".";
    }

    private boolean hasUsefulText(String text) {
        return text != null && text.matches(".*[\\p{L}\\p{N}].*") && text.trim().length() >= 2;
    }

    private String extractMathExpression(String text) {
        if (text == null || text.isBlank()) return null;
        String normalized = text
                .replace("²", "^2")
                .replace("³", "^3")
                .replace("÷", "/")
                .replace("−", "-")
                .replace("×", "x")
                .replace("·", "*")
                .replaceAll("\\s+", " ")
                .trim();
        var matcher = java.util.regex.Pattern
                .compile("(?i)([a-z0-9][a-z0-9\\s+\\-*/×xX^().]*\\s*[=<>]\\s*[a-z0-9][a-z0-9\\s+\\-*/×xX^().]*)")
                .matcher(normalized);
        while (matcher.find()) {
            String candidate = matcher.group(1)
                    .replaceAll("\\s*([+\\-*/×=<>^()])\\s*", " $1 ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (candidate.matches("(?i)[a-z0-9\\s+\\-*/×xX=<>^().]+") && candidate.matches(".*[0-9].*")) {
                return candidate;
            }
        }

        // También detectar dy/dx, derivadas
        var derivMatcher = java.util.regex.Pattern.compile("(?i)d[a-z]/d[a-z]\\s*=?.+").matcher(normalized);
        if (derivMatcher.find()) {
            return derivMatcher.group().trim();
        }

        return null;
    }

    private String normalizeType(String type) {
        return switch (type) {
            case "math", "graph", "geometry", "algorithm", "flowchart", "text" -> type;
            default -> "unknown";
        };
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) return "auto";
        return switch (mode.trim().toLowerCase(Locale.ROOT)) {
            case "math", "matemática", "matematicas", "matemáticas" -> "math";
            case "graph", "grafica", "gráfica", "grafico", "gráfico" -> "graph";
            case "geometry", "geometria", "geometría" -> "geometry";
            case "algorithm", "algoritmo" -> "algorithm";
            case "flowchart", "diagrama" -> "flowchart";
            case "text", "texto" -> "text";
            default -> "auto";
        };
    }

    /**
     * Intenta usar el Math OCR especializado (pix2tex) a través del document-service.
     * Devuelve null si el servicio no está disponible.
     */
    private MathOcrResult tryMathOcr(String imageBase64, String whiteboardId) {
        try {
            MathOcrClient client = mathOcrClient.getIfAvailable();
            if (client == null) {
                log.debug("[WHITEBOARD_INTERPRET] whiteboardId={} MathOcrClient no disponible", whiteboardId);
                return null;
            }
            long startTime = System.currentTimeMillis();
            MathOcrResult result = client.ocrMath(imageBase64);
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[WHITEBOARD_INTERPRET] whiteboardId={} Math OCR: method={} latex='{}' confidence={} elapsedMs={}",
                    whiteboardId, result.method(),
                    result.latex() != null && result.latex().length() > 80
                            ? result.latex().substring(0, 80) + "..."
                            : result.latex(),
                    result.confidence(), elapsed);
            return result;
        } catch (Exception e) {
            log.warn("[WHITEBOARD_INTERPRET] whiteboardId={} Error llamando a Math OCR: {}", whiteboardId, e.getMessage());
            return null;
        }
    }

    /**
     * Heurística para detectar si un conjunto de paths (trazos) forma ejes cartesianos.
     * Busca:
     * - Una línea horizontal larga que cruza la imagen (eje X)
     * - Una línea vertical larga que cruza la imagen (eje Y)
     * - Ambas se intersecan
     */
    private boolean isLikelyAxes(List<Map<String, Object>> elements) {
        // Solo consideramos paths (trazos manuscritos)
        List<Map<String, Object>> paths = elements.stream()
                .filter(el -> "path".equals(String.valueOf(el.get("type"))))
                .toList();

        if (paths.size() < 2) return false;

        int horizontalCandidates = 0;
        int verticalCandidates = 0;

        for (Map<String, Object> path : paths) {
            Object pointsObj = path.get("points");
            if (!(pointsObj instanceof List<?> pointsList) || pointsList.size() < 4) continue;

            // Calcular extensión horizontal y vertical de los puntos
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

            for (Object pt : pointsList) {
                if (pt instanceof List<?> coords && coords.size() >= 2) {
                    double x = ((Number) coords.get(0)).doubleValue();
                    double y = ((Number) coords.get(1)).doubleValue();
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }

            double width = maxX - minX;
            double height = maxY - minY;

            // Línea horizontal larga: ancho >> alto
            if (width > height * 5 && width > 100) {
                horizontalCandidates++;
            }
            // Línea vertical larga: alto >> ancho
            if (height > width * 5 && height > 100) {
                verticalCandidates++;
            }
        }

        // Si hay al menos una línea horizontal larga y una vertical larga, probablemente son ejes
        return horizontalCandidates >= 1 && verticalCandidates >= 1;
    }
}

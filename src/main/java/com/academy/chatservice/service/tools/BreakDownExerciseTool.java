package com.academy.chatservice.service.tools;

import com.academy.chatservice.model.tools.BreakDownExerciseArgs;
import com.academy.chatservice.model.tools.ExerciseBreakdownResponse;
import com.academy.chatservice.model.tools.ExerciseStepDTO;
import com.academy.chatservice.model.tools.ToolDefinition;
import com.academy.chatservice.service.LLMClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class BreakDownExerciseTool implements ChatTool<BreakDownExerciseArgs> {

    private final LLMClient llmClient;
    private final ObjectMapper objectMapper;

    public BreakDownExerciseTool(LLMClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "break_down_exercise",
                "Desglosa un ejercicio académico en pasos pedagógicos progresivos para que el frontend pueda mostrar una guía paso a paso.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "exerciseText", Map.of(
                                        "type", "string",
                                        "description", "Texto completo del ejercicio a desglosar"
                                ),
                                "exerciseTitle", Map.of(
                                        "type", "string",
                                        "description", "Título o identificador del ejercicio, por ejemplo Ejercicio 2"
                                ),
                                "userLevel", Map.of(
                                        "type", "string",
                                        "enum", List.of("basico", "intermedio", "avanzado"),
                                        "description", "Nivel de detalle de la explicación"
                                ),
                                "showFullSolution", Map.of(
                                        "type", "boolean",
                                        "description", "Si es true, puede mostrar la solución completa; si es false, solo guía progresiva"
                                )
                        ),
                        "required", List.of("exerciseText", "exerciseTitle", "userLevel", "showFullSolution")
                )
        );
    }

    @Override
    public Class<BreakDownExerciseArgs> argumentType() {
        return BreakDownExerciseArgs.class;
    }

    @Override
    public ExerciseBreakdownResponse execute(BreakDownExerciseArgs args) {
        String prompt = buildBreakdownPrompt(args);
        String raw = llmClient.generate(prompt);
        try {
            ExerciseBreakdownResponse parsed = objectMapper.readValue(extractJson(raw), ExerciseBreakdownResponse.class);
            return normalize(args.exerciseTitle(), parsed);
        } catch (Exception ignored) {
            return fallback(args);
        }
    }

    private String buildBreakdownPrompt(BreakDownExerciseArgs args) {
        return """
                Sos un tutor académico. Convertí el ejercicio en una guía interactiva paso a paso.
                Respondé únicamente JSON válido, sin markdown, sin comentarios y sin texto fuera del objeto.

                Reglas:
                - El campo type debe ser exactamente "exercise_breakdown".
                - Usá entre 3 y 6 pasos.
                - Cada paso debe tener stepNumber, title, content y hint.
                - La secuencia debe ser pedagógica y progresiva.
                - Si showFullSolution=false, no reveles la solución final: guiá con razonamiento, pistas y próximos movimientos.
                - Si showFullSolution=true, podés incluir el cierre completo en el último paso.
                - Ajustá el detalle al nivel: %s.

                Formato:
                {
                  "type": "exercise_breakdown",
                  "exerciseTitle": "%s",
                  "steps": [
                    {
                      "stepNumber": 1,
                      "title": "Comprender el enunciado",
                      "content": "Primero identificamos los datos y qué se pide.",
                      "hint": "Subrayá los datos importantes antes de calcular."
                    }
                  ]
                }

                showFullSolution: %s
                Ejercicio:
                %s
                """.formatted(
                args.userLevel(),
                escapeForPrompt(args.exerciseTitle()),
                args.showFullSolution(),
                args.exerciseText()
        );
    }

    private String extractJson(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private ExerciseBreakdownResponse normalize(String requestedTitle, ExerciseBreakdownResponse response) {
        String title = response.exerciseTitle() == null || response.exerciseTitle().isBlank()
                ? requestedTitle
                : response.exerciseTitle();
        List<ExerciseStepDTO> steps = response.steps() == null ? List.of() : response.steps();
        if (steps.isEmpty()) {
            return fallback(new BreakDownExerciseArgs(requestedTitle, requestedTitle, "intermedio", false));
        }
        return new ExerciseBreakdownResponse(title, steps);
    }

    private ExerciseBreakdownResponse fallback(BreakDownExerciseArgs args) {
        return new ExerciseBreakdownResponse(
                args.exerciseTitle(),
                List.of(
                        new ExerciseStepDTO(
                                1,
                                "Comprender el enunciado",
                                "Primero identificamos qué información aparece en el ejercicio y cuál es el objetivo exacto antes de aplicar fórmulas o escribir código.",
                                "Separá datos, condiciones y pregunta final."
                        ),
                        new ExerciseStepDTO(
                                2,
                                "Elegir una estrategia",
                                "Ahora vinculamos el enunciado con el concepto correspondiente y decidimos qué procedimiento conviene usar.",
                                "Nombrá el tema principal del ejercicio."
                        ),
                        new ExerciseStepDTO(
                                3,
                                args.showFullSolution() ? "Resolver y verificar" : "Avanzar sin cerrar la respuesta",
                                args.showFullSolution()
                                        ? "Aplicá el procedimiento paso a paso y verificá que el resultado responda exactamente lo pedido."
                                        : "Hacé el primer desarrollo parcial y revisá si cumple las condiciones antes de pedir la solución completa.",
                                args.showFullSolution()
                                        ? "Comprobá unidades, casos borde o coherencia del resultado."
                                        : "Intentá completar este paso y pedí el cierre cuando quieras comparar."
                        )
                )
        );
    }

    private String escapeForPrompt(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

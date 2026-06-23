package com.academy.chatservice.service;

import com.academy.chatservice.model.LearningEvidenceSnapshot;
import com.academy.chatservice.model.LearningExerciseSignal;
import com.academy.chatservice.model.LearningProfileAssessment;
import com.academy.chatservice.model.LearningProfileDto;
import com.academy.chatservice.model.LearningProfileSignals;
import com.academy.chatservice.model.LearningRecommendationDto;
import com.academy.chatservice.model.ProfileMaturity;
import com.academy.chatservice.model.WeeklyStudyPlanItemDto;
import com.academy.chatservice.repository.LearningProfileRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class LearningProfileService {

    private final LearningProfileRepository learningProfileRepository;
    private final ProfileMaturityCalculator profileMaturityCalculator;

    public LearningProfileService(LearningProfileRepository learningProfileRepository,
                                  ProfileMaturityCalculator profileMaturityCalculator) {
        this.learningProfileRepository = learningProfileRepository;
        this.profileMaturityCalculator = profileMaturityCalculator;
    }

    public LearningProfileDto getProfile(String userEmail) {
        LearningEvidenceSnapshot evidence = learningProfileRepository.fetchEvidence(userEmail);
        LearningProfileSignals signals = learningProfileRepository.fetchSignals(userEmail);
        LearningProfileAssessment assessment = profileMaturityCalculator.assess(evidence);

        return new LearningProfileDto(
                assessment.maturity(),
                evidence.documentsAnalyzed(),
                evidence.exercisesDetected(),
                evidence.relevantInteractions(),
                assessment.progressPercentage(),
                assessment.canGenerateRecommendations(),
                assessment.canGenerateStudyPlan(),
                buildRecommendations(evidence, signals, assessment),
                buildWeeklyStudyPlan(evidence, signals, assessment),
                buildStrengths(evidence, assessment),
                buildWeaknesses(evidence, assessment)
        );
    }

    private List<LearningRecommendationDto> buildRecommendations(LearningEvidenceSnapshot evidence,
                                                                 LearningProfileSignals signals,
                                                                 LearningProfileAssessment assessment) {
        if (!assessment.canGenerateRecommendations()) {
            return List.of();
        }

        String confidence = recommendationConfidence(assessment);
        int exercisesToAdvanced = Math.max(0, 20 - evidence.exercisesDetected());
        int documentsToAdvanced = Math.max(0, 6 - evidence.documentsAnalyzed());
        int interactionsToAdvanced = Math.max(0, 30 - evidence.relevantInteractions());
        List<DocumentExerciseBlock> blocks = buildDocumentBlocks(signals);

        List<LearningRecommendationDto> recommendations = new ArrayList<>();
        if (!blocks.isEmpty()) {
            DocumentExerciseBlock firstBlock = blocks.getFirst();
            recommendations.add(new LearningRecommendationDto(
                    "Priorizá " + formatDocument(firstBlock.documentName()),
                    "En " + formatDocument(firstBlock.documentName())
                            + " ya detectamos " + joinExercises(firstBlock.exercises(), 3)
                            + ". Cerrá esos ejercicios antes de abrir otro PDF para convertir evidencia detectada en práctica real.",
                    confidence
            ));
        }

        if (blocks.size() > 1) {
            DocumentExerciseBlock secondBlock = blocks.get(1);
            recommendations.add(new LearningRecommendationDto(
                    "SeguÍ con " + formatDocument(secondBlock.documentName()),
                    "Después del primer bloque, pasá a "
                            + joinExercises(secondBlock.exercises(), 2)
                            + " de " + formatDocument(secondBlock.documentName())
                            + ". Eso te suma variedad sin cambiar de material ni mezclar demasiados temas en una sola sesión.",
                    confidence
            ));
        }

        recommendations.add(new LearningRecommendationDto(
                exercisesToAdvanced > 0
                        ? "Subí de " + evidence.exercisesDetected() + " a 20 ejercicios detectados"
                        : "Mantené el umbral avanzado con ejercicios nuevos",
                exercisesToAdvanced > 0
                        ? "Con los PDFs que ya cargaste"
                        + ", te faltan " + exercisesToAdvanced
                        + " ejercicios concretos para llegar al siguiente escalón. No necesitás material nuevo todavía: completá más ejercicios en los prácticos que ya están listos."
                        : "Ya llegaste al umbral avanzado de ejercicios detectados. Mantené variedad y seguí sumando ejercicios nuevos sobre documentos distintos.",
                confidence
        ));

        recommendations.add(new LearningRecommendationDto(
                "Próximo objetivo medible",
                "Para consolidar el siguiente nivel con margen real, te faltan "
                        + documentsToAdvanced + " documento(s), "
                        + exercisesToAdvanced + " ejercicio(s) y "
                        + interactionsToAdvanced + " interacción(es) relevantes.",
                confidence
        ));

        return List.copyOf(recommendations);
    }

    private String recommendationConfidence(LearningProfileAssessment assessment) {
        if (assessment.progressPercentage() >= 100) {
            return "Confianza alta";
        }

        return switch (assessment.maturity()) {
            case PERFIL_INICIAL -> "Confianza baja";
            case PERFIL_CONFIABLE -> "Confianza media";
            case PERFIL_AVANZADO -> "Confianza alta";
            default -> "Sin confianza suficiente";
        };
    }

    private List<WeeklyStudyPlanItemDto> buildWeeklyStudyPlan(LearningEvidenceSnapshot evidence,
                                                              LearningProfileSignals signals,
                                                              LearningProfileAssessment assessment) {
        if (!assessment.canGenerateStudyPlan()) {
            return List.of();
        }

        List<DocumentExerciseBlock> blocks = buildDocumentBlocks(signals);
        DocumentExerciseBlock firstBlock = nth(blocks, 0);
        DocumentExerciseBlock secondBlock = nth(blocks, 1);
        DocumentExerciseBlock thirdBlock = nth(blocks, 2);
        DocumentExerciseBlock fourthBlock = nth(blocks, 3);
        DocumentExerciseBlock reviewBlock = fourthBlock != null ? fourthBlock : (thirdBlock != null ? thirdBlock : secondBlock);

        return List.of(
                new WeeklyStudyPlanItemDto(
                        "Lunes",
                        firstBlock != null
                                ? formatDocument(firstBlock.documentName())
                                : "2 ejercicios nuevos",
                        "Práctica focalizada",
                        firstBlock != null
                                ? "Arrancá con " + joinExercises(firstBlock.exercises(), 2)
                                + " de " + formatDocument(firstBlock.documentName())
                                + ". Hacé un intento completo por ejercicio antes de abrir el chat."
                                : "Elegí 2 ejercicios de documentos distintos. Intentá resolverlos sin ayuda durante 10 minutos cada uno y registrá en TutorIA solamente el punto exacto donde te trabaste."
                ),
                new WeeklyStudyPlanItemDto(
                        "Martes",
                        secondBlock != null
                                ? formatDocument(secondBlock.documentName())
                                : "1 corrección guiada",
                        "Segundo bloque",
                        secondBlock != null
                                ? "SeguÍ con " + joinExercises(secondBlock.exercises(), 2)
                                + " de " + formatDocument(secondBlock.documentName())
                                + ". Usá TutorIA sólo para corregir el paso exacto donde te frenes."
                                : "Tomá el ejercicio que peor salió el lunes y pedile a TutorIA una explicación paso a paso. Cerrá con una variante corta del mismo tipo de ejercicio."
                ),
                new WeeklyStudyPlanItemDto(
                        "Miércoles",
                        thirdBlock != null
                                ? formatDocument(thirdBlock.documentName())
                                : "3 intentos sin asistencia",
                        "Autonomía",
                        thirdBlock != null
                                ? "Reservá esta sesión para " + joinExercises(thirdBlock.exercises(), 2)
                                + " de " + formatDocument(thirdBlock.documentName())
                                + ". La meta es completar el bloque sin pedir hints intermedios."
                                : "Resolvé 3 ejercicios seguidos sin abrir respuesta ni pedir hints. Al final usá TutorIA sólo para validar el resultado o ubicar errores concretos."
                ),
                new WeeklyStudyPlanItemDto(
                        "Jueves",
                        reviewBlock != null
                                ? "Repaso de " + formatDocument(reviewBlock.documentName())
                                : "Rehacer fallos",
                        "Refuerzo",
                        reviewBlock != null
                                ? "Volvé sobre " + joinExercises(reviewBlock.exercises(), 2)
                                + " de " + formatDocument(reviewBlock.documentName())
                                + ". Si uno falla de nuevo, ahí sí abrí TutorIA con una duda puntual."
                                : "Rehacé 2 ejercicios que ya habías fallado. Si volvés a errar, escribí en el chat exactamente en qué paso te equivocaste y qué regla aplicaste mal."
                ),
                new WeeklyStudyPlanItemDto(
                        "Viernes",
                        firstBlock != null
                                ? "Cierre con " + formatDocument(firstBlock.documentName())
                                : "1 ejercicio más difícil",
                        "Aplicación",
                        firstBlock != null
                                ? "Cerrá la semana con el ejercicio más largo de "
                                + formatDocument(firstBlock.documentName())
                                + " o con el siguiente que todavía no trabajaste. El objetivo es terminar una resolución completa, no sólo consultas sueltas."
                                : "Cerrá la semana con 1 ejercicio más largo o más difícil que los anteriores. Resolvelo explicando por escrito cada decisión antes de consultar a TutorIA."
                )
        );
    }

    private List<DocumentExerciseBlock> buildDocumentBlocks(LearningProfileSignals signals) {
        LinkedHashMap<String, List<String>> grouped = new LinkedHashMap<>();

        for (String documentName : signals.recentDocuments()) {
            grouped.putIfAbsent(documentName, new ArrayList<>());
        }

        for (LearningExerciseSignal exercise : signals.recentExercises()) {
            grouped.computeIfAbsent(exercise.documentName(), ignored -> new ArrayList<>());
            List<String> exercises = grouped.get(exercise.documentName());
            String formattedExercise = formatExercise(exercise.exerciseRef());
            if (!exercises.contains(formattedExercise)) {
                exercises.add(formattedExercise);
            }
        }

        List<DocumentExerciseBlock> blocks = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                blocks.add(new DocumentExerciseBlock(entry.getKey(), List.copyOf(entry.getValue())));
            }
        }
        return List.copyOf(blocks);
    }

    private static String formatDocument(String documentName) {
        String normalized = safe(documentName).replaceAll("\\.[Pp][Dd][Ff]$", "");
        normalized = normalized.replace('_', ' ').replace('-', ' ');
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized.isBlank() ? "tu práctico reciente" : normalized;
    }

    private static String formatExercise(String exerciseRef) {
        String normalized = safe(exerciseRef).trim().replaceAll("[\\.:]+$", "");
        if (normalized.isBlank()) {
            return "el ejercicio detectado";
        }
        if (normalized.toLowerCase(Locale.ROOT).startsWith("ejercicio")) {
            normalized = normalized.replaceFirst("(?i)^ejercicio\\s*", "Ejercicio ");
        } else if (normalized.toLowerCase(Locale.ROOT).startsWith("ej")) {
            normalized = normalized.replaceFirst("(?i)^ej\\s*", "Ejercicio ");
        }
        return normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static <T> T first(List<T> values) {
        return nth(values, 0);
    }

    private static <T> T second(List<T> values) {
        return nth(values, 1);
    }

    private static <T> T nth(List<T> values, int index) {
        if (values == null || index < 0 || index >= values.size()) {
            return null;
        }
        return values.get(index);
    }

    private String joinExercises(List<String> exercises, int limit) {
        if (exercises == null || exercises.isEmpty()) {
            return "los ejercicios detectados";
        }
        List<String> visible = exercises.subList(0, Math.min(limit, exercises.size()));
        if (visible.size() == 1) {
            return visible.getFirst();
        }
        if (visible.size() == 2) {
            return visible.get(0) + " y " + visible.get(1);
        }
        return String.join(", ", visible.subList(0, visible.size() - 1))
                + " y " + visible.getLast();
    }

    private record DocumentExerciseBlock(String documentName, List<String> exercises) {
    }

    private List<String> buildStrengths(LearningEvidenceSnapshot evidence, LearningProfileAssessment assessment) {
        if (!assessment.canGenerateStrengths()) {
            return List.of();
        }

        List<String> strengths = new ArrayList<>();
        if (evidence.documentsAnalyzed() >= 3) {
            strengths.add("Ya existe una base documental suficiente para detectar patrones estables sobre tu material de estudio.");
        }
        if (evidence.exercisesDetected() >= 10) {
            strengths.add("La práctica acumulada sobre ejercicios detectados reduce ruido y mejora la precisión de futuras recomendaciones.");
        }
        if (evidence.relevantInteractions() >= 15) {
            strengths.add("Tus interacciones con TutorIA ya alcanzan un volumen útil para reconocer hábitos de consulta y seguimiento.");
        }
        return List.copyOf(strengths);
    }

    private List<String> buildWeaknesses(LearningEvidenceSnapshot evidence, LearningProfileAssessment assessment) {
        if (!assessment.canGenerateWeaknesses()) {
            return List.of();
        }

        List<String> weaknesses = new ArrayList<>();
        if (evidence.documentsAnalyzed() < 6) {
            weaknesses.add("Falta ampliar la variedad de documentos para elevar la confianza del perfil al nivel avanzado.");
        }
        if (evidence.exercisesDetected() < 20) {
            weaknesses.add("Conviene trabajar más ejercicios para separar mejor dudas puntuales de debilidades recurrentes.");
        }
        if (evidence.relevantInteractions() < 30) {
            weaknesses.add("Necesitás más intercambios relevantes con TutorIA para estabilizar un plan semanal automático.");
        }
        return List.copyOf(weaknesses);
    }
}

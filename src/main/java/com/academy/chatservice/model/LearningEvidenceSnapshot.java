package com.academy.chatservice.model;

import java.util.EnumMap;
import java.util.Map;

public final class LearningEvidenceSnapshot {

    private final EnumMap<LearningEvidenceMetric, Long> metrics;

    private LearningEvidenceSnapshot(EnumMap<LearningEvidenceMetric, Long> metrics) {
        this.metrics = new EnumMap<>(metrics);
    }

    public static LearningEvidenceSnapshot of(long documentsAnalyzed,
                                              long exercisesDetected,
                                              long relevantInteractions) {
        EnumMap<LearningEvidenceMetric, Long> metrics = new EnumMap<>(LearningEvidenceMetric.class);
        metrics.put(LearningEvidenceMetric.DOCUMENTS_ANALYZED, sanitize(documentsAnalyzed));
        metrics.put(LearningEvidenceMetric.EXERCISES_DETECTED, sanitize(exercisesDetected));
        metrics.put(LearningEvidenceMetric.RELEVANT_INTERACTIONS, sanitize(relevantInteractions));
        return new LearningEvidenceSnapshot(metrics);
    }

    public LearningEvidenceSnapshot withMetric(LearningEvidenceMetric metric, long value) {
        EnumMap<LearningEvidenceMetric, Long> copy = new EnumMap<>(metrics);
        copy.put(metric, sanitize(value));
        return new LearningEvidenceSnapshot(copy);
    }

    public long valueOf(LearningEvidenceMetric metric) {
        return metrics.getOrDefault(metric, 0L);
    }

    public Map<LearningEvidenceMetric, Long> asMap() {
        return Map.copyOf(metrics);
    }

    public int documentsAnalyzed() {
        return toInt(valueOf(LearningEvidenceMetric.DOCUMENTS_ANALYZED));
    }

    public int exercisesDetected() {
        return toInt(valueOf(LearningEvidenceMetric.EXERCISES_DETECTED));
    }

    public int relevantInteractions() {
        return toInt(valueOf(LearningEvidenceMetric.RELEVANT_INTERACTIONS));
    }

    private static int toInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
    }

    private static long sanitize(long value) {
        return Math.max(0L, value);
    }
}

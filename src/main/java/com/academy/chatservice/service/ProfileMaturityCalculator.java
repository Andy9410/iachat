package com.academy.chatservice.service;

import com.academy.chatservice.model.LearningEvidenceMetric;
import com.academy.chatservice.model.LearningEvidenceSnapshot;
import com.academy.chatservice.model.LearningProfileAssessment;
import com.academy.chatservice.model.ProfileMaturity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ProfileMaturityCalculator {

    private static final ThresholdBand ADVANCED = new ThresholdBand(
            ProfileMaturity.PERFIL_AVANZADO,
            Map.of(
                    LearningEvidenceMetric.DOCUMENTS_ANALYZED, 6L,
                    LearningEvidenceMetric.EXERCISES_DETECTED, 20L,
                    LearningEvidenceMetric.RELEVANT_INTERACTIONS, 30L
            )
    );

    private static final ThresholdBand RELIABLE = new ThresholdBand(
            ProfileMaturity.PERFIL_CONFIABLE,
            Map.of(
                    LearningEvidenceMetric.DOCUMENTS_ANALYZED, 3L,
                    LearningEvidenceMetric.EXERCISES_DETECTED, 10L,
                    LearningEvidenceMetric.RELEVANT_INTERACTIONS, 15L
            )
    );

    private static final ThresholdBand INITIAL = new ThresholdBand(
            ProfileMaturity.PERFIL_INICIAL,
            Map.of(
                    LearningEvidenceMetric.DOCUMENTS_ANALYZED, 2L,
                    LearningEvidenceMetric.EXERCISES_DETECTED, 10L,
                    LearningEvidenceMetric.RELEVANT_INTERACTIONS, 5L
            )
    );

    private static final ThresholdBand PROGRESS_TARGET = new ThresholdBand(
            ProfileMaturity.PERFIL_CONFIABLE,
            Map.of(
                    LearningEvidenceMetric.DOCUMENTS_ANALYZED, 3L,
                    LearningEvidenceMetric.EXERCISES_DETECTED, 10L,
                    LearningEvidenceMetric.RELEVANT_INTERACTIONS, 15L
            )
    );

    private static final List<ThresholdBand> ORDERED_BANDS = List.of(ADVANCED, RELIABLE, INITIAL);

    public ProfileMaturity calculateMaturity(LearningEvidenceSnapshot snapshot) {
        return ORDERED_BANDS.stream()
                .filter(band -> band.matches(snapshot))
                .map(ThresholdBand::maturity)
                .findFirst()
                .orElse(ProfileMaturity.PERFIL_INSUFICIENTE);
    }

    public LearningProfileAssessment assess(LearningEvidenceSnapshot snapshot) {
        ProfileMaturity maturity = calculateMaturity(snapshot);
        return new LearningProfileAssessment(
                maturity,
                calculateProgressPercentage(snapshot),
                isEnoughDataForRecommendations(snapshot),
                isEnoughDataForStudyPlan(snapshot),
                isEnoughDataForStrengths(snapshot),
                isEnoughDataForWeaknesses(snapshot)
        );
    }

    public boolean isEnoughDataForRecommendations(LearningEvidenceSnapshot snapshot) {
        return calculateMaturity(snapshot).atLeast(ProfileMaturity.PERFIL_INICIAL);
    }

    public boolean isEnoughDataForStudyPlan(LearningEvidenceSnapshot snapshot) {
        return calculateMaturity(snapshot).atLeast(ProfileMaturity.PERFIL_CONFIABLE);
    }

    public boolean isEnoughDataForStrengths(LearningEvidenceSnapshot snapshot) {
        return calculateMaturity(snapshot).atLeast(ProfileMaturity.PERFIL_CONFIABLE);
    }

    public boolean isEnoughDataForWeaknesses(LearningEvidenceSnapshot snapshot) {
        return calculateMaturity(snapshot).atLeast(ProfileMaturity.PERFIL_CONFIABLE);
    }

    public int calculateProgressPercentage(LearningEvidenceSnapshot snapshot) {
        double ratio = PROGRESS_TARGET.minimums().entrySet().stream()
                .mapToDouble(entry -> {
                    double target = entry.getValue();
                    double current = snapshot.valueOf(entry.getKey());
                    return Math.min(current / target, 1.0d);
                })
                .average()
                .orElse(0.0d);

        int rawPercentage = (int) Math.round(ratio * 100.0d);
        int roundedToFive = Math.round(rawPercentage / 5.0f) * 5;
        return Math.max(0, Math.min(100, roundedToFive));
    }

    private record ThresholdBand(ProfileMaturity maturity, Map<LearningEvidenceMetric, Long> minimums) {
        boolean matches(LearningEvidenceSnapshot snapshot) {
            return minimums.entrySet().stream()
                    .allMatch(entry -> snapshot.valueOf(entry.getKey()) >= entry.getValue());
        }
    }
}

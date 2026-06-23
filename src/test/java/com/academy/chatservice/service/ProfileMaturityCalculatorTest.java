package com.academy.chatservice.service;

import com.academy.chatservice.model.LearningEvidenceSnapshot;
import com.academy.chatservice.model.ProfileMaturity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileMaturityCalculatorTest {

    private final ProfileMaturityCalculator calculator = new ProfileMaturityCalculator();

    @Test
    void shouldReturnInsufficientWhenAnyCoreSignalIsBelowInitialThreshold() {
        LearningEvidenceSnapshot snapshot = LearningEvidenceSnapshot.of(2, 14, 4);

        assertEquals(ProfileMaturity.PERFIL_INSUFICIENTE, calculator.calculateMaturity(snapshot));
        assertFalse(calculator.isEnoughDataForRecommendations(snapshot));
        assertFalse(calculator.isEnoughDataForStudyPlan(snapshot));
    }

    @Test
    void shouldReturnInitialWhenInitialThresholdsAreMet() {
        LearningEvidenceSnapshot snapshot = LearningEvidenceSnapshot.of(2, 14, 8);

        assertEquals(ProfileMaturity.PERFIL_INICIAL, calculator.calculateMaturity(snapshot));
        assertTrue(calculator.isEnoughDataForRecommendations(snapshot));
        assertFalse(calculator.isEnoughDataForStudyPlan(snapshot));
        assertEquals(75, calculator.calculateProgressPercentage(snapshot));
    }

    @Test
    void shouldReturnReliableWhenReliableThresholdsAreMet() {
        LearningEvidenceSnapshot snapshot = LearningEvidenceSnapshot.of(4, 10, 18);

        assertEquals(ProfileMaturity.PERFIL_CONFIABLE, calculator.calculateMaturity(snapshot));
        assertTrue(calculator.isEnoughDataForStrengths(snapshot));
        assertTrue(calculator.isEnoughDataForWeaknesses(snapshot));
    }

    @Test
    void shouldReturnAdvancedWhenAdvancedThresholdsAreMet() {
        LearningEvidenceSnapshot snapshot = LearningEvidenceSnapshot.of(6, 20, 32);

        assertEquals(ProfileMaturity.PERFIL_AVANZADO, calculator.calculateMaturity(snapshot));
        assertEquals(100, calculator.calculateProgressPercentage(snapshot));
        assertTrue(calculator.isEnoughDataForStudyPlan(snapshot));
    }
}

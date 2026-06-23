package com.academy.chatservice.service;

import com.academy.chatservice.model.LearningEvidenceSnapshot;
import com.academy.chatservice.model.LearningExerciseSignal;
import com.academy.chatservice.model.LearningProfileDto;
import com.academy.chatservice.model.LearningProfileSignals;
import com.academy.chatservice.repository.LearningProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LearningProfileServiceTest {

    @Test
    void shouldBuildConcreteRecommendationsAndWeeklyPlanFromSignals() {
        LearningProfileRepository repository = new StubLearningProfileRepository(
                LearningEvidenceSnapshot.of(8, 11, 36),
                new LearningProfileSignals(
                        List.of("p7_2025_S2.pdf", "p6_2025_S2.pdf"),
                        List.of(
                                new LearningExerciseSignal("p7_2025_S2.pdf", "ejercicio 10.", 2),
                                new LearningExerciseSignal("p6_2025_S2.pdf", "ejercicio 8.", 2),
                                new LearningExerciseSignal("p5_2025 - S2.pdf", "ejercicio 7.", 2)
                        ),
                        List.of()
                )
        );
        LearningProfileService service = new LearningProfileService(repository, new ProfileMaturityCalculator());

        LearningProfileDto profile = service.getProfile("learnsoft@edu.uy");

        assertThat(profile.recommendations()).isNotEmpty();
        assertThat(profile.recommendations().getFirst().title()).contains("p7 2025 S2");
        assertThat(profile.recommendations().getFirst().description()).contains("Ejercicio 10");
        assertThat(profile.weeklyStudyPlan()).isNotEmpty();
        assertThat(profile.weeklyStudyPlan().getFirst().title()).contains("p7 2025 S2");
        assertThat(profile.weeklyStudyPlan().get(1).title()).contains("p6 2025 S2");
    }

    private static final class StubLearningProfileRepository extends LearningProfileRepository {
        private final LearningEvidenceSnapshot evidence;
        private final LearningProfileSignals signals;

        private StubLearningProfileRepository(LearningEvidenceSnapshot evidence, LearningProfileSignals signals) {
            super(null);
            this.evidence = evidence;
            this.signals = signals;
        }

        @Override
        public LearningEvidenceSnapshot fetchEvidence(String userEmail) {
            return evidence;
        }

        @Override
        public LearningProfileSignals fetchSignals(String userEmail) {
            return signals;
        }
    }
}

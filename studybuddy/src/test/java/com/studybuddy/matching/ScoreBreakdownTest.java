package com.studybuddy.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

import com.studybuddy.entity.MatchingConfig;
import com.studybuddy.entity.enums.MatchingStrategyType;

/**
 * The weighted overall score and the shared-time label on a match card, with no Spring.
 * The breakdown is course 1, availability 0.5, study mode 0.8, goal 0, group size 1 unless a
 * test says otherwise.
 */
class ScoreBreakdownTest {

    private final ScoreBreakdown breakdown = new ScoreBreakdown(1, 0.5, 0.8, 0, 1, 60);

    // MAT-2: equal weights give the plain average, x 100
    @Test
    void equalWeightsGiveTheAverage() {
        assertThat(breakdown.weightedScore(config(20, 20, 20, 20, 20))).isCloseTo(66.0, within(0.001));
    }

    // A3, MAT-2: with only one weight above 0, the overall score is that criterion alone
    @Test
    void onlyOneWeightOnMeansOnlyThatCriterionCounts() {
        assertThat(breakdown.weightedScore(config(0, 1, 0, 0, 0))).isCloseTo(50.0, within(0.001));
        assertThat(breakdown.weightedScore(config(0, 0, 0, 7, 0))).isZero();
        assertThat(breakdown.weightedScore(config(5, 0, 0, 0, 0))).isCloseTo(100.0, within(0.001));
    }

    // A3, MAT-2: weights are relative (3/1 counts the same as 30/10)
    @Test
    void weightsAreRelative() {
        double small = breakdown.weightedScore(config(3, 1, 0, 0, 0));
        double large = breakdown.weightedScore(config(30, 10, 0, 0, 0));

        assertThat(small).isCloseTo(87.5, within(0.001));
        assertThat(large).isCloseTo(small, within(0.001));
    }

    // MAT-2, S4: the shared free time as it reads on a match card
    @Test
    void sharedTimeLabelReadsAsHoursAndMinutes() {
        assertThat(withMinutes(0).sharedTimeLabel()).isEqualTo("None");
        assertThat(withMinutes(45).sharedTimeLabel()).isEqualTo("45m");
        assertThat(withMinutes(60).sharedTimeLabel()).isEqualTo("1h");
        assertThat(withMinutes(90).sharedTimeLabel()).isEqualTo("1h 30m");
        assertThat(withMinutes(240).sharedTimeLabel()).isEqualTo("4h");
    }

    private static ScoreBreakdown withMinutes(long minutes) {
        return new ScoreBreakdown(1, 1, 1, 1, 1, minutes);
    }

    private static MatchingConfig config(int course, int availability, int mode, int goal, int size) {
        return new MatchingConfig(MatchingStrategyType.BALANCED, course, availability, mode, goal, size, 120);
    }
}

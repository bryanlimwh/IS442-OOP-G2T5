package com.studybuddy.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.DayOfWeek;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import com.studybuddy.entity.AvailabilitySlot;
import com.studybuddy.entity.Course;
import com.studybuddy.entity.StudyPreference;
import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;

/**
 * Boundaries of CompatibilityCalculator not covered by CompatibilityCalculatorTest (a teammate's
 * file): the "either" score's limits, availability exactly at the target session, and no slots.
 * No Spring. Both preferences are IS442, Exam preparation, One-to-one unless a test says otherwise.
 */
class CompatibilityCalculatorBoundaryTest {

    private static final int TARGET_MINUTES = 120;

    private final Course is442 = new Course("IS442", "Object Oriented Programming");

    // MAT-4: just below 0 is refused like 1.5 is
    @Test
    void eitherScoreBelowZeroIsRefused() {
        assertThatThrownBy(() -> new CompatibilityCalculator(-0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("studybuddy.matching.either-preference-score must be between 0 and 1, was -0.1");
    }

    // MAT-4, MAT-2: 0 and 1 are allowed, and are what an EITHER vs IN_PERSON pair scores
    @Test
    void eitherScoreOfZeroOrOneIsAllowedAndUsed() {
        StudyPreference inPerson = preference(StudyMode.IN_PERSON, Arrangement.ONE_TO_ONE);
        StudyPreference either = preference(StudyMode.EITHER, Arrangement.EITHER);

        ScoreBreakdown strict = new CompatibilityCalculator(0).score(inPerson, either, TARGET_MINUTES);
        ScoreBreakdown generous = new CompatibilityCalculator(1).score(inPerson, either, TARGET_MINUTES);

        assertThat(strict.studyMode()).isZero();
        assertThat(strict.groupSize()).isZero();
        assertThat(generous.studyMode()).isEqualTo(1.0);
        assertThat(generous.groupSize()).isEqualTo(1.0);
    }

    // MAT-2: shared time just below, at and just above the target session (120 minutes)
    @Test
    void availabilityAroundTheTargetSession() {
        assertThat(availability(LocalTime.of(11, 59))).isCloseTo(119.0 / 120, within(0.0001));
        assertThat(availability(LocalTime.of(12, 0))).isEqualTo(1.0);
        assertThat(availability(LocalTime.of(12, 1))).isEqualTo(1.0);
    }

    // MAT-2: a one-minute target gives full marks for any shared minute
    @Test
    void oneMinuteTargetGivesFullMarksForAnyOverlap() {
        StudyPreference mine = withSlot(LocalTime.of(10, 0), LocalTime.of(10, 1));
        StudyPreference theirs = withSlot(LocalTime.of(10, 0), LocalTime.of(10, 30));

        ScoreBreakdown breakdown = new CompatibilityCalculator(0.8).score(mine, theirs, 1);

        assertThat(breakdown.sharedMinutes()).isEqualTo(1);
        assertThat(breakdown.availability()).isEqualTo(1.0);
    }

    // MAT-2: no slots on one side, or on both, share no time; the other criteria still score
    @Test
    void noSlotsShareNoTime() {
        CompatibilityCalculator calculator = new CompatibilityCalculator(0.8);
        StudyPreference empty = preference(StudyMode.IN_PERSON, Arrangement.ONE_TO_ONE);
        StudyPreference busy = withSlot(LocalTime.of(9, 0), LocalTime.of(17, 0));

        for (ScoreBreakdown breakdown : new ScoreBreakdown[] { calculator.score(empty, busy, TARGET_MINUTES),
                calculator.score(busy, empty, TARGET_MINUTES),
                calculator.score(empty, preference(StudyMode.IN_PERSON, Arrangement.ONE_TO_ONE), TARGET_MINUTES) }) {
            assertThat(breakdown.sharedMinutes()).isZero();
            assertThat(breakdown.availability()).isZero();
            assertThat(breakdown.course()).isEqualTo(1.0);
            assertThat(breakdown.studyGoal()).isEqualTo(1.0);
        }
    }

    // MAT-2: EITHER on both sides is the same choice, so a full match whatever the either score is
    @Test
    void eitherOnBothSidesIsAFullMatchEvenWithZeroEitherScore() {
        StudyPreference either = preference(StudyMode.EITHER, Arrangement.EITHER);

        ScoreBreakdown breakdown = new CompatibilityCalculator(0).score(either,
                preference(StudyMode.EITHER, Arrangement.EITHER), TARGET_MINUTES);

        assertThat(breakdown.studyMode()).isEqualTo(1.0);
        assertThat(breakdown.groupSize()).isEqualTo(1.0);
    }

    // Mine is Mon 10:00 to the given end; theirs is Mon 10:00-14:00
    private double availability(LocalTime myEnd) {
        StudyPreference mine = withSlot(LocalTime.of(10, 0), myEnd);
        StudyPreference theirs = withSlot(LocalTime.of(10, 0), LocalTime.of(14, 0));
        return new CompatibilityCalculator(0.8).score(mine, theirs, TARGET_MINUTES).availability();
    }

    private StudyPreference withSlot(LocalTime start, LocalTime end) {
        StudyPreference preference = preference(StudyMode.IN_PERSON, Arrangement.ONE_TO_ONE);
        preference.addAvailabilitySlot(new AvailabilitySlot(DayOfWeek.MONDAY, start, end));
        return preference;
    }

    private StudyPreference preference(StudyMode mode, Arrangement arrangement) {
        return new StudyPreference(is442, mode, arrangement, StudyGoal.EXAM_PREPARATION);
    }
}

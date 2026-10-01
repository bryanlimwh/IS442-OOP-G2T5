package com.studybuddy.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.DayOfWeek;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import com.studybuddy.entity.AvailabilitySlot;
import com.studybuddy.entity.Course;
import com.studybuddy.entity.MatchingConfig;
import com.studybuddy.entity.StudyPreference;
import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.MatchingStrategyType;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;

class CompatibilityCalculatorTest {

    private static final double EITHER_SCORE = 0.8;
    private static final int TARGET_MINUTES = 120;

    private final CompatibilityCalculator calculator = new CompatibilityCalculator(EITHER_SCORE);
    private final Course is442 = new Course("IS442", "Object Oriented Programming");
    private final Course cs201 = new Course("CS201", "Data Structures and Algorithms");

    @Test
    void appendixAExampleScores92WithEqualWeights() {
        StudyPreference studentA = preference(is442, StudyMode.IN_PERSON, Arrangement.EITHER,
                StudyGoal.EXAM_PREPARATION);
        slot(studentA, DayOfWeek.WEDNESDAY, 19, 21);
        slot(studentA, DayOfWeek.FRIDAY, 14, 16);
        StudyPreference studentB = preference(is442, StudyMode.EITHER, Arrangement.ONE_TO_ONE,
                StudyGoal.EXAM_PREPARATION);
        slot(studentB, DayOfWeek.WEDNESDAY, 19, 21);
        slot(studentB, DayOfWeek.THURSDAY, 18, 20);

        ScoreBreakdown breakdown = calculator.score(studentA, studentB, TARGET_MINUTES);

        assertThat(breakdown.course()).isEqualTo(1.0);
        assertThat(breakdown.sharedMinutes()).isEqualTo(120);
        assertThat(breakdown.availability()).isEqualTo(1.0);
        assertThat(breakdown.studyMode()).isEqualTo(EITHER_SCORE);
        assertThat(breakdown.studyGoal()).isEqualTo(1.0);
        assertThat(breakdown.groupSize()).isEqualTo(EITHER_SCORE);
        assertThat(breakdown.weightedScore(equalWeights())).isCloseTo(92.0, within(0.001));
    }

    @Test
    void availabilityIsProportionalBelowTheTargetSession() {
        StudyPreference mine = defaultPreference(is442);
        slot(mine, DayOfWeek.MONDAY, 10, 12);
        StudyPreference theirs = defaultPreference(is442);
        slot(theirs, DayOfWeek.MONDAY, 11, 14);

        ScoreBreakdown breakdown = calculator.score(mine, theirs, TARGET_MINUTES);

        assertThat(breakdown.sharedMinutes()).isEqualTo(60);
        assertThat(breakdown.availability()).isEqualTo(0.5);
    }

    @Test
    void availabilityIsCappedAtFullMarks() {
        StudyPreference mine = defaultPreference(is442);
        slot(mine, DayOfWeek.MONDAY, 9, 17);
        StudyPreference theirs = defaultPreference(is442);
        slot(theirs, DayOfWeek.MONDAY, 9, 17);

        ScoreBreakdown breakdown = calculator.score(mine, theirs, TARGET_MINUTES);

        assertThat(breakdown.sharedMinutes()).isEqualTo(480);
        assertThat(breakdown.availability()).isEqualTo(1.0);
    }

    @Test
    void sharedTimeIsAddedUpAcrossSeveralSlots() {
        StudyPreference mine = defaultPreference(is442);
        slot(mine, DayOfWeek.MONDAY, 10, 11);
        slot(mine, DayOfWeek.TUESDAY, 10, 11);
        StudyPreference theirs = defaultPreference(is442);
        slot(theirs, DayOfWeek.MONDAY, 10, 12);
        slot(theirs, DayOfWeek.TUESDAY, 10, 12);

        assertThat(calculator.score(mine, theirs, TARGET_MINUTES).sharedMinutes()).isEqualTo(120);
    }

    @Test
    void slotsThatOnlyTouchOrFallOnDifferentDaysShareNoTime() {
        StudyPreference mine = defaultPreference(is442);
        slot(mine, DayOfWeek.MONDAY, 10, 12);
        StudyPreference theirs = defaultPreference(is442);
        slot(theirs, DayOfWeek.MONDAY, 12, 14);
        slot(theirs, DayOfWeek.TUESDAY, 10, 12);

        ScoreBreakdown breakdown = calculator.score(mine, theirs, TARGET_MINUTES);

        assertThat(breakdown.sharedMinutes()).isZero();
        assertThat(breakdown.availability()).isZero();
    }

    @Test
    void differentCoursesDoNotMatch() {
        ScoreBreakdown breakdown = calculator.score(defaultPreference(is442), defaultPreference(cs201),
                TARGET_MINUTES);

        assertThat(breakdown.course()).isZero();
    }

    @Test
    void conflictingStudyModesScoreZeroButBothEitherIsAFullMatch() {
        StudyPreference inPerson = preference(is442, StudyMode.IN_PERSON, Arrangement.EITHER, StudyGoal.CONCEPT_REVIEW);
        StudyPreference online = preference(is442, StudyMode.ONLINE, Arrangement.EITHER, StudyGoal.CONCEPT_REVIEW);
        StudyPreference either = preference(is442, StudyMode.EITHER, Arrangement.EITHER, StudyGoal.CONCEPT_REVIEW);

        assertThat(calculator.score(inPerson, online, TARGET_MINUTES).studyMode()).isZero();
        assertThat(calculator.score(either, either, TARGET_MINUTES).studyMode()).isEqualTo(1.0);
    }

    @Test
    void conflictingGroupSizesAndGoalsScoreZero() {
        StudyPreference mine = preference(is442, StudyMode.ONLINE, Arrangement.ONE_TO_ONE, StudyGoal.CONCEPT_REVIEW);
        StudyPreference theirs = preference(is442, StudyMode.ONLINE, Arrangement.SMALL_GROUP,
                StudyGoal.PROJECT_DISCUSSION);

        ScoreBreakdown breakdown = calculator.score(mine, theirs, TARGET_MINUTES);

        assertThat(breakdown.groupSize()).isZero();
        assertThat(breakdown.studyGoal()).isZero();
    }

    @Test
    void aZeroWeightCriterionIsIgnored() {
        // Only availability (0.5) and study goal (1.0) count, at weights 1 and 3: (0.5 + 3) / 4 = 87.5%.
        MatchingConfig config = new MatchingConfig(MatchingStrategyType.BALANCED, 0, 1, 0, 3, 0, TARGET_MINUTES);
        ScoreBreakdown breakdown = new ScoreBreakdown(0.0, 0.5, 0.0, 1.0, 0.0, 60);

        assertThat(breakdown.weightedScore(config)).isCloseTo(87.5, within(0.001));
    }

    @Test
    void rejectsAnEitherScoreOutsideZeroToOne() {
        assertThatThrownBy(() -> new CompatibilityCalculator(1.5)).isInstanceOf(IllegalArgumentException.class);
    }

    private MatchingConfig equalWeights() {
        return new MatchingConfig(MatchingStrategyType.BALANCED, 20, 20, 20, 20, 20, TARGET_MINUTES);
    }

    private StudyPreference defaultPreference(Course course) {
        return preference(course, StudyMode.ONLINE, Arrangement.EITHER, StudyGoal.CONCEPT_REVIEW);
    }

    private StudyPreference preference(Course course, StudyMode mode, Arrangement arrangement, StudyGoal goal) {
        return new StudyPreference(course, mode, arrangement, goal);
    }

    private void slot(StudyPreference preference, DayOfWeek day, int startHour, int endHour) {
        preference.addAvailabilitySlot(
                new AvailabilitySlot(day, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0)));
    }
}

package com.studybuddy.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;

class MatchingStrategyTest {

    // Same three results for every strategy; each strategy should put a different one first.
    private final MatchResult sameCourseShortOverlap = result("Amy", 70, 1.0, 60);
    private final MatchResult otherCourseHighScore = result("Ben", 85, 0.0, 30);
    private final MatchResult sameCourseLongOverlap = result("Cal", 60, 1.0, 180);
    private final List<MatchResult> results = List.of(sameCourseShortOverlap, otherCourseHighScore,
            sameCourseLongOverlap);

    @Test
    void balancedRanksByWeightedScore() {
        assertThat(rank(new BalancedMatchingStrategy()))
                .containsExactly(otherCourseHighScore, sameCourseShortOverlap, sameCourseLongOverlap);
    }

    @Test
    void availabilityFirstRanksBySharedMinutesThenScore() {
        assertThat(rank(new AvailabilityFirstMatchingStrategy()))
                .containsExactly(sameCourseLongOverlap, sameCourseShortOverlap, otherCourseHighScore);
    }

    @Test
    void courseFirstRanksSameCourseFirstThenScore() {
        assertThat(rank(new CourseFirstMatchingStrategy()))
                .containsExactly(sameCourseShortOverlap, sameCourseLongOverlap, otherCourseHighScore);
    }

    @Test
    void equalScoresAreOrderedByName() {
        MatchResult zoe = result("Zoe", 50, 1.0, 60);
        MatchResult adam = result("Adam", 50, 1.0, 60);

        assertThat(List.of(zoe, adam).stream().sorted(new BalancedMatchingStrategy().ranking()).toList())
                .containsExactly(adam, zoe);
    }

    private List<MatchResult> rank(MatchingStrategy strategy) {
        return results.stream().sorted(strategy.ranking()).toList();
    }

    private MatchResult result(String name, double score, double courseScore, long sharedMinutes) {
        StudentPublicProfile student = new StudentPublicProfile(null, name, "School", "Programme", 1, List.of());
        PreferenceSummary preference = new PreferenceSummary(1L, "IS442", "Object Oriented Programming",
                StudyMode.ONLINE, Arrangement.EITHER, StudyGoal.CONCEPT_REVIEW, List.of());
        ScoreBreakdown breakdown = new ScoreBreakdown(courseScore, 0.5, 1.0, 1.0, 1.0, sharedMinutes);
        return new MatchResult(student, preference, breakdown, score);
    }
}

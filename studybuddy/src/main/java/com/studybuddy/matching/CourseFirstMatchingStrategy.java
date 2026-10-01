package com.studybuddy.matching;

import java.util.Comparator;

import org.springframework.stereotype.Component;

import com.studybuddy.domain.enums.MatchingStrategyType;

/**
 * Ranks students who want a buddy for the same course first, then by weighted
 * score. Makes a difference when searching across courses (e.g. by study goal),
 * since a search for one course only ever returns that course.
 */
@Component
public class CourseFirstMatchingStrategy implements MatchingStrategy {

    @Override
    public MatchingStrategyType getType() {
        return MatchingStrategyType.COURSE_FIRST;
    }

    @Override
    public Comparator<MatchResult> ranking() {
        return Comparator.comparingDouble((MatchResult result) -> result.breakdown().course())
                .reversed()
                .thenComparing(MatchResult.BY_SCORE);
    }
}

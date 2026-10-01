package com.studybuddy.matching;

import java.util.Comparator;

import org.springframework.stereotype.Component;

import com.studybuddy.entity.enums.MatchingStrategyType;

/**
 * Ranks students with the most shared free time first, then by weighted score.
 * Uses the actual shared minutes rather than the availability score, because
 * that score is capped at one study session and the brief asks for the
 * strongest timetable overlap first.
 */
@Component
public class AvailabilityFirstMatchingStrategy implements MatchingStrategy {

    @Override
    public MatchingStrategyType getType() {
        return MatchingStrategyType.AVAILABILITY_FIRST;
    }

    @Override
    public Comparator<MatchResult> ranking() {
        return Comparator.comparingLong((MatchResult result) -> result.breakdown().sharedMinutes())
                .reversed()
                .thenComparing(MatchResult.BY_SCORE);
    }
}

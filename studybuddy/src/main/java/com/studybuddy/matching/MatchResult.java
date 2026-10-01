package com.studybuddy.matching;

import java.util.Comparator;

/**
 * One potential study buddy for the searching student: who they are, the
 * preference they matched on, how well each criterion matched, and the
 * overall weighted score out of 100.
 */
public record MatchResult(
        StudentPublicProfile student,
        PreferenceSummary preference,
        ScoreBreakdown breakdown,
        double score) {

    /**
     * Highest weighted score first; ties broken by name so the order is the
     * same on every search. Used by every strategy, alone or as a tie-breaker.
     */
    public static final Comparator<MatchResult> BY_SCORE = Comparator.comparingDouble(MatchResult::score)
            .reversed()
            .thenComparing(result -> result.student().name());
}

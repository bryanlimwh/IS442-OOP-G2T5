package com.studybuddy.matching;

import java.util.Comparator;

import com.studybuddy.domain.enums.MatchingStrategyType;

/**
 * A way of ranking match results (the Strategy pattern). Every strategy sees
 * the same scores; they differ only in what they put first. The admin picks
 * which one is used in MatchingConfig.
 */
public interface MatchingStrategy {

    /**
     * The setting that selects this strategy.
     */
    MatchingStrategyType getType();

    /**
     * Orders results best match first.
     */
    Comparator<MatchResult> ranking();
}

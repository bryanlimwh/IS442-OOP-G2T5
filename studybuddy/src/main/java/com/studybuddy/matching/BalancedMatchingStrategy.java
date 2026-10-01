package com.studybuddy.matching;

import java.util.Comparator;

import org.springframework.stereotype.Component;

import com.studybuddy.entity.enums.MatchingStrategyType;

/**
 * Ranks purely by the weighted score, so every criterion counts by its weight
 * and none is put ahead of the others.
 */
@Component
public class BalancedMatchingStrategy implements MatchingStrategy {

    @Override
    public MatchingStrategyType getType() {
        return MatchingStrategyType.BALANCED;
    }

    @Override
    public Comparator<MatchResult> ranking() {
        return MatchResult.BY_SCORE;
    }
}

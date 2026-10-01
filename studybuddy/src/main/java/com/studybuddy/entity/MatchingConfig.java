package com.studybuddy.entity;

import com.studybuddy.entity.enums.MatchingStrategyType;
import com.studybuddy.exception.InvalidMatchingConfigException;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * The admin's settings for the matching engine: which strategy ranks the
 * results, how much each criterion counts, and how much shared free time
 * counts as full availability.
 * <p>
 * There is a single row, created from application.properties by
 * MatchingConfigDataSeeder and changed by an admin afterwards.
 * <p>
 * Weights are relative, not percentages: a criterion's share of the score is
 * its weight divided by the total of all weights, so they don't need to add
 * up to 100. A weight of 0 switches that criterion off.
 */
@Entity
public class MatchingConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private MatchingStrategyType strategy;

    private int courseWeight;
    private int availabilityWeight;
    private int studyModeWeight;
    private int studyGoalWeight;
    private int groupSizeWeight;

    // Shared free time at or above this counts as full marks for availability:
    // roughly one study session. Beyond that, more overlap doesn't help a pair.
    private int targetSessionMinutes;

    /**
     * No-arg constructor required by JPA.
     */
    protected MatchingConfig() {
    }

    public MatchingConfig(MatchingStrategyType strategy, int courseWeight, int availabilityWeight,
            int studyModeWeight, int studyGoalWeight, int groupSizeWeight, int targetSessionMinutes) {
        update(strategy, courseWeight, availabilityWeight, studyModeWeight, studyGoalWeight, groupSizeWeight,
                targetSessionMinutes);
    }

    // ---- Behaviour ----

    /**
     * Replaces every setting at once, so the config is never left half-changed
     * if one value is invalid.
     *
     * @throws InvalidMatchingConfigException if a weight is negative, every
     *         weight is 0, or the session length isn't positive
     */
    public void update(MatchingStrategyType strategy, int courseWeight, int availabilityWeight,
            int studyModeWeight, int studyGoalWeight, int groupSizeWeight, int targetSessionMinutes) {
        if (strategy == null) {
            throw new InvalidMatchingConfigException("Choose a matching strategy.");
        }
        int[] weights = { courseWeight, availabilityWeight, studyModeWeight, studyGoalWeight, groupSizeWeight };
        int total = 0;
        for (int weight : weights) {
            if (weight < 0) {
                throw new InvalidMatchingConfigException("Weights can't be negative.");
            }
            total += weight;
        }
        if (total == 0) {
            throw new InvalidMatchingConfigException("At least one criterion needs a weight above 0.");
        }
        if (targetSessionMinutes <= 0) {
            throw new InvalidMatchingConfigException("The study session length must be at least 1 minute.");
        }

        this.strategy = strategy;
        this.courseWeight = courseWeight;
        this.availabilityWeight = availabilityWeight;
        this.studyModeWeight = studyModeWeight;
        this.studyGoalWeight = studyGoalWeight;
        this.groupSizeWeight = groupSizeWeight;
        this.targetSessionMinutes = targetSessionMinutes;
    }

    // Computed rather than stored, so it can never disagree with the weights.
    public int getTotalWeight() {
        return courseWeight + availabilityWeight + studyModeWeight + studyGoalWeight + groupSizeWeight;
    }

    // A weight's share of the score as a whole percentage, e.g. 20 of a total 100 -> 20.
    public long sharePercent(int weight) {
        return Math.round(100.0 * weight / getTotalWeight());
    }

    // ---- Getters (changes go through update(), so there are no setters) ----

    public Long getId() {
        return id;
    }

    public MatchingStrategyType getStrategy() {
        return strategy;
    }

    public int getCourseWeight() {
        return courseWeight;
    }

    public int getAvailabilityWeight() {
        return availabilityWeight;
    }

    public int getStudyModeWeight() {
        return studyModeWeight;
    }

    public int getStudyGoalWeight() {
        return studyGoalWeight;
    }

    public int getGroupSizeWeight() {
        return groupSizeWeight;
    }

    public int getTargetSessionMinutes() {
        return targetSessionMinutes;
    }
}

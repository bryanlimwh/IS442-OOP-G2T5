package com.studybuddy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.studybuddy.entity.MatchingConfig;
import com.studybuddy.entity.enums.MatchingStrategyType;

/**
 * The starting matching settings, read from the studybuddy.matching.defaults.*
 * entries in application.properties. Used once, to create the MatchingConfig
 * row; after that an admin changes the settings in the app.
 */
@ConfigurationProperties(prefix = "studybuddy.matching.defaults")
public record MatchingDefaults(
        MatchingStrategyType strategy,
        int courseWeight,
        int availabilityWeight,
        int studyModeWeight,
        int studyGoalWeight,
        int groupSizeWeight,
        int targetSessionMinutes) {

    public MatchingConfig toConfig() {
        return new MatchingConfig(strategy, courseWeight, availabilityWeight, studyModeWeight, studyGoalWeight,
                groupSizeWeight, targetSessionMinutes);
    }
}

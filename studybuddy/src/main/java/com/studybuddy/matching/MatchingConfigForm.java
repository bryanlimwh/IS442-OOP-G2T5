package com.studybuddy.matching;

import com.studybuddy.entity.MatchingConfig;
import com.studybuddy.entity.enums.MatchingStrategyType;

/**
 * The values on the admin's matching settings form. Kept separate from the
 * MatchingConfig entity so a form post can never create or overwrite the
 * entity directly: changes go through MatchingConfig.update(), which checks them.
 */
public record MatchingConfigForm(
        MatchingStrategyType strategy,
        int courseWeight,
        int availabilityWeight,
        int studyModeWeight,
        int studyGoalWeight,
        int groupSizeWeight,
        int targetSessionMinutes) {

    // Pre-fills the form with the settings currently in use.
    public static MatchingConfigForm from(MatchingConfig config) {
        return new MatchingConfigForm(config.getStrategy(), config.getCourseWeight(),
                config.getAvailabilityWeight(), config.getStudyModeWeight(), config.getStudyGoalWeight(),
                config.getGroupSizeWeight(), config.getTargetSessionMinutes());
    }
}

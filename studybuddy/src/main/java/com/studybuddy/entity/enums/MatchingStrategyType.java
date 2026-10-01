package com.studybuddy.entity.enums;

/**
 * The ways the matching engine can rank compatible students, chosen by an
 * admin. Each has one MatchingStrategy implementation.
 */
public enum MatchingStrategyType {
    BALANCED("Balanced",
            "Ranks by the weighted score across all criteria."),
    AVAILABILITY_FIRST("Availability first",
            "Ranks students with the most shared free time first, then by the weighted score."),
    COURSE_FIRST("Course first",
            "Ranks students looking for help in the same course first, then by the weighted score.");

    private final String label;
    private final String description;

    MatchingStrategyType(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }
}

package com.studybuddy.matching;

import com.studybuddy.domain.MatchingConfig;

/**
 * How well two study preferences match on each criterion, each scored from 0
 * (no match) to 1 (full match), plus the shared free time the availability
 * score was worked out from.
 */
public record ScoreBreakdown(
        double course,
        double availability,
        double studyMode,
        double studyGoal,
        double groupSize,
        long sharedMinutes) {

    /**
     * The overall match score out of 100: each criterion's score counts in
     * proportion to its weight in the admin's settings.
     */
    public double weightedScore(MatchingConfig config) {
        double weightedSum = course * config.getCourseWeight()
                + availability * config.getAvailabilityWeight()
                + studyMode * config.getStudyModeWeight()
                + studyGoal * config.getStudyGoalWeight()
                + groupSize * config.getGroupSizeWeight();
        return 100 * weightedSum / config.getTotalWeight();
    }

    // For display, e.g. 90 -> "1h 30m", 120 -> "2h", 0 -> "None"
    public String sharedTimeLabel() {
        if (sharedMinutes == 0) {
            return "None";
        }
        long hours = sharedMinutes / 60;
        long minutes = sharedMinutes % 60;
        if (hours == 0) {
            return minutes + "m";
        }
        return minutes == 0 ? hours + "h" : hours + "h " + minutes + "m";
    }
}

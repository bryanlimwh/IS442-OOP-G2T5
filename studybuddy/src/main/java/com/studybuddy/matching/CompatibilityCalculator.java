package com.studybuddy.matching;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.studybuddy.entity.AvailabilitySlot;
import com.studybuddy.entity.StudyPreference;
import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.StudyMode;

/**
 * Scores how well two study preferences match on each criterion. It only
 * scores; weighting and ranking are left to ScoreBreakdown and the strategies.
 */
@Component
public class CompatibilityCalculator {

    private static final double FULL_MATCH = 1.0;
    private static final double NO_MATCH = 0.0;

    // Score when one student chose a specific option and the other chose EITHER: compatible,
    // but below an exact match, so two students who both want in-person rank above in-person + either.
    private final double eitherPreferenceScore;

    public CompatibilityCalculator(
            @Value("${studybuddy.matching.either-preference-score}") double eitherPreferenceScore) {
        if (eitherPreferenceScore < NO_MATCH || eitherPreferenceScore > FULL_MATCH) {
            throw new IllegalArgumentException(
                    "studybuddy.matching.either-preference-score must be between 0 and 1, was " + eitherPreferenceScore);
        }
        this.eitherPreferenceScore = eitherPreferenceScore;
    }

    /**
     * @param targetSessionMinutes shared free time that earns full marks for availability
     */
    public ScoreBreakdown score(StudyPreference mine, StudyPreference theirs, int targetSessionMinutes) {
        long sharedMinutes = sharedMinutes(mine, theirs);
        return new ScoreBreakdown(
                mine.getCourse().equals(theirs.getCourse()) ? FULL_MATCH : NO_MATCH,
                Math.min(FULL_MATCH, (double) sharedMinutes / targetSessionMinutes),
                flexibleMatch(mine.getStudyMode() == theirs.getStudyMode(),
                        mine.getStudyMode() == StudyMode.EITHER || theirs.getStudyMode() == StudyMode.EITHER),
                mine.getStudyGoal() == theirs.getStudyGoal() ? FULL_MATCH : NO_MATCH,
                flexibleMatch(mine.getArrangement() == theirs.getArrangement(),
                        mine.getArrangement() == Arrangement.EITHER || theirs.getArrangement() == Arrangement.EITHER),
                sharedMinutes);
    }

    // Total minutes per week both students are free, across every pair of their slots.
    private long sharedMinutes(StudyPreference mine, StudyPreference theirs) {
        long total = 0;
        for (AvailabilitySlot mySlot : mine.getAvailabilitySlots()) {
            for (AvailabilitySlot theirSlot : theirs.getAvailabilitySlots()) {
                total += mySlot.overlapMinutes(theirSlot);
            }
        }
        return total;
    }

    // For criteria with an EITHER option (study mode, group size): the same choice is a full match;
    // otherwise it's a partial match if either student is flexible, and no match if they conflict.
    private double flexibleMatch(boolean sameChoice, boolean eitherStudentFlexible) {
        if (sameChoice) {
            return FULL_MATCH;
        }
        return eitherStudentFlexible ? eitherPreferenceScore : NO_MATCH;
    }
}

package com.studybuddy.matching;

import com.studybuddy.domain.StudyPreference;
import com.studybuddy.domain.enums.Arrangement;
import com.studybuddy.domain.enums.StudyGoal;
import com.studybuddy.domain.enums.StudyMode;

/**
 * What a student is searching for, from the matches page. Every field is
 * optional: a blank one doesn't narrow the search.
 *
 * @param courseId        only match on this course
 * @param studyGoal       only students with this study goal
 * @param studyMode       only students who can study this way (IN_PERSON or ONLINE)
 * @param groupSize       only students happy with this group size (ONE_TO_ONE or SMALL_GROUP)
 * @param sharedTimeOnly  only students who share at least some free time with the searcher
 */
public record MatchSearch(
        Long courseId,
        StudyGoal studyGoal,
        StudyMode studyMode,
        Arrangement groupSize,
        Boolean sharedTimeOnly) {

    // An unticked checkbox sends nothing, so a missing value means "not ticked".
    // (A primitive boolean would make Spring reject the search instead.)
    public MatchSearch {
        if (sharedTimeOnly == null) {
            sharedTimeOnly = false;
        }
    }

    /**
     * True if the searcher's own preference is in scope: with a course chosen,
     * only their preference for that course is matched on.
     */
    public boolean includesMine(StudyPreference mine) {
        return courseId == null || courseId.equals(mine.getCourse().getCourseId());
    }

    /**
     * True if another student's preference, and how it scored against the
     * searcher's, passes every filter that was set.
     */
    public boolean accepts(StudyPreference theirs, ScoreBreakdown breakdown) {
        return (courseId == null || courseId.equals(theirs.getCourse().getCourseId()))
                && (studyGoal == null || studyGoal == theirs.getStudyGoal())
                // A student who chose EITHER can study either way, so they pass both mode filters.
                && (studyMode == null || studyMode == StudyMode.EITHER
                        || theirs.getStudyMode() == studyMode || theirs.getStudyMode() == StudyMode.EITHER)
                && (groupSize == null || groupSize == Arrangement.EITHER
                        || theirs.getArrangement() == groupSize || theirs.getArrangement() == Arrangement.EITHER)
                && (!sharedTimeOnly || breakdown.sharedMinutes() > 0);
    }
}

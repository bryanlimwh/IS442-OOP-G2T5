package com.studybuddy.matching;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.studybuddy.entity.Course;
import com.studybuddy.entity.StudyPreference;
import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;

/**
 * The matches page's filters, with no Spring. "Theirs" is another student's preference
 * (IS442, In person, One-to-one, Exam preparation) unless a test says otherwise; the score
 * breakdown only matters for the shared-time filter.
 */
class MatchSearchTest {

    private final Course is442 = course(1L, "IS442");
    private final Course cs201 = course(2L, "CS201");
    private final StudyPreference theirs = preference(is442, StudyMode.IN_PERSON, Arrangement.ONE_TO_ONE,
            StudyGoal.EXAM_PREPARATION);
    private final ScoreBreakdown sharesAnHour = breakdown(60);

    // S5: an empty search keeps everyone, and an unticked shared-time box means false
    @Test
    void emptySearchAcceptsEveryone() {
        MatchSearch empty = new MatchSearch(null, null, null, null, null);

        assertThat(empty.sharedTimeOnly()).isFalse();
        assertThat(empty.accepts(theirs, breakdown(0))).isTrue();
        assertThat(empty.includesMine(preference(cs201, StudyMode.ONLINE, Arrangement.EITHER, StudyGoal.CONCEPT_REVIEW)))
                .isTrue();
    }

    // S3, S5
    @Test
    void courseFilterKeepsThatCourseOnly() {
        assertThat(search(1L, null, null, null, false).accepts(theirs, sharesAnHour)).isTrue();
        assertThat(search(2L, null, null, null, false).accepts(theirs, sharesAnHour)).isFalse();
    }

    // S3, S5: with a course chosen, only the searcher's own preference for it is matched on
    @Test
    void courseFilterPicksTheSearchersPreferenceForIt() {
        MatchSearch is442Only = search(1L, null, null, null, false);

        assertThat(is442Only.includesMine(theirs)).isTrue();
        assertThat(is442Only.includesMine(preference(cs201, StudyMode.ONLINE, Arrangement.EITHER,
                StudyGoal.CONCEPT_REVIEW))).isFalse();
    }

    // S3, S5
    @Test
    void goalFilterKeepsThatGoalOnly() {
        assertThat(search(null, StudyGoal.EXAM_PREPARATION, null, null, false).accepts(theirs, sharesAnHour)).isTrue();
        assertThat(search(null, StudyGoal.CONCEPT_REVIEW, null, null, false).accepts(theirs, sharesAnHour)).isFalse();
    }

    // S5: a student who chose EITHER passes both study mode filters
    @Test
    void studyModeFilterKeepsThatModeAndEither() {
        StudyPreference flexible = preference(is442, StudyMode.EITHER, Arrangement.ONE_TO_ONE,
                StudyGoal.EXAM_PREPARATION);

        assertThat(search(null, null, StudyMode.IN_PERSON, null, false).accepts(theirs, sharesAnHour)).isTrue();
        assertThat(search(null, null, StudyMode.ONLINE, null, false).accepts(theirs, sharesAnHour)).isFalse();
        assertThat(search(null, null, StudyMode.ONLINE, null, false).accepts(flexible, sharesAnHour)).isTrue();
        assertThat(search(null, null, StudyMode.IN_PERSON, null, false).accepts(flexible, sharesAnHour)).isTrue();
    }

    // S5: a student who chose EITHER passes both group size filters
    @Test
    void groupSizeFilterKeepsThatSizeAndEither() {
        StudyPreference flexible = preference(is442, StudyMode.IN_PERSON, Arrangement.EITHER,
                StudyGoal.EXAM_PREPARATION);

        assertThat(search(null, null, null, Arrangement.ONE_TO_ONE, false).accepts(theirs, sharesAnHour)).isTrue();
        assertThat(search(null, null, null, Arrangement.SMALL_GROUP, false).accepts(theirs, sharesAnHour)).isFalse();
        assertThat(search(null, null, null, Arrangement.SMALL_GROUP, false).accepts(flexible, sharesAnHour)).isTrue();
    }

    // S5, N-D3: pins current behaviour, update if fixed. An EITHER filter (not offered on the page,
    // only by editing the address) keeps everyone
    @Test
    void eitherFilterKeepsEveryone() {
        StudyPreference online = preference(is442, StudyMode.ONLINE, Arrangement.SMALL_GROUP,
                StudyGoal.EXAM_PREPARATION);

        assertThat(search(null, null, StudyMode.EITHER, null, false).accepts(theirs, sharesAnHour)).isTrue();
        assertThat(search(null, null, StudyMode.EITHER, null, false).accepts(online, sharesAnHour)).isTrue();
        assertThat(search(null, null, null, Arrangement.EITHER, false).accepts(online, sharesAnHour)).isTrue();
    }

    // S5: "Only students free when I am" means at least one shared minute
    @Test
    void sharedTimeFilterNeedsAtLeastOneMinute() {
        MatchSearch sharedOnly = search(null, null, null, null, true);

        assertThat(sharedOnly.accepts(theirs, breakdown(0))).isFalse();
        assertThat(sharedOnly.accepts(theirs, breakdown(1))).isTrue();
        // Unticked, no shared time is fine
        assertThat(search(null, null, null, null, false).accepts(theirs, breakdown(0))).isTrue();
    }

    // S3, S5: every filter has to pass
    @Test
    void combinedFiltersMustAllPass() {
        MatchSearch all = search(1L, StudyGoal.EXAM_PREPARATION, StudyMode.IN_PERSON, Arrangement.ONE_TO_ONE, true);

        assertThat(all.accepts(theirs, sharesAnHour)).isTrue();
        assertThat(all.accepts(theirs, breakdown(0))).isFalse();
        assertThat(all.accepts(preference(is442, StudyMode.IN_PERSON, Arrangement.ONE_TO_ONE, StudyGoal.CONCEPT_REVIEW),
                sharesAnHour)).isFalse();
        assertThat(all.accepts(preference(cs201, StudyMode.IN_PERSON, Arrangement.ONE_TO_ONE,
                StudyGoal.EXAM_PREPARATION), sharesAnHour)).isFalse();
    }

    private static MatchSearch search(Long courseId, StudyGoal goal, StudyMode mode, Arrangement size,
            boolean sharedTimeOnly) {
        return new MatchSearch(courseId, goal, mode, size, sharedTimeOnly);
    }

    private static ScoreBreakdown breakdown(long sharedMinutes) {
        return new ScoreBreakdown(1, 1, 1, 1, 1, sharedMinutes);
    }

    private static StudyPreference preference(Course course, StudyMode mode, Arrangement arrangement, StudyGoal goal) {
        return new StudyPreference(course, mode, arrangement, goal);
    }

    private static Course course(long id, String code) {
        Course course = new Course(code, code + " course");
        ReflectionTestUtils.setField(course, "courseId", id);
        return course;
    }
}

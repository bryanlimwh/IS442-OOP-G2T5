package com.studybuddy.service.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.studybuddy.domain.enums.MatchingStrategyType;
import com.studybuddy.domain.enums.StudyGoal;
import com.studybuddy.matching.MatchResult;
import com.studybuddy.matching.MatchSearch;
import com.studybuddy.matching.MatchingConfigForm;
import com.studybuddy.matching.PreferenceSummary;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.StudentRepository;

/**
 * Runs the matching engine against the seeded data (see the seeders in config/).
 * Each test is rolled back, so changing settings or accounts here doesn't leak into other tests.
 */
@SpringBootTest
@Transactional
class MatchingServiceTest {

    private static final String ALEX = "student@studybuddy.com";
    private static final String BEN = "ben.koh@studybuddy.com";

    @Autowired
    private MatchingService matchingService;
    @Autowired
    private MatchingConfigService matchingConfigService;
    @Autowired
    private CourseRepository courseRepository;
    @Autowired
    private StudentRepository studentRepository;

    @Test
    void appendixAPairComesTopForIs442WithDefaultSettings() {
        List<MatchResult> results = matchingService.findMatches(ALEX, courseSearch("IS442"));

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).student().name()).isEqualTo("Ben Koh");
        assertThat(results.get(0).score()).isCloseTo(92.0, within(0.001));
        assertThat(results).allSatisfy(result -> assertThat(result.preference().courseCode()).isEqualTo("IS442"));
    }

    @Test
    void neverListsTheSearcherOrTheSameStudentTwice() {
        List<MatchResult> results = matchingService.findMatches(ALEX, emptySearch());
        List<String> names = results.stream().map(result -> result.student().name()).toList();

        assertThat(names).doesNotContain("Alex Tan").doesNotHaveDuplicates();
    }

    @Test
    void preferencesForACourseTheSearcherAlsoWantsAreComparedOnThatCourse() {
        Set<String> myCourses = matchingService.getPreferences(ALEX).stream()
                .map(PreferenceSummary::courseCode)
                .collect(Collectors.toSet());

        List<MatchResult> results = matchingService.findMatches(ALEX, emptySearch());

        assertThat(results)
                .filteredOn(result -> myCourses.contains(result.preference().courseCode()))
                .isNotEmpty()
                .allSatisfy(result -> assertThat(result.breakdown().course()).isEqualTo(1.0));
    }

    @Test
    void filtersNarrowTheResults() {
        MatchSearch search = new MatchSearch(null, StudyGoal.EXAM_PREPARATION, null, null, true);

        List<MatchResult> results = matchingService.findMatches(ALEX, search);

        assertThat(results).isNotEmpty().allSatisfy(result -> {
            assertThat(result.preference().studyGoal()).isEqualTo(StudyGoal.EXAM_PREPARATION);
            assertThat(result.breakdown().sharedMinutes()).isPositive();
        });
    }

    @Test
    void theAdminsStrategyDecidesTheOrder() {
        useStrategy(MatchingStrategyType.AVAILABILITY_FIRST);

        List<MatchResult> results = matchingService.findMatches(ALEX, courseSearch("IS442"));

        assertThat(results).isSortedAccordingTo(
                Comparator.comparingLong((MatchResult result) -> result.breakdown().sharedMinutes()).reversed());
    }

    @Test
    void deactivatedStudentsAreNotMatched() {
        studentRepository.findByEmail(BEN).orElseThrow().setActive(false);

        List<MatchResult> results = matchingService.findMatches(ALEX, courseSearch("IS442"));

        assertThat(results).noneSatisfy(result -> assertThat(result.student().name()).isEqualTo("Ben Koh"));
    }

    private MatchSearch emptySearch() {
        return new MatchSearch(null, null, null, null, null);
    }

    private MatchSearch courseSearch(String courseCode) {
        Long courseId = courseRepository.findAll().stream()
                .filter(course -> course.getCourseCode().equals(courseCode))
                .findFirst().orElseThrow()
                .getCourseId();
        return new MatchSearch(courseId, null, null, null, null);
    }

    private void useStrategy(MatchingStrategyType strategy) {
        MatchingConfigForm current = MatchingConfigForm.from(matchingConfigService.getCurrent());
        matchingConfigService.update(new MatchingConfigForm(strategy, current.courseWeight(),
                current.availabilityWeight(), current.studyModeWeight(), current.studyGoalWeight(),
                current.groupSizeWeight(), current.targetSessionMinutes()));
    }
}

package com.studybuddy.service.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.studybuddy.entity.Admin;
import com.studybuddy.entity.AvailabilitySlot;
import com.studybuddy.entity.Course;
import com.studybuddy.entity.MatchingConfig;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyPreference;
import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.MatchingStrategyType;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;
import com.studybuddy.exception.StudentNotFoundException;
import com.studybuddy.matching.AvailabilityFirstMatchingStrategy;
import com.studybuddy.matching.BalancedMatchingStrategy;
import com.studybuddy.matching.CompatibilityCalculator;
import com.studybuddy.matching.CourseFirstMatchingStrategy;
import com.studybuddy.matching.MatchResult;
import com.studybuddy.matching.MatchSearch;
import com.studybuddy.matching.MatchingConfigForm;
import com.studybuddy.matching.PreferenceSummary;
import com.studybuddy.repository.AdminRepository;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.MatchingConfigRepository;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.service.student.StudyPreferenceService;

import jakarta.persistence.EntityManager;

/**
 * MatchingService against an empty in-memory H2 with its own data (no seeders), unlike the
 * teammate's MatchingServiceTest, which uses the seeded data. Each test is rolled back. The
 * "either" score is 0.8 (application.properties). Settings: Balanced, every weight 20, 120 minutes.
 * <p>
 * Alex (the searcher) has one IS442 preference: In person, Either group size, Exam preparation,
 * Mon 10:00-14:00. Three other students, built so each strategy orders them differently:
 * <ul>
 *   <li>Amy: IS442, same choices, Mon 10:00-11:00 (60 min): scores 90</li>
 *   <li>Ben: CS201, same choices, Mon 10:00-14:00 (240 min): course 0, scores 80</li>
 *   <li>Cal: IS442, Online, Problem solving, Mon 10:00-13:00 (180 min): scores 60</li>
 * </ul>
 */
@DataJpaTest
@Import({ MatchingService.class, MatchingConfigService.class, CompatibilityCalculator.class,
        BalancedMatchingStrategy.class, AvailabilityFirstMatchingStrategy.class, CourseFirstMatchingStrategy.class,
        StudyPreferenceService.class })
class MatchingServiceFixtureTest {

    private static final String ALEX = "alex@test.com";
    private static final MatchSearch EVERYTHING = new MatchSearch(null, null, null, null, false);

    @Autowired
    private MatchingService service;
    @Autowired
    private MatchingConfigService matchingConfigService;
    @Autowired
    private StudyPreferenceService studyPreferenceService;
    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private CourseRepository courseRepository;
    @Autowired
    private MatchingConfigRepository matchingConfigRepository;
    @Autowired
    private AdminRepository adminRepository;
    @Autowired
    private EntityManager entityManager;

    private Course is442;
    private Course cs201;

    @BeforeEach
    void setUp() {
        is442 = courseRepository.save(new Course("IS442", "Object Oriented Programming"));
        cs201 = courseRepository.save(new Course("CS201", "Data Structures and Algorithms"));
        matchingConfigRepository.save(new MatchingConfig(MatchingStrategyType.BALANCED, 20, 20, 20, 20, 20, 120));

        student(ALEX, "Alex Tan",
                preference(is442, StudyMode.IN_PERSON, StudyGoal.EXAM_PREPARATION, 10, 14));
        student("amy@test.com", "Amy Lee",
                preference(is442, StudyMode.IN_PERSON, StudyGoal.EXAM_PREPARATION, 10, 11));
        student("ben@test.com", "Ben Koh", preference(cs201, StudyMode.IN_PERSON, StudyGoal.EXAM_PREPARATION, 10, 14));
        student("cal@test.com", "Cal Ng", preference(is442, StudyMode.ONLINE, StudyGoal.PROBLEM_SOLVING, 10, 13));
        flushAndClear();
    }

    // S3, S4, MAT-2, MAT-3: Balanced orders by the weighted score
    @Test
    void balancedRanksByScore() {
        List<MatchResult> results = service.findMatches(ALEX, EVERYTHING);

        assertThat(names(results)).containsExactly("Amy Lee", "Ben Koh", "Cal Ng");
        assertThat(results).extracting(MatchResult::score).containsExactly(90.0, 80.0, 60.0);
        assertThat(results.get(1).breakdown().course()).isZero();
        assertThat(results.get(1).breakdown().sharedMinutes()).isEqualTo(240);
    }

    // A4, D2, MAT-3: the admin's strategy decides the order on the very next search; the scores stay the same
    @Test
    void eachStrategyOrdersTheSameResultsDifferently() {
        useSettings(MatchingStrategyType.AVAILABILITY_FIRST, 20, 20, 20, 20, 20);
        List<MatchResult> availabilityFirst = service.findMatches(ALEX, EVERYTHING);
        useSettings(MatchingStrategyType.COURSE_FIRST, 20, 20, 20, 20, 20);
        List<MatchResult> courseFirst = service.findMatches(ALEX, EVERYTHING);

        // Shared minutes: Ben 240, Cal 180, Amy 60
        assertThat(names(availabilityFirst)).containsExactly("Ben Koh", "Cal Ng", "Amy Lee");
        // Same course first (Amy, Cal), then Ben
        assertThat(names(courseFirst)).containsExactly("Amy Lee", "Cal Ng", "Ben Koh");
        assertThat(availabilityFirst).extracting(MatchResult::score).containsExactlyInAnyOrder(90.0, 80.0, 60.0);
    }

    // A3, MAT-2: changed weights change the scores on the next search
    @Test
    void weightsChangeTheScoresOnTheNextSearch() {
        useSettings(MatchingStrategyType.BALANCED, 0, 1, 0, 0, 0);
        List<MatchResult> availabilityOnly = service.findMatches(ALEX, EVERYTHING);
        useSettings(MatchingStrategyType.BALANCED, 1, 0, 0, 0, 0);
        List<MatchResult> courseOnly = service.findMatches(ALEX, EVERYTHING);

        assertThat(scoreOf(availabilityOnly, "Ben Koh")).isCloseTo(100.0, within(0.001));
        assertThat(scoreOf(availabilityOnly, "Cal Ng")).isCloseTo(100.0, within(0.001));
        assertThat(scoreOf(availabilityOnly, "Amy Lee")).isCloseTo(50.0, within(0.001));
        assertThat(scoreOf(courseOnly, "Ben Koh")).isZero();
        assertThat(scoreOf(courseOnly, "Amy Lee")).isCloseTo(100.0, within(0.001));
    }

    // S3, S5: a course search leaves out students without a preference for that course
    @Test
    void courseSearchLeavesOutOtherCourses() {
        List<MatchResult> results = service.findMatches(ALEX, new MatchSearch(is442.getCourseId(), null, null,
                null, false));

        assertThat(names(results)).containsExactly("Amy Lee", "Cal Ng");
        assertThat(results).allSatisfy(result -> assertThat(result.preference().courseCode()).isEqualTo("IS442"));
    }

    // S3, N-D4: pins current behaviour, update if fixed. Searching a course the searcher has no
    // preference for finds nobody, even though Ben wants CS201
    @Test
    void courseWithoutOwnPreferenceFindsNobody() {
        assertThat(service.findMatches(ALEX, new MatchSearch(cs201.getCourseId(), null, null, null, false))).isEmpty();
    }

    // S5: the filters reach the matching (goal, shared time)
    @Test
    void filtersNarrowTheResults() {
        student("dan@test.com", "Dan Ho", preference(is442, StudyMode.IN_PERSON, StudyGoal.EXAM_PREPARATION, 18, 20));
        flushAndClear();

        assertThat(names(service.findMatches(ALEX, new MatchSearch(null, StudyGoal.EXAM_PREPARATION, null, null,
                false)))).containsExactly("Amy Lee", "Ben Koh", "Dan Ho");
        // Dan is free 18:00-20:00, when Alex isn't
        assertThat(names(service.findMatches(ALEX, new MatchSearch(null, null, null, null, true))))
                .containsExactly("Amy Lee", "Ben Koh", "Cal Ng");
        assertThat(names(service.findMatches(ALEX, new MatchSearch(null, null, StudyMode.ONLINE, null, false))))
                .containsExactly("Cal Ng");
    }

    // MAT-3: someone matched through several preferences is listed once, through the best one
    @Test
    void studentWithSeveralPreferencesIsListedOnceThroughTheBest() {
        // IS442: Online, Concept review, no shared time: course 1 + group size 1 = 40. CS201: as Ben, 80.
        student("dana@test.com", "Dana Lim",
                preference(is442, StudyMode.ONLINE, StudyGoal.CONCEPT_REVIEW, 18, 20),
                preference(cs201, StudyMode.IN_PERSON, StudyGoal.EXAM_PREPARATION, 10, 14));
        flushAndClear();

        List<MatchResult> dana = service.findMatches(ALEX, EVERYTHING).stream()
                .filter(result -> result.student().name().equals("Dana Lim"))
                .toList();

        assertThat(dana).hasSize(1);
        assertThat(dana.get(0).preference().courseCode()).isEqualTo("CS201");
        assertThat(dana.get(0).score()).isCloseTo(80.0, within(0.001));
    }

    // MAT-3, N-D1: pins current behaviour, update if fixed. "Best" is decided by the active strategy:
    // under Availability first the card shows the preference with the most shared time, not the top score
    @Test
    void bestResultFollowsTheActiveStrategy() {
        // IS442 like Amy (90, 60 min); CS201 Online, Problem solving, 240 min (40)
        student("erin@test.com", "Erin Tay",
                preference(is442, StudyMode.IN_PERSON, StudyGoal.EXAM_PREPARATION, 10, 11),
                preference(cs201, StudyMode.ONLINE, StudyGoal.PROBLEM_SOLVING, 10, 14));
        flushAndClear();

        assertThat(erinsResult().preference().courseCode()).isEqualTo("IS442");
        useSettings(MatchingStrategyType.AVAILABILITY_FIRST, 20, 20, 20, 20, 20);
        MatchResult underAvailabilityFirst = erinsResult();
        assertThat(underAvailabilityFirst.preference().courseCode()).isEqualTo("CS201");
        assertThat(underAvailabilityFirst.score()).isCloseTo(40.0, within(0.001));
    }

    // MAT-3: never the searcher, never a deactivated student
    @Test
    void searcherAndInactiveStudentsAreLeftOut() {
        studentRepository.findByEmail("amy@test.com").orElseThrow().setActive(false);
        flushAndClear();

        assertThat(names(service.findMatches(ALEX, EVERYTHING))).containsExactly("Ben Koh", "Cal Ng")
                .doesNotContain("Alex Tan");
    }

    // S3: a student with no study preferences has nothing to be matched on
    @Test
    void studentWithNoPreferencesGetsNoMatches() {
        student("zoe@test.com", "Zoe Chia");
        flushAndClear();

        assertThat(service.findMatches("zoe@test.com", EVERYTHING)).isEmpty();
        assertThat(service.getPreferences("zoe@test.com")).isEmpty();
    }

    // S3: an unknown email, or an admin's, isn't a student
    @Test
    void unknownEmailOrAdminIsNotFound() {
        adminRepository.save(new Admin("admin@test.com", "dummyHash"));

        assertThatThrownBy(() -> service.findMatches("ghost@test.com", EVERYTHING))
                .isInstanceOf(StudentNotFoundException.class);
        assertThatThrownBy(() -> service.getPreferences("ghost@test.com")).isInstanceOf(StudentNotFoundException.class);
        assertThatThrownBy(() -> service.findMatches("admin@test.com", EVERYTHING))
                .isInstanceOf(StudentNotFoundException.class);
    }

    // S2, S3: the searcher's own preferences, by course code
    @Test
    void ownPreferencesAreListedByCourseCode() {
        Student current = studentRepository.findByEmail(ALEX).orElseThrow();
        current.addCourse(courseRepository.findById(cs201.getCourseId()).orElseThrow());
        current.addPreference(preference(courseRepository.findById(cs201.getCourseId()).orElseThrow(),
                StudyMode.ONLINE, StudyGoal.CONCEPT_REVIEW, 9, 10));
        flushAndClear();

        assertThat(service.getPreferences(ALEX)).extracting(PreferenceSummary::courseCode)
                .containsExactly("CS201", "IS442");
    }

    // S2, S3, K19: a new preference is matched on the next search. Replaces the teammate's
    // StudyPreferenceServiceTest.newPreferenceShowsUpInMatching with own data instead of seeded students
    @Test
    void newPreferenceIsMatchedOnTheNextSearch() {
        Student fay = studentRepository.save(new Student("fay@test.com", "dummyHash", "Fay Wong", "SCIS", "IS", 2,
                "91234567"));
        fay.addCourse(courseRepository.findById(is442.getCourseId()).orElseThrow());
        flushAndClear();
        assertThat(names(service.findMatches(ALEX, EVERYTHING))).doesNotContain("Fay Wong");

        StudyPreference created = studyPreferenceService.createPreference("fay@test.com", is442.getCourseId(),
                StudyMode.IN_PERSON, Arrangement.EITHER, StudyGoal.EXAM_PREPARATION);
        studyPreferenceService.addAvailabilitySlot(created.getId(), "fay@test.com", DayOfWeek.MONDAY,
                LocalTime.of(10, 0), LocalTime.of(14, 0));
        flushAndClear();

        // Same as Alex in every way: 100, first
        List<MatchResult> results = service.findMatches(ALEX, EVERYTHING);
        assertThat(results.get(0).student().name()).isEqualTo("Fay Wong");
        assertThat(results.get(0).score()).isCloseTo(100.0, within(0.001));
    }

    private MatchResult erinsResult() {
        return service.findMatches(ALEX, EVERYTHING).stream()
                .filter(result -> result.student().name().equals("Erin Tay"))
                .findFirst().orElseThrow();
    }

    private void useSettings(MatchingStrategyType strategy, int course, int availability, int mode, int goal,
            int size) {
        matchingConfigService.update(new MatchingConfigForm(strategy, course, availability, mode, goal, size, 120));
    }

    private static List<String> names(List<MatchResult> results) {
        return results.stream().map(result -> result.student().name()).toList();
    }

    private static double scoreOf(List<MatchResult> results, String name) {
        return results.stream().filter(result -> result.student().name().equals(name)).findFirst().orElseThrow()
                .score();
    }

    // Group size is always EITHER, the same as Alex's, so it always scores 1
    private static StudyPreference preference(Course course, StudyMode mode, StudyGoal goal, int startHour,
            int endHour) {
        StudyPreference preference = new StudyPreference(course, mode, Arrangement.EITHER, goal);
        preference.addAvailabilitySlot(new AvailabilitySlot(DayOfWeek.MONDAY, LocalTime.of(startHour, 0),
                LocalTime.of(endHour, 0)));
        return preference;
    }

    private Student student(String email, String name, StudyPreference... preferences) {
        Student student = new Student(email, "dummyHash", name, "School of Computing and Information Systems",
                "Information Systems", 2, "91234567");
        for (StudyPreference preference : preferences) {
            if (!student.getCoursesTaken().contains(preference.getCourse())) {
                student.addCourse(preference.getCourse());
            }
            student.addPreference(preference);
        }
        return studentRepository.save(student);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}

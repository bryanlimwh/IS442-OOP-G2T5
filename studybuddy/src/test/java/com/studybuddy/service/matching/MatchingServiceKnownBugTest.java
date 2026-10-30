package com.studybuddy.service.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.studybuddy.entity.AvailabilitySlot;
import com.studybuddy.entity.Course;
import com.studybuddy.entity.MatchRequest;
import com.studybuddy.entity.MatchingConfig;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyBuddyConnection;
import com.studybuddy.entity.StudyPreference;
import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.MatchingStrategyType;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;
import com.studybuddy.matching.AvailabilityFirstMatchingStrategy;
import com.studybuddy.matching.BalancedMatchingStrategy;
import com.studybuddy.matching.CompatibilityCalculator;
import com.studybuddy.matching.CourseFirstMatchingStrategy;
import com.studybuddy.matching.MatchResult;
import com.studybuddy.matching.MatchSearch;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.MatchRequestRepository;
import com.studybuddy.repository.MatchingConfigRepository;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.repository.StudyBuddyConnectionRepository;

import jakarta.persistence.EntityManager;

/**
 * Regression tests for K17, resolved by team decision TD-5 (2026-10-30): the matches page keeps listing
 * buddies and students with a pending request either way, and only replaces their "Send request" button
 * with a label (MatchingControllerTest). So findMatches must still return them. These were @Disabled
 * known-bug tests until then; the class keeps its name so the workbook Test IDs stay the same.
 * Empty in-memory H2 with own data; Alex searches, and Ben, Chloe and Dan all have an IS442
 * preference like Alex's.
 */
@DataJpaTest
@Import({ MatchingService.class, MatchingConfigService.class, CompatibilityCalculator.class,
        BalancedMatchingStrategy.class, AvailabilityFirstMatchingStrategy.class, CourseFirstMatchingStrategy.class })
class MatchingServiceKnownBugTest {

    private static final MatchSearch EVERYTHING = new MatchSearch(null, null, null, null, false);

    @Autowired
    private MatchingService service;
    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private CourseRepository courseRepository;
    @Autowired
    private MatchingConfigRepository matchingConfigRepository;
    @Autowired
    private StudyBuddyConnectionRepository connectionRepository;
    @Autowired
    private MatchRequestRepository matchRequestRepository;
    @Autowired
    private EntityManager entityManager;

    private Student alex;
    private Student ben;
    private Student chloe;

    @BeforeEach
    void setUp() {
        Course is442 = courseRepository.save(new Course("IS442", "Object Oriented Programming"));
        matchingConfigRepository.save(new MatchingConfig(MatchingStrategyType.BALANCED, 20, 20, 20, 20, 20, 120));
        alex = student("alex@test.com", "Alex Tan", is442);
        ben = student("ben@test.com", "Ben Koh", is442);
        chloe = student("chloe@test.com", "Chloe Lim", is442);
        student("dan@test.com", "Dan Ho", is442);
    }

    // S3, MAT-3, K17, TD-5: intended (team decision 2026-10-30). A buddy is still listed; the page shows a label
    // instead of the button
    @Test
    void connectedBuddyIsStillListed() {
        connectionRepository.save(new StudyBuddyConnection(alex, ben));
        flushAndClear();

        assertThat(names(service.findMatches("alex@test.com", EVERYTHING)))
                .containsExactlyInAnyOrder("Ben Koh", "Chloe Lim", "Dan Ho");
    }

    // S3, MAT-3, K17, TD-5: intended (team decision 2026-10-30). Students with a pending request either way are
    // still listed; the page shows a label instead of the button
    @Test
    void studentWithPendingRequestEitherWayIsStillListed() {
        matchRequestRepository.save(new MatchRequest(alex, ben, "Study IS442 together?"));
        matchRequestRepository.save(new MatchRequest(chloe, alex, null));
        flushAndClear();

        assertThat(names(service.findMatches("alex@test.com", EVERYTHING)))
                .containsExactlyInAnyOrder("Ben Koh", "Chloe Lim", "Dan Ho");
    }

    private static List<String> names(List<MatchResult> results) {
        return results.stream().map(result -> result.student().name()).toList();
    }

    // Takes the course, with an IS442 preference: In person, Either, Exam preparation, Mon 10:00-12:00
    private Student student(String email, String name, Course course) {
        Student student = new Student(email, "dummyHash", name, "School of Computing and Information Systems",
                "Information Systems", 2, "91234567");
        student.addCourse(course);
        StudyPreference preference = new StudyPreference(course, StudyMode.IN_PERSON, Arrangement.EITHER,
                StudyGoal.EXAM_PREPARATION);
        preference.addAvailabilitySlot(new AvailabilitySlot(DayOfWeek.MONDAY, LocalTime.of(10, 0),
                LocalTime.of(12, 0)));
        student.addPreference(preference);
        return studentRepository.save(student);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}

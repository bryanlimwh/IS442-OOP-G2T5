package com.studybuddy.service.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyPreference;
import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;
import com.studybuddy.exception.CourseNotFoundException;
import com.studybuddy.exception.StudyPreferenceNotFoundException;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.repository.StudyPreferenceRepository;

import jakarta.persistence.EntityManager;

/**
 * Gaps in StudyPreferenceServiceTest (a teammate's file, which uses the seeded data): missing
 * choices on update, another student's update and slot removal, and slot boundaries. Empty
 * in-memory H2 with own data; each test is rolled back. Alex and Ben take IS442 and CS201; Alex
 * has preference IS442 (Online, One-to-one, Concept review) with one slot Mon 10:00-12:00.
 */
@DataJpaTest
@Import(StudyPreferenceService.class)
class StudyPreferenceServiceGapTest {

    private static final String ALEX = "alex@test.com";
    private static final String BEN = "ben@test.com";
    private static final String CHOICES_REQUIRED = "Choose a study mode, group size and study goal";
    private static final String BAD_SLOT = "A slot needs a day and must end after it starts";

    @Autowired
    private StudyPreferenceService service;
    @Autowired
    private StudyPreferenceRepository preferenceRepository;
    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private CourseRepository courseRepository;
    @Autowired
    private EntityManager entityManager;

    private Course cs201;
    private Long preferenceId;
    private Long slotId;

    @BeforeEach
    void setUp() {
        Course is442 = courseRepository.save(new Course("IS442", "Object Oriented Programming"));
        cs201 = courseRepository.save(new Course("CS201", "Data Structures and Algorithms"));
        saveStudent(ALEX, "Alex Tan", is442, cs201);
        saveStudent(BEN, "Ben Koh", is442, cs201);
        preferenceId = service.createPreference(ALEX, is442.getCourseId(), StudyMode.ONLINE, Arrangement.ONE_TO_ONE,
                StudyGoal.CONCEPT_REVIEW).getId();
        slotId = service.addAvailabilitySlot(preferenceId, ALEX, DayOfWeek.MONDAY, time(10, 0), time(12, 0)).getId();
        flushAndClear();
    }

    // S2, PRE-1: each missing choice is refused on create, and nothing is saved
    @Test
    void createRefusesEachMissingChoice() {
        Long course = cs201.getCourseId();
        assertThatThrownBy(() -> service.createPreference(ALEX, course, null, Arrangement.EITHER, StudyGoal.CONCEPT_REVIEW))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(CHOICES_REQUIRED);
        assertThatThrownBy(() -> service.createPreference(ALEX, course, StudyMode.ONLINE, null, StudyGoal.CONCEPT_REVIEW))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(CHOICES_REQUIRED);
        assertThatThrownBy(() -> service.createPreference(ALEX, course, StudyMode.ONLINE, Arrangement.EITHER, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(CHOICES_REQUIRED);
        flushAndClear();

        assertThat(service.getPreferences(ALEX)).extracting(p -> p.getCourse().getCourseCode()).containsExactly("IS442");
    }

    // S2, PRE-1: each missing choice is refused on update too, and the saved choices stay
    @Test
    void updateRefusesEachMissingChoiceAndKeepsTheOldOnes() {
        assertThatThrownBy(() -> service.updatePreference(preferenceId, ALEX, null, Arrangement.EITHER,
                StudyGoal.EXAM_PREPARATION)).isInstanceOf(IllegalArgumentException.class).hasMessage(CHOICES_REQUIRED);
        assertThatThrownBy(() -> service.updatePreference(preferenceId, ALEX, StudyMode.IN_PERSON, null,
                StudyGoal.EXAM_PREPARATION)).isInstanceOf(IllegalArgumentException.class).hasMessage(CHOICES_REQUIRED);
        assertThatThrownBy(() -> service.updatePreference(preferenceId, ALEX, StudyMode.IN_PERSON, Arrangement.EITHER,
                null)).isInstanceOf(IllegalArgumentException.class).hasMessage(CHOICES_REQUIRED);
        flushAndClear();

        assertOriginalChoices();
    }

    // S2, PRE-1: a course that doesn't exist
    @Test
    void createForUnknownCourseIsNotFound() {
        assertThatThrownBy(() -> service.createPreference(ALEX, 999_999L, StudyMode.ONLINE, Arrangement.EITHER,
                StudyGoal.CONCEPT_REVIEW)).isInstanceOf(CourseNotFoundException.class);
    }

    // PRE-2: another student can't update the preference or remove its slot, and nothing changes
    @Test
    void anotherStudentCannotUpdateOrRemoveSlots() {
        assertThatThrownBy(() -> service.updatePreference(preferenceId, BEN, StudyMode.IN_PERSON, Arrangement.EITHER,
                StudyGoal.EXAM_PREPARATION)).isInstanceOf(StudyPreferenceNotFoundException.class);
        assertThatThrownBy(() -> service.removeAvailabilitySlot(preferenceId, BEN, slotId))
                .isInstanceOf(StudyPreferenceNotFoundException.class);
        flushAndClear();

        assertOriginalChoices();
        assertThat(slots()).hasSize(1);
        // The owner still can
        service.removeAvailabilitySlot(preferenceId, ALEX, slotId);
        flushAndClear();
        assertThat(slots()).isEmpty();
    }

    // PRE-3: slots that only touch, or are on another day, don't overlap
    @Test
    void touchingSlotsAndSameTimesOnAnotherDayAreAllowed() {
        service.addAvailabilitySlot(preferenceId, ALEX, DayOfWeek.MONDAY, time(12, 0), time(14, 0));
        service.addAvailabilitySlot(preferenceId, ALEX, DayOfWeek.MONDAY, time(8, 0), time(10, 0));
        service.addAvailabilitySlot(preferenceId, ALEX, DayOfWeek.TUESDAY, time(10, 0), time(12, 0));
        flushAndClear();

        assertThat(slots()).hasSize(4);
    }

    // PRE-3: even one minute of overlap is refused, and the slot isn't added
    @Test
    void oneMinuteOverlapIsRefused() {
        assertThatThrownBy(() -> service.addAvailabilitySlot(preferenceId, ALEX, DayOfWeek.MONDAY, time(11, 59),
                time(13, 0))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This slot overlaps one you already have on MONDAY");
        assertThatThrownBy(() -> service.addAvailabilitySlot(preferenceId, ALEX, DayOfWeek.MONDAY, time(9, 0),
                time(10, 1))).isInstanceOf(IllegalArgumentException.class);
        flushAndClear();

        assertThat(slots()).hasSize(1);
    }

    // PRE-3: a slot needs a day, a start and an end, and must end after it starts
    @Test
    void incompleteOrEmptySlotIsRefused() {
        assertThatThrownBy(() -> service.addAvailabilitySlot(preferenceId, ALEX, null, time(14, 0), time(15, 0)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(BAD_SLOT);
        assertThatThrownBy(() -> service.addAvailabilitySlot(preferenceId, ALEX, DayOfWeek.FRIDAY, null, time(15, 0)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(BAD_SLOT);
        assertThatThrownBy(() -> service.addAvailabilitySlot(preferenceId, ALEX, DayOfWeek.FRIDAY, time(14, 0), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(BAD_SLOT);
        assertThatThrownBy(() -> service.addAvailabilitySlot(preferenceId, ALEX, DayOfWeek.FRIDAY, time(14, 0),
                time(14, 0))).isInstanceOf(IllegalArgumentException.class).hasMessage(BAD_SLOT);
        flushAndClear();

        assertThat(slots()).hasSize(1);
    }

    // PRE-3: removing a slot the preference doesn't have
    @Test
    void removingAnUnknownSlotIsRefused() {
        assertThatThrownBy(() -> service.removeAvailabilitySlot(preferenceId, ALEX, 999_999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This preference has no slot 999999");
        flushAndClear();

        assertThat(slots()).hasSize(1);
    }

    // PRE-2: My preferences lists the student's own preferences by course code, not in the order they were made
    @Test
    void preferencesAreListedByCourseCode() {
        service.createPreference(ALEX, cs201.getCourseId(), StudyMode.IN_PERSON, Arrangement.EITHER,
                StudyGoal.EXAM_PREPARATION);
        flushAndClear();

        assertThat(service.getPreferences(ALEX)).extracting(p -> p.getCourse().getCourseCode())
                .containsExactly("CS201", "IS442");
    }

    private void assertOriginalChoices() {
        StudyPreference saved = preferenceRepository.findById(preferenceId).orElseThrow();
        assertThat(saved.getStudyMode()).isEqualTo(StudyMode.ONLINE);
        assertThat(saved.getArrangement()).isEqualTo(Arrangement.ONE_TO_ONE);
        assertThat(saved.getStudyGoal()).isEqualTo(StudyGoal.CONCEPT_REVIEW);
    }

    private List<AvailabilitySlot> slots() {
        return preferenceRepository.findById(preferenceId).orElseThrow().getAvailabilitySlots();
    }

    private void saveStudent(String email, String name, Course... courses) {
        Student student = new Student(email, "dummyHash", name, "SCIS", "Information Systems", 2, "91234567");
        for (Course course : courses) {
            student.addCourse(course);
        }
        studentRepository.save(student);
    }

    private static LocalTime time(int hour, int minute) {
        return LocalTime.of(hour, minute);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}

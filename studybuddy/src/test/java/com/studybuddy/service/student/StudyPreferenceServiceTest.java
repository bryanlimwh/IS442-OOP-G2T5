package com.studybuddy.service.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.studybuddy.entity.AvailabilitySlot;
import com.studybuddy.entity.StudyPreference;
import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;
import com.studybuddy.exception.StudyPreferenceNotFoundException;
import com.studybuddy.matching.MatchResult;
import com.studybuddy.matching.MatchSearch;
import com.studybuddy.repository.AvailabilitySlotRepository;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.StudyPreferenceRepository;
import com.studybuddy.service.matching.MatchingService;

import jakarta.persistence.EntityManager;

/**
 * Uses the seeded students (Alex and Ben). Each test is rolled back afterwards.
 * flushAndClear() writes changes to the database and forgets cached objects,
 * so the checks after it read what was really saved.
 */
@SpringBootTest
@Transactional
class StudyPreferenceServiceTest {

    private static final String ALEX = "student@studybuddy.com";
    // Takes IS442, IS216, CS201. Has one preference: IS442, slots Wed 19-21 and Thu 18-20.
    private static final String BEN = "ben.koh@studybuddy.com";

    @Autowired
    private StudyPreferenceService service;
    @Autowired
    private MatchingService matchingService;
    @Autowired
    private StudyPreferenceRepository preferenceRepository;
    @Autowired
    private AvailabilitySlotRepository slotRepository;
    @Autowired
    private CourseRepository courseRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void createPreferenceWithASlot() {
        StudyPreference created = service.createPreference(BEN, courseId("CS201"),
                StudyMode.ONLINE, Arrangement.SMALL_GROUP, StudyGoal.PROBLEM_SOLVING);
        AvailabilitySlot slot = service.addAvailabilitySlot(created.getId(), BEN,
                DayOfWeek.MONDAY, time(10), time(12));
        flushAndClear();

        StudyPreference saved = preferenceRepository.findById(created.getId()).orElseThrow();
        assertThat(saved.getStudyMode()).isEqualTo(StudyMode.ONLINE);
        assertThat(saved.getAvailabilitySlots()).extracting(AvailabilitySlot::getId).containsExactly(slot.getId());
    }

    @Test
    void createRulesAreEnforced() {
        // Ben doesn't take IS111
        assertThatThrownBy(() -> service.createPreference(BEN, courseId("IS111"),
                StudyMode.ONLINE, Arrangement.EITHER, StudyGoal.CONCEPT_REVIEW))
                .isInstanceOf(IllegalStateException.class);
        // Ben already has an IS442 preference
        assertThatThrownBy(() -> service.createPreference(BEN, courseId("IS442"),
                StudyMode.ONLINE, Arrangement.EITHER, StudyGoal.CONCEPT_REVIEW))
                .isInstanceOf(IllegalStateException.class);
        // Missing study goal
        assertThatThrownBy(() -> service.createPreference(BEN, courseId("CS201"),
                StudyMode.ONLINE, Arrangement.EITHER, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updatePreference() {
        Long id = bensIs442().getId();

        service.updatePreference(id, BEN, StudyMode.ONLINE, Arrangement.SMALL_GROUP, StudyGoal.CONCEPT_REVIEW);
        flushAndClear();

        StudyPreference saved = preferenceRepository.findById(id).orElseThrow();
        assertThat(saved.getStudyGoal()).isEqualTo(StudyGoal.CONCEPT_REVIEW);
        assertThat(saved.getAvailabilitySlots()).hasSize(2);
    }

    @Test
    void deletingAPreferenceAlsoDeletesItsSlots() {
        StudyPreference is442 = bensIs442();
        List<Long> slotIds = is442.getAvailabilitySlots().stream().map(AvailabilitySlot::getId).toList();

        service.deletePreference(is442.getId(), BEN);
        flushAndClear();

        assertThat(preferenceRepository.findById(is442.getId())).isEmpty();
        assertThat(slotRepository.findAllById(slotIds)).isEmpty();
    }

    @Test
    void anotherStudentCannotTouchAPreference() {
        Long id = bensIs442().getId();

        assertThatThrownBy(() -> service.getPreference(id, ALEX))
                .isInstanceOf(StudyPreferenceNotFoundException.class);
        assertThatThrownBy(() -> service.deletePreference(id, ALEX))
                .isInstanceOf(StudyPreferenceNotFoundException.class);
        assertThatThrownBy(() -> service.addAvailabilitySlot(id, ALEX, DayOfWeek.MONDAY, time(9), time(10)))
                .isInstanceOf(StudyPreferenceNotFoundException.class);
    }

    @Test
    void badSlotsAreRejected() {
        Long id = bensIs442().getId();

        // Ends before it starts
        assertThatThrownBy(() -> service.addAvailabilitySlot(id, BEN, DayOfWeek.MONDAY, time(12), time(10)))
                .isInstanceOf(IllegalArgumentException.class);
        // Overlaps Wed 19-21
        assertThatThrownBy(() -> service.addAvailabilitySlot(id, BEN, DayOfWeek.WEDNESDAY, time(20), time(22)))
                .isInstanceOf(IllegalArgumentException.class);
        // Only touching Wed 19-21 is fine
        service.addAvailabilitySlot(id, BEN, DayOfWeek.WEDNESDAY, time(21), time(22));
        assertThat(bensIs442().getAvailabilitySlots()).hasSize(3);
    }

    @Test
    void removeSlot() {
        StudyPreference is442 = bensIs442();
        Long slotId = is442.getAvailabilitySlots().get(0).getId();

        service.removeAvailabilitySlot(is442.getId(), BEN, slotId);
        flushAndClear();

        assertThat(slotRepository.findById(slotId)).isEmpty();
        assertThat(bensIs442().getAvailabilitySlots()).hasSize(1);
    }

    // A new preference should change who the matching engine finds.
    @Test
    void newPreferenceShowsUpInMatching() {
        StudyPreference created = service.createPreference(BEN, courseId("CS201"),
                StudyMode.IN_PERSON, Arrangement.EITHER, StudyGoal.EXAM_PREPARATION);
        // Same as Alex's CS201 slot
        service.addAvailabilitySlot(created.getId(), BEN, DayOfWeek.FRIDAY, time(13), time(15));
        flushAndClear();

        List<MatchResult> results = matchingService.findMatches(ALEX,
                new MatchSearch(courseId("CS201"), null, null, null, false));

        assertThat(results.get(0).student().name()).isEqualTo("Ben Koh");
    }

    private StudyPreference bensIs442() {
        return service.getPreferences(BEN).stream()
                .filter(p -> p.getCourse().getCourseCode().equals("IS442"))
                .findFirst().orElseThrow();
    }

    private Long courseId(String code) {
        return courseRepository.findAll().stream()
                .filter(course -> course.getCourseCode().equals(code))
                .findFirst().orElseThrow().getCourseId();
    }

    private LocalTime time(int hour) {
        return LocalTime.of(hour, 0);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}

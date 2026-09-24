package com.studybuddy.config;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import com.studybuddy.domain.AvailabilitySlot;
import com.studybuddy.domain.Course;
import com.studybuddy.domain.StudyPreference;
import com.studybuddy.domain.enums.Arrangement;
import com.studybuddy.domain.enums.StudyGoal;
import com.studybuddy.domain.enums.StudyMode;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.StudyPreferenceRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// Runs after CourseDataSeeder. Also seeds each preference's AvailabilitySlots:
// StudyPreference cascades ALL to its slots, so saving the preference saves
// the slots too and no separate AvailabilitySlot seeder is needed.
// These preferences are not linked to any student yet (StudyPreference has no
// Student field until Student is created).
@Component
@Order(2)
public class StudyPreferenceDataSeeder implements CommandLineRunner {

    private final StudyPreferenceRepository studyPreferenceRepository;
    private final CourseRepository courseRepository;

    public StudyPreferenceDataSeeder(StudyPreferenceRepository studyPreferenceRepository,
            CourseRepository courseRepository) {
        this.studyPreferenceRepository = studyPreferenceRepository;
        this.courseRepository = courseRepository;
    }

    @Override
    public void run(String... args) {
        List<Course> courses = courseRepository.findAll();
        if (studyPreferenceRepository.count() == 0 && courses.size() >= 5) {
            StudyPreference p1 = new StudyPreference(courses.get(0),
                    StudyMode.IN_PERSON, Arrangement.ONE_TO_ONE, StudyGoal.CONCEPT_REVIEW);
            addSlot(p1, DayOfWeek.MONDAY, 10, 12);
            addSlot(p1, DayOfWeek.WEDNESDAY, 14, 16);

            StudyPreference p2 = new StudyPreference(courses.get(1),
                    StudyMode.ONLINE, Arrangement.SMALL_GROUP, StudyGoal.PROBLEM_SOLVING);
            addSlot(p2, DayOfWeek.TUESDAY, 19, 21);

            StudyPreference p3 = new StudyPreference(courses.get(3),
                    StudyMode.EITHER, Arrangement.SMALL_GROUP, StudyGoal.PROJECT_DISCUSSION);
            addSlot(p3, DayOfWeek.THURSDAY, 15, 18);
            addSlot(p3, DayOfWeek.SATURDAY, 10, 13);

            StudyPreference p4 = new StudyPreference(courses.get(4),
                    StudyMode.IN_PERSON, Arrangement.EITHER, StudyGoal.EXAM_PREPARATION);
            addSlot(p4, DayOfWeek.FRIDAY, 13, 15);

            List<StudyPreference> preferences = List.of(p1, p2, p3, p4);
            studyPreferenceRepository.saveAll(preferences);
            System.out.println("Seeded " + preferences.size() + " study preferences with availability slots");
        }
    }

    // Sets both sides of the relationship so the FK on the slot is populated.
    private void addSlot(StudyPreference preference, DayOfWeek day, int startHour, int endHour) {
        AvailabilitySlot slot = new AvailabilitySlot(day, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0));
        slot.setStudyPreference(preference);
        preference.getAvailabilitySlots().add(slot);
    }
}

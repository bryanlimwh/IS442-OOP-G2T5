package com.studybuddy.config;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import com.studybuddy.entity.AvailabilitySlot;
import com.studybuddy.entity.Course;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyPreference;
import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.repository.StudyPreferenceRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Runs after CourseDataSeeder and StudentDataSeeder, both of which this
// seeder depends on. Also seeds each preference's AvailabilitySlots:
// StudyPreference cascades ALL to its slots, so saving the preference saves
// the slots too and no separate AvailabilitySlot seeder is needed.
// All seeded preferences are attached to the one seeded student.
@Component
@Order(3)
public class StudyPreferenceDataSeeder implements CommandLineRunner {

    private final StudyPreferenceRepository studyPreferenceRepository;
    private final CourseRepository courseRepository;
    private final StudentRepository studentRepository;

    public StudyPreferenceDataSeeder(StudyPreferenceRepository studyPreferenceRepository,
            CourseRepository courseRepository, StudentRepository studentRepository) {
        this.studyPreferenceRepository = studyPreferenceRepository;
        this.courseRepository = courseRepository;
        this.studentRepository = studentRepository;
    }

    // Transactional so the fetched Student's lazy `preferences` collection
    // stays attached to an open Hibernate session across the fetch, the
    // addPreference() mutations below, and the final save.
    @Override
    @Transactional
    public void run(String... args) {
        List<Course> courses = courseRepository.findAll();
        Student student = studentRepository.findAll().stream().findFirst().orElse(null);
        if (studyPreferenceRepository.count() == 0 && courses.size() >= 5 && student != null) {
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
            preferences.forEach(student::addPreference);
            studentRepository.save(student);
            System.out.println("Seeded " + preferences.size() + " study preferences with availability slots for "
                    + student.getDisplayName());
        }
    }

    private void addSlot(StudyPreference preference, DayOfWeek day, int startHour, int endHour) {
        preference.addAvailabilitySlot(
                new AvailabilitySlot(day, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0)));
    }
}

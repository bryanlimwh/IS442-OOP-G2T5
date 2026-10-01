package com.studybuddy.config;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.studybuddy.domain.AvailabilitySlot;
import com.studybuddy.domain.Course;
import com.studybuddy.domain.Student;
import com.studybuddy.domain.StudyPreference;
import com.studybuddy.domain.enums.Arrangement;
import com.studybuddy.domain.enums.StudyGoal;
import com.studybuddy.domain.enums.StudyMode;
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
//
// Alex Tan (the demo login) and Ben Koh get hand-written preferences: their
// IS442 preferences are Students A and B from the brief's Appendix A, so the
// demo can show that example being scored. Every other student gets one or two
// random preferences, only for courses they are taking. The random generator
// uses a fixed seed, so every run seeds exactly the same data.
@Component
@Order(3)
public class StudyPreferenceDataSeeder implements CommandLineRunner {

    private static final long RANDOM_SEED = 442L;
    private static final List<DayOfWeek> STUDY_DAYS = List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);
    private static final int EARLIEST_START_HOUR = 9;
    private static final int LATEST_START_HOUR = 19;
    private static final int MAX_SLOT_HOURS = 3;
    private static final int MAX_SLOTS_PER_PREFERENCE = 3;
    private static final int MAX_PREFERENCES_PER_STUDENT = 2;

    private final StudyPreferenceRepository studyPreferenceRepository;
    private final CourseRepository courseRepository;
    private final StudentRepository studentRepository;

    public StudyPreferenceDataSeeder(StudyPreferenceRepository studyPreferenceRepository,
            CourseRepository courseRepository, StudentRepository studentRepository) {
        this.studyPreferenceRepository = studyPreferenceRepository;
        this.courseRepository = courseRepository;
        this.studentRepository = studentRepository;
    }

    // Transactional so each Student's lazy collections (coursesTaken and
    // preferences) stay attached to an open Hibernate session. The students
    // are managed entities, so the new preferences (and, through the cascade,
    // their slots) are saved when the transaction commits; no save() needed.
    @Override
    @Transactional
    public void run(String... args) {
        if (studyPreferenceRepository.count() > 0) {
            return;
        }
        Map<String, Course> coursesByCode = courseRepository.findAll().stream()
                .collect(Collectors.toMap(Course::getCourseCode, Function.identity()));
        Random random = new Random(RANDOM_SEED);
        int seeded = 0;

        for (Student student : studentRepository.findAll()) {
            List<StudyPreference> preferences = switch (student.getEmail()) {
                case StudentDataSeeder.DEMO_STUDENT_EMAIL -> alexPreferences(coursesByCode);
                case StudentDataSeeder.APPENDIX_B_STUDENT_EMAIL -> benPreferences(coursesByCode);
                default -> randomPreferences(student, random);
            };
            preferences.forEach(student::addPreference);
            seeded += preferences.size();
        }

        System.out.println("Seeded " + seeded + " study preferences with availability slots");
    }

    private List<StudyPreference> alexPreferences(Map<String, Course> coursesByCode) {
        StudyPreference is111 = new StudyPreference(coursesByCode.get("IS111"),
                StudyMode.IN_PERSON, Arrangement.ONE_TO_ONE, StudyGoal.CONCEPT_REVIEW);
        addSlot(is111, DayOfWeek.MONDAY, 10, 12);
        addSlot(is111, DayOfWeek.WEDNESDAY, 14, 16);

        StudyPreference is112 = new StudyPreference(coursesByCode.get("IS112"),
                StudyMode.ONLINE, Arrangement.SMALL_GROUP, StudyGoal.PROBLEM_SOLVING);
        addSlot(is112, DayOfWeek.TUESDAY, 19, 21);

        // Appendix A, Student A. "2-3 students" is closest to EITHER, which allows one-to-one or a small group.
        StudyPreference is442 = new StudyPreference(coursesByCode.get("IS442"),
                StudyMode.IN_PERSON, Arrangement.EITHER, StudyGoal.EXAM_PREPARATION);
        addSlot(is442, DayOfWeek.WEDNESDAY, 19, 21);
        addSlot(is442, DayOfWeek.FRIDAY, 14, 16);

        StudyPreference cs201 = new StudyPreference(coursesByCode.get("CS201"),
                StudyMode.IN_PERSON, Arrangement.EITHER, StudyGoal.EXAM_PREPARATION);
        addSlot(cs201, DayOfWeek.FRIDAY, 13, 15);

        return List.of(is111, is112, is442, cs201);
    }

    // Appendix A, Student B. "2 students" is ONE_TO_ONE.
    private List<StudyPreference> benPreferences(Map<String, Course> coursesByCode) {
        StudyPreference is442 = new StudyPreference(coursesByCode.get("IS442"),
                StudyMode.EITHER, Arrangement.ONE_TO_ONE, StudyGoal.EXAM_PREPARATION);
        addSlot(is442, DayOfWeek.WEDNESDAY, 19, 21);
        addSlot(is442, DayOfWeek.THURSDAY, 18, 20);
        return List.of(is442);
    }

    private List<StudyPreference> randomPreferences(Student student, Random random) {
        List<Course> courses = new ArrayList<>(student.getCoursesTaken());
        Collections.shuffle(courses, random);
        int count = Math.min(courses.size(), 1 + random.nextInt(MAX_PREFERENCES_PER_STUDENT));

        List<StudyPreference> preferences = new ArrayList<>();
        for (Course course : courses.subList(0, count)) {
            StudyPreference preference = new StudyPreference(course,
                    pick(StudyMode.values(), random),
                    pick(Arrangement.values(), random),
                    pick(StudyGoal.values(), random));
            addRandomSlots(preference, random);
            preferences.add(preference);
        }
        return preferences;
    }

    // Each slot is on a different day, so a preference's own slots never overlap each other.
    private void addRandomSlots(StudyPreference preference, Random random) {
        List<DayOfWeek> days = new ArrayList<>(STUDY_DAYS);
        Collections.shuffle(days, random);
        int slotCount = 1 + random.nextInt(MAX_SLOTS_PER_PREFERENCE);
        for (DayOfWeek day : days.subList(0, slotCount)) {
            int startHour = EARLIEST_START_HOUR + random.nextInt(LATEST_START_HOUR - EARLIEST_START_HOUR + 1);
            int endHour = startHour + 1 + random.nextInt(MAX_SLOT_HOURS);
            addSlot(preference, day, startHour, endHour);
        }
    }

    private <T> T pick(T[] values, Random random) {
        return values[random.nextInt(values.length)];
    }

    private void addSlot(StudyPreference preference, DayOfWeek day, int startHour, int endHour) {
        preference.addAvailabilitySlot(
                new AvailabilitySlot(day, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0)));
    }
}

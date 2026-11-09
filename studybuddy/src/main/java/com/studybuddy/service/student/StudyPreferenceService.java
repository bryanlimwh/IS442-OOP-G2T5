package com.studybuddy.service.student;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.studybuddy.entity.AvailabilitySlot;
import com.studybuddy.entity.Course;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyPreference;
import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;
import com.studybuddy.exception.CourseNotFoundException;
import com.studybuddy.exception.StudentNotFoundException;
import com.studybuddy.exception.StudyPreferenceNotFoundException;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.repository.StudyPreferenceRepository;

/**
 * A student managing their study preferences (one per course) and each
 * preference's weekly availability slots. The student is identified by their
 * login email, so they can only change their own.
 */
@Service
// Keeps the database session open for lazy lists, saves entity changes when each
// method ends, and undoes everything if a method throws.
@Transactional
public class StudyPreferenceService {

    private final StudyPreferenceRepository studyPreferenceRepository;
    private final StudentRepository studentRepository;
    private final CourseRepository courseRepository;

    public StudyPreferenceService(StudyPreferenceRepository studyPreferenceRepository,
            StudentRepository studentRepository, CourseRepository courseRepository) {
        this.studyPreferenceRepository = studyPreferenceRepository;
        this.studentRepository = studentRepository;
        this.courseRepository = courseRepository;
    }

    public List<StudyPreference> getPreferences(String email) {
        return findStudent(email).getPreferences().stream()
                .sorted(Comparator.comparing(p -> p.getCourse().getCourseCode()))
                .toList();
    }

    // Only for a course the student takes, and at most one per course.
    public StudyPreference createPreference(String email, Long courseId,
            StudyMode mode, Arrangement arrangement, StudyGoal goal) {
        requireChoices(mode, arrangement, goal);
        Student student = findStudent(email);
        Course course = courseRepository.findById(courseId).orElseThrow(() -> new CourseNotFoundException(courseId));
        if (!student.getCoursesTaken().contains(course)) {
            throw new IllegalStateException("Add " + course.getCourseCode() + " to your courses first");
        }
        if (student.getPreferences().stream().anyMatch(p -> p.getCourse().equals(course))) {
            throw new IllegalStateException("You already have a preference for " + course.getCourseCode());
        }
        StudyPreference preference = new StudyPreference(course, mode, arrangement, goal);
        student.addPreference(preference);
        return studyPreferenceRepository.save(preference);
    }

    // The course can't be changed; delete the preference and create a new one instead.
    public StudyPreference updatePreference(Long preferenceId, String email,
            StudyMode mode, Arrangement arrangement, StudyGoal goal) {
        requireChoices(mode, arrangement, goal);
        StudyPreference preference = findOwnPreference(preferenceId, email);
        preference.setStudyMode(mode);
        preference.setArrangement(arrangement);
        preference.setStudyGoal(goal);
        return preference;
    }

    // Its slots are deleted with it.
    public void deletePreference(Long preferenceId, String email) {
        StudyPreference preference = findOwnPreference(preferenceId, email);
        preference.getStudent().getPreferences().remove(preference);
    }

    // A slot must end after it starts and not overlap the others: matching adds up
    // the overlap of every pair of slots, so overlapping slots would count twice.
    public AvailabilitySlot addAvailabilitySlot(Long preferenceId, String email,
            DayOfWeek day, LocalTime start, LocalTime end) {
        StudyPreference preference = findOwnPreference(preferenceId, email);
        if (day == null || start == null || end == null || !start.isBefore(end)) {
            throw new IllegalArgumentException("A slot needs a day and must end after it starts");
        }
        AvailabilitySlot slot = new AvailabilitySlot(day, start, end);
        if (preference.getAvailabilitySlots().stream().anyMatch(slot::overlaps)) {
            throw new IllegalArgumentException("This slot overlaps one you already have on " + day);
        }
        preference.addAvailabilitySlot(slot);
        studyPreferenceRepository.flush(); // writes the slot now, so it has an id
        return slot;
    }

    public void removeAvailabilitySlot(Long preferenceId, String email, Long slotId) {
        StudyPreference preference = findOwnPreference(preferenceId, email);
        if (!preference.getAvailabilitySlots().removeIf(slot -> slot.getId().equals(slotId))) {
            throw new IllegalArgumentException("This preference has no slot " + slotId);
        }
    }

    // Another student's preference is treated as not found, so its existence isn't revealed.
    private StudyPreference findOwnPreference(Long preferenceId, String email) {
        return studyPreferenceRepository.findById(preferenceId)
                .filter(p -> p.getStudent().getEmail().equals(email))
                .orElseThrow(() -> new StudyPreferenceNotFoundException(preferenceId));
    }

    private void requireChoices(StudyMode mode, Arrangement arrangement, StudyGoal goal) {
        if (mode == null || arrangement == null || goal == null) {
            throw new IllegalArgumentException("Choose a study mode, group size and study goal");
        }
    }

    private Student findStudent(String email) {
        return studentRepository.findByEmail(email).orElseThrow(() -> new StudentNotFoundException(email));
    }
}

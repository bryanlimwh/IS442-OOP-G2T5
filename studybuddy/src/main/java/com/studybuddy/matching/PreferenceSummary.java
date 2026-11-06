package com.studybuddy.matching;

import java.util.Comparator;
import java.util.List;

import com.studybuddy.entity.AvailabilitySlot;
import com.studybuddy.entity.Course;
import com.studybuddy.entity.StudyPreference;
import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;

/**
 * A read-only copy of the study preference a match was found through, for
 * display. Copied out of the entity so the page never holds a live entity
 * (whose student, and contact number, are one getter away).
 */
public record PreferenceSummary(
        Long courseId,
        String courseCode,
        String courseName,
        StudyMode studyMode,
        Arrangement arrangement,
        StudyGoal studyGoal,
        List<String> availability) {

    public static PreferenceSummary from(StudyPreference preference) {
        List<String> availability = preference.getAvailabilitySlots().stream()
                .sorted(Comparator.comparing(AvailabilitySlot::getDayOfWeek)
                        .thenComparing(AvailabilitySlot::getStartTime))
                .map(AvailabilitySlot::getLabel)
                .toList();
        Course course = preference.getCourse();
        return new PreferenceSummary(course.getCourseId(), course.getCourseCode(), course.getCourseName(),
                preference.getStudyMode(), preference.getArrangement(), preference.getStudyGoal(), availability);
    }

}

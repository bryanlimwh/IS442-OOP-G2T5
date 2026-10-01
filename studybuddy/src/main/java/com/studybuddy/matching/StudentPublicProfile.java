package com.studybuddy.matching;

import java.util.List;

import com.studybuddy.domain.Course;
import com.studybuddy.domain.Student;

/**
 * What any student may see about another student before a match is accepted.
 * Deliberately has no contact number (or email), so match results can't
 * reveal it however they are displayed.
 */
public record StudentPublicProfile(
        Long studentId,
        String name,
        String school,
        String programme,
        int yearOfStudy,
        List<String> courseCodes) {

    public static StudentPublicProfile from(Student student) {
        return new StudentPublicProfile(student.getUserId(), student.getName(), student.getSchool(),
                student.getProgramme(), student.getYearOfStudy(),
                student.getCoursesTaken().stream().map(Course::getCourseCode).toList());
    }
}

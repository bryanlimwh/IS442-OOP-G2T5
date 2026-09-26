package com.studybuddy.exception;

/**
 * Thrown when no course exists with the requested id.
 */
public class CourseNotFoundException extends RuntimeException {

    public CourseNotFoundException(Long courseId) {
        super("Course " + courseId + " not found");
    }
}

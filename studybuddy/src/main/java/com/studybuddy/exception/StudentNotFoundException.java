package com.studybuddy.exception;

/**
 * Thrown when no student exists with the requested id.
 */
public class StudentNotFoundException extends RuntimeException {

    public StudentNotFoundException(Long studentId) {
        super("Student " + studentId + " not found");
    }
}

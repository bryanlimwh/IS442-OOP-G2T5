package com.studybuddy.exception;

/**
 * Thrown when no student exists with the requested id or email.
 */
public class StudentNotFoundException extends RuntimeException {

    public StudentNotFoundException(Long studentId) {
        super("Student " + studentId + " not found");
    }

    public StudentNotFoundException(String email) {
        super("No student account for " + email);
    }
}

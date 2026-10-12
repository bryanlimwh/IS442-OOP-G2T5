package com.studybuddy.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when no student exists with the requested id or email.
 * <p>
 * @ResponseStatus makes Spring answer with 404 Not Found instead of
 * a generic 500 error page if a controller doesn't catch it.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class StudentNotFoundException extends RuntimeException {

    public StudentNotFoundException(Long studentId) {
        super("Student " + studentId + " not found");
    }

    public StudentNotFoundException(String email) {
        super("No student account for " + email);
    }
}

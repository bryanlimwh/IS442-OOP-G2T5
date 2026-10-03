package com.studybuddy.exception;

/**
 * Thrown when no account (Student or Admin) exists with the requested id.
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(Long userId) {
        super("User " + userId + " not found");
    }
}

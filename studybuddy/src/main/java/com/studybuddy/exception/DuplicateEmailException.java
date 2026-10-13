package com.studybuddy.exception;

/**
 * Thrown when an account would be given an email that another account
 * already uses. The email is the login identifier, so it must be unique.
 */
public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException(String email) {
        super("Another account already uses " + email + ".");
    }
}

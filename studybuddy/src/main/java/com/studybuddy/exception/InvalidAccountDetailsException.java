package com.studybuddy.exception;

/**
 * Thrown when account details can't be used, such as a blank or malformed
 * email or a password that is too short or too long. The message is written
 * for the admin, so the page can show it as it is.
 */
public class InvalidAccountDetailsException extends RuntimeException {

    public InvalidAccountDetailsException(String message) {
        super(message);
    }
}

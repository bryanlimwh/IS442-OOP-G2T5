package com.studybuddy.exception;

/**
 * Thrown when an action would remove the last active admin account,
 * which would lock everyone out of admin functions.
 */
public class LastAdminException extends RuntimeException {

    public LastAdminException() {
        super("Cannot remove the last active admin account");
    }
}

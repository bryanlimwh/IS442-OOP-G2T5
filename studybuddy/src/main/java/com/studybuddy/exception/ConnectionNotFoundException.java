package com.studybuddy.exception;

/**
 * Thrown when no study-buddy connection with the given id exists for the
 * logged-in student. A connection belonging to two other students gets this
 * too, so a student can't learn which connection ids exist.
 */
public class ConnectionNotFoundException extends RuntimeException {

    public ConnectionNotFoundException(Long connectionId) {
        super("Study buddy connection " + connectionId + " not found");
    }
}

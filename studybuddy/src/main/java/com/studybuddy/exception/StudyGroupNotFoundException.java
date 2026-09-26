package com.studybuddy.exception;

/**
 * Thrown when no study group exists with the requested id.
 */
public class StudyGroupNotFoundException extends RuntimeException {

    public StudyGroupNotFoundException(Long groupId) {
        super("Study group " + groupId + " not found");
    }
}

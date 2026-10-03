package com.studybuddy.exception;

/**
 * Thrown when no study preference exists with the requested id.
 */
public class StudyPreferenceNotFoundException extends RuntimeException {

    public StudyPreferenceNotFoundException(Long preferenceId) {
        super("Study preference " + preferenceId + " not found");
    }
}

package com.studybuddy.exception;

/**
 * Thrown when matching settings would leave the engine unable to score
 * students, such as a negative weight or every weight set to 0.
 */
public class InvalidMatchingConfigException extends RuntimeException {

    public InvalidMatchingConfigException(String message) {
        super(message);
    }
}

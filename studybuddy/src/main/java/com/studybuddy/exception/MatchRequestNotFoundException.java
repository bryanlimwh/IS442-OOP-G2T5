package com.studybuddy.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when no match request exists with the requested id.
 * <p>
 * @ResponseStatus makes Spring answer with 404 Not Found instead of
 * a generic 500 error page if a controller doesn't catch it.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class MatchRequestNotFoundException extends RuntimeException {

    public MatchRequestNotFoundException(Long requestId) {
        super("Match request " + requestId + " not found");
    }
}

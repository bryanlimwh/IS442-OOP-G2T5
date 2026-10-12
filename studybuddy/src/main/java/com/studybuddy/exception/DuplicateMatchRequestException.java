package com.studybuddy.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a student tries to send a match request while a PENDING request
 * already exists between the same two students, in either direction.
 * <p>
 * @ResponseStatus makes Spring answer with 409 Conflict instead of
 * a generic 500 error page when this is thrown from a controller.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateMatchRequestException extends RuntimeException {

    // Private: callers use the two factory methods below,
    // so each situation always gets the correct message
    private DuplicateMatchRequestException(String message) {
        super(message);
    }

    // The sender has already sent this recipient a request that is still PENDING
    public static DuplicateMatchRequestException alreadySent(Long senderId, Long recipientId) {
        return new DuplicateMatchRequestException(
                "Student " + senderId + " already has a pending request to student " + recipientId);
    }

    // The recipient has already sent the sender a request,
    // so the sender should accept that one instead of sending a new one
    public static DuplicateMatchRequestException alreadyReceived(Long senderId, Long recipientId) {
        return new DuplicateMatchRequestException(
                "Student " + recipientId + " has already sent you a request. Accept it instead of sending a new one");
    }
}
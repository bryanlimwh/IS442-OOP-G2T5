package com.studybuddy.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a student tries to view or act on a match request that isn't
 * theirs: viewing one they neither sent nor received, or accepting one that
 * was sent to someone else.
 * <p>
 * @ResponseStatus makes Spring answer with 403 Forbidden instead of a
 * generic 500 error page when this is thrown from a controller.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class NotMatchRequestParticipantException extends RuntimeException {

    public NotMatchRequestParticipantException(Long requestId, Long studentId) {
        super("Student " + studentId + " is not allowed to access match request " + requestId);
    }
}

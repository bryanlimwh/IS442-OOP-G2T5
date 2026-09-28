package com.studybuddy.exception;

/**
 * Thrown when a student tries a leader-only action (editing, closing or
 * managing the members of a study group) on a group they don't lead.
 * <p>
 * The {@code StudyGroup} entity doesn't know who is logged in, so this check
 * is made in the service layer.
 */
public class NotGroupLeaderException extends RuntimeException {

    public NotGroupLeaderException(Long groupId, Long studentId) {
        super("Student " + studentId + " is not the leader of study group " + groupId);
    }
}

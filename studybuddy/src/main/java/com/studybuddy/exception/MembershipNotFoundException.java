package com.studybuddy.exception;

/**
 * Thrown when no group membership (join request or member) exists with the
 * requested id.
 */
public class MembershipNotFoundException extends RuntimeException {

    public MembershipNotFoundException(Long membershipId) {
        super("Group membership " + membershipId + " not found");
    }
}

package com.studybuddy.entity.enums;

/**
 * Where a student's link to a study group stands. Rows are kept rather than
 * deleted, so REJECTED, REMOVED, GROUP_CLOSED, CANCELLED and LEFT act as history.
 */
public enum MembershipStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    REMOVED,
    // The group was closed while the request was still pending: nobody turned the student down.
    GROUP_CLOSED,
    // The student withdrew their own request before the leader answered it.
    CANCELLED,
    // The member left the group themselves (the leader removing them is REMOVED).
    LEFT
}

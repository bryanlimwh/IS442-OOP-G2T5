package com.studybuddy.entity.enums;

/**
 * Where a student's link to a study group stands. Rows are kept rather than
 * deleted, so REJECTED, REMOVED and GROUP_CLOSED act as history.
 */
public enum MembershipStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    REMOVED,
    // The group was closed while the request was still pending: nobody turned the student down.
    GROUP_CLOSED
}

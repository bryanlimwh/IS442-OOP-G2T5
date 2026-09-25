package com.studybuddy.domain.enums;

/**
 * Where a student's link to a study group stands. Rows are kept rather than
 * deleted, so REJECTED and REMOVED act as history.
 */
public enum MembershipStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    REMOVED
}

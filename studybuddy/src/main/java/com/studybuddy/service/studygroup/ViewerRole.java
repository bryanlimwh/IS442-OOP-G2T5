package com.studybuddy.service.studygroup;

import com.studybuddy.entity.enums.MembershipStatus;

/**
 * How the logged-in student is linked to the study group they are looking at,
 * which decides what the group's page offers them.
 * <p>
 * This is not the same as {@link MembershipStatus}: the leader is also an
 * ACCEPTED member but needs their own answer, and a student who never asked
 * has no membership row at all.
 */
public enum ViewerRole {
    LEADER("You lead"),
    MEMBER("Joined"),
    PENDING("Request sent"),
    REJECTED("Declined"),
    REMOVED("Removed"),
    // The group closed before the leader answered. No Browse label: Browse only lists open groups.
    GROUP_CLOSED(""),
    NONE("");

    private final String label;

    ViewerRole(String label) {
        this.label = label;
    }

    // The short label on a Browse card, e.g. "Joined". Empty for NONE, which shows no label.
    public String getLabel() {
        return label;
    }

    /**
     * True if the student may send a join request: they have never asked, or
     * were rejected or removed (the group lets them ask again). Whether the
     * group has room is a separate check.
     */
    public boolean canRequestToJoin() {
        return this == NONE || this == REJECTED || this == REMOVED;
    }

    /**
     * The role for a student who isn't the leader, from their membership status.
     */
    static ViewerRole fromStatus(MembershipStatus status) {
        return switch (status) {
            case ACCEPTED -> MEMBER;
            case PENDING -> PENDING;
            case REJECTED -> REJECTED;
            case REMOVED -> REMOVED;
            case GROUP_CLOSED -> GROUP_CLOSED;
            // The student withdrew it themselves, so the page just offers to join, with no "declined" message
            case CANCELLED -> NONE;
            // They chose to leave, so they can simply ask again, like a student who cancelled
            case LEFT -> NONE;
        };
    }
}

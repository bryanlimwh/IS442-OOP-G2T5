package com.studybuddy.entity.enums;


public enum MatchRequestStatus {
    PENDING("Waiting for a reply"),
    ACCEPTED("Accepted"),
    REJECTED("Declined");

    private final String label;

    MatchRequestStatus(String label) {
        this.label = label;
    }

    // How the status is shown on the Match requests page, e.g. "Declined" rather than REJECTED
    public String getLabel() {
        return label;
    }
}

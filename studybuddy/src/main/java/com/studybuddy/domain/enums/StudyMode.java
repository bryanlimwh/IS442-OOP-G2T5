package com.studybuddy.domain.enums;

public enum StudyMode {
    IN_PERSON("In person"),
    ONLINE("Online"),
    EITHER("In person or online");

    private final String label;

    StudyMode(String label) {
        this.label = label;
    }

    // How the value is shown on pages, e.g. "In person" rather than IN_PERSON
    public String getLabel() {
        return label;
    }
}

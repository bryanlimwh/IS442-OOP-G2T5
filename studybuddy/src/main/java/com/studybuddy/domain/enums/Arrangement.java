package com.studybuddy.domain.enums;

public enum Arrangement {
    ONE_TO_ONE("One-to-one"),
    SMALL_GROUP("Small group"),
    EITHER("One-to-one or small group");

    private final String label;

    Arrangement(String label) {
        this.label = label;
    }

    // How the value is shown on pages, e.g. "One-to-one" rather than ONE_TO_ONE
    public String getLabel() {
        return label;
    }
}

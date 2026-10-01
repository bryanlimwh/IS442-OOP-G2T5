package com.studybuddy.domain.enums;

public enum StudyGoal {
    CONCEPT_REVIEW("Concept review"),
    PROBLEM_SOLVING("Problem solving"),
    EXAM_PREPARATION("Exam preparation"),
    PROJECT_DISCUSSION("Project discussion");

    private final String label;

    StudyGoal(String label) {
        this.label = label;
    }

    // How the value is shown on pages, e.g. "Exam preparation" rather than EXAM_PREPARATION
    public String getLabel() {
        return label;
    }
}

package com.studybuddy.dto;

import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The values on the create and edit study group forms.
 * <p>
 * {@code StudyGroup} has no setters, so the form binds to this object instead,
 * and {@code StudyGroupService} passes the values to the entity's constructor or
 * {@code updateDetails()}. The checks here let the page show friendly errors
 * before the service is called; the entity still enforces the same rules.
 */
public class StudyGroupForm {

    @NotBlank(message = "Please enter a group name")
    private String name;

    private String description; // optional

    // Only used when creating a group: the course can't be changed afterwards.
    @NotNull(message = "Please choose a course")
    private Long courseId;

    @NotNull(message = "Please choose a study goal")
    private StudyGoal studyGoal;

    @NotNull(message = "Please choose a study mode")
    private StudyMode studyMode;

    // Integer rather than int, so a blank field fails @NotNull with a clear message
    // instead of a type-conversion error. 2 = the leader plus at least one member.
    @NotNull(message = "Please enter a maximum group size")
    @Min(value = 2, message = "A group needs room for at least 2 members, including you")
    private Integer maxSize;

    /**
     * Empty form, filled in by Spring from the submitted fields.
     */
    public StudyGroupForm() {
    }

    // ---- Getters / Setters (no Lombok in this project) ----

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }

    public StudyGoal getStudyGoal() {
        return studyGoal;
    }

    public void setStudyGoal(StudyGoal studyGoal) {
        this.studyGoal = studyGoal;
    }

    public StudyMode getStudyMode() {
        return studyMode;
    }

    public void setStudyMode(StudyMode studyMode) {
        this.studyMode = studyMode;
    }

    public Integer getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(Integer maxSize) {
        this.maxSize = maxSize;
    }
}

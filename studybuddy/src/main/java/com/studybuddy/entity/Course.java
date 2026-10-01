package com.studybuddy.entity;

import java.util.Objects;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * A university course (e.g. IS442) that students can pick when setting
 * their study preferences.
 */
@Entity
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long courseId;

    private String courseCode;
    private String courseName;

    /**
     * No-arg constructor required by JPA.
     */
    protected Course() {
    }

    public Course(String courseCode, String courseName) {
        this.courseCode = courseCode;
        this.courseName = courseName;
    }

    // ---- Getters / Setters (no Lombok in this project) ----

    public Long getCourseId() {
        return courseId;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public void setCourseCode(String courseCode) {
        this.courseCode = courseCode;
    }

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    // ---- equals()/hashCode(): compare by courseId ONLY (same approach as User) ----
    // Lets matching ask "same course?" without relying on Hibernate handing back
    // the same object. Two unsaved courses (null ids) are never equal.

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Course)) return false;
        Course other = (Course) o;
        return courseId != null && courseId.equals(other.getCourseId());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(courseId);
    }
}

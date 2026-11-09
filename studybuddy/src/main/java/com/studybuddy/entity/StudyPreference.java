package com.studybuddy.entity;

import java.util.ArrayList;
import java.util.List;

import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;

/**
 * A student's study preferences for one course: how, when and why they want
 * to study. Used as the input when matching study buddies.
 */
@Entity
public class StudyPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private Course course;

    @Enumerated(EnumType.STRING)
    private StudyMode studyMode;

    @Enumerated(EnumType.STRING)
    private Arrangement arrangement;

    @Enumerated(EnumType.STRING)
    private StudyGoal studyGoal;

    @OneToMany(mappedBy = "studyPreference", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AvailabilitySlot> availabilitySlots = new ArrayList<>();

    // Owning side of the relationship: this is where the student_id FK lives.
    @ManyToOne
    @JoinColumn(name = "student_id")
    private Student student;

    /**
     * No-arg constructor required by JPA.
     */
    protected StudyPreference() {
    }

    public StudyPreference(Course course, StudyMode studyMode, Arrangement arrangement, StudyGoal studyGoal) {
        this.course = course;
        this.studyMode = studyMode;
        this.arrangement = arrangement;
        this.studyGoal = studyGoal;
    }

    // ---- Getters / Setters (no Lombok in this project) ----

    public Long getId() {
        return id;
    }

    public Course getCourse() {
        return course;
    }

    public StudyMode getStudyMode() {
        return studyMode;
    }

    public void setStudyMode(StudyMode studyMode) {
        this.studyMode = studyMode;
    }

    public Arrangement getArrangement() {
        return arrangement;
    }

    public void setArrangement(Arrangement arrangement) {
        this.arrangement = arrangement;
    }

    public StudyGoal getStudyGoal() {
        return studyGoal;
    }

    public void setStudyGoal(StudyGoal studyGoal) {
        this.studyGoal = studyGoal;
    }

    // Sets both sides of the relationship so the slot's foreign key is never null.
    public void addAvailabilitySlot(AvailabilitySlot slot) {
        slot.setStudyPreference(this);
        this.availabilitySlots.add(slot);
    }

    public List<AvailabilitySlot> getAvailabilitySlots() {
        return availabilitySlots;
    }

    public Student getStudent() {
        return student;
    }

    public void setStudent(Student student) {
        this.student = student;
    }
}

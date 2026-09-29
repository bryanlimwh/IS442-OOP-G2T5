package com.studybuddy.entity;

import java.time.DayOfWeek;
import java.time.LocalTime;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/**
 * A recurring weekly time window (day + start/end time) in which a student
 * is free to study. Belongs to exactly one {@link StudyPreference}.
 */
@Entity
public class AvailabilitySlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private DayOfWeek dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;

    @ManyToOne
    private StudyPreference studyPreference;

    /**
     * No-arg constructor required by JPA.
     */
    protected AvailabilitySlot() {
    }

    public AvailabilitySlot(DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime) {
        this.dayOfWeek = dayOfWeek;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    /**
     * True if this slot and the other fall on the same day and their
     * time ranges intersect. Slots that merely touch (one ends exactly
     * when the other starts) do not overlap.
     */
    public boolean overlaps(AvailabilitySlot other) {
        return dayOfWeek == other.dayOfWeek
                && startTime.isBefore(other.endTime)
                && other.startTime.isBefore(endTime);
    }

    // ---- Getters / Setters (no Lombok in this project) ----

    public Long getId() {
        return id;
    }

    public DayOfWeek getDayOfWeek() {
        return dayOfWeek;
    }

    public void setDayOfWeek(DayOfWeek dayOfWeek) {
        this.dayOfWeek = dayOfWeek;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }

    public StudyPreference getStudyPreference() {
        return studyPreference;
    }

    public void setStudyPreference(StudyPreference studyPreference) {
        this.studyPreference = studyPreference;
    }
}

package com.studybuddy.entity;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.Locale;

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

    /**
     * How many minutes this slot and the other have in common: 0 if they
     * don't overlap, otherwise from the later start to the earlier end.
     */
    public long overlapMinutes(AvailabilitySlot other) {
        if (!overlaps(other)) {
            return 0;
        }
        LocalTime laterStart = startTime.isAfter(other.startTime) ? startTime : other.startTime;
        LocalTime earlierEnd = endTime.isBefore(other.endTime) ? endTime : other.endTime;
        return Duration.between(laterStart, earlierEnd).toMinutes();
    }

    /**
     * The slot as people read it, e.g. "Wed 19:00-21:00". Used everywhere a slot is shown
     * (matches, study groups, preferences), so the format is the same on every page.
     */
    public String getLabel() {
        return dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + startTime + "-" + endTime;
    }

    // ---- Getters / Setters (no Lombok in this project) ----

    public Long getId() {
        return id;
    }

    public DayOfWeek getDayOfWeek() {
        return dayOfWeek;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public void setStudyPreference(StudyPreference studyPreference) {
        this.studyPreference = studyPreference;
    }
}

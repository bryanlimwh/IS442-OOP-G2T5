package com.studybuddy.dto;

import java.time.DayOfWeek;
import java.time.LocalTime;

import com.studybuddy.entity.AvailabilitySlot;

import org.springframework.format.annotation.DateTimeFormat;

/**
 * One time-slot row on the create and edit study group forms: a day plus a
 * From and To time. Any of the three may be blank while the leader is still
 * filling the form in; {@code StudyGroupFormValidator} checks every row before
 * it becomes an {@link AvailabilitySlot}.
 */
public class SlotForm {

    private DayOfWeek dayOfWeek;

    // HH:mm on both sides: how the dropdown values are written, and how they are read back
    @DateTimeFormat(pattern = "HH:mm")
    private LocalTime startTime;

    @DateTimeFormat(pattern = "HH:mm")
    private LocalTime endTime;

    /**
     * Empty row, filled in by Spring from the submitted fields.
     */
    public SlotForm() {
    }

    /**
     * A row pre-filled from one of the group's saved slots, for the edit form.
     */
    public static SlotForm from(AvailabilitySlot slot) {
        SlotForm row = new SlotForm();
        row.dayOfWeek = slot.getDayOfWeek();
        row.startTime = slot.getStartTime();
        row.endTime = slot.getEndTime();
        return row;
    }

    // ---- Behaviour ----

    // Call only once the validator has passed the row: AvailabilitySlot itself checks nothing.
    public AvailabilitySlot toAvailabilitySlot() {
        return new AvailabilitySlot(dayOfWeek, startTime, endTime);
    }

    // ---- Getters / Setters (no Lombok in this project) ----

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
}

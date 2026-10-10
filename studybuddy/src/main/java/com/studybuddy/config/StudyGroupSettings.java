package com.studybuddy.config;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import com.studybuddy.service.studygroup.GroupSort;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The study group settings, read from the studybuddy.groups.* entries in
 * application.properties: how many weekly time slots a group may have, which
 * times the From / To dropdowns offer, and the Browse page's starting order.
 * <p>
 * Bound like {@link MatchingDefaults}, so these can be changed without
 * touching the code.
 */
@ConfigurationProperties(prefix = "studybuddy.groups")
public record StudyGroupSettings(
        int maxAvailabilitySlots,
        LocalTime earliestSlotTime,
        LocalTime latestSlotTime,
        int slotStepMinutes,
        GroupSort defaultSort) {

    /**
     * Stops the app at startup if the settings can't produce a usable form.
     *
     * @throws IllegalArgumentException if a value is missing or out of range
     */
    public StudyGroupSettings {
        if (maxAvailabilitySlots < 1) {
            throw new IllegalArgumentException("studybuddy.groups.max-availability-slots must be at least 1");
        }
        if (earliestSlotTime == null || latestSlotTime == null || !earliestSlotTime.isBefore(latestSlotTime)) {
            throw new IllegalArgumentException(
                    "studybuddy.groups.earliest-slot-time must be before studybuddy.groups.latest-slot-time");
        }
        if (slotStepMinutes < 1) {
            throw new IllegalArgumentException("studybuddy.groups.slot-step-minutes must be at least 1");
        }
        if (defaultSort == null) {
            throw new IllegalArgumentException("studybuddy.groups.default-sort is required");
        }
    }

    /**
     * Every time the From / To dropdowns offer, e.g. 08:00, 08:15, ... 23:00.
     * The validator also uses it to refuse a time that isn't on the list.
     */
    public List<LocalTime> timeOptions() {
        List<LocalTime> times = new ArrayList<>();
        LocalTime time = earliestSlotTime;
        while (!time.isAfter(latestSlotTime)) {
            times.add(time);
            LocalTime next = time.plusMinutes(slotStepMinutes);
            // LocalTime wraps round at midnight, so stop rather than start again from 00:00
            if (!next.isAfter(time)) {
                break;
            }
            time = next;
        }
        return times;
    }
}

package com.studybuddy.service.studygroup;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import com.studybuddy.config.StudyGroupSettings;
import com.studybuddy.dto.SlotForm;
import com.studybuddy.dto.StudyGroupForm;
import com.studybuddy.entity.AvailabilitySlot;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

/**
 * Checks the time-slot rows on the create and edit study group forms. The
 * single-field rules (name, goal, mode, size) are annotations on
 * {@link StudyGroupForm}; these rules compare several fields and rows, so they
 * live here instead.
 * <p>
 * Implements Spring's {@link Validator} interface. The controller registers it
 * with {@code @InitBinder}, so a single {@code @Valid} runs the annotations
 * and these checks together, and every message ends up in the same
 * {@code BindingResult} for the page to show. {@code AvailabilitySlot} checks
 * nothing itself, so these checks are what keep a group's slots sensible:
 * <ol>
 *   <li>at least one slot, and no more than the configured maximum</li>
 *   <li>every row has a day, a start time and an end time, from the dropdown's times</li>
 *   <li>each slot ends after it starts</li>
 *   <li>no two slots overlap</li>
 * </ol>
 */
@Component
@EnableConfigurationProperties(StudyGroupSettings.class)
public class StudyGroupFormValidator implements Validator {

    private static final String SLOTS_FIELD = "slots";

    private final StudyGroupSettings settings;

    public StudyGroupFormValidator(StudyGroupSettings settings) {
        this.settings = settings;
    }

    @Override
    public boolean supports(Class<?> type) {
        return StudyGroupForm.class.isAssignableFrom(type);
    }

    @Override
    public void validate(Object target, Errors errors) {
        List<SlotForm> slots = ((StudyGroupForm) target).getSlots();
        if (slots.isEmpty()) {
            rejectSlots(errors, "slots.none", "Please add at least one time slot");
            return;
        }
        if (slots.size() > settings.maxAvailabilitySlots()) {
            rejectSlots(errors, "slots.tooMany",
                    "A group can have at most " + settings.maxAvailabilitySlots() + " time slots");
            return;
        }

        // Only rows that pass their own checks are compared for overlaps, so one
        // bad row doesn't also produce confusing overlap messages.
        List<Integer> completeRows = new ArrayList<>();
        for (int index = 0; index < slots.size(); index++) {
            if (checkRow(slots.get(index), index + 1, errors)) {
                completeRows.add(index);
            }
        }
        checkOverlaps(slots, completeRows, errors);
    }

    // Checks 2 and 3 for one row. Returns true if the row is a usable slot.
    private boolean checkRow(SlotForm slot, int slotNumber, Errors errors) {
        List<String> missing = new ArrayList<>();
        if (slot.getDayOfWeek() == null) {
            missing.add("a day");
        }
        if (slot.getStartTime() == null) {
            missing.add("a start time");
        }
        if (slot.getEndTime() == null) {
            missing.add("an end time");
        }
        if (!missing.isEmpty()) {
            rejectSlots(errors, "slot.incomplete", "Slot " + slotNumber + ": please choose " + joinWithAnd(missing));
            return false;
        }
        // The dropdowns only offer these times, so another value means the page was bypassed
        List<LocalTime> allowedTimes = settings.timeOptions();
        if (!allowedTimes.contains(slot.getStartTime()) || !allowedTimes.contains(slot.getEndTime())) {
            rejectSlots(errors, "slot.timeNotOffered", "Slot " + slotNumber + ": please choose times from the list ("
                    + settings.earliestSlotTime() + " to " + settings.latestSlotTime() + ")");
            return false;
        }
        if (!slot.getEndTime().isAfter(slot.getStartTime())) {
            rejectSlots(errors, "slot.endBeforeStart",
                    "Slot " + slotNumber + ": the end time must be after the start time");
            return false;
        }
        return true;
    }

    // Check 4. Reuses AvailabilitySlot.overlaps(), so slots that only touch (e.g. 14:00-16:00 and 16:00-18:00) are fine.
    private void checkOverlaps(List<SlotForm> slots, List<Integer> completeRows, Errors errors) {
        for (int first = 0; first < completeRows.size(); first++) {
            for (int second = first + 1; second < completeRows.size(); second++) {
                int firstIndex = completeRows.get(first);
                int secondIndex = completeRows.get(second);
                AvailabilitySlot firstSlot = slots.get(firstIndex).toAvailabilitySlot();
                AvailabilitySlot secondSlot = slots.get(secondIndex).toAvailabilitySlot();
                if (firstSlot.overlaps(secondSlot)) {
                    rejectSlots(errors, "slots.overlap",
                            "Slots " + (firstIndex + 1) + " and " + (secondIndex + 1) + " overlap");
                }
            }
        }
    }

    // Every slot message is filed under "slots", so the page lists them together under the time slots.
    private static void rejectSlots(Errors errors, String errorCode, String message) {
        errors.rejectValue(SLOTS_FIELD, errorCode, message);
    }

    // ["a day", "a start time", "an end time"] -> "a day, a start time and an end time"
    private static String joinWithAnd(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }
}

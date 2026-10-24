package com.studybuddy.service.studygroup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;

import com.studybuddy.config.StudyGroupSettings;
import com.studybuddy.dto.SlotForm;
import com.studybuddy.dto.StudyGroupForm;

/**
 * The time-slot checks on the create / edit group form, with no Spring. The settings are
 * built here with the values in application.properties (3 slots, 08:00 to 23:00 in
 * 15-minute steps); StudyGroupFormValidatorPropertiesTest checks the real file gives the same.
 */
class StudyGroupFormValidatorTest {

    private final StudyGroupFormValidator validator = new StudyGroupFormValidator(
            new StudyGroupSettings(3, LocalTime.of(8, 0), LocalTime.of(23, 0), 15, GroupSort.NEWEST));

    // GRP-7
    @Test
    void validSlotsPass() {
        assertThat(slotErrors(slot(DayOfWeek.MONDAY, "10:00", "12:00"))).isEmpty();
    }

    // GRP-7
    @Test
    void supportsOnlyTheGroupForm() {
        assertThat(validator.supports(StudyGroupForm.class)).isTrue();
        assertThat(validator.supports(SlotForm.class)).isFalse();
    }

    // GRP-7: slot count 0
    @Test
    void noSlotsIsRefused() {
        assertThat(slotErrors()).containsExactly("Please add at least one time slot");
    }

    // GRP-7: slot count 1, 3 and 4
    @Test
    void oneToThreeSlotsAreAllowed() {
        assertThat(slotErrors(slot(DayOfWeek.MONDAY, "10:00", "12:00"))).isEmpty();
        assertThat(slotErrors(slot(DayOfWeek.MONDAY, "10:00", "12:00"), slot(DayOfWeek.TUESDAY, "10:00", "12:00"),
                slot(DayOfWeek.WEDNESDAY, "10:00", "12:00"))).isEmpty();

        // Four is refused on its own, without checking the rows
        assertThat(slotErrors(slot(DayOfWeek.MONDAY, "10:00", "12:00"), slot(DayOfWeek.TUESDAY, "10:00", "12:00"),
                slot(DayOfWeek.WEDNESDAY, "10:00", "12:00"), slot(DayOfWeek.MONDAY, "11:00", "11:00")))
                .containsExactly("A group can have at most 3 time slots");
    }

    // GRP-7
    @Test
    void incompleteRowSaysWhatIsMissing() {
        SlotForm empty = new SlotForm();
        SlotForm noDay = slot(null, "10:00", "12:00");
        SlotForm noEnd = slot(DayOfWeek.MONDAY, "10:00", null);

        assertThat(slotErrors(empty, noDay, noEnd)).containsExactly(
                "Slot 1: please choose a day, a start time and an end time",
                "Slot 2: please choose a day",
                "Slot 3: please choose an end time");
    }

    // GRP-7: the first and last times on the list
    @Test
    void earliestAndLatestTimesAreAccepted() {
        assertThat(slotErrors(slot(DayOfWeek.MONDAY, "08:00", "23:00"))).isEmpty();
    }

    // GRP-7: one 15-minute step is the shortest slot
    @Test
    void quarterHourSlotIsAccepted() {
        assertThat(slotErrors(slot(DayOfWeek.MONDAY, "08:15", "08:30"))).isEmpty();
    }

    // GRP-7
    @Test
    void timesNotOnTheListAreRefused() {
        String message = "Slot 1: please choose times from the list (08:00 to 23:00)";

        assertThat(slotErrors(slot(DayOfWeek.MONDAY, "07:45", "10:00"))).containsExactly(message);
        assertThat(slotErrors(slot(DayOfWeek.MONDAY, "22:00", "23:15"))).containsExactly(message);
        // Inside the day but not on a 15-minute step
        assertThat(slotErrors(slot(DayOfWeek.MONDAY, "10:10", "12:00"))).containsExactly(message);
        assertThat(slotErrors(slot(DayOfWeek.MONDAY, "10:00", "11:59"))).containsExactly(message);
    }

    // GRP-7
    @Test
    void endMustBeAfterStart() {
        String message = "Slot 1: the end time must be after the start time";

        assertThat(slotErrors(slot(DayOfWeek.MONDAY, "12:00", "10:00"))).containsExactly(message);
        assertThat(slotErrors(slot(DayOfWeek.MONDAY, "10:00", "10:00"))).containsExactly(message);
        // 23:00 is the latest time, so a slot can't start there
        assertThat(slotErrors(slot(DayOfWeek.MONDAY, "23:00", "23:00"))).containsExactly(message);
    }

    // GRP-7
    @Test
    void overlappingSlotsAreRefused() {
        assertThat(slotErrors(slot(DayOfWeek.MONDAY, "10:00", "12:00"), slot(DayOfWeek.MONDAY, "11:45", "13:00")))
                .containsExactly("Slots 1 and 2 overlap");

        // Every overlapping pair is named
        assertThat(slotErrors(slot(DayOfWeek.FRIDAY, "10:00", "14:00"), slot(DayOfWeek.FRIDAY, "11:00", "12:00"),
                slot(DayOfWeek.FRIDAY, "13:00", "15:00")))
                .containsExactly("Slots 1 and 2 overlap", "Slots 1 and 3 overlap");
    }

    // GRP-7: touching slots share no time
    @Test
    void touchingOrOtherDaySlotsAreAllowed() {
        assertThat(slotErrors(slot(DayOfWeek.MONDAY, "14:00", "16:00"), slot(DayOfWeek.MONDAY, "16:00", "18:00"),
                slot(DayOfWeek.TUESDAY, "14:00", "16:00"))).isEmpty();
    }

    // GRP-7: a bad row is reported once, not also as an overlap
    @Test
    void badRowIsNotComparedForOverlaps() {
        // Rows 2 and 3 both fall inside row 1's 10:00-12:00
        assertThat(slotErrors(slot(DayOfWeek.MONDAY, "10:00", "12:00"), slot(DayOfWeek.MONDAY, "11:30", "11:00"),
                slot(DayOfWeek.MONDAY, "11:00", "11:10")))
                .containsExactly("Slot 2: the end time must be after the start time",
                        "Slot 3: please choose times from the list (08:00 to 23:00)");
    }

    // GRP-7: every message is filed under the "slots" field, so the page lists them together
    @Test
    void messagesAreFiledUnderSlots() {
        Errors errors = validate(slot(DayOfWeek.MONDAY, "12:00", "10:00"), new SlotForm());

        assertThat(errors.getErrorCount()).isEqualTo(2);
        assertThat(errors.getFieldErrors()).extracting(FieldError::getField).containsOnly("slots");
        assertThat(errors.getFieldErrors()).extracting(FieldError::getCode)
                .containsExactly("slot.endBeforeStart", "slot.incomplete");
    }

    private List<String> slotErrors(SlotForm... slots) {
        return validate(slots).getFieldErrors("slots").stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .toList();
    }

    private Errors validate(SlotForm... slots) {
        StudyGroupForm form = new StudyGroupForm();
        form.setSlots(List.of(slots));
        Errors errors = new BeanPropertyBindingResult(form, "form");
        validator.validate(form, errors);
        return errors;
    }

    private static SlotForm slot(DayOfWeek day, String start, String end) {
        SlotForm slot = new SlotForm();
        slot.setDayOfWeek(day);
        slot.setStartTime(start == null ? null : LocalTime.parse(start));
        slot.setEndTime(end == null ? null : LocalTime.parse(end));
        return slot;
    }
}

package com.studybuddy.service.studygroup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;

import com.studybuddy.config.StudyGroupSettings;
import com.studybuddy.dto.SlotForm;
import com.studybuddy.dto.StudyGroupForm;

/**
 * StudyGroupFormValidator with the settings bound from the real application.properties.
 * The context holds only the validator and its settings: no auto-configuration, so no
 * database, no seeders and no web server.
 */
@SpringBootTest(classes = StudyGroupFormValidator.class)
class StudyGroupFormValidatorPropertiesTest {

    @Autowired
    private StudyGroupFormValidator validator;
    @Autowired
    private StudyGroupSettings settings;
    @Autowired
    private ApplicationContext context;

    // GRP-7
    @Test
    void settingsComeFromApplicationProperties() {
        assertThat(settings.maxAvailabilitySlots()).isEqualTo(3);
        assertThat(settings.earliestSlotTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(settings.latestSlotTime()).isEqualTo(LocalTime.of(23, 0));
        assertThat(settings.slotStepMinutes()).isEqualTo(15);
        assertThat(settings.defaultSort()).isEqualTo(GroupSort.NEWEST);

        // The light context really has no database and no seeders
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(CommandLineRunner.class)).isEmpty();
    }

    // GRP-7
    @Test
    void configuredValidatorUsesTheseSettings() {
        assertThat(validate(slot("08:00", "23:00")).hasErrors()).isFalse();
        assertThat(validate(slot("07:45", "10:00")).getFieldError("slots").getDefaultMessage())
                .isEqualTo("Slot 1: please choose times from the list (08:00 to 23:00)");
        assertThat(validate(slot("08:00", "09:00"), slot("10:00", "11:00"), slot("12:00", "13:00"),
                slot("14:00", "15:00")).getFieldError("slots").getDefaultMessage())
                .isEqualTo("A group can have at most 3 time slots");
    }

    private Errors validate(SlotForm... slots) {
        StudyGroupForm form = new StudyGroupForm();
        form.setSlots(List.of(slots));
        Errors errors = new BeanPropertyBindingResult(form, "form");
        validator.validate(form, errors);
        return errors;
    }

    private static SlotForm slot(String start, String end) {
        SlotForm slot = new SlotForm();
        slot.setDayOfWeek(DayOfWeek.MONDAY);
        slot.setStartTime(LocalTime.parse(start));
        slot.setEndTime(LocalTime.parse(end));
        return slot;
    }
}

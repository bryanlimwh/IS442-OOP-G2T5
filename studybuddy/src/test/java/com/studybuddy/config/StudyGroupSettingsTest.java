package com.studybuddy.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.studybuddy.service.studygroup.GroupSort;

/**
 * The study group settings record: the From / To time list and the startup checks. Plain
 * unit test; the values in application.properties are checked by StudyGroupFormValidatorPropertiesTest.
 */
class StudyGroupSettingsTest {

    private static final LocalTime EIGHT = LocalTime.of(8, 0);
    private static final LocalTime ELEVEN_PM = LocalTime.of(23, 0);

    // GRP-7
    @Test
    void timeOptionsRunFromEarliestToLatestInSteps() {
        List<LocalTime> times = new StudyGroupSettings(3, EIGHT, ELEVEN_PM, 15, GroupSort.NEWEST).timeOptions();

        // 15 hours of 4 steps each, plus 23:00 itself
        assertThat(times).hasSize(61);
        assertThat(times.get(0)).isEqualTo(EIGHT);
        assertThat(times.get(1)).isEqualTo(LocalTime.of(8, 15));
        assertThat(times.get(times.size() - 1)).isEqualTo(ELEVEN_PM);
        assertThat(times).doesNotContain(LocalTime.of(7, 45), LocalTime.of(8, 10), LocalTime.of(23, 15));
    }

    // GRP-7: LocalTime wraps round at midnight; the list stops instead of starting again
    @Test
    void timeOptionsStopBeforeMidnight() {
        List<LocalTime> times = new StudyGroupSettings(3, LocalTime.of(23, 0), LocalTime.of(23, 59), 30,
                GroupSort.NEWEST).timeOptions();

        assertThat(times).containsExactly(LocalTime.of(23, 0), LocalTime.of(23, 30));
    }

    // GRP-7
    @Test
    void unusableSettingsAreRefused() {
        assertThatThrownBy(() -> new StudyGroupSettings(0, EIGHT, ELEVEN_PM, 15, GroupSort.NEWEST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("studybuddy.groups.max-availability-slots must be at least 1");
        assertThatThrownBy(() -> new StudyGroupSettings(3, ELEVEN_PM, ELEVEN_PM, 15, GroupSort.NEWEST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("studybuddy.groups.earliest-slot-time must be before studybuddy.groups.latest-slot-time");
        assertThatThrownBy(() -> new StudyGroupSettings(3, null, ELEVEN_PM, 15, GroupSort.NEWEST))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StudyGroupSettings(3, EIGHT, ELEVEN_PM, 0, GroupSort.NEWEST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("studybuddy.groups.slot-step-minutes must be at least 1");
        assertThatThrownBy(() -> new StudyGroupSettings(3, EIGHT, ELEVEN_PM, 15, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("studybuddy.groups.default-sort is required");

        // The smallest usable values are accepted
        assertThat(new StudyGroupSettings(1, EIGHT, LocalTime.of(8, 1), 1, GroupSort.NAME).timeOptions())
                .containsExactly(EIGHT, LocalTime.of(8, 1));
    }

    // GRP-7: leaving latest-slot-time out of application.properties stops the app, as a missing earliest time does
    @Test
    void missingLatestSlotTimeIsRefused() {
        assertThatThrownBy(() -> new StudyGroupSettings(3, EIGHT, null, 15, GroupSort.NEWEST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("studybuddy.groups.earliest-slot-time must be before studybuddy.groups.latest-slot-time");
    }
}

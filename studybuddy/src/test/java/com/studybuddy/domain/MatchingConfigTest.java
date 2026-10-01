package com.studybuddy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.studybuddy.domain.enums.MatchingStrategyType;
import com.studybuddy.exception.InvalidMatchingConfigException;

class MatchingConfigTest {

    private final MatchingConfig config = new MatchingConfig(MatchingStrategyType.BALANCED, 20, 20, 20, 20, 20, 120);

    @Test
    void sharesAreEachWeightOverTheTotal() {
        config.update(MatchingStrategyType.BALANCED, 1, 3, 0, 0, 0, 120);

        assertThat(config.getTotalWeight()).isEqualTo(4);
        assertThat(config.sharePercent(config.getCourseWeight())).isEqualTo(25);
        assertThat(config.sharePercent(config.getAvailabilityWeight())).isEqualTo(75);
    }

    @Test
    void rejectsAllZeroWeightsAndLeavesSettingsUnchanged() {
        assertThatThrownBy(() -> config.update(MatchingStrategyType.COURSE_FIRST, 0, 0, 0, 0, 0, 60))
                .isInstanceOf(InvalidMatchingConfigException.class);

        assertThat(config.getStrategy()).isEqualTo(MatchingStrategyType.BALANCED);
        assertThat(config.getTargetSessionMinutes()).isEqualTo(120);
    }

    @Test
    void rejectsNegativeWeightsMissingStrategyAndNonPositiveSessionLength() {
        assertThatThrownBy(() -> config.update(MatchingStrategyType.BALANCED, -1, 20, 20, 20, 20, 120))
                .isInstanceOf(InvalidMatchingConfigException.class);
        assertThatThrownBy(() -> config.update(null, 20, 20, 20, 20, 20, 120))
                .isInstanceOf(InvalidMatchingConfigException.class);
        assertThatThrownBy(() -> config.update(MatchingStrategyType.BALANCED, 20, 20, 20, 20, 20, 0))
                .isInstanceOf(InvalidMatchingConfigException.class);
    }
}

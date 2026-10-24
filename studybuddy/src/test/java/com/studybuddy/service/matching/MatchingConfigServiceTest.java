package com.studybuddy.service.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.studybuddy.entity.MatchingConfig;
import com.studybuddy.entity.enums.MatchingStrategyType;
import com.studybuddy.exception.InvalidMatchingConfigException;
import com.studybuddy.matching.MatchingConfigForm;
import com.studybuddy.repository.MatchingConfigRepository;

import jakarta.persistence.EntityManager;

/**
 * MatchingConfigService against an empty in-memory H2 (no seeders, so no config row unless a
 * test saves one); each test is rolled back. The saved settings are Balanced, every weight 20,
 * 120 minutes. flushAndClear() writes changes to the database and forgets cached objects, so
 * the checks after it read what was really saved.
 */
@DataJpaTest
@Import(MatchingConfigService.class)
class MatchingConfigServiceTest {

    @Autowired
    private MatchingConfigService service;
    @Autowired
    private MatchingConfigRepository repository;
    @Autowired
    private EntityManager entityManager;

    // A3, A4
    @Test
    void getCurrentReturnsTheSavedSettings() {
        Long id = saveDefaults().getId();

        MatchingConfig current = service.getCurrent();

        assertThat(current.getId()).isEqualTo(id);
        assertThat(current.getStrategy()).isEqualTo(MatchingStrategyType.BALANCED);
        assertThat(current.getTotalWeight()).isEqualTo(100);
        assertThat(current.getTargetSessionMinutes()).isEqualTo(120);
    }

    // A3: the seeder normally creates the row; without it nothing can be matched
    @Test
    void getCurrentWithoutSettingsFails() {
        assertThatThrownBy(service::getCurrent)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No matching config found; MatchingConfigDataSeeder should create it at startup");
    }

    // A3: if there were ever two rows, the first one saved is the one in use
    @Test
    void severalRowsUseTheFirstOne() {
        Long first = saveDefaults().getId();
        repository.save(new MatchingConfig(MatchingStrategyType.COURSE_FIRST, 1, 1, 1, 1, 1, 30));

        assertThat(service.getCurrent().getId()).isEqualTo(first);
        assertThat(service.getCurrent().getStrategy()).isEqualTo(MatchingStrategyType.BALANCED);
    }

    // A3, A4, MAT-1
    @Test
    void updateSavesTheNewSettings() {
        saveDefaults();

        service.update(new MatchingConfigForm(MatchingStrategyType.AVAILABILITY_FIRST, 0, 3, 1, 0, 2, 90));
        flushAndClear();

        MatchingConfig saved = service.getCurrent();
        assertThat(saved.getStrategy()).isEqualTo(MatchingStrategyType.AVAILABILITY_FIRST);
        assertThat(saved.getCourseWeight()).isZero();
        assertThat(saved.getAvailabilityWeight()).isEqualTo(3);
        assertThat(saved.getStudyModeWeight()).isEqualTo(1);
        assertThat(saved.getStudyGoalWeight()).isZero();
        assertThat(saved.getGroupSizeWeight()).isEqualTo(2);
        assertThat(saved.getTargetSessionMinutes()).isEqualTo(90);
        assertThat(repository.count()).isEqualTo(1);
    }

    // A3, MAT-1: each refusal has its own message and leaves the saved settings as they were
    @Test
    void refusedUpdatesChangeNothing() {
        saveDefaults();
        flushAndClear();

        assertRefused(new MatchingConfigForm(null, 20, 20, 20, 20, 20, 120), "Choose a matching strategy.");
        assertRefused(new MatchingConfigForm(MatchingStrategyType.COURSE_FIRST, 20, -1, 20, 20, 20, 120),
                "Weights can't be negative.");
        assertRefused(new MatchingConfigForm(MatchingStrategyType.COURSE_FIRST, 0, 0, 0, 0, 0, 120),
                "At least one criterion needs a weight above 0.");
        assertRefused(new MatchingConfigForm(MatchingStrategyType.COURSE_FIRST, 20, 20, 20, 20, 20, 0),
                "The study session length must be at least 1 minute.");
        assertRefused(new MatchingConfigForm(MatchingStrategyType.COURSE_FIRST, 20, 20, 20, 20, 20, -30),
                "The study session length must be at least 1 minute.");
    }

    // MAT-1: the smallest valid settings: one weight of 1 and a one-minute session
    @Test
    void smallestValidSettingsAreAccepted() {
        saveDefaults();

        service.update(new MatchingConfigForm(MatchingStrategyType.BALANCED, 0, 0, 0, 1, 0, 1));
        flushAndClear();

        assertThat(service.getCurrent().getTotalWeight()).isEqualTo(1);
        assertThat(service.getCurrent().getTargetSessionMinutes()).isEqualTo(1);
    }

    // A3: updating with no settings row fails the same way as reading
    @Test
    void updateWithoutSettingsFails() {
        assertThatThrownBy(() -> service.update(new MatchingConfigForm(MatchingStrategyType.BALANCED,
                20, 20, 20, 20, 20, 120)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(repository.count()).isZero();
    }

    private void assertRefused(MatchingConfigForm form, String message) {
        assertThatThrownBy(() -> service.update(form))
                .isInstanceOf(InvalidMatchingConfigException.class)
                .hasMessage(message);
        flushAndClear();

        MatchingConfig saved = service.getCurrent();
        assertThat(saved.getStrategy()).isEqualTo(MatchingStrategyType.BALANCED);
        assertThat(saved.getCourseWeight()).isEqualTo(20);
        assertThat(saved.getAvailabilityWeight()).isEqualTo(20);
        assertThat(saved.getTotalWeight()).isEqualTo(100);
        assertThat(saved.getTargetSessionMinutes()).isEqualTo(120);
    }

    private MatchingConfig saveDefaults() {
        return repository.save(new MatchingConfig(MatchingStrategyType.BALANCED, 20, 20, 20, 20, 20, 120));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}

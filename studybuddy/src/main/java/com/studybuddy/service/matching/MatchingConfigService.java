package com.studybuddy.service.matching;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.studybuddy.domain.MatchingConfig;
import com.studybuddy.matching.MatchingConfigForm;
import com.studybuddy.repository.MatchingConfigRepository;

/**
 * Reads and changes the matching engine's settings. The settings themselves
 * check that they are valid (see MatchingConfig.update()).
 */
@Service
public class MatchingConfigService {

    private final MatchingConfigRepository matchingConfigRepository;

    public MatchingConfigService(MatchingConfigRepository matchingConfigRepository) {
        this.matchingConfigRepository = matchingConfigRepository;
    }

    @Transactional(readOnly = true)
    public MatchingConfig getCurrent() {
        return matchingConfigRepository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new IllegalStateException(
                        "No matching config found; MatchingConfigDataSeeder should create it at startup"));
    }

    /**
     * Applies the admin's new settings.
     *
     * @throws com.studybuddy.exception.InvalidMatchingConfigException if the
     *         settings are invalid, in which case nothing is changed
     */
    @Transactional
    public MatchingConfig update(MatchingConfigForm form) {
        MatchingConfig config = getCurrent();
        config.update(form.strategy(), form.courseWeight(), form.availabilityWeight(), form.studyModeWeight(),
                form.studyGoalWeight(), form.groupSizeWeight(), form.targetSessionMinutes());
        return config;
    }
}

package com.studybuddy.config;

import com.studybuddy.entity.MatchingConfig;
import com.studybuddy.repository.MatchingConfigRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// Creates the single MatchingConfig row from application.properties, so
// matching works before an admin has changed anything. Doesn't depend on any
// other seeder's data.
//
// The database is in-memory, so the row is recreated from the properties on
// every start and an admin's changes last until the app stops.
@Component
@Order(4)
@EnableConfigurationProperties(MatchingDefaults.class)
public class MatchingConfigDataSeeder implements CommandLineRunner {

    private final MatchingConfigRepository matchingConfigRepository;
    private final MatchingDefaults defaults;

    public MatchingConfigDataSeeder(MatchingConfigRepository matchingConfigRepository, MatchingDefaults defaults) {
        this.matchingConfigRepository = matchingConfigRepository;
        this.defaults = defaults;
    }

    @Override
    public void run(String... args) {
        if (matchingConfigRepository.count() == 0) {
            MatchingConfig config = matchingConfigRepository.save(defaults.toConfig());
            System.out.println("Seeded matching config: " + config.getStrategy() + " strategy, "
                    + config.getTargetSessionMinutes() + "-minute target session");
        }
    }
}

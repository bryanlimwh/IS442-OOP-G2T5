package com.studybuddy.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.studybuddy.entity.MatchingConfig;

public interface MatchingConfigRepository extends JpaRepository<MatchingConfig, Long> {

    // There is only ever one row, created by MatchingConfigDataSeeder.
    Optional<MatchingConfig> findFirstByOrderByIdAsc();
}

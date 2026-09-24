package com.studybuddy.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.studybuddy.domain.StudyPreference;

public interface StudyPreferenceRepository extends JpaRepository<StudyPreference, Long> {
}

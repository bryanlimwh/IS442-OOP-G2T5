package com.studybuddy.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.studybuddy.entity.StudyPreference;

public interface StudyPreferenceRepository extends JpaRepository<StudyPreference, Long> {
}

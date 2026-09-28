package com.studybuddy.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.studybuddy.domain.StudyGroup;

public interface StudyGroupRepository extends JpaRepository<StudyGroup, Long> {
}

package com.studybuddy.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.studybuddy.entity.StudyGroup;

public interface StudyGroupRepository extends JpaRepository<StudyGroup, Long> {
}

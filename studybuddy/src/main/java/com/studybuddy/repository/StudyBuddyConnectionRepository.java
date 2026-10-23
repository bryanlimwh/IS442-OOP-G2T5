package com.studybuddy.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.studybuddy.entity.StudyBuddyConnection;

public interface StudyBuddyConnectionRepository extends JpaRepository<StudyBuddyConnection, Long> {

    // SELECT * FROM study_buddy_connection WHERE student1_id = ? OR student2_id = ?
    // Pass the same id twice to get every connection a student is in, whichever side they're on.
    List<StudyBuddyConnection> findByStudent1_userIdOrStudent2_userId(Long student1Id, Long student2Id);

    // True if a connection exists with these two students in exactly this order.
    // The pair is unordered, so callers check both orders (see StudyBuddyConnectionService).
    boolean existsByStudent1_userIdAndStudent2_userId(Long student1Id, Long student2Id);
}

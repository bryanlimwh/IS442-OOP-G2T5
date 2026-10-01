package com.studybuddy.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.studybuddy.entity.MatchRequest;

// Extending JpaRepository<Entity, IDType> provides the following methods:
// save(), findAll(), findById(), deleteById()
public interface MatchRequestRepository extends JpaRepository<MatchRequest, Long>{
    // Spring generates the implementation at runtime, so there's no class to write

    // Derived Query Methods
    // Spring Data reads the method name and builds the defined SQL queries
    // Add derived methods when business logic requires searching by something other than the row ID (Primary Key)

    // SELECT * FROM match_request WHERE sender_id (MatchRequest.sender.userid) = ?
    List<MatchRequest> findBySender_userId(Long senderId);

    // SELECT * FROM match_request WHERE recipient_id (MatchRequest.recipient.userid) = ?
    List<MatchRequest> findByRecipient_userId(Long recipientId);
}

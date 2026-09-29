package com.studybuddy.service.studybuddymatching;

import java.util.List;

import org.springframework.stereotype.Service;

import com.studybuddy.entity.Student;
import com.studybuddy.entity.MatchRequest;
import com.studybuddy.repository.MatchRequestRepository;

import org.springframework.transaction.annotation.Transactional;

@Service
public class MatchRequestService {

    // Stores the object containing the reference
    // to the 'match_request' table in the H2 database
    private final MatchRequestRepository repo;

    // Spring's IoC container (the ApplicationContext) will create the MatchRequestRepository object automatically
    public MatchRequestService(MatchRequestRepository repo) {
        this.repo = repo;
    }

    // @Transactional specified to treat all H2 database operations
    // executed within the method as a single transaction,
    // meaning that all database changes made during the execution of the method
    // are rolled back if any one of its database operations fails

    // Creates a new entry in the 'match_request' table
    // using the information in the MatchRequest object
    @Transactional
    public boolean createRequest(Student sender, Student recipient, String message) {
        // Checks if the sender and recipient are the same student
        if (sender.equals(recipient)) {
            throw new IllegalArgumentException("Same sender and recipient");
        }

        MatchRequest req = new MatchRequest(sender, recipient, message);

        try {
            repo.save(req);
        } catch (Exception e) {
            System.out.println(e);
            return false;
        }
        return true;
    }

    // Retrives a row in the 'match_request' table with the specified id
    @Transactional
    public MatchRequest getRequestById(Long userId, Long requestId) {

        // Load the row from the 'match_request' table where 'id' = requestId
        // as a Java object (SELECT ... WHERE id = ?)
        MatchRequest req = repo.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found: " + requestId));
        
        // Checks if the user requesting the match request is either its sender or recipient
        if (req.getSender().getUserId() != userId || req.getRecipient().getUserId() != userId) {
            // Can consider creating an appropriate custom exception instead
            throw new IllegalArgumentException("Unauthorised access to match request");
        }

        return req;          
    }

    // Retrieves all rows in the 'match_request' table
    // where 'sender_id' = userId
    @Transactional
    public List<MatchRequest> getMatchRequestBySender(Long userId) {
        List<MatchRequest> reqList = repo.findBySender_userId(userId);

        if (reqList.isEmpty()) {
            new IllegalArgumentException("No match requests sent by user id: " + userId);
        }

        return reqList;
    }

    // Retrieves all rows in the 'match_request' table
    // where 'recipient_id' = userId
    @Transactional
    public List<MatchRequest> getMatchRequestByRecipient(Long userId) {
        List<MatchRequest> reqList = repo.findByRecipient_userId(userId);

        if (reqList.isEmpty()) {
            new IllegalArgumentException("No match requests received for user id: " + userId);
        }

        return reqList;
    }

    // Updates the 'status' column match request record in the 'match_request' table to "ACCEPTED"
    @Transactional
    // Called by MatchRequestController when the URL to accept match requests is accessed
    // UPDATE the row in the 'match_request' table whose 'id' = id
    public MatchRequest acceptRequest(Long id) {
        // 1. Load the row from H2 as a Java object (SELECT ... WHERE id = ?)
        MatchRequest req = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Request not found: " + id));

        // 2. Apply the business rule; the entity checks that the change is valid
        req.accept();

        // 3. Optional: dirty checking would save it anyway, but calling save() makes the intent clear
        // Hibernate runs: UPDATE match_request SET status='ACCEPTED' WHERE id=?
        return repo.save(req);

        // TO IMPLEMENT:
        // Create a new StudyBuddyConnection and save it to the database
        // 
        // StudyBuddyConnection connectionEntry = new StudyBuddyConnection(
        //     this.sender,
        //     this.recipient
        // );
    }
}
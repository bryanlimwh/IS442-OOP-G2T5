// Same package as MatchRequestRepository, so the test sits "next to" the class it tests
// (it lives under src/test/java instead of src/main/java).
package com.studybuddy.repository;

// Static imports let us write assertEquals(...) instead of Assertions.assertEquals(...).
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Optional is the "may or may not contain a value" wrapper returned by findById()
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// @DataJpaTest starts only the database/JPA part of Spring (faster than the full app)
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.studybuddy.entity.Student;
import com.studybuddy.entity.enums.MatchRequestStatus;
import com.studybuddy.entity.MatchRequest;

/*
 * @DataJpaTest:
 *  - Starts an in-memory H2 database just for this test run.
 *  - Creates tables from your @Entity classes.
 *  - Loads only repositories/JPA (no controllers, services or Spring Security).
 *  - Wraps EACH test in a transaction that is ROLLED BACK afterwards,
 *    so test data never leaks into other tests or your real database file.
 */
@DataJpaTest
class MatchRequestRepositoryTest {

    // @Autowired asks Spring to inject (supply) a ready-made object
    @Autowired
    // Spring creates the MatchRequestRepository and injects it here
    private MatchRequestRepository matchRequestRepository;

    @Autowired
    // Needed because a MatchRequest must reference two saved Students.
    private StudentRepository studentRepository;

    private Student sender;
    private Student recipient;
    private MatchRequest request;

    // ---------- Helper method ----------
    // Builds and saves a Student so each test doesn't repeat the constructor
    // save() runs an INSERT into the 'user_account' table in H2
    // and returns the Student with its generated userId
    private Student createStudent(String email, String name, String contact) {
        return studentRepository.save(
            new Student(
                email,
                "dummyHash",
                name,
                "School of Computing and Information Systems",
                "Information Systems",
                3,
                contact
            )
        );
    }

    // @BeforeEach marks a method that JUnit should run before EVERY @Test method
    @BeforeEach
    void setUp() {
        // Creates the Student acting as the match request sender
        sender = createStudent("sender@email.com", "Amy Blythe", "12345678");
        // Creates the Student acting as the match request recipient
        recipient = createStudent("recipient@email.com", "Charles Dickens", "87654321");

        // Creates the object in Java memory only; nothing is in H2 yet.
        request = new MatchRequest(
            sender,
            recipient,
            "Would you like to study together?"
        );
    }

    // ---------- Test 1: Save() & findById() ----------
    // @Test marks a method that JUnit should run as a test.
    @Test
    void saveMatchRequestAndFindById() {
        // saveAndFlush() = save + immediately send the INSERT SQL to H2,
        // so any database error (e.g. bad mapping) appears here, not later
        MatchRequest savedMatchRequest = matchRequestRepository.saveAndFlush(request);

        // H2 generates the ID of the match request on insert
        // A non-null ID proves the row was written
        assertNotNull(savedMatchRequest.getId());

        // Retrieves the row created in the H2 database for the match request using its primary key
        Optional<MatchRequest> retrieved = matchRequestRepository.findById(savedMatchRequest.getId());

        // Verifies that the Row exists
        assertTrue(retrieved.isPresent());
        // Verifies that the MatchRequest.status == "PENDING"
        assertEquals(MatchRequestStatus.PENDING, retrieved.get().getStatus());
        // Verifies that the sender is the correct Student
        assertEquals(sender.getUserId(), retrieved.get().getSender().getUserId());
    }


    // ---------- Test 2: findBySender ----------
    @Test
    void findBySenderReturnsSendersRequests() {
        // Saves a new MatchRequest to the H2 database
        matchRequestRepository.saveAndFlush(request);

        // Runs SELECT ... WHERE sender_id = ? 
        List<MatchRequest> results =
                matchRequestRepository.findBySender_userId(sender.getUserId());

        // ASSERT: exactly one request belongs to this sender
        assertEquals(1, results.size());
        // Verifies the returned request really belongs to this sender
        assertEquals(sender.getUserId(), results.get(0).getSender().getUserId());
    }

    // ---------- Test 3: findByRecipient ----------
    @Test
    void findByRecipientReturnsRecipientsRequests() {
        // Saves a new MatchRequest to the H2 database
        matchRequestRepository.saveAndFlush(request);

        // Runs SELECT ... WHERE recipient_id = ? 
        List<MatchRequest> results =
                matchRequestRepository.findByRecipient_userId(recipient.getUserId());

        // ASSERT: exactly one request belongs to this recipient
        assertEquals(1, results.size());
        // Verifies the returned request really belongs to this recipient
        assertEquals(recipient.getUserId(), results.get(0).getRecipient().getUserId());
    }

    // ---------- Test 4: deleteById ----------
    @Test
    void deleteMatchRequestById() {
        // saveAndFlush() = save + immediately send the INSERT SQL to H2,
        // so any database error (e.g. bad mapping) appears here, not later
        MatchRequest savedMatchRequest = matchRequestRepository.saveAndFlush(request);

        // Deletes the created match request from H2 using its ID
        matchRequestRepository.deleteById(savedMatchRequest.getId());

        // Retrieves the row created in the H2 database for the match request using its primary key
        Optional<MatchRequest> retrieved = matchRequestRepository.findById(savedMatchRequest.getId());

        // Verifies that the row does not exists
        assertTrue(retrieved.isEmpty());
    }

    // ---------- Test 5: exists...AndStatus() finds a matching PENDING request ----------
    @Test
    void existsReturnsTrueForPendingRequestInSameDirection() {
        // Saves the PENDING request from setUp() (sender -> recipient) to H2
        matchRequestRepository.saveAndFlush(request);

        // Verifies the query finds a row where sender_id, recipient_id AND status all match
        assertTrue(matchRequestRepository.existsBySender_userIdAndRecipient_userIdAndStatus(
                sender.getUserId(), recipient.getUserId(), MatchRequestStatus.PENDING));
    }

    // ---------- Test 6: exists...AndStatus() only checks one direction ----------
    @Test
    void existsReturnsFalseWhenSenderAndRecipientAreSwapped() {
        // Saves the request from setUp(), which only goes sender -> recipient
        matchRequestRepository.saveAndFlush(request);

        // Verifies the query does NOT match recipient -> sender.
        // MatchRequestService relies on this when it checks the reverse direction separately
        assertFalse(matchRequestRepository.existsBySender_userIdAndRecipient_userIdAndStatus(
                recipient.getUserId(), sender.getUserId(), MatchRequestStatus.PENDING));
    }

    // ---------- Test 7: exists...AndStatus() filters by status ----------
    @Test
    void existsReturnsFalseWhenRequestIsNoLongerPending() {
        // Changes the setUp() request's status from PENDING to ACCEPTED before saving
        request.accept();
        // INSERTs the row with status = 'ACCEPTED'
        matchRequestRepository.saveAndFlush(request);

        // Verifies an ACCEPTED request is not counted as PENDING
        assertFalse(matchRequestRepository.existsBySender_userIdAndRecipient_userIdAndStatus(
                sender.getUserId(), recipient.getUserId(), MatchRequestStatus.PENDING));
    }
}
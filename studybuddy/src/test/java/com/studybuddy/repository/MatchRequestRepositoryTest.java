// Same package as MatchRequestRepository, so the test sits "next to" the class it tests
// (it lives under src/test/java instead of src/main/java).
package com.studybuddy.repository;

// Static imports let us write assertEquals(...) instead of Assertions.assertEquals(...).
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Optional is the "may or may not contain a value" wrapper returned by findById()
import java.util.List;
import java.util.Optional;


import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// @DataJpaTest starts only the database/JPA part of Spring (faster than the full app)
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.studybuddy.domain.Student;
import com.studybuddy.domain.enums.MatchRequestStatus;
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

    // ---------- Test 1: save and read by ID ----------
    // @Test marks a method that JUnit should run as a test.
    @Test
    void saveAndReadMatchRequest() {
        Student sender = createStudent("sender@email.com", "Amy Blythe", "12345678");
        Student recipient = createStudent("recipient@email.com", "Charles Dickens", "87654321");

        // Creates the object in Java memory only; nothing is in H2 yet.
        MatchRequest request = new MatchRequest(
                sender,
                recipient,
                "Would you like to study together?"
        );


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

    // ---------- Test 2: your derived query method ----------
    @Test
    void findBySenderReturnsSendersRequests() {
        Student sender = createStudent("a@email.com", "Alex", "11111111");
        Student recipient = createStudent("b@email.com", "Ben", "22222222");

        // Saves a new MatchRequest to the H2 database
        matchRequestRepository.saveAndFlush(new MatchRequest(sender, recipient, "Study IS442?"));

        // Runs SELECT ... WHERE sender_id = ? 
        List<MatchRequest> results =
                matchRequestRepository.findBySender_userId(sender.getUserId());

        // ASSERT: exactly one request belongs to this sender.
        assertEquals(1, results.size());
    }
}
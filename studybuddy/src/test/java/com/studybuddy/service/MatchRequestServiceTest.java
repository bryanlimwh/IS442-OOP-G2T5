package com.studybuddy.service;

// AssertJ assertions: assertThat(...) checks a value,
// assertThatThrownBy(...) checks that a piece of code throws an exception
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.studybuddy.entity.Student;
import com.studybuddy.entity.enums.MatchRequestStatus;
import com.studybuddy.exception.DuplicateMatchRequestException;
import com.studybuddy.exception.MatchRequestNotFoundException;
import com.studybuddy.exception.NotMatchRequestParticipantException;
import com.studybuddy.exception.StudentNotFoundException;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.service.studybuddymatching.MatchRequestService;
import com.studybuddy.service.studybuddymatching.MatchRequestSummary;

/**
 * Integration test for MatchRequestService using the real repositories and an in-memory H2.
 *
 * Checks the match request rules:
 * - students act as themselves and only see their own requests
 * - only the recipient can accept, and only once
 * - contact numbers stay hidden until a request is accepted
 * - invalid input (unknown students/requests, sending to yourself) is rejected
 *
 * Creates its own test students, so it does not depend on the data seeders.
 */

// Starts only JPA + an in-memory H2 database (no controllers, security or seeders).
// Each test runs in a transaction that is rolled back afterwards.
@DataJpaTest
// @DataJpaTest only loads repositories, so the service must be added explicitly
@Import(MatchRequestService.class)
class MatchRequestServiceTest {

    // Test students' contact numbers, checked when a request is accepted
    private static final String ALEX_CONTACT = "91234567";
    private static final String BEN_CONTACT = "92345678";

    // The real service being tested, with real repositories injected by Spring
    @Autowired
    private MatchRequestService service;

    // Used to save the test students into H2
    @Autowired
    private StudentRepository studentRepository;

    // Test students: Alex = sender, Ben = recipient, Chloe = unrelated student
    private Student alex;
    private Student ben;
    private Student chloe;

    // ID of the request Alex sends Ben during setup
    private Long requestId;

    // ---------- Helper method ----------
    // Builds and saves a test Student (INSERT into user_account + student tables)
    private Student saveStudent(String email, String name, String contact) {
        return studentRepository.save(new Student(
                email,
                "dummyHash",  // Placeholder: this test doesn't check passwords
                name,
                "School of Computing and Information Systems",
                "Information Systems",
                2,
                contact));
    }

    // ---------- Setup ----------
    // Runs before EVERY test, so each test starts with the same fresh data
    @BeforeEach
    void setUp() {
        // Saves 3 students into H2; save() returns them with their generated user IDs
        alex = saveStudent("alex@test.com", "Alex Tan", ALEX_CONTACT);
        ben = saveStudent("ben@test.com", "Ben Koh", BEN_CONTACT);
        chloe = saveStudent("chloe@test.com", "Chloe Lim", "93456789");

        // Alex sends Ben a request; createRequest() returns the saved request's summary,
        // so its generated ID can be stored directly
        requestId = service.createRequest(alex.getEmail(), ben.getUserId(), "Study IS442 together?").requestId();

        // Verifies H2 generated an ID, proving the row was saved
        assertThat(requestId).isNotNull();
    }

    // ================= Creating and viewing requests =================

    @Test
    void newRequestIsPendingWithCorrectDetails() {
        // Reads the request as the sender
        MatchRequestSummary summary = service.getRequestById(alex.getEmail(), requestId);

        // Verifies a new request starts as PENDING
        assertThat(summary.status()).isEqualTo(MatchRequestStatus.PENDING);
        // Verifies the message was saved exactly as sent
        assertThat(summary.message()).isEqualTo("Study IS442 together?");
        // Verifies BaseEntity's @PrePersist set the creation time
        assertThat(summary.createdAt()).isNotNull();
    }

    @Test
    void senderSeesRequestInSentList() {
        // Verifies Alex's "sent" list contains exactly 1 request, with the correct ID
        assertThat(service.getMatchRequestBySender(alex.getEmail()))
                .hasSize(1)
                .first()
                .extracting(MatchRequestSummary::requestId)
                .isEqualTo(requestId);
    }

    @Test
    void recipientSeesRequestInReceivedList() {
        // Verifies Ben's "received" list contains exactly 1 request, with the correct ID
        assertThat(service.getMatchRequestByRecipient(ben.getEmail()))
                .hasSize(1)
                .first()
                .extracting(MatchRequestSummary::requestId)
                .isEqualTo(requestId);
    }

    @Test
    void recipientCanViewRequestById() {
        // Verifies the recipient (not just the sender) can open the request
        assertThat(service.getRequestById(ben.getEmail(), requestId).message())
                .isEqualTo("Study IS442 together?");
    }

    // ================= Access control =================

    @Test
    void unrelatedStudentHasEmptyLists() {
        // Verifies Chloe neither sent nor received anything
        assertThat(service.getMatchRequestBySender(chloe.getEmail())).isEmpty();
        assertThat(service.getMatchRequestByRecipient(chloe.getEmail())).isEmpty();
    }

    @Test
    void unrelatedStudentCannotViewRequest() {
        // Verifies Chloe is blocked from viewing a request she is not part of (403 Forbidden)
        assertThatThrownBy(() -> service.getRequestById(chloe.getEmail(), requestId))
                .isInstanceOf(NotMatchRequestParticipantException.class);
    }

    @Test
    void unrelatedStudentCannotAcceptRequest() {
        // Verifies Chloe is blocked from accepting a request that was not sent to her
        assertThatThrownBy(() -> service.acceptRequest(chloe.getEmail(), requestId))
                .isInstanceOf(NotMatchRequestParticipantException.class);
    }

    @Test
    void senderCannotAcceptOwnRequest() {
        // Verifies only the RECIPIENT can accept; the sender is blocked
        assertThatThrownBy(() -> service.acceptRequest(alex.getEmail(), requestId))
                .isInstanceOf(NotMatchRequestParticipantException.class);
    }

    // ================= Accepting and contact numbers =================

    @Test
    void contactNumbersHiddenWhilePending() {
        // Verifies neither student can see the other's number before acceptance
        assertThat(service.getRequestById(alex.getEmail(), requestId).buddyContactNumber()).isNull();
        assertThat(service.getRequestById(ben.getEmail(), requestId).buddyContactNumber()).isNull();
    }

    @Test
    void acceptingRevealsEachOthersContactNumber() {
        // Ben (the recipient) accepts; the status is UPDATEd to ACCEPTED in H2
        MatchRequestSummary accepted = service.acceptRequest(ben.getEmail(), requestId);

        // Verifies the status changed
        assertThat(accepted.status()).isEqualTo(MatchRequestStatus.ACCEPTED);
        // Verifies Ben now sees Alex's number
        assertThat(accepted.buddyContactNumber()).isEqualTo(ALEX_CONTACT);
        // Verifies Alex now sees Ben's number (re-read from H2, proving the change was saved)
        assertThat(service.getRequestById(alex.getEmail(), requestId).buddyContactNumber())
                .isEqualTo(BEN_CONTACT);
    }

    @Test
    void cannotAcceptSameRequestTwice() {
        // First acceptance succeeds
        service.acceptRequest(ben.getEmail(), requestId);

        // Verifies a second acceptance is rejected by MatchRequest.accept(),
        // because the request is no longer PENDING
        assertThatThrownBy(() -> service.acceptRequest(ben.getEmail(), requestId))
                .isInstanceOf(IllegalStateException.class);
    }


    // ================= Rejecting requests =================

    @Test
    void recipientCanRejectRequest() {
        // Ben (the recipient) rejects; the status is UPDATEd to REJECTED in H2
        MatchRequestSummary rejected = service.rejectRequest(ben.getEmail(), requestId);

        // Verifies the returned summary shows the new status
        assertThat(rejected.status()).isEqualTo(MatchRequestStatus.REJECTED);
        // Verifies the change was saved (re-read as the sender)
        assertThat(service.getRequestById(alex.getEmail(), requestId).status())
                .isEqualTo(MatchRequestStatus.REJECTED);
    }

    @Test
    void contactNumbersStayHiddenAfterRejection() {
        // Ben rejects the request
        MatchRequestSummary rejected = service.rejectRequest(ben.getEmail(), requestId);

        // Verifies neither student sees the other's number, since the request was not accepted
        assertThat(rejected.buddyContactNumber()).isNull();
        assertThat(service.getRequestById(alex.getEmail(), requestId).buddyContactNumber()).isNull();
    }

    @Test
    void senderCannotRejectOwnRequest() {
        // Verifies only the RECIPIENT can reject; the sender is blocked (403 Forbidden)
        assertThatThrownBy(() -> service.rejectRequest(alex.getEmail(), requestId))
                .isInstanceOf(NotMatchRequestParticipantException.class);
    }

    @Test
    void unrelatedStudentCannotRejectRequest() {
        // Verifies Chloe cannot reject a request that was not sent to her
        assertThatThrownBy(() -> service.rejectRequest(chloe.getEmail(), requestId))
                .isInstanceOf(NotMatchRequestParticipantException.class);
    }

    @Test
    void cannotRejectSameRequestTwice() {
        // First rejection succeeds
        service.rejectRequest(ben.getEmail(), requestId);

        // Verifies a second rejection fails in MatchRequest.reject(),
        // because the request is no longer PENDING
        assertThatThrownBy(() -> service.rejectRequest(ben.getEmail(), requestId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotRejectAcceptedRequest() {
        // Ben accepts first
        service.acceptRequest(ben.getEmail(), requestId);

        // Verifies an ACCEPTED request can no longer be rejected
        assertThatThrownBy(() -> service.rejectRequest(ben.getEmail(), requestId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotAcceptRejectedRequest() {
        // Ben rejects first
        service.rejectRequest(ben.getEmail(), requestId);

        // Verifies a REJECTED request can no longer be accepted
        assertThatThrownBy(() -> service.acceptRequest(ben.getEmail(), requestId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectingUnknownRequestIsRejected() {
        // Verifies a request ID that doesn't exist cannot be rejected
        assertThatThrownBy(() -> service.rejectRequest(ben.getEmail(), 999_999L))
                .isInstanceOf(MatchRequestNotFoundException.class);
    }

    @Test
    void senderCanSendNewRequestAfterRejection() {
        // Ben rejects Alex's request, so it is no longer PENDING
        service.rejectRequest(ben.getEmail(), requestId);

        // Verifies Alex can send Ben a new request
        // (only PENDING requests or a StudyBuddyConnection block new ones)
        MatchRequestSummary newRequest = service.createRequest(alex.getEmail(), ben.getUserId(), "Try again?");

        // Verifies the new request is a separate PENDING request, not the rejected one
        assertThat(newRequest.status()).isEqualTo(MatchRequestStatus.PENDING);
        assertThat(newRequest.requestId()).isNotEqualTo(requestId);
    }


    // ================= Invalid input =================

    @Test
    void cannotSendRequestToSelf() {
        // Verifies a student cannot send a request to themselves
        assertThatThrownBy(() -> service.createRequest(alex.getEmail(), alex.getUserId(), "Hi me"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cannotSendRequestToUnknownRecipient() {
        // Verifies a recipient ID that doesn't exist in H2 is rejected
        assertThatThrownBy(() -> service.createRequest(alex.getEmail(), 999_999L, "Hello?"))
                .isInstanceOf(StudentNotFoundException.class);
    }

    @Test
    void unknownSenderEmailIsRejected() {
        // Verifies an email with no student account is rejected
        assertThatThrownBy(() -> service.createRequest("ghost@test.com", ben.getUserId(), "Hello?"))
                .isInstanceOf(StudentNotFoundException.class);
    }

    @Test
    void viewingUnknownRequestIsRejected() {
        // Verifies a request ID that doesn't exist cannot be viewed
        assertThatThrownBy(() -> service.getRequestById(alex.getEmail(), 999_999L))
                .isInstanceOf(MatchRequestNotFoundException.class);
    }

    @Test
    void acceptingUnknownRequestIsRejected() {
        // Verifies a request ID that doesn't exist cannot be accepted
        assertThatThrownBy(() -> service.acceptRequest(ben.getEmail(), 999_999L))
                .isInstanceOf(MatchRequestNotFoundException.class);
    }

    @Test
    void cannotSendDuplicatePendingRequest() {
        // Setup already created a PENDING request from Alex to Ben.
        // Verifies Alex cannot send Ben a second request while the first is PENDING
        assertThatThrownBy(() -> service.createRequest(alex.getEmail(), ben.getUserId(), "Again?"))
                .isInstanceOf(DuplicateMatchRequestException.class)
                .hasMessageContaining("already has a pending request");
    }

    @Test
    void recipientCannotSendRequestBackWhilePending() {
        // Setup already created a PENDING request from Alex to Ben.
        // Verifies Ben cannot send Alex a new request, and is told to accept Alex's instead
        assertThatThrownBy(() -> service.createRequest(ben.getEmail(), alex.getUserId(), "Study together?"))
                .isInstanceOf(DuplicateMatchRequestException.class)
                .hasMessageContaining("Accept it instead");
    }
}

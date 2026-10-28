package com.studybuddy.service.studybuddymatching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import com.studybuddy.entity.Student;
import com.studybuddy.entity.enums.MatchRequestStatus;
import com.studybuddy.repository.MatchRequestRepository;
import com.studybuddy.repository.StudentRepository;

import jakarta.persistence.EntityManager;

/**
 * Gaps in MatchRequestServiceTest (a teammate's file): sending again after a rejection the other
 * way, an inactive sender, the message's length, and that request summaries never carry a contact
 * number. Empty in-memory H2 with own data; each test is rolled back. Students Alex (91234567),
 * Ben (92345678) and Chloe (93456789); no requests at the start.
 */
@DataJpaTest
@Import({ MatchRequestService.class, StudyBuddyConnectionService.class })
class MatchRequestServiceGapTest {

    private static final String ALEX = "alex@test.com";
    private static final String BEN = "ben@test.com";

    @Autowired
    private MatchRequestService service;
    @Autowired
    private StudyBuddyConnectionService connectionService;
    @Autowired
    private MatchRequestRepository matchRequestRepository;
    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private EntityManager entityManager;

    private Student alex;
    private Student ben;
    private Student chloe;

    @BeforeEach
    void setUp() {
        alex = saveStudent(ALEX, "Alex Tan", "91234567");
        ben = saveStudent(BEN, "Ben Koh", "92345678");
        chloe = saveStudent("chloe@test.com", "Chloe Lim", "93456789");
    }

    // S7, S8, REQ-1: after Ben turns Alex down, Ben may still send Alex a request himself
    @Test
    void recipientCanSendBackAfterRejecting() {
        Long first = service.createRequest(ALEX, ben.getUserId(), "Study?").requestId();
        service.rejectRequest(BEN, first);

        MatchRequestSummary back = service.createRequest(BEN, alex.getUserId(), "Changed my mind");

        assertThat(back.status()).isEqualTo(MatchRequestStatus.PENDING);
        assertThat(back.sender().name()).isEqualTo("Ben Koh");
    }

    // S8, REQ-1, REQ-3: a new request after a rejection can be accepted and connects the pair
    @Test
    void newRequestAfterRejectionCanBeAccepted() {
        service.rejectRequest(BEN, service.createRequest(ALEX, ben.getUserId(), null).requestId());
        Long second = service.createRequest(ALEX, ben.getUserId(), null).requestId();

        service.acceptRequest(BEN, second);

        assertThat(connectionService.areConnected(alex, ben)).isTrue();
        assertThat(service.getMatchRequestBySender(ALEX)).extracting(MatchRequestSummary::status)
                .containsExactlyInAnyOrder(MatchRequestStatus.REJECTED, MatchRequestStatus.ACCEPTED);
    }

    // REQ-1, ACC-6, N-E1: pins current behaviour, update if fixed. A deactivated student can't log in,
    // but one still logged in (open sessions aren't ended) can still send requests
    @Test
    void deactivatedSenderCanStillSend() {
        alex.setActive(false);
        flushAndClear();

        MatchRequestSummary sent = service.createRequest(ALEX, chloe.getUserId(), "Still here");

        assertThat(sent.status()).isEqualTo(MatchRequestStatus.PENDING);
        assertThat(matchRequestRepository.count()).isEqualTo(1);
    }

    // S7: the message is optional
    @Test
    void requestWithoutMessageIsSaved() {
        Long id = service.createRequest(ALEX, ben.getUserId(), null).requestId();
        flushAndClear();

        assertThat(service.getRequestById(BEN, id).message()).isNull();
    }

    // S7, K4: the database is the service's limit; the controller checks first (K4 fixed 2026-10-28).
    // At the service, 255 characters fit and 256 are refused
    @Test
    void messageOver255CharactersFailsInDatabase() {
        Long id = service.createRequest(ALEX, ben.getUserId(), "M".repeat(255)).requestId();
        flushAndClear();
        assertThat(service.getRequestById(ALEX, id).message()).hasSize(255);

        assertThatThrownBy(() -> service.createRequest(ALEX, chloe.getUserId(), "M".repeat(256)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // S7, S8, REQ-4: a request summary has no contact number, before or after it is accepted
    @Test
    void requestSummariesNeverCarryAContactNumber() {
        MatchRequestSummary created = service.createRequest(ALEX, ben.getUserId(), "Study?");
        MatchRequestSummary accepted = service.acceptRequest(BEN, created.requestId());

        assertThat(Arrays.stream(MatchRequestSummary.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("requestId", "sender", "recipient", "message", "status", "createdAt");
        List<MatchRequestSummary> all = List.of(created, accepted, service.getMatchRequestBySender(ALEX).get(0),
                service.getMatchRequestByRecipient(BEN).get(0), service.getRequestById(BEN, created.requestId()));
        assertThat(all).allSatisfy(summary -> assertThat(summary.toString())
                .doesNotContain("91234567").doesNotContain("92345678"));
    }

    // S9, REQ-5: ending the connection deletes it, but the accepted request stays in both lists as history
    @Test
    void acceptedRequestStaysAfterTheConnectionEnds() {
        Long id = service.createRequest(ALEX, ben.getUserId(), "Study?").requestId();
        service.acceptRequest(BEN, id);
        Long connectionId = connectionService.getConnections(ALEX).get(0).connectionId();

        connectionService.endConnection(BEN, connectionId);
        flushAndClear();

        assertThat(connectionService.areConnected(alex, ben)).isFalse();
        assertThat(service.getMatchRequestBySender(ALEX)).extracting(MatchRequestSummary::status)
                .containsExactly(MatchRequestStatus.ACCEPTED);
        assertThat(service.getMatchRequestByRecipient(BEN)).extracting(MatchRequestSummary::status)
                .containsExactly(MatchRequestStatus.ACCEPTED);
    }

    private Student saveStudent(String email, String name, String contact) {
        return studentRepository.save(new Student(email, "dummyHash", name,
                "School of Computing and Information Systems", "Information Systems", 2, contact));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}

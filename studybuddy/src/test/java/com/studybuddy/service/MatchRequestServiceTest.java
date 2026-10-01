package com.studybuddy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.studybuddy.entity.enums.MatchRequestStatus;
import com.studybuddy.exception.NotMatchRequestParticipantException;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.service.studybuddymatching.MatchRequestService;
import com.studybuddy.service.studybuddymatching.MatchRequestSummary;

/**
 * Checks the match request security rules: students act as themselves, only see
 * their own requests, only the recipient can accept, and contact numbers stay
 * hidden until a request is accepted.
 * Uses the seeded students; each test is rolled back afterwards.
 */
@SpringBootTest
@Transactional
class MatchRequestServiceTest {

    private static final String ALEX = "student@studybuddy.com";
    private static final String BEN = "ben.koh@studybuddy.com";
    private static final String CHLOE = "chloe.lim@studybuddy.com";

    @Autowired
    private MatchRequestService service;
    @Autowired
    private StudentRepository studentRepository;

    private Long requestId;

    // Alex sends Ben a request before each test.
    @BeforeEach
    void alexSendsBenARequest() {
        assertThat(service.createRequest(ALEX, idOf(BEN), "Study IS442 together?")).isTrue();
        requestId = service.getMatchRequestBySender(ALEX).get(0).requestId();
    }

    @Test
    void senderAndRecipientEachSeeTheRequest() {
        assertThat(service.getMatchRequestBySender(ALEX)).hasSize(1);
        assertThat(service.getMatchRequestByRecipient(BEN)).hasSize(1);
        assertThat(service.getRequestById(ALEX, requestId).status()).isEqualTo(MatchRequestStatus.PENDING);
        assertThat(service.getRequestById(BEN, requestId).message()).isEqualTo("Study IS442 together?");
    }

    @Test
    void anotherStudentCannotViewOrAcceptTheRequest() {
        assertThat(service.getMatchRequestBySender(CHLOE)).isEmpty();
        assertThatThrownBy(() -> service.getRequestById(CHLOE, requestId))
                .isInstanceOf(NotMatchRequestParticipantException.class);
        assertThatThrownBy(() -> service.acceptRequest(CHLOE, requestId))
                .isInstanceOf(NotMatchRequestParticipantException.class);
    }

    @Test
    void theSenderCannotAcceptTheirOwnRequest() {
        assertThatThrownBy(() -> service.acceptRequest(ALEX, requestId))
                .isInstanceOf(NotMatchRequestParticipantException.class);
    }

    @Test
    void contactNumbersAreHiddenUntilTheRecipientAccepts() {
        assertThat(service.getRequestById(ALEX, requestId).buddyContactNumber()).isNull();
        assertThat(service.getRequestById(BEN, requestId).buddyContactNumber()).isNull();

        MatchRequestSummary accepted = service.acceptRequest(BEN, requestId);

        // Each side now sees the other's number: Ben sees Alex's, Alex sees Ben's.
        assertThat(accepted.status()).isEqualTo(MatchRequestStatus.ACCEPTED);
        assertThat(accepted.buddyContactNumber()).isEqualTo("91234567");
        assertThat(service.getRequestById(ALEX, requestId).buddyContactNumber()).isEqualTo("92345678");
    }

    private Long idOf(String email) {
        return studentRepository.findByEmail(email).orElseThrow().getUserId();
    }
}

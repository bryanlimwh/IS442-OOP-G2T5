package com.studybuddy.service.studybuddymatching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.studybuddy.entity.Student;
import com.studybuddy.exception.ConnectionNotFoundException;
import com.studybuddy.repository.StudentRepository;

import jakarta.persistence.EntityManager;

/**
 * Gaps in StudyBuddyConnectionServiceTest (a teammate's file): the list's order, ending a
 * connection for both students and twice, and whose contact number a summary carries. Empty
 * in-memory H2 with own data; each test is rolled back. Students Alex (91234567), Ben (92345678)
 * and Chloe (93456789); no connections at the start.
 */
@DataJpaTest
@Import(StudyBuddyConnectionService.class)
class StudyBuddyConnectionServiceGapTest {

    private static final String ALEX = "alex@test.com";

    @Autowired
    private StudyBuddyConnectionService service;
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
        ben = saveStudent("ben@test.com", "Ben Koh", "92345678");
        chloe = saveStudent("chloe@test.com", "Chloe Lim", "93456789");
    }

    // S9: the newest buddy comes first
    @Test
    void connectionsAreListedNewestFirst() {
        service.connect(alex, ben);
        entityManager.flush();
        // Creation times on this machine tick once a millisecond; make sure the second one is later
        waitForClockTick();
        service.connect(chloe, alex);
        flushAndClear();

        assertThat(service.getConnections(ALEX)).extracting(summary -> summary.buddy().name())
                .containsExactly("Chloe Lim", "Ben Koh");
    }

    // S8, S9, REQ-4: the summary holds the buddy's number, never the viewer's own
    @Test
    void summaryCarriesOnlyTheBuddysNumber() {
        service.connect(alex, ben);
        flushAndClear();

        ConnectionSummary alexView = service.getConnections(ALEX).get(0);
        ConnectionSummary benView = service.getConnections("ben@test.com").get(0);

        assertThat(Arrays.stream(ConnectionSummary.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("connectionId", "buddy", "buddyContactNumber", "connectedSince");
        assertThat(alexView.buddyContactNumber()).isEqualTo("92345678");
        assertThat(alexView.toString()).doesNotContain("91234567").doesNotContain("93456789");
        assertThat(benView.buddyContactNumber()).isEqualTo("91234567");
        assertThat(benView.toString()).doesNotContain("92345678");
    }

    // S9, REQ-5: once ended, neither student sees the other (or their number)
    @Test
    void endingHidesTheConnectionFromBoth() {
        service.connect(alex, ben);
        service.connect(alex, chloe);
        flushAndClear();
        Long withBen = service.getConnections("ben@test.com").get(0).connectionId();

        service.endConnection(ALEX, withBen);
        flushAndClear();

        assertThat(service.getConnections("ben@test.com")).isEmpty();
        assertThat(service.getConnections(ALEX)).extracting(summary -> summary.buddy().name())
                .containsExactly("Chloe Lim");
        assertThat(service.areConnected(alex, ben)).isFalse();
        assertThat(service.areConnected(ben, alex)).isFalse();
    }

    // REQ-5: an ended connection can't be ended again
    @Test
    void endingTwiceFails() {
        service.connect(alex, ben);
        flushAndClear();
        Long id = service.getConnections(ALEX).get(0).connectionId();
        service.endConnection(ALEX, id);
        flushAndClear();

        assertThatThrownBy(() -> service.endConnection(ALEX, id)).isInstanceOf(ConnectionNotFoundException.class);
        assertThatThrownBy(() -> service.endConnection("ben@test.com", id))
                .isInstanceOf(ConnectionNotFoundException.class);
    }

    private static void waitForClockTick() {
        LocalDateTime start = LocalDateTime.now();
        while (!LocalDateTime.now().isAfter(start)) {
            Thread.onSpinWait();
        }
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

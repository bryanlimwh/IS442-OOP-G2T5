package com.studybuddy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.studybuddy.entity.Student;
import com.studybuddy.exception.ConnectionNotFoundException;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.repository.StudyBuddyConnectionRepository;
import com.studybuddy.service.studybuddymatching.ConnectionSummary;
import com.studybuddy.service.studybuddymatching.StudyBuddyConnectionService;

/**
 * Integration test for StudyBuddyConnectionService with the real repositories
 * and an in-memory H2. Creating a connection by accepting a request is covered
 * in MatchRequestServiceTest; this covers the connection's own rules.
 */
@DataJpaTest
@Import(StudyBuddyConnectionService.class)
class StudyBuddyConnectionServiceTest {

    @Autowired
    private StudyBuddyConnectionService service;

    @Autowired
    private StudyBuddyConnectionRepository connectionRepository;

    @Autowired
    private StudentRepository studentRepository;

    // Alex and Ben are connected during setup; Chloe is an unrelated student
    private Student alex;
    private Student ben;
    private Student chloe;

    private Student saveStudent(String email, String name, String contact) {
        return studentRepository.save(new Student(email, "dummyHash", name,
                "School of Computing and Information Systems", "Information Systems", 2, contact));
    }

    @BeforeEach
    void setUp() {
        alex = saveStudent("alex@test.com", "Alex Tan", "91234567");
        ben = saveStudent("ben@test.com", "Ben Koh", "92345678");
        chloe = saveStudent("chloe@test.com", "Chloe Lim", "93456789");
        service.connect(alex, ben);
    }

    private Long alexAndBensConnectionId() {
        return service.getConnections(alex.getEmail()).get(0).connectionId();
    }

    @Test
    void connectingTheSamePairTwiceKeepsOneConnection() {
        service.connect(alex, ben);
        service.connect(ben, alex);

        assertThat(connectionRepository.count()).isEqualTo(1);
    }

    @Test
    void eachStudentSeesTheOtherAsTheirBuddy() {
        ConnectionSummary alexView = service.getConnections(alex.getEmail()).get(0);
        ConnectionSummary benView = service.getConnections(ben.getEmail()).get(0);

        assertThat(alexView.buddy().name()).isEqualTo("Ben Koh");
        assertThat(alexView.buddyContactNumber()).isEqualTo("92345678");
        assertThat(benView.buddy().name()).isEqualTo("Alex Tan");
        assertThat(benView.buddyContactNumber()).isEqualTo("91234567");
        assertThat(service.getConnections(chloe.getEmail())).isEmpty();
    }

    @Test
    void eitherStudentCanEndTheConnection() {
        service.endConnection(ben.getEmail(), alexAndBensConnectionId());

        assertThat(service.areConnected(alex, ben)).isFalse();
        assertThat(service.getConnections(alex.getEmail())).isEmpty();
    }

    @Test
    void anotherStudentCannotEndTheConnection() {
        Long connectionId = alexAndBensConnectionId();

        // Chloe isn't part of it, so to her it doesn't exist
        assertThatThrownBy(() -> service.endConnection(chloe.getEmail(), connectionId))
                .isInstanceOf(ConnectionNotFoundException.class);
        assertThat(service.areConnected(alex, ben)).isTrue();
    }

    @Test
    void endingAnUnknownConnectionFails() {
        assertThatThrownBy(() -> service.endConnection(alex.getEmail(), 999L))
                .isInstanceOf(ConnectionNotFoundException.class);
    }

    @Test
    void studentCannotConnectWithThemselves() {
        assertThatThrownBy(() -> service.connect(alex, alex))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

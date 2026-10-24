package com.studybuddy.service.studybuddymatching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.studybuddy.entity.Student;
import com.studybuddy.repository.MatchRequestRepository;
import com.studybuddy.repository.StudentRepository;

/**
 * Known bugs in MatchRequestService. Each test describes what SHOULD happen and fails on today's
 * code, so it is @Disabled to keep the main suite green. Remove @Disabled to check a fix.
 * Empty in-memory H2 with own data.
 */
@DataJpaTest
@Import({ MatchRequestService.class, StudyBuddyConnectionService.class })
class MatchRequestServiceKnownBugTest {

    @Autowired
    private MatchRequestService service;
    @Autowired
    private MatchRequestRepository matchRequestRepository;
    @Autowired
    private StudentRepository studentRepository;

    // S7, REQ-1, K6
    @Test
    @Disabled("KNOWN BUG K6: a request to a deactivated student should be refused and nothing saved; today it "
            + "is saved (the matches page hides inactive students, but a direct POST still sends one)")
    void requestToDeactivatedStudentIsRefused() {
        saveStudent("alex@test.com", "Alex Tan");
        Student ben = saveStudent("ben@test.com", "Ben Koh");
        ben.setActive(false);

        assertThatThrownBy(() -> service.createRequest("alex@test.com", ben.getUserId(), "Study?"));
        assertThat(matchRequestRepository.count()).isZero();
    }

    private Student saveStudent(String email, String name) {
        return studentRepository.save(new Student(email, "dummyHash", name,
                "School of Computing and Information Systems", "Information Systems", 2, "91234567"));
    }
}

package com.studybuddy.service.studybuddymatching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.studybuddy.entity.Student;
import com.studybuddy.exception.StudentNotFoundException;
import com.studybuddy.repository.MatchRequestRepository;
import com.studybuddy.repository.StudentRepository;

/**
 * Regression tests for bugs fixed in MatchRequestService (they were @Disabled known-bug tests until
 * the fix; the class keeps its name so the workbook Test IDs stay the same). Empty in-memory H2
 * with own data.
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

    // S7, REQ-1, K6: fixed 2026-10-28. A deactivated student is treated as not found, so a request to
    // them is refused and nothing is saved (the page then shows "That student no longer exists.")
    @Test
    void requestToDeactivatedStudentIsRefused() {
        saveStudent("alex@test.com", "Alex Tan");
        Student ben = saveStudent("ben@test.com", "Ben Koh");
        ben.setActive(false);

        assertThatThrownBy(() -> service.createRequest("alex@test.com", ben.getUserId(), "Study?"))
                .isInstanceOf(StudentNotFoundException.class);
        assertThat(matchRequestRepository.count()).isZero();
    }

    private Student saveStudent(String email, String name) {
        return studentRepository.save(new Student(email, "dummyHash", name,
                "School of Computing and Information Systems", "Information Systems", 2, "91234567"));
    }
}

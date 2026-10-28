package com.studybuddy.service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetails;

import com.studybuddy.entity.Student;
import com.studybuddy.repository.StudentRepository;

/**
 * Regression tests for bugs fixed in AuthService (they were @Disabled known-bug tests until the
 * fix; the class keeps its name so the workbook Test IDs stay the same).
 */
@DataJpaTest
@Import(AuthService.class)
class AuthServiceKnownBugTest {

    @Autowired
    private AuthService authService;
    @Autowired
    private StudentRepository studentRepository;

    // ACC-8, K1: fixed 2026-10-28. Login finds the account whatever the letter case of the typed email
    // (and with spaces around it), because sign-up and the admin page store emails trimmed and lower-cased
    @Test
    void emailInAnotherLetterCaseFindsTheAccount() {
        studentRepository.save(new Student("alex@test.com", "hashOfAlex", "Alex Tan",
                "School of Computing and Information Systems", "Information Systems", 2, "91234567"));

        UserDetails user = authService.loadUserByUsername("Alex@Test.COM");

        assertThat(user.getUsername()).isEqualTo("alex@test.com");
        assertThat(authService.loadUserByUsername("  ALEX@test.com ").getUsername()).isEqualTo("alex@test.com");
    }
}

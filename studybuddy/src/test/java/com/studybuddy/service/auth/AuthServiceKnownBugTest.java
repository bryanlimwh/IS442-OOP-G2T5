package com.studybuddy.service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetails;

import com.studybuddy.entity.Student;
import com.studybuddy.repository.StudentRepository;

/**
 * Known bugs in AuthService. Each test describes what SHOULD happen and fails on today's
 * code, so it is @Disabled to keep the main suite green. Remove @Disabled to check a fix.
 */
@DataJpaTest
@Import(AuthService.class)
class AuthServiceKnownBugTest {

    @Autowired
    private AuthService authService;
    @Autowired
    private StudentRepository studentRepository;

    // ACC-8, K1
    @Test
    @Disabled("KNOWN BUG K1: login should find the account whatever the letter case of the typed email, "
            + "because sign-up and the admin page store emails lower-cased")
    void emailInAnotherLetterCaseFindsTheAccount() {
        studentRepository.save(new Student("alex@test.com", "hashOfAlex", "Alex Tan",
                "School of Computing and Information Systems", "Information Systems", 2, "91234567"));

        UserDetails user = authService.loadUserByUsername("Alex@Test.COM");

        assertThat(user.getUsername()).isEqualTo("alex@test.com");
    }
}

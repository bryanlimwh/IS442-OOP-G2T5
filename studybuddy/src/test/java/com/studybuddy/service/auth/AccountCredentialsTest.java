package com.studybuddy.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.studybuddy.entity.Admin;
import com.studybuddy.entity.Student;
import com.studybuddy.exception.DuplicateEmailException;
import com.studybuddy.exception.InvalidAccountDetailsException;
import com.studybuddy.repository.AdminRepository;
import com.studybuddy.repository.StudentRepository;

/**
 * The email and password rules shared by student sign-up and the admin account page.
 * Runs against an empty in-memory H2 (no seeders); each test is rolled back.
 */
@DataJpaTest
@Import(AccountCredentials.class)
class AccountCredentialsTest {

    @Autowired
    private AccountCredentials credentials;
    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private AdminRepository adminRepository;

    // ================= Email =================

    // ACC-1
    @Test
    void normaliseEmailTrimsAndLowerCases() {
        assertThat(credentials.normaliseEmail("  Alex.Tan@Example.COM ")).isEqualTo("alex.tan@example.com");
    }

    // ACC-1
    @Test
    void normaliseEmailAcceptsValidShapes() {
        assertThat(credentials.normaliseEmail("a@b.co")).isEqualTo("a@b.co");
        assertThat(credentials.normaliseEmail("first.last+tag@mail.smu.edu.sg"))
                .isEqualTo("first.last+tag@mail.smu.edu.sg");
    }

    // ACC-1
    @Test
    void normaliseEmailRejectsBlank() {
        for (String blank : Arrays.asList(null, "", "   ")) {
            assertThatThrownBy(() -> credentials.normaliseEmail(blank))
                    .isInstanceOf(InvalidAccountDetailsException.class)
                    .hasMessage("Enter an email address.");
        }
    }

    // ACC-1
    @Test
    void normaliseEmailRejectsMalformedShapes() {
        List<String> malformed = List.of("alex", "alex@", "@example.com", "alex@example",
                "alex tan@example.com", "alex@@example.com", "alex@exa mple.com");
        for (String email : malformed) {
            assertThatThrownBy(() -> credentials.normaliseEmail(email))
                    .as(email)
                    .isInstanceOf(InvalidAccountDetailsException.class)
                    .hasMessage("Enter a valid email address, like name@example.com.");
        }
    }

    // ================= Password =================

    // ACC-2
    @Test
    void passwordMinimumIsEightCharacters() {
        assertThatThrownBy(() -> credentials.checkPassword("1234567"))
                .isInstanceOf(InvalidAccountDetailsException.class)
                .hasMessage("Passwords must be at least 8 characters.");
        assertThatCode(() -> credentials.checkPassword("12345678")).doesNotThrowAnyException();
    }

    // ACC-2
    @Test
    void passwordMaximumIs72Bytes() {
        assertThatCode(() -> credentials.checkPassword("a".repeat(72))).doesNotThrowAnyException();
        assertThatThrownBy(() -> credentials.checkPassword("a".repeat(73)))
                .isInstanceOf(InvalidAccountDetailsException.class);
    }

    // ACC-2: the limit is bytes, not characters ("€" is 3 bytes in UTF-8)
    @Test
    void multibytePasswordIsLimitedByBytes() {
        String fits = "€".repeat(24);
        String tooLong = "€".repeat(25);
        assertThat(fits.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        assertThat(tooLong.getBytes(StandardCharsets.UTF_8)).hasSize(75);

        assertThatCode(() -> credentials.checkPassword(fits)).doesNotThrowAnyException();
        // Only 25 characters, but 75 bytes
        assertThatThrownBy(() -> credentials.checkPassword(tooLong))
                .isInstanceOf(InvalidAccountDetailsException.class);
    }

    // ACC-2
    @Test
    void blankOrNullPasswordIsRejected() {
        assertThatThrownBy(() -> credentials.checkPassword(null))
                .isInstanceOf(InvalidAccountDetailsException.class);
        assertThatThrownBy(() -> credentials.checkPassword(""))
                .isInstanceOf(InvalidAccountDetailsException.class);
        assertThatThrownBy(() -> credentials.checkPassword("        "))
                .isInstanceOf(InvalidAccountDetailsException.class)
                .hasMessage("Passwords can't be only spaces.");
    }

    // ACC-2: passwords aren't trimmed, so spaces count towards the length
    @Test
    void spacesCountTowardsPasswordLength() {
        assertThatCode(() -> credentials.checkPassword("      ab")).doesNotThrowAnyException();
    }

    // ================= Email already in use =================

    // ACC-3
    @Test
    void emailAvailableWhenFreeOrKeptBySameAccount() {
        Student alex = saveStudent("alex@test.com");

        assertThatCode(() -> credentials.requireEmailAvailable("free@test.com", null)).doesNotThrowAnyException();
        // The account being updated may keep its own email
        assertThatCode(() -> credentials.requireEmailAvailable("alex@test.com", alex.getUserId()))
                .doesNotThrowAnyException();
    }

    // ACC-3
    @Test
    void emailTakenByAnotherAccountIsRefused() {
        Student alex = saveStudent("alex@test.com");
        adminRepository.save(new Admin("admin@test.com", "dummyHash"));

        assertThatThrownBy(() -> credentials.requireEmailAvailable("alex@test.com", null))
                .isInstanceOf(DuplicateEmailException.class)
                .hasMessage("Another account already uses alex@test.com.");
        // Taken by an account of the other role counts too
        assertThatThrownBy(() -> credentials.requireEmailAvailable("admin@test.com", alex.getUserId()))
                .isInstanceOf(DuplicateEmailException.class);
    }

    private Student saveStudent(String email) {
        return studentRepository.save(new Student(email, "dummyHash", "Alex Tan",
                "School of Computing and Information Systems", "Information Systems", 2, "91234567"));
    }
}

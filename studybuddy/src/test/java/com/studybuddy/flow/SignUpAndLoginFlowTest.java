package com.studybuddy.flow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Full-stack flows for signing up, logging in and out, and which pages each role reaches.
 * Replaces manual cases MAN-01, MAN-02, MAN-26, MAN-28 and MAN-46.
 */
class SignUpAndLoginFlowTest extends FlowTestSupport {

    // S1, ACC-1: a new student signs up, logs in with the new account, and logs out again (MAN-01)
    @Test
    void newStudentSignsUpLogsInAndLogsOut() {
        String email = "new.student." + unique() + "@example.com";
        Browser student = browser(email);
        student.open("/register");
        student.submit("/register", "email", email, "password", PASSWORD, "name", "New Student", "school", "SCIS",
                "programme", "Information Systems", "yearOfStudy", "2", "contactNumber", contactNumber());

        assertThat(student.url()).isEqualTo("/login?registered");
        assertThat(student.text()).contains("Your account is ready. Log in to add your courses.");

        student.logIn(email, PASSWORD);
        assertThat(student.url()).isEqualTo("/home");
        assertThat(student.text()).contains("Welcome back", "Logged in as " + email, "Find study buddies");

        student.logOut();
        assertThat(student.url()).isEqualTo("/login?logout");
        assertThat(student.text()).contains("You've logged out.");

        // The session is gone: Home sends the visitor back to the login page
        student.open("/home");
        assertThat(student.url()).isEqualTo("/login");
    }

    // ACC-3, PRF-1: signing up with an email that is already used keeps everything typed except the password (MAN-02)
    @Test
    void signUpWithATakenEmailKeepsTheTypedDetails() {
        Browser existing = signUp("Taken Email");
        String number = contactNumber();
        Browser copyCat = visitor();
        copyCat.open("/register");
        copyCat.submit("/register", "email", existing.email(), "password", PASSWORD, "name", "Copy Cat", "school", "SCIS",
                "programme", "IS", "yearOfStudy", "1", "contactNumber", number);

        assertThat(copyCat.url()).isEqualTo("/register");
        assertThat(copyCat.text()).contains("Another account already uses " + existing.email() + ".");
        assertThat(copyCat.html()).contains("value=\"" + existing.email() + "\"", "value=\"Copy Cat\"",
                "value=\"SCIS\"", "value=\"IS\"", "value=\"1\"", "value=\"" + number + "\"");
        assertThat(copyCat.sectionAfter("id=\"password\"", ">")).doesNotContain("value=");
    }

    // ACC-8, ACC-6: the login email is matched trimmed and lower-cased; a wrong password is refused (MAN-28)
    @Test
    void loginIgnoresEmailCaseAndSpacesButNotTheWrongPassword() {
        Browser student = signUp("Case Check");
        student.logOut();

        student.logIn("  " + student.email().toUpperCase() + " ", PASSWORD);
        assertThat(student.url()).isEqualTo("/home");
        assertThat(student.text()).contains("Logged in as " + student.email());

        Browser wrong = visitor();
        wrong.logIn(student.email(), "not-the-password");
        assertThat(wrong.url()).isEqualTo("/login?error");
        assertThat(wrong.text())
                .contains("That email and password don't match an active account. Check them and try again.");
    }

    // S1, PRF-1, K3, K4: an over-long school keeps the sign-up form and creates no account (MAN-46)
    @Test
    void overLongSchoolKeepsTheFormAndCreatesNoAccount() {
        String email = "long.school." + unique() + "@example.com";
        String school = "S".repeat(256);
        Browser visitor = visitor();
        visitor.open("/register");
        visitor.submit("/register", "email", email, "password", PASSWORD, "name", "Long School", "school", school,
                "programme", "Information Systems", "yearOfStudy", "2", "contactNumber", contactNumber());

        assertThat(visitor.url()).isEqualTo("/register");
        assertThat(visitor.text()).contains("Name, school, programme and email can be at most 255 characters.");
        assertThat(visitor.html()).contains("value=\"" + school + "\"", "value=\"" + email + "\"");

        visitor.logIn(email, PASSWORD);
        assertThat(visitor.url()).isEqualTo("/login?error");
    }

    // D3, SEC-1, SEC-2: a student can't open admin pages, an admin can't open student pages, a visitor is sent to log in (MAN-26)
    @Test
    void eachRoleIsKeptToItsOwnPages() {
        Browser student = signUp("Role Student");
        student.open("/admin/accounts");
        assertThat(student.status()).isEqualTo(403);
        student.open("/admin/matching");
        assertThat(student.status()).isEqualTo(403);

        Browser admin = newAdmin();
        admin.open("/matches");
        assertThat(admin.status()).isEqualTo(403);
        admin.open("/groups");
        assertThat(admin.status()).isEqualTo(403);
        admin.open("/home");
        assertThat(admin.text()).contains("User accounts", "Matching settings").doesNotContain("Match requests", "Study groups");

        Browser visitor = visitor();
        visitor.open("/groups");
        assertThat(visitor.url()).isEqualTo("/login");
        assertThat(visitor.text()).contains("Log in");
    }
}

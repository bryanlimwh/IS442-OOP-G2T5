package com.studybuddy.flow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.studybuddy.entity.Admin;
import com.studybuddy.entity.User;
import com.studybuddy.repository.UserRepository;

/**
 * Full-stack flows for the admin's User accounts page: creating an admin, changing an email,
 * resetting a password, deactivating, reactivating, deleting, and the usage figures.
 * Replaces MAN-16 to MAN-19, MAN-21, MAN-29 and part of MAN-20 (a student who is still used
 * can't be deleted: that refusal only happens when the database commits, so it is in e2e/).
 */
class AdminAccountsFlowTest extends FlowTestSupport {

    @Autowired
    UserRepository userRepository;

    private static long accountId(Browser admin, String email) {
        admin.open("/admin/accounts");
        return admin.idAfter(">" + email + "<", "/admin/accounts/(\\d+)/");
    }

    private static String row(Browser admin, String email) {
        int start = admin.html().lastIndexOf("<tr>", admin.html().indexOf(">" + email + "<"));
        return admin.html().substring(start, admin.html().indexOf("</tr>", start));
    }

    // A1, ACC-1, ACC-2: an admin adds an admin account (email trimmed and lower-cased) that can log in straight away (MAN-16)
    @Test
    void adminCreatesAnAdminWhoCanLogInStraightAway() {
        Browser admin = newAdmin();
        String suffix = unique();
        String typed = "  Second.Admin." + suffix + "@StudyBuddy.com ";
        String stored = "second.admin." + suffix + "@studybuddy.com";

        admin.open("/admin/accounts");
        admin.submit("/admin/accounts", "email", typed, "password", PASSWORD);
        assertThat(admin.text()).contains("Admin account " + stored + " has been created. It can log in straight away.");
        assertThat(row(admin, stored)).contains("Admin", "Active");

        Browser second = browser(stored);
        second.logIn(stored, PASSWORD);
        assertThat(second.text()).contains("Logged in as " + stored, "User accounts", "Courses", "Matching settings");
    }

    // A1, ACC-3: an admin changes a student's email and password; a taken email is refused; the student logs in with the new ones (MAN-17)
    @Test
    void adminChangesAnEmailAndResetsAPassword() {
        Browser admin = newAdmin();
        Browser ben = signUp("Ben Renamed");
        Browser other = signUp("Olga Other");
        long benId = accountId(admin, ben.email());
        String newEmail = "ben.renamed." + unique() + "@example.com";

        admin.submit("/admin/accounts/" + benId + "/email", "email", newEmail);
        assertThat(admin.text()).contains("The account now logs in with " + newEmail + ".");
        admin.submit("/admin/accounts/" + benId + "/password", "password", "newpassword1");
        assertThat(admin.text()).contains("The password for " + newEmail + " has been changed.");

        long otherId = accountId(admin, other.email());
        admin.submit("/admin/accounts/" + otherId + "/email", "email", newEmail);
        assertThat(admin.text()).contains("Another account already uses " + newEmail + ".");

        Browser login = visitor();
        login.logIn(newEmail, PASSWORD);
        assertThat(login.url()).isEqualTo("/login?error");
        login.logIn(newEmail, "newpassword1");
        assertThat(login.text()).contains("Logged in as " + newEmail);
    }

    // A1, ACC-6: a deactivated student can't log in; reactivating lets them in again (MAN-18)
    @Test
    void deactivatedStudentCannotLogInUntilReactivated() {
        Browser admin = newAdmin();
        Browser chloe = signUp("Chloe Paused");
        chloe.logOut();
        long chloeId = accountId(admin, chloe.email());

        admin.submit("/admin/accounts/" + chloeId + "/deactivate");
        assertThat(admin.text()).contains(chloe.email() + " has been deactivated and can no longer log in.");
        assertThat(row(admin, chloe.email())).contains("Inactive", "Reactivate");

        chloe.logIn(chloe.email(), PASSWORD);
        assertThat(chloe.url()).isEqualTo("/login?error");
        assertThat(chloe.text())
                .contains("That email and password don't match an active account. Check them and try again.");

        admin.submit("/admin/accounts/" + chloeId + "/reactivate");
        assertThat(admin.text()).contains(chloe.email() + " has been reactivated.");
        assertThat(row(admin, chloe.email())).contains("Active", "Deactivate");
        chloe.logIn(chloe.email(), PASSWORD);
        assertThat(chloe.url()).isEqualTo("/home");
    }

    // A1, ACC-4: the last active admin can neither deactivate nor delete their own account (MAN-19)
    @Test
    void lastActiveAdminCannotBeDeactivatedOrDeleted() {
        Browser admin = newAdmin();
        // Every other admin (the seeded one included) is made inactive inside this test's transaction only:
        // the rollback restores them, and nothing outside this test ever sees the change.
        for (User user : userRepository.findAll()) {
            if (user instanceof Admin && user.isActive() && !user.getEmail().equals(admin.email())) {
                admin.submit("/admin/accounts/" + user.getUserId() + "/deactivate");
            }
        }
        long ownId = accountId(admin, admin.email());
        assertThat(row(admin, admin.email())).contains("(you)");

        admin.submit("/admin/accounts/" + ownId + "/deactivate");
        assertThat(admin.text()).contains("This is the last active admin account, so it can't be deactivated.");
        admin.submit("/admin/accounts/" + ownId + "/delete");
        assertThat(admin.text()).contains("This is the last active admin account, so it can't be deleted.");
        assertThat(row(admin, admin.email())).contains("Active");
    }

    // A1: an admin account nobody uses can be deleted; a student's delete box warns that a used student can't be (MAN-20)
    @Test
    void unusedAdminCanBeDeletedAndTheStudentDeleteBoxWarns() {
        Browser admin = newAdmin();
        String third = "third.admin." + unique() + "@example.com";
        admin.open("/admin/accounts");
        admin.submit("/admin/accounts", "email", third, "password", PASSWORD);
        Browser student = signUp("Del Warning");

        admin.open("/admin/accounts");
        assertThat(row(admin, student.email()))
                .contains("A student with match requests, study groups or study buddies can't be deleted.");
        admin.submit("/admin/accounts/" + accountId(admin, third) + "/delete");
        assertThat(admin.text()).contains("The account has been deleted.").doesNotContain(third);
    }

    // A2: the account list shows each account's role, status and usage figures, and marks the admin's own row (MAN-21)
    @Test
    void accountListShowsStatusAndUsage() {
        Browser admin = newAdmin();
        String firstCode = "USE" + unique();
        long first = createCourse(admin, firstCode);
        long second = createCourse(admin, "USF" + unique());
        Browser alex = signUp("Alex Usage");
        takeCourse(alex, first);
        takeCourse(alex, second);
        addPreference(alex, first, firstCode, "ONLINE", "ONE_TO_ONE", "CONCEPT_REVIEW");
        alex.open("/groups/new");
        alex.submit("/groups", "name", "Usage Group " + unique(), "courseId", String.valueOf(first),
                "studyGoal", "EXAM_PREPARATION", "studyMode", "ONLINE", "maxSize", "3",
                "slots[0].dayOfWeek", "MONDAY", "slots[0].startTime", "10:00", "slots[0].endTime", "12:00");
        studentWithPreference("Ben Usage", first, firstCode, "ONLINE", "ONE_TO_ONE", "CONCEPT_REVIEW",
                "MONDAY", "10:00", "12:00");
        Browser cat = studentWithPreference("Cat Usage", first, firstCode, "ONLINE", "ONE_TO_ONE", "CONCEPT_REVIEW",
                "MONDAY", "10:00", "12:00");
        alex.open("/matches?courseId=" + first);
        alex.submit("/match-requests", "recipientId",
                String.valueOf(alex.id("href=\"/students/(\\d+)\">Ben Usage<")), "message", "");
        cat.open("/matches?courseId=" + first);
        cat.submit("/match-requests", "recipientId",
                String.valueOf(cat.id("href=\"/students/(\\d+)\">Alex Usage<")), "message", "");

        admin.open("/admin/accounts");
        assertThat(row(admin, alex.email())).contains("Alex Usage", "Student", "Active",
                "Courses 2 · Preferences 1 · Groups led 1", "Requests sent 1 · received 1");
        assertThat(row(admin, admin.email())).contains("(you)", "Admin", "&mdash;");
    }

    // A1, K2, ACC-6: pins current behaviour (suspected bug K2): after the admin changes a logged-in student's email, that student's pages fail until they log in again, and a deactivated student's open session keeps working (MAN-29)
    @Test
    void changingALoggedInStudentsEmailBreaksTheirSession() {
        Browser admin = newAdmin();
        Browser chloe = signUp("Chloe Session");
        Browser dave = signUp("Dave Session");

        admin.submit("/admin/accounts/" + accountId(admin, chloe.email()) + "/email",
                "email", "chloe.new." + unique() + "@example.com");
        chloe.open("/student/profile");
        assertThat(chloe.status()).isEqualTo(404);

        admin.submit("/admin/accounts/" + accountId(admin, dave.email()) + "/deactivate");
        dave.open("/student/profile");
        assertThat(dave.status()).isEqualTo(200);
        assertThat(dave.html()).contains("My profile", "value=\"Dave Session\"");
    }
}

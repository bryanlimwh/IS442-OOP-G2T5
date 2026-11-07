package com.studybuddy.flow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Full-stack flows for a student's own profile, courses and study preferences, and for
 * viewing another student's public profile. Replaces MAN-03, MAN-04, MAN-40 and MAN-45.
 */
class ProfileAndPreferencesFlowTest extends FlowTestSupport {

    // S1, PRF-1, PRF-2: a student edits their details, adds and removes a course, and can't drop a course with a preference (MAN-03)
    @Test
    void studentUpdatesDetailsAndCourses() {
        Browser admin = newAdmin();
        String kept = "PRF" + unique();
        String extra = "PRX" + unique();
        long keptId = createCourse(admin, kept);
        long extraId = createCourse(admin, extra);
        Browser student = signUp("Profile Owner");
        takeCourse(student, keptId);
        addPreference(student, keptId, kept, "ONLINE", "ONE_TO_ONE", "CONCEPT_REVIEW");

        student.open("/student/profile");
        student.submit("/student/profile", "name", "Profile Owner", "school", "SCIS", "programme", "Computer Science",
                "yearOfStudy", "3", "contactNumber", "91234567");
        assertThat(student.text()).contains("Your profile has been updated.");
        // Reloading shows the saved values, and the banner only once
        student.open("/student/profile");
        assertThat(student.html()).contains("value=\"Computer Science\"", "value=\"3\"", "value=\"91234567\"");
        assertThat(student.text()).doesNotContain("Your profile has been updated.");

        takeCourse(student, extraId);
        assertThat(student.text()).contains(extra);
        student.submit("/student/profile/courses/" + extraId + "/remove");
        assertThat(student.text()).contains("The course has been removed.");
        assertThat(student.sectionAfter("<h2>Your courses</h2>", "Add a course")).doesNotContain(extra);

        student.submit("/student/profile/courses/" + keptId + "/remove");
        assertThat(student.text()).contains("Delete your " + kept + " study preference before removing the course.");
        assertThat(student.sectionAfter("<h2>Your courses</h2>", "</table>")).contains(kept);
    }

    // S2, PRE-1, PRE-3, UI-4: a student adds a preference, adds slots (an overlapping one is refused, a touching one is fine) and deletes it (MAN-04)
    @Test
    void studentManagesAPreferenceAndItsSlots() {
        Browser admin = newAdmin();
        String code = "PRE" + unique();
        long courseId = createCourse(admin, code);
        Browser student = signUp("Slot Keeper");
        takeCourse(student, courseId);

        student.open("/student/preferences");
        student.submit("/student/preferences", "courseId", String.valueOf(courseId), "studyMode", "ONLINE",
                "arrangement", "ONE_TO_ONE", "studyGoal", "CONCEPT_REVIEW");
        assertThat(student.text())
                .contains("Your " + code + " preference has been added. Add the times you're free below.");
        long preferenceId = student.idAfter("<h3>" + code + " ", "/student/preferences/(\\d+)/update");

        addSlot(student, preferenceId, "MONDAY", "10:00", "12:00");
        student.submit("/student/preferences/" + preferenceId + "/slots", "dayOfWeek", "MONDAY", "startTime", "11:00",
                "endTime", "13:00");
        assertThat(student.text()).contains("This slot overlaps one you already have on MONDAY.");
        addSlot(student, preferenceId, "MONDAY", "12:00", "13:00");

        student.open("/student/preferences");
        assertThat(student.text()).contains(code, "Online", "One-to-one", "Concept review",
                "Mon 10:00-12:00", "Mon 12:00-13:00").doesNotContain("Mon 11:00-13:00");

        student.submit("/student/preferences/" + preferenceId + "/delete");
        assertThat(student.text()).contains("The study preference has been deleted.",
                "You haven't set any study preferences yet.");
    }

    // S1, PRF-1, K4: a name over 255 characters is refused with a message and the old name stays (MAN-40)
    @Test
    void overLongNameIsRefusedAndTheNameIsKept() {
        Browser student = signUp("Short Name");
        student.open("/student/profile");
        student.submit("/student/profile", "name", "A".repeat(256), "school", "SCIS", "programme", "Information Systems",
                "yearOfStudy", "2", "contactNumber", "91234567");

        assertThat(student.url()).isEqualTo("/student/profile");
        assertThat(student.text()).contains("Name, school and programme can be at most 255 characters.");
        assertThat(student.html()).contains("value=\"Short Name\"").doesNotContain("A".repeat(256));
    }

    // S1, PRF-1, K4: an over-long school or programme is refused the same way as an over-long name (MAN-40)
    @Test
    void overLongSchoolOrProgrammeIsRefused() {
        Browser student = signUp("Long Fields");
        student.open("/student/profile");
        student.submit("/student/profile", "name", "Long Fields", "school", "S".repeat(256), "programme", "IS",
                "yearOfStudy", "2", "contactNumber", "91234567");
        assertThat(student.text()).contains("Name, school and programme can be at most 255 characters.");
        student.submit("/student/profile", "name", "Long Fields", "school", "SCIS", "programme", "P".repeat(256),
                "yearOfStudy", "2", "contactNumber", "91234567");
        assertThat(student.text()).contains("Name, school and programme can be at most 255 characters.");
        assertThat(student.html()).contains("value=\"SCIS\"", "value=\"Information Systems\"")
                .doesNotContain("S".repeat(256), "P".repeat(256));
    }

    // S1, S2, S9, K16: pins current behaviour (suspected bug K16): a student whose account was deleted while they were logged in gets a bare 404 on their own pages
    @Test
    void deletedStudentStillLoggedInGetsNotFound() {
        Browser admin = newAdmin();
        Browser student = signUp("Gone Student");
        admin.open("/admin/accounts");
        long id = admin.idAfter(">" + student.email() + "<", "/admin/accounts/(\\d+)/");
        admin.submit("/admin/accounts/" + id + "/delete");
        assertThat(admin.text()).contains("The account has been deleted.");

        for (String page : new String[] {"/student/profile", "/student/preferences", "/connections"}) {
            student.open(page);
            assertThat(student.status()).as(page).isEqualTo(404);
        }
    }

    // S6, PRF-3, REQ-4, D3, TD-6: another student's public profile shows study details but never the contact number or email; unknown or deactivated ids go back to matches (MAN-45, MAN-07)
    @Test
    void publicProfileShowsStudyDetailsOnly() {
        Browser admin = newAdmin();
        String code = "PUB" + unique();
        long courseId = createCourse(admin, code);
        String hiddenNumber = contactNumber();
        Browser other = signUp("Pat Public", hiddenNumber);
        takeCourse(other, courseId);
        long preferenceId = addPreference(other, courseId, code, "EITHER", "SMALL_GROUP", "EXAM_PREPARATION");
        addSlot(other, preferenceId, "WEDNESDAY", "19:00", "21:00");
        Browser viewer = studentWithPreference("Val Viewer", courseId, code, "ONLINE", "SMALL_GROUP",
                "EXAM_PREPARATION", "WEDNESDAY", "19:00", "21:00");

        viewer.open("/matches?courseId=" + courseId);
        assertThat(viewer.text()).contains("Pat Public").doesNotContain(hiddenNumber);
        assertThat(viewer.html()).doesNotContain(hiddenNumber, other.email());
        long otherId = viewer.id("href=\"/students/(\\d+)\">Pat Public<");

        viewer.open("/students/" + otherId);
        assertThat(viewer.text()).contains("Pat Public", "Information Systems, Year 2 · SCIS",
                "Contact numbers are only shared between study buddies", code, "Exam preparation",
                "In person or online", "Small group", "Wed 19:00-21:00");
        assertThat(viewer.html()).doesNotContain(hiddenNumber, other.email());

        viewer.open("/students/999999999");
        assertThat(viewer.url()).startsWith("/matches");
        assertThat(viewer.text()).contains("That student's profile no longer exists.");

        admin.open("/students/" + otherId);
        assertThat(admin.status()).isEqualTo(403);

        admin.open("/admin/accounts");
        admin.submit("/admin/accounts/" + otherId + "/deactivate");
        viewer.open("/students/" + otherId);
        assertThat(viewer.text()).contains("That student's profile no longer exists.");
    }
}

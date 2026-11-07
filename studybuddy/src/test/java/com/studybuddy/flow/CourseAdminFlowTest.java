package com.studybuddy.flow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Full-stack flows for the Courses page: the admin adds, edits and deletes a course; students
 * only read the list. Replaces MAN-25, MAN-30 and MAN-24 except its last step (a course that
 * is still used can't be deleted: that refusal happens when the database commits, so it is
 * in e2e/).
 */
class CourseAdminFlowTest extends FlowTestSupport {

    // CRS-1: the admin adds, renames and deletes an unused course; a blank code is refused (MAN-24)
    @Test
    void adminAddsEditsAndDeletesAnUnusedCourse() {
        Browser admin = newAdmin();
        String code = "CRS" + unique();
        long courseId = createCourse(admin, code);

        admin.open("/courses?edit=" + courseId);
        admin.submit("/courses/" + courseId + "/update", "courseCode", code, "courseName", "Manual Testing II");
        assertThat(admin.text()).contains(code + " has been updated.", "Manual Testing II");

        admin.submit("/courses/" + courseId + "/update", "courseCode", " ", "courseName", "No code");
        assertThat(admin.url()).isEqualTo("/courses?edit=" + courseId);
        assertThat(admin.text()).contains("Enter both a course code and a course name.");

        admin.submit("/courses/" + courseId + "/delete");
        assertThat(admin.text()).contains("The course has been deleted.").doesNotContain(code);
    }

    // CRS-1, D3: a student sees the course list without any edit, delete or add controls, and can't post changes (MAN-25)
    @Test
    void studentsSeeAReadOnlyCourseList() {
        Browser admin = newAdmin();
        String code = "RDO" + unique();
        long courseId = createCourse(admin, code);
        Browser student = signUp("Rita Reader");

        for (String page : new String[] {"/courses", "/courses?edit=" + courseId}) {
            student.open(page);
            assertThat(student.text()).contains(code).doesNotContain("Actions", "Add a course", "Delete course");
            assertThat(student.html()).doesNotContain("/courses/" + courseId + "/delete", "name=\"courseCode\"");
        }
        student.submit("/courses/" + courseId + "/delete");
        assertThat(student.status()).isEqualTo(403);
        admin.open("/courses");
        assertThat(admin.text()).contains(code);
    }

    // CRS-1, K4: a course code over 255 characters is refused with a message and nothing is added (MAN-30)
    @Test
    void overLongCourseCodeIsRefused() {
        Browser admin = newAdmin();
        String code = "C".repeat(256);
        admin.open("/courses");
        admin.submit("/courses", "courseCode", code, "courseName", "Too long");

        assertThat(admin.url()).isEqualTo("/courses");
        assertThat(admin.text()).contains("Course code and course name can be at most 255 characters.")
                .doesNotContain("Too long");
    }
}

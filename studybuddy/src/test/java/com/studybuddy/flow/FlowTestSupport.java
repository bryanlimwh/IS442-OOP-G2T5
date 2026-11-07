package com.studybuddy.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.studybuddy.entity.Admin;
import com.studybuddy.repository.AdminRepository;

import jakarta.persistence.EntityManager;

/**
 * Base for the full-stack functional flows: the whole application (real services, real H2,
 * real SecurityConfig with form login and CSRF), driven over HTTP by {@link Browser}.
 * <p>
 * Every flow makes its own students, admin and courses with a unique suffix, so nothing
 * depends on the seeded data or on another test. Each test runs in one transaction that is
 * rolled back afterwards, so nothing is left behind. Because of that rollback, refusals that
 * only happen when the database commits (deleting a course or student that is still used)
 * are left to the browser tests in e2e/, whose server really commits.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
abstract class FlowTestSupport {

    static final String PASSWORD = "password123";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager entityManager;

    @Autowired
    AdminRepository adminRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    /** A short suffix that no other test, run or seeded row uses, e.g. "7k3x9a". */
    static String unique() {
        return SEQUENCE.incrementAndGet() + Long.toString(System.nanoTime(), 36).substring(4);
    }

    /** A contact number no other student in the test uses, so "not on the page" checks mean something. */
    static String contactNumber() {
        return String.valueOf(80_000_000 + (SEQUENCE.incrementAndGet() * 7_919 + System.nanoTime() % 1000) % 19_999_999);
    }

    Browser browser(String email) {
        return new Browser(mvc, () -> {
            entityManager.flush();
            entityManager.clear();
        }, email);
    }

    /** An anonymous visitor. */
    Browser visitor() {
        return browser(null);
    }

    /**
     * A fresh admin, saved directly (an admin can only be created by another admin, and the
     * flows must not use the seeded one), then logged in through the login form.
     */
    Browser newAdmin() {
        String email = "flow.admin." + unique() + "@example.com";
        adminRepository.save(new Admin(email, passwordEncoder.encode(PASSWORD)));
        Browser admin = browser(email);
        admin.logIn(email, PASSWORD);
        assertThat(admin.url()).isEqualTo("/home");
        return admin;
    }

    /** Signs a new student up through /register and logs them in. */
    Browser signUp(String name) {
        return signUp(name, contactNumber());
    }

    Browser signUp(String name, String contact) {
        String email = name.toLowerCase().replace(' ', '.') + "." + unique() + "@example.com";
        Browser student = browser(email);
        student.open("/register");
        student.submit("/register", "email", email, "password", PASSWORD, "name", name, "school", "SCIS",
                "programme", "Information Systems", "yearOfStudy", "2", "contactNumber", contact);
        assertThat(student.url()).isEqualTo("/login?registered");
        student.logIn(email, PASSWORD);
        assertThat(student.url()).isEqualTo("/home");
        return student;
    }

    /** The admin adds a course through /courses; returns its id, read from the course's row. */
    long createCourse(Browser admin, String code) {
        admin.open("/courses");
        admin.submit("/courses", "courseCode", code, "courseName", "Flow course " + code);
        assertThat(admin.text()).contains(code + " has been added.");
        return admin.idAfter(">" + code + "<", "/courses/(\\d+)/delete");
    }

    /** The student adds the course to their profile. */
    void takeCourse(Browser student, long courseId) {
        student.open("/student/profile");
        student.submit("/student/profile/courses", "courseId", String.valueOf(courseId));
        assertThat(student.text()).contains("The course has been added.");
    }

    /** The student adds a study preference for a course they take; returns the preference id. */
    long addPreference(Browser student, long courseId, String code, String mode, String arrangement, String goal) {
        student.open("/student/preferences");
        student.submit("/student/preferences", "courseId", String.valueOf(courseId), "studyMode", mode,
                "arrangement", arrangement, "studyGoal", goal);
        assertThat(student.text()).contains("Your " + code + " preference has been added.");
        return student.idAfter("<h3>" + code + " ", "/student/preferences/(\\d+)/update");
    }

    void addSlot(Browser student, long preferenceId, String day, String start, String end) {
        student.open("/student/preferences?edit=" + preferenceId);
        student.submit("/student/preferences/" + preferenceId + "/slots", "dayOfWeek", day, "startTime", start,
                "endTime", end);
        assertThat(student.text()).contains("The time slot has been added.");
    }

    /** A student who takes the course and has one preference with one slot: the usual match setup. */
    Browser studentWithPreference(String name, long courseId, String code, String mode, String arrangement,
            String goal, String day, String start, String end) {
        Browser student = signUp(name);
        takeCourse(student, courseId);
        long preferenceId = addPreference(student, courseId, code, mode, arrangement, goal);
        addSlot(student, preferenceId, day, start, end);
        return student;
    }
}

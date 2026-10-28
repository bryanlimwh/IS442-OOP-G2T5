package com.studybuddy.service.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.studybuddy.entity.Admin;
import com.studybuddy.entity.AvailabilitySlot;
import com.studybuddy.entity.Course;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyPreference;
import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;
import com.studybuddy.exception.CourseNotFoundException;
import com.studybuddy.exception.StudentNotFoundException;
import com.studybuddy.matching.PreferenceSummary;
import com.studybuddy.matching.StudentPublicProfile;
import com.studybuddy.repository.AdminRepository;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.service.auth.AccountCredentials;

import jakarta.persistence.EntityManager;

/**
 * Gaps in StudentProfileServiceTest (a teammate's file, which uses the seeded data): the profile
 * rules' boundaries on both sign-up and update, and the public profile's fields. Empty in-memory
 * H2 with own data; each test is rolled back. Alex (alex@test.com) is a student taking IS442:
 * name Alex Tan, school SCIS, programme Information Systems, year 2, contact 91234567.
 * flushAndClear() writes changes to the database and forgets cached objects, so the checks
 * after it read what was really saved.
 */
@DataJpaTest
@Import({ StudentProfileService.class, AccountCredentials.class })
class StudentProfileServiceGapTest {

    private static final String ALEX = "alex@test.com";
    private static final long UNKNOWN_ID = 999_999L;
    private static final String DETAILS_REQUIRED = "Name, school and programme are required";
    private static final String YEAR_RANGE = "Year of study must be between 1 and 5";
    private static final String CONTACT_RULE = "Contact number must be 8 digits";

    @TestConfiguration
    static class PasswordConfig {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }
    }

    @Autowired
    private StudentProfileService service;
    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private CourseRepository courseRepository;
    @Autowired
    private AdminRepository adminRepository;
    @Autowired
    private EntityManager entityManager;

    private Course is442;

    @BeforeEach
    void setUp() {
        is442 = courseRepository.save(new Course("IS442", "Object Oriented Programming"));
        Student alex = new Student(ALEX, "dummyHash", "Alex Tan", "SCIS", "Information Systems", 2, "91234567");
        alex.addCourse(is442);
        studentRepository.save(alex);
        flushAndClear();
    }

    // S1, PRF-1: years 1 and 5 are allowed on sign-up, 0 and 6 aren't
    @Test
    void yearOfStudyBoundariesOnSignUp() {
        assertThat(signUp("year1@test.com", "Year One", "SCIS", "IS", 1, "91234567").getYearOfStudy()).isEqualTo(1);
        assertThat(signUp("year5@test.com", "Year Five", "SCIS", "IS", 5, "91234567").getYearOfStudy()).isEqualTo(5);

        for (int year : new int[] { 0, 6, -1 }) {
            assertThatThrownBy(() -> signUp("bad@test.com", "Bad Year", "SCIS", "IS", year, "91234567"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage(YEAR_RANGE);
        }
        assertThat(studentRepository.findByEmail("bad@test.com")).isEmpty();
    }

    // S1, PRF-1: the same boundaries on update; a refused update changes nothing
    @Test
    void yearOfStudyBoundariesOnUpdate() {
        service.updateProfile(ALEX, "Alex Tan", "SCIS", "Information Systems", 5, "91234567");
        service.updateProfile(ALEX, "Alex Tan", "SCIS", "Information Systems", 1, "91234567");

        for (int year : new int[] { 0, 6 }) {
            assertThatThrownBy(() -> service.updateProfile(ALEX, "Changed", "SCIS", "IS", year, "98765432"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage(YEAR_RANGE);
        }
        flushAndClear();

        assertUnchangedExceptYear(1);
    }

    // S1, PRF-1: exactly 8 digits on sign-up
    @Test
    void contactNumberMustBeEightDigitsOnSignUp() {
        assertThat(signUp("eight@test.com", "Eight Digits", "SCIS", "IS", 2, "81234567").getContactNumber())
                .isEqualTo("81234567");

        for (String contact : Arrays.asList("1234567", "123456789", "1234567a", "1234-567", "", null)) {
            assertThatThrownBy(() -> signUp("bad@test.com", "Bad Contact", "SCIS", "IS", 2, contact))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage(CONTACT_RULE);
        }
        assertThat(studentRepository.findByEmail("bad@test.com")).isEmpty();
    }

    // S1, PRF-1: exactly 8 digits on update; a refused update changes nothing
    @Test
    void contactNumberMustBeEightDigitsOnUpdate() {
        for (String contact : Arrays.asList("1234567", "123456789", "abcdefgh", null)) {
            assertThatThrownBy(() -> service.updateProfile(ALEX, "Changed", "SCIS", "IS", 3, contact))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage(CONTACT_RULE);
        }
        flushAndClear();

        assertUnchangedExceptYear(2);
    }

    // PRF-1, K10: pins current behaviour, update if fixed. The service doesn't strip the contact number
    // (only the profile page's controller does), so one with a space is refused here
    @Test
    void contactNumberWithSpacesIsRefusedByTheService() {
        assertThatThrownBy(() -> service.updateProfile(ALEX, "Alex Tan", "SCIS", "IS", 2, " 91234567 "))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(CONTACT_RULE);
        assertThatThrownBy(() -> signUp("space@test.com", "Space", "SCIS", "IS", 2, "9123 4567"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(CONTACT_RULE);
    }

    // S1, PRF-1: name, school and programme are required on sign-up (null, empty or only spaces)
    @Test
    void blankDetailsAreRefusedOnSignUp() {
        for (String blank : Arrays.asList(null, "", "   ")) {
            assertThatThrownBy(() -> signUp("bad@test.com", blank, "SCIS", "IS", 2, "91234567"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage(DETAILS_REQUIRED);
            assertThatThrownBy(() -> signUp("bad@test.com", "Name", blank, "IS", 2, "91234567"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage(DETAILS_REQUIRED);
            assertThatThrownBy(() -> signUp("bad@test.com", "Name", "SCIS", blank, 2, "91234567"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage(DETAILS_REQUIRED);
        }
        assertThat(studentRepository.findByEmail("bad@test.com")).isEmpty();
    }

    // S1, PRF-1: the same on update; a refused update changes nothing
    @Test
    void blankDetailsAreRefusedOnUpdate() {
        for (String blank : Arrays.asList(null, "", "   ")) {
            assertThatThrownBy(() -> service.updateProfile(ALEX, blank, "SCIS", "IS", 3, "98765432"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage(DETAILS_REQUIRED);
            assertThatThrownBy(() -> service.updateProfile(ALEX, "Changed", blank, "IS", 3, "98765432"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage(DETAILS_REQUIRED);
            assertThatThrownBy(() -> service.updateProfile(ALEX, "Changed", "SCIS", blank, 3, "98765432"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage(DETAILS_REQUIRED);
        }
        flushAndClear();

        assertUnchangedExceptYear(2);
    }

    // S1, PRF-1: name, school and programme are saved without surrounding spaces, on both paths
    @Test
    void detailsAreStripped() {
        Student created = signUp("new@test.com", "  New Student ", " SCIS ", " Economics  ", 1, "81234567");
        service.updateProfile(ALEX, "  Alex T ", " SOE ", " Economics ", 3, "98765432");
        flushAndClear();

        Student saved = studentRepository.findById(created.getUserId()).orElseThrow();
        assertThat(saved.getName()).isEqualTo("New Student");
        assertThat(saved.getSchool()).isEqualTo("SCIS");
        assertThat(saved.getProgramme()).isEqualTo("Economics");
        Student alex = studentRepository.findByEmail(ALEX).orElseThrow();
        assertThat(alex.getName()).isEqualTo("Alex T");
        assertThat(alex.getSchool()).isEqualTo("SOE");
        assertThat(alex.getContactNumber()).isEqualTo("98765432");
    }

    // S6, PRF-3, REQ-4: what other students see has no contact number and no email
    @Test
    void publicProfileHasNoContactNumberOrEmail() {
        Long id = studentRepository.findByEmail(ALEX).orElseThrow().getUserId();

        StudentPublicProfile profile = service.getPublicProfile(id);

        assertThat(profile.name()).isEqualTo("Alex Tan");
        assertThat(profile.courseCodes()).containsExactly("IS442");
        assertThat(Arrays.stream(StudentPublicProfile.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("studentId", "name", "school", "programme", "yearOfStudy", "courseCodes");
        assertThat(profile.toString()).doesNotContain("91234567").doesNotContain(ALEX);
    }

    // S1, S6, PRF-2: unknown students and courses are reported, not ignored
    @Test
    void unknownStudentOrCourseIsNotFound() {
        assertThatThrownBy(() -> service.getPublicProfile(UNKNOWN_ID)).isInstanceOf(StudentNotFoundException.class);
        assertThatThrownBy(() -> service.getProfile("ghost@test.com")).isInstanceOf(StudentNotFoundException.class);
        assertThatThrownBy(() -> service.updateProfile("ghost@test.com", "Ghost", "SCIS", "IS", 2, "91234567"))
                .isInstanceOf(StudentNotFoundException.class);
        assertThatThrownBy(() -> service.addCourse(ALEX, UNKNOWN_ID)).isInstanceOf(CourseNotFoundException.class);
        assertThatThrownBy(() -> service.removeCourse(ALEX, UNKNOWN_ID)).isInstanceOf(CourseNotFoundException.class);
        flushAndClear();

        assertThat(service.getCoursesTaken(ALEX)).extracting(Course::getCourseCode).containsExactly("IS442");
    }

    // S1, PRF-1, K4: pins current behaviour, update if fixed. Nothing limits the length before the
    // database does: 255 characters fit, 256 are refused when the change is written
    @Test
    void profileValueOver255CharactersFailsInDatabase() {
        service.updateProfile(ALEX, "N".repeat(255), "SCIS", "IS", 2, "91234567");
        flushAndClear();
        assertThat(service.getProfile(ALEX).getName()).hasSize(255);

        service.updateProfile(ALEX, "N".repeat(256), "SCIS", "IS", 2, "91234567");
        // In the app this happens when the service's transaction commits; flush() forces it here
        assertThatThrownBy(() -> studentRepository.flush()).isInstanceOf(DataIntegrityViolationException.class);
    }

    // S6, PRF-3, REQ-4: the public profile page lists only that student's study preferences, which
    // carry no contact number or email
    @Test
    void publicPreferencesListOnlyThatStudentsPreferences() {
        Course cs201 = courseRepository.save(new Course("CS201", "Data Structures and Algorithms"));
        Student alex = studentRepository.findByEmail(ALEX).orElseThrow();
        alex.addPreference(preference(is442, DayOfWeek.WEDNESDAY));
        Student ben = new Student("ben@test.com", "dummyHash", "Ben Koh", "SCIS", "IS", 3, "92345678");
        ben.addCourse(cs201);
        ben.addPreference(preference(cs201, DayOfWeek.FRIDAY));
        studentRepository.save(ben);
        flushAndClear();

        List<PreferenceSummary> alexs = service.getPublicPreferences(alex.getUserId());

        assertThat(alexs).extracting(PreferenceSummary::courseCode).containsExactly("IS442");
        assertThat(alexs.get(0).availability()).containsExactly("Wed 19:00-21:00");
        assertThat(Arrays.stream(PreferenceSummary.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("courseId", "courseCode", "courseName", "studyMode", "arrangement", "studyGoal",
                        "availability");
        assertThat(alexs.toString()).doesNotContain("91234567").doesNotContain(ALEX);
    }

    // S6: a student with no study preferences has an empty list, not an error
    @Test
    void studentWithoutPreferencesHasNoPublicPreferences() {
        Long id = studentRepository.findByEmail(ALEX).orElseThrow().getUserId();

        assertThat(service.getPublicPreferences(id)).isEmpty();
    }

    // S6, D3: an unknown id, or an admin's, has no public study profile
    @Test
    void unknownOrAdminIdHasNoPublicProfile() {
        Long adminId = adminRepository.save(new Admin("admin@test.com", "dummyHash")).getUserId();

        for (Long id : List.of(UNKNOWN_ID, adminId)) {
            assertThatThrownBy(() -> service.getPublicProfile(id)).isInstanceOf(StudentNotFoundException.class);
            assertThatThrownBy(() -> service.getPublicPreferences(id)).isInstanceOf(StudentNotFoundException.class);
        }
    }

    // In person, Either group size, Exam preparation, 19:00-21:00 on the given day
    private static StudyPreference preference(Course course, DayOfWeek day) {
        StudyPreference preference = new StudyPreference(course, StudyMode.IN_PERSON, Arrangement.EITHER,
                StudyGoal.EXAM_PREPARATION);
        preference.addAvailabilitySlot(new AvailabilitySlot(day, LocalTime.of(19, 0), LocalTime.of(21, 0)));
        return preference;
    }

    private Student signUp(String email, String name, String school, String programme, int year, String contact) {
        return service.createProfile(email, "password123", name, school, programme, year, contact);
    }

    private void assertUnchangedExceptYear(int year) {
        Student alex = studentRepository.findByEmail(ALEX).orElseThrow();
        assertThat(alex.getName()).isEqualTo("Alex Tan");
        assertThat(alex.getSchool()).isEqualTo("SCIS");
        assertThat(alex.getProgramme()).isEqualTo("Information Systems");
        assertThat(alex.getYearOfStudy()).isEqualTo(year);
        assertThat(alex.getContactNumber()).isEqualTo("91234567");
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}

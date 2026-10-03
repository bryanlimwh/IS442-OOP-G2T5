package com.studybuddy.service.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.studybuddy.entity.Course;
import com.studybuddy.entity.Student;
import com.studybuddy.matching.StudentPublicProfile;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.StudentRepository;

import jakarta.persistence.EntityManager;

/**
 * Uses the seeded students (Alex and Ben). Each test is rolled back afterwards.
 * flushAndClear() writes changes to the database and forgets cached objects,
 * so the checks after it read what was really saved.
 */
@SpringBootTest
@Transactional
class StudentProfileServiceTest {

    private static final String ALEX = "student@studybuddy.com";
    private static final String BEN = "ben.koh@studybuddy.com"; // takes IS442, IS216, CS201

    @Autowired
    private StudentProfileService service;
    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private CourseRepository courseRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void updatedProfileIsSaved() {
        service.updateProfile(ALEX, "Alex Tan", "SCIS", "Computer Science", 3, "87654321");
        flushAndClear();

        Student saved = studentRepository.findByEmail(ALEX).orElseThrow();
        assertThat(saved.getProgramme()).isEqualTo("Computer Science");
        assertThat(saved.getYearOfStudy()).isEqualTo(3);
        assertThat(saved.getContactNumber()).isEqualTo("87654321");
    }

    @Test
    void invalidProfileIsRejected() {
        assertThatThrownBy(() -> service.updateProfile(ALEX, " ", "SCIS", "IS", 2, "91234567"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateProfile(ALEX, "Alex", "SCIS", "IS", 0, "91234567"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateProfile(ALEX, "Alex", "SCIS", "IS", 2, "1234"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicProfileHidesContactDetails() {
        Long benId = studentRepository.findByEmail(BEN).orElseThrow().getUserId();

        StudentPublicProfile profile = service.getPublicProfile(benId);

        assertThat(profile.name()).isEqualTo("Ben Koh");
        assertThat(profile.toString()).doesNotContain("92345678", BEN);
    }

    @Test
    void addAndRemoveCourse() {
        service.addCourse(BEN, courseId("IS111"));
        service.removeCourse(BEN, courseId("CS201"));
        flushAndClear();

        assertThat(service.getCoursesTaken(BEN)).extracting(Course::getCourseCode)
                .containsExactlyInAnyOrder("IS442", "IS216", "IS111");
    }

    @Test
    void courseRulesAreEnforced() {
        // Already taking it
        assertThatThrownBy(() -> service.addCourse(BEN, courseId("IS442")))
                .isInstanceOf(IllegalStateException.class);
        // Not taking it
        assertThatThrownBy(() -> service.removeCourse(BEN, courseId("IS111")))
                .isInstanceOf(IllegalStateException.class);
        // Ben still has an IS442 study preference
        assertThatThrownBy(() -> service.removeCourse(BEN, courseId("IS442")))
                .isInstanceOf(IllegalStateException.class);
    }

    private Long courseId(String code) {
        return courseRepository.findAll().stream()
                .filter(course -> course.getCourseCode().equals(code))
                .findFirst().orElseThrow().getCourseId();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}

package com.studybuddy.service.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import com.studybuddy.entity.Course;
import com.studybuddy.entity.Student;
import com.studybuddy.exception.CourseNotFoundException;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.StudentRepository;

import jakarta.persistence.EntityManager;

/**
 * CourseService against an empty in-memory H2 (no seeders); each test is rolled back.
 * The service checks nothing itself: blank values are refused by CourseController and
 * admin-only access by its @PreAuthorize (see CourseControllerTest).
 * flushAndClear() writes changes to the database and forgets cached objects, so the
 * checks after it read what was really saved.
 */
@DataJpaTest
@Import(CourseService.class)
class CourseServiceTest {

    private static final long UNKNOWN_ID = 999_999L;

    @Autowired
    private CourseService service;
    @Autowired
    private CourseRepository courseRepository;
    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private EntityManager entityManager;

    // CRS-1
    @Test
    void createCourseSavesCodeAndName() {
        Course created = service.createCourse("IS442", "Object Oriented Programming");
        flushAndClear();

        Course saved = courseRepository.findById(created.getCourseId()).orElseThrow();
        assertThat(saved.getCourseCode()).isEqualTo("IS442");
        assertThat(saved.getCourseName()).isEqualTo("Object Oriented Programming");
    }

    // CRS-1
    @Test
    void getAllCoursesReturnsEveryCourse() {
        service.createCourse("IS442", "Object Oriented Programming");
        service.createCourse("CS201", "Data Structures and Algorithms");

        assertThat(service.getAllCourses()).extracting(Course::getCourseCode)
                .containsExactlyInAnyOrder("IS442", "CS201");
    }

    // CRS-1
    @Test
    void unknownCourseIsNotFound() {
        assertThatThrownBy(() -> service.getCourseById(UNKNOWN_ID)).isInstanceOf(CourseNotFoundException.class);
        assertThatThrownBy(() -> service.updateCourse(UNKNOWN_ID, "IS442", "Object Oriented Programming"))
                .isInstanceOf(CourseNotFoundException.class);
        assertThatThrownBy(() -> service.deleteCourse(UNKNOWN_ID)).isInstanceOf(CourseNotFoundException.class);

        // Updating a missing course doesn't create one
        assertThat(courseRepository.count()).isZero();
    }

    // CRS-1
    @Test
    void updateCourseChangesCodeAndName() {
        Long id = service.createCourse("IS442", "OOP").getCourseId();

        service.updateCourse(id, "IS443", "Object Oriented Programming II");
        flushAndClear();

        Course saved = courseRepository.findById(id).orElseThrow();
        assertThat(saved.getCourseCode()).isEqualTo("IS443");
        assertThat(saved.getCourseName()).isEqualTo("Object Oriented Programming II");
    }

    // CRS-1
    @Test
    void deleteCourseRemovesIt() {
        Long id = service.createCourse("IS442", "Object Oriented Programming").getCourseId();

        service.deleteCourse(id);
        flushAndClear();

        assertThat(courseRepository.findById(id)).isEmpty();
    }

    // CRS-1: in the app the database refuses at commit; flush() forces it here
    @Test
    void deletingCourseInUseFails() {
        Course is442 = service.createCourse("IS442", "Object Oriented Programming");
        Student alex = studentRepository.save(new Student("alex@test.com", "dummyHash", "Alex Tan",
                "School of Computing and Information Systems", "Information Systems", 2, "91234567"));
        alex.addCourse(is442);
        flushAndClear();

        service.deleteCourse(is442.getCourseId());

        assertThatThrownBy(() -> courseRepository.flush()).isInstanceOf(DataIntegrityViolationException.class);
    }

    // CRS-1: the service saves whatever it is given; CourseController is the only blank check
    @Test
    void serviceSavesBlankValues() {
        Long id = service.createCourse("", " ").getCourseId();
        flushAndClear();

        assertThat(courseRepository.findById(id).orElseThrow().getCourseCode()).isEmpty();
    }

    // CRS-1, K4: pins current behaviour, update if fixed. Nothing limits the length before the
    // database does, so a code over 255 characters reaches it and is refused there.
    @Test
    void courseCodeOver255CharactersFailsInDatabase() {
        // 255 is the column size and still fits
        assertThat(service.createCourse("C".repeat(255), "Long code").getCourseId()).isNotNull();

        assertThatThrownBy(() -> service.createCourse("C".repeat(256), "Too long"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}

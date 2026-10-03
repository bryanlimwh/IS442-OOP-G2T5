package com.studybuddy.service.student;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.studybuddy.entity.Course;
import com.studybuddy.entity.Student;
import com.studybuddy.exception.CourseNotFoundException;
import com.studybuddy.exception.StudentNotFoundException;
import com.studybuddy.matching.StudentPublicProfile;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.StudentRepository;

/**
 * A student viewing and editing their own profile and courses.
 * The student is identified by their login email, so they can only edit their own.
 */
@Service
// Keeps the database session open for lazy lists, saves entity changes when each
// method ends, and undoes everything if a method throws.
@Transactional
public class StudentProfileService {

    private static final int MAX_YEAR_OF_STUDY = 5; // allows for double degrees
    private static final String CONTACT_NUMBER_PATTERN = "\\d{8}"; // exactly 8 digits

    private final StudentRepository studentRepository;
    private final CourseRepository courseRepository;

    public StudentProfileService(StudentRepository studentRepository, CourseRepository courseRepository) {
        this.studentRepository = studentRepository;
        this.courseRepository = courseRepository;
    }

    // The student's own full profile, including contact number. Don't show it to other students.
    public Student getProfile(String email) {
        return findStudent(email);
    }

    // What other students may see: no contact number or email.
    public StudentPublicProfile getPublicProfile(Long studentId) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new StudentNotFoundException(studentId));
        return StudentPublicProfile.from(student);
    }

    public Student updateProfile(String email, String name, String school, String programme,
            int yearOfStudy, String contactNumber) {
        if (isBlank(name) || isBlank(school) || isBlank(programme)) {
            throw new IllegalArgumentException("Name, school and programme are required");
        }
        if (yearOfStudy < 1 || yearOfStudy > MAX_YEAR_OF_STUDY) {
            throw new IllegalArgumentException("Year of study must be between 1 and " + MAX_YEAR_OF_STUDY);
        }
        if (contactNumber == null || !contactNumber.matches(CONTACT_NUMBER_PATTERN)) {
            throw new IllegalArgumentException("Contact number must be 8 digits");
        }
        Student student = findStudent(email);
        student.setName(name);
        student.setSchool(school);
        student.setProgramme(programme);
        student.setYearOfStudy(yearOfStudy);
        student.setContactNumber(contactNumber);
        return student;
    }

    public List<Course> getCoursesTaken(String email) {
        return List.copyOf(findStudent(email).getCoursesTaken());
    }

    public void addCourse(String email, Long courseId) {
        Student student = findStudent(email);
        Course course = findCourse(courseId);
        if (student.getCoursesTaken().contains(course)) {
            throw new IllegalStateException("You are already taking " + course.getCourseCode());
        }
        student.addCourse(course);
    }

    // Blocked while the student has a study preference for the course, so they
    // aren't matched on a course they no longer take.
    public void removeCourse(String email, Long courseId) {
        Student student = findStudent(email);
        Course course = findCourse(courseId);
        if (!student.getCoursesTaken().contains(course)) {
            throw new IllegalStateException("You are not taking " + course.getCourseCode());
        }
        if (student.getPreferences().stream().anyMatch(p -> p.getCourse().equals(course))) {
            throw new IllegalStateException("Delete your " + course.getCourseCode()
                    + " study preference before removing the course");
        }
        student.getCoursesTaken().remove(course);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Course findCourse(Long courseId) {
        return courseRepository.findById(courseId).orElseThrow(() -> new CourseNotFoundException(courseId));
    }

    private Student findStudent(String email) {
        return studentRepository.findByEmail(email).orElseThrow(() -> new StudentNotFoundException(email));
    }
}

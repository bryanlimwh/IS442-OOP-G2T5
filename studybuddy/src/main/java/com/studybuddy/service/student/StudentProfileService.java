package com.studybuddy.service.student;

import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.studybuddy.entity.Course;
import com.studybuddy.entity.Student;
import com.studybuddy.exception.CourseNotFoundException;
import com.studybuddy.exception.StudentNotFoundException;
import com.studybuddy.matching.PreferenceSummary;
import com.studybuddy.matching.StudentPublicProfile;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.service.auth.AccountCredentials;

/**
 * A student creating their profile (signing up), then viewing and editing it
 * and their courses. After sign-up the student is identified by their login
 * email, so they can only edit their own.
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
    // Login email and password rules, shared with the admin account page
    private final AccountCredentials credentials;
    // The BCrypt encoder login checks against, so the new password works straight away
    private final PasswordEncoder passwordEncoder;

    public StudentProfileService(StudentRepository studentRepository, CourseRepository courseRepository,
            AccountCredentials credentials, PasswordEncoder passwordEncoder) {
        this.studentRepository = studentRepository;
        this.courseRepository = courseRepository;
        this.credentials = credentials;
        this.passwordEncoder = passwordEncoder;
    }

    // Sign-up: creates an active student account with its profile. Courses and study
    // preferences are added afterwards from the profile pages, once they've logged in.
    public Student createProfile(String email, String rawPassword, String name, String school,
            String programme, int yearOfStudy, String contactNumber) {
        checkProfileDetails(name, school, programme, yearOfStudy, contactNumber);
        String normalisedEmail = credentials.normaliseEmail(email);
        credentials.checkPassword(rawPassword);
        credentials.requireEmailAvailable(normalisedEmail, null);

        return studentRepository.save(new Student(normalisedEmail, passwordEncoder.encode(rawPassword),
                name.strip(), school.strip(), programme.strip(), yearOfStudy, contactNumber));
    }

    // The student's own full profile, including contact number. Don't show it to other students.
    public Student getProfile(String email) {
        return findStudent(email);
    }

    // What other students may see: no contact number or email. A deactivated student's profile is
    // treated as unknown (team decision TD-6), as on the matches page.
    public StudentPublicProfile getPublicProfile(Long studentId) {
        return StudentPublicProfile.from(findActiveStudent(studentId));
    }

    // The study preferences shown on another student's public profile, copied out of the
    // entities so the page never holds a Student (and its contact number).
    public List<PreferenceSummary> getPublicPreferences(Long studentId) {
        return findActiveStudent(studentId).getPreferences().stream()
                .map(PreferenceSummary::from)
                .toList();
    }

    public Student updateProfile(String email, String name, String school, String programme,
            int yearOfStudy, String contactNumber) {
        checkProfileDetails(name, school, programme, yearOfStudy, contactNumber);
        Student student = findStudent(email);
        student.setName(name.strip());
        student.setSchool(school.strip());
        student.setProgramme(programme.strip());
        student.setYearOfStudy(yearOfStudy);
        student.setContactNumber(contactNumber);
        return student;
    }

    // The same rules whether the profile is being created or updated.
    private void checkProfileDetails(String name, String school, String programme,
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

    private Student findActiveStudent(Long studentId) {
        return studentRepository.findById(studentId)
                .filter(Student::isActive)
                .orElseThrow(() -> new StudentNotFoundException(studentId));
    }

    private Student findStudent(String email) {
        return studentRepository.findByEmail(email).orElseThrow(() -> new StudentNotFoundException(email));
    }
}

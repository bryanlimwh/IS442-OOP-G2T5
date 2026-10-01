package com.studybuddy.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.studybuddy.entity.Course;
import com.studybuddy.entity.Student;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.StudentRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

// Seeds the 50 student profiles the brief asks for. Runs after
// CourseDataSeeder (each student is enrolled in some of its courses) and
// before StudyPreferenceDataSeeder, which only sets preferences for courses a
// student is taking.
//
// The first two students are written out by hand: Alex Tan is the demo login,
// and Ben Koh is "Student B" from the brief's Appendix A example. Everyone else
// gets a school and programme in turn and three random courses. The random
// generator uses a fixed seed, so every run seeds exactly the same data.
@Component
@Order(2)
public class StudentDataSeeder implements CommandLineRunner {

    private static final String EMAIL_DOMAIN = "@studybuddy.com";
    // Package-private so StudyPreferenceDataSeeder can find these two students again.
    static final String DEMO_STUDENT_EMAIL = "student@studybuddy.com";
    static final String APPENDIX_B_STUDENT_EMAIL = "ben.koh@studybuddy.com";
    private static final long RANDOM_SEED = 442L;
    private static final int COURSES_PER_STUDENT = 3;

    // { school, programme } pairs, handed out to the generated students in turn.
    private static final String[][] SCHOOL_PROGRAMMES = {
            { "School of Computing and Information Systems", "Information Systems" },
            { "School of Computing and Information Systems", "Computer Science" },
            { "Lee Kong Chian School of Business", "Business Management" },
            { "School of Accountancy", "Accountancy" },
            { "School of Economics", "Economics" },
            { "Yong Pung How School of Law", "Law" },
            { "School of Social Sciences", "Social Sciences" },
    };

    private static final List<String> GENERATED_STUDENT_NAMES = List.of(
            "Chloe Lim", "Daniel Ng", "Emily Wong", "Farhan Ismail", "Grace Lee",
            "Hannah Goh", "Isaac Chua", "Jasmine Teo", "Kevin Ong", "Lydia Tan",
            "Marcus Yeo", "Nadia Rahman", "Oliver Sim", "Priya Nair", "Qi Hui Chen",
            "Ryan Low", "Sarah Koh", "Timothy Ho", "Umairah Aziz", "Vincent Lau",
            "Wei Ling Toh", "Xavier Phua", "Yasmin Salleh", "Zachary Foo", "Amelia Seah",
            "Bryan Quek", "Cheryl Ang", "Darren Kwek", "Elaine Soh", "Felix Tay",
            "Gabriel Loh", "Hui Min Ong", "Irfan Hassan", "Joanne Pang", "Kai Wen Lim",
            "Li Ting Ho", "Matthew Chong", "Nicole Yap", "Owen Tan", "Pei Shan Lee",
            "Rachel Tham", "Samuel Kang", "Tessa Lim", "Uma Pillai", "Victor Heng",
            "Wen Jie Liu", "Yi Xuan Goh", "Zoe Chia");

    private final StudentRepository studentRepository;
    private final CourseRepository courseRepository;
    private final PasswordEncoder passwordEncoder;
    private final String seedPassword;

    public StudentDataSeeder(StudentRepository studentRepository, CourseRepository courseRepository,
            PasswordEncoder passwordEncoder, @Value("${studybuddy.seed.password}") String seedPassword) {
        this.studentRepository = studentRepository;
        this.courseRepository = courseRepository;
        this.passwordEncoder = passwordEncoder;
        this.seedPassword = seedPassword;
    }

    @Override
    public void run(String... args) {
        if (studentRepository.count() > 0) {
            return;
        }
        List<Course> courses = courseRepository.findAll();
        Map<String, Course> coursesByCode = courses.stream()
                .collect(Collectors.toMap(Course::getCourseCode, Function.identity()));
        // Hashing is deliberately slow, so hash once and share it: every seeded account has the same password.
        String passwordHash = passwordEncoder.encode(seedPassword);
        List<Student> students = new ArrayList<>();

        Student alex = new Student(DEMO_STUDENT_EMAIL, passwordHash,
                "Alex Tan", "School of Computing and Information Systems",
                "Information Systems", 2, "91234567");
        enrol(alex, coursesByCode, "IS111", "IS112", "IS442", "CS201");
        students.add(alex);

        Student ben = new Student(APPENDIX_B_STUDENT_EMAIL, passwordHash,
                "Ben Koh", "School of Computing and Information Systems",
                "Information Systems", 3, "92345678");
        enrol(ben, coursesByCode, "IS442", "IS216", "CS201");
        students.add(ben);

        Random random = new Random(RANDOM_SEED);
        for (int i = 0; i < GENERATED_STUDENT_NAMES.size(); i++) {
            String name = GENERATED_STUDENT_NAMES.get(i);
            String[] schoolProgramme = SCHOOL_PROGRAMMES[i % SCHOOL_PROGRAMMES.length];
            Student student = new Student(toEmail(name), passwordHash, name,
                    schoolProgramme[0], schoolProgramme[1], 1 + i % 4, contactNumberFor(i));

            List<Course> shuffled = new ArrayList<>(courses);
            Collections.shuffle(shuffled, random);
            shuffled.subList(0, COURSES_PER_STUDENT).forEach(student::addCourse);
            students.add(student);
        }

        studentRepository.saveAll(students);
        System.out.println("Seeded " + students.size() + " students (demo login: " + alex.getEmail() + ")");
    }

    private void enrol(Student student, Map<String, Course> coursesByCode, String... courseCodes) {
        for (String code : courseCodes) {
            student.addCourse(coursesByCode.get(code));
        }
    }

    // "Qi Hui Chen" -> "qi.hui.chen@studybuddy.com"
    private String toEmail(String name) {
        return name.toLowerCase().replace(' ', '.') + EMAIL_DOMAIN;
    }

    // Distinct 8-digit Singapore-style mobile numbers, all starting with 8.
    private String contactNumberFor(int index) {
        return String.format("8%07d", 1000000 + index * 13579);
    }
}

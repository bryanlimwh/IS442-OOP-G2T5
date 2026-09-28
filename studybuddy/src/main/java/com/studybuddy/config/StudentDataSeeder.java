package com.studybuddy.config;

import com.studybuddy.domain.Student;
import com.studybuddy.repository.StudentRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

// Runs after CourseDataSeeder and before StudyPreferenceDataSeeder, which
// attaches its seeded preferences to this student.
@Component
@Order(2)
public class StudentDataSeeder implements CommandLineRunner {

    private final StudentRepository studentRepository;
    private final PasswordEncoder passwordEncoder;
    private final String seedPassword;

    public StudentDataSeeder(StudentRepository studentRepository, PasswordEncoder passwordEncoder,
            @Value("${studybuddy.seed.password}") String seedPassword) {
        this.studentRepository = studentRepository;
        this.passwordEncoder = passwordEncoder;
        this.seedPassword = seedPassword;
    }

    @Override
    public void run(String... args) {
        if (studentRepository.count() == 0) {
            Student student = new Student("student@studybuddy.com", passwordEncoder.encode(seedPassword),
                    "Alex Tan", "School of Computing and Information Systems",
                    "Information Systems", 2, "91234567");
            studentRepository.save(student);
            System.out.println("Seeded test student: " + student.getDisplayName());
        }
    }
}

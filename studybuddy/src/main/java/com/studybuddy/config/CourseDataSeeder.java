package com.studybuddy.config;

import java.util.List;

import com.studybuddy.entity.Course;
import com.studybuddy.repository.CourseRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// Runs first: StudyPreferenceDataSeeder needs these courses to exist.
@Component
@Order(1)
public class CourseDataSeeder implements CommandLineRunner {

    private final CourseRepository courseRepository;

    public CourseDataSeeder(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    @Override
    public void run(String... args) {
        if (courseRepository.count() == 0) {
            List<Course> courses = List.of(
                    new Course("IS111", "Introduction to Programming"),
                    new Course("IS112", "Data Management"),
                    new Course("IS216", "Web Application Development II"),
                    new Course("IS442", "Object Oriented Programming"),
                    new Course("CS201", "Data Structures and Algorithms"),
                    new Course("ACCT101", "Financial Accounting"),
                    new Course("ECON101", "Principles of Economics"),
                    new Course("MKTG101", "Principles of Marketing"),
                    new Course("STAT151", "Statistics for Business"),
                    new Course("LAW101", "Business Law"));
            courseRepository.saveAll(courses);
            System.out.println("Seeded " + courses.size() + " courses");
        }
    }
}

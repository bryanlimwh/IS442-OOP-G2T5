package com.studybuddy.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.studybuddy.entity.Course;

public interface CourseRepository extends JpaRepository<Course, Long> {
}

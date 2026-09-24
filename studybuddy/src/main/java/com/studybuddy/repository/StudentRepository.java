package com.studybuddy.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.studybuddy.domain.Student;

public interface StudentRepository extends JpaRepository<Student, Long> {
}

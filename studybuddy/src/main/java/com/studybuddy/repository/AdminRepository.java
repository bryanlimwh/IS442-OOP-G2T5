package com.studybuddy.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.studybuddy.domain.Admin;

public interface AdminRepository extends JpaRepository<Admin, Long> {
}
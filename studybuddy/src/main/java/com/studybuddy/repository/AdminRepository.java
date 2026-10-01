package com.studybuddy.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.studybuddy.entity.Admin;

public interface AdminRepository extends JpaRepository<Admin, Long> {
}
package com.studybuddy.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.studybuddy.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {
    User findByEmail(String email);
}
package com.studybuddy.matcher.repository;

import com.studybuddy.matcher.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    User findByEmail(String email);
}
package com.studybuddy.config;

import com.studybuddy.entity.Admin;
import com.studybuddy.repository.AdminRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class AdminDataSeeder implements CommandLineRunner {

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final String seedPassword;

    public AdminDataSeeder(AdminRepository adminRepository, PasswordEncoder passwordEncoder,
            @Value("${studybuddy.seed.password}") String seedPassword) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.seedPassword = seedPassword;
    }

    @Override
    public void run(String... args) {
        if (adminRepository.count() == 0) {
            Admin admin = new Admin("admin@studybuddy.com", passwordEncoder.encode(seedPassword));
            adminRepository.save(admin);
            System.out.println("Seeded test admin: " + admin.getDisplayName());
        }
    }
}
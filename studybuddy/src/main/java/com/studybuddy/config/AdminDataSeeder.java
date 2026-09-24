package com.studybuddy.config;

import com.studybuddy.domain.Admin;
import com.studybuddy.repository.AdminRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class AdminDataSeeder implements CommandLineRunner {

    private final AdminRepository adminRepository;

    public AdminDataSeeder(AdminRepository adminRepository) {
        this.adminRepository = adminRepository;
    }

    @Override
    public void run(String... args) {
        if (adminRepository.count() == 0) {
            Admin admin = new Admin("admin@studybuddy.com", "temp-password-hash");
            adminRepository.save(admin);
            System.out.println("Seeded test admin: " + admin.getDisplayName());
        }
    }
}
package com.studybuddy.entity;

import com.studybuddy.entity.enums.Role;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "admin") 
// The admin's corresponding row in the 'User' table is tagged with user_type = 'ADMIN'
@DiscriminatorValue("ADMIN")
public class Admin extends User {

    protected Admin() {
        super();
    }

    public Admin(String email, String passwordHash) {
        super(email, passwordHash);
    }

    @Override
    public String getDisplayName() {
        return "Admin: " + getEmail();
    }

    @Override
    public Role getRole() {
        return Role.ADMIN;
    }
}
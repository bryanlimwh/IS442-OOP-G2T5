package com.studybuddy.matcher.domain;

import com.studybuddy.matcher.domain.enums.Role;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
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
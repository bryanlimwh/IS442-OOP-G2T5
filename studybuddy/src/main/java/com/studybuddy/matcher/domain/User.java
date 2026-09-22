package com.studybuddy.matcher.domain;

import com.studybuddy.matcher.domain.enums.Role;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Abstract base class for all system accounts (Student, Admin).
 * Stores the shared identity every login needs. Stays a plain data holder —
 * password verification is delegated to Spring Security's PasswordEncoder,
 * not implemented here.
 */
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "user_type")
public abstract class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long userId;

    private String email;          // unique, not null — the login identifier
    private String passwordHash;   // never a raw password; never logged or serialized
    private LocalDateTime createdAt; // set once, in the constructor
    private boolean active;        // backs the "view account status" requirement

    /**
     * No-arg constructor required by JPA.
     */
    protected User() {
    }

    /**
     * Called via super(...) from Student/Admin constructors only.
     */
    protected User(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = LocalDateTime.now();
        this.active = true;
    }

    // ---- Behaviour ----

    // NOTE: checkPassword(rawPassword, PasswordEncoder) intentionally omitted for now.
    // Spring Security (and PasswordEncoder) is not yet a project dependency.
    // Add it back once the team wires up login/auth — see TODO in README or task board.

    public void changePasswordHash(String newHash) {
        this.passwordHash = newHash;
    }

    /**
     * Student -> name; Admin -> "Admin: " + email
     */
    public abstract String getDisplayName();

    /**
     * Feeds Spring Security's authorities later — a separate concern from
     * the JPA discriminator column, not a duplicate of it.
     */
    public abstract Role getRole();

    // ---- Getters / Setters (no Lombok in this project) ----

    public Long getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    protected String getPasswordHash() {
        return passwordHash;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    // ---- equals()/hashCode(): compare by userId ONLY ----
    // Avoids identity-equality bugs on detached JPA entities.

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User)) return false;
        User other = (User) o;
        return userId != null && userId.equals(other.getUserId());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(userId);
    }
}
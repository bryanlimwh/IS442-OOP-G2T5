/**
 * Abstract Java Class designed to hold methods that are commonly required by other entity objects
 * 
 * Currently contains methods related to generating fields involving date & time
 * Such as 'createdAt', 'updatedAt' (NOT IMPLEMENTED), etc 
 */

package com.studybuddy.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;


// Declares that the class should not be mapped to a table itself
// But that its variables (columns)) are added to every entity class
// and their mapped database table that extends it
@MappedSuperclass
public abstract class BaseEntity {
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // JPA wil call this method automatically just before the INSERT runs
    @PrePersist
    protected void onCreate() {
        // Records the current date and time
        this.createdAt = LocalDateTime.now();
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
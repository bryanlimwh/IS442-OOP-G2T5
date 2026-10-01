package com.studybuddy.entity;

import com.studybuddy.entity.*;

import jakarta.persistence.*;


public class StudyBuddyConnection extends BaseEntity {

    // Marks the variable (column) 'id' as the Primary Key of the table
    @Id
    // Instructs H2 to auto-increment the value for the column 'id'
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    // Specifies that multiple rows (StudyBuddyConnection) in the '' table
    // can belong to 1 row (Student) in the 'student' table
    
    // FetchType.LAZY: the Student is only loaded from H2 when getSender() is called
    // Default for @ManyToOne is FetchType.EAGER so setting LAZY is a common best practice
    @ManyToOne(fetch = FetchType.LAZY)
    // Enforces that the value in this column cannot be null
    // Prevents the value in this column from even being modified
    // by instructing JPA (Hiberante) to exclude it from UPDATE statements
    @JoinColumn(nullable = false, updatable = false)
    private Student student1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(nullable = false, updatable = false)
    private Student student2;

    // Required by JPA (Hibernate) to rebuild MatchRequest objects
    // when it reads rows back from the H2 database
    public StudyBuddyConnection() {}

    public StudyBuddyConnection(Student student1, Student student2) {
        this.student1 = student1;
        this.student2 = student2;
    }

    public long getId() {
        return id;
    }

    public Student getStudent1() {
        return student1;
    }

    public Student getStudent2() {
        return student2;
    }
}

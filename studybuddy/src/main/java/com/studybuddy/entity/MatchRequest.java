package com.studybuddy.entity;

import com.studybuddy.domain.*;
import com.studybuddy.domain.enums.MatchRequestStatus;

// JPA Annotations
import jakarta.persistence.*;

// Tells JPA that this class maps to a database table
@Entity
// Explicitly sets the table name (Optional)
@Table(name = "match_request")
public class MatchRequest extends BaseEntity {

    // Marks the variable (column) 'id' as the Primary Key of the table
    @Id
    // Instructs H2 to auto-increment the value for the column 'id'
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Specifies that multiple rows (MatchRequest) in the 'match_request' table
    // can belong to 1 row (Student) in the 'student' table
    
    // FetchType.LAZY: the Student is only loaded from H2 when getSender() is called
    // Default for @ManyToOne is FetchType.EAGER so setting LAZY is a common best practice
    @ManyToOne(fetch = FetchType.LAZY)
    // Enforces that the value in this column cannot be null
    // Prevents the value in this column from even being modified
    // by instructing JPA (Hiberante) to exclude it from UPDATE statements
    @JoinColumn(name = "sender_id", nullable = false, updatable = false)
    private Student sender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false, updatable = false)
    private Student recipient;

    // JPA will still map variable to database table columns even if @Column is not explicitly indicated
    // The variable 'message' will be mapped to a column called 'message'
    private String message;

    // Saves an enum as its name (e.g. "PENDING") instead of its position number
    // Prevents issues when the order of enums changes later
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MatchRequestStatus status;


    // Required by JPA (Hibernate) to rebuild MatchRequest objects
    // when it reads rows back from the H2 database
    protected MatchRequest() {}

    // For MatchRequestService.java to create MatchRequest objects
    // to insert into the H2 database
    public MatchRequest(Student sender, Student recipient, String message) {
        this.sender = sender;
        this.recipient = recipient;
        this.message = message;
        this.status = MatchRequestStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public Student getSender() {
        return sender;
    }

    public Student getRecipient() {
        return recipient;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public MatchRequestStatus getStatus() {
        return status;
    }

    public void accept() {
        // Verifies that the current request being accepted is a pending request
        if (this.status != MatchRequestStatus.PENDING) {
            throw new IllegalStateException("Only PENDING requests can be accepted");
        }

        // Changes the MatchRequest object's 'status' to "APPROVED"
        // MatchRequestService will call JPA (Hibernate) to UPDATE the appropriate row
        this.status = MatchRequestStatus.ACCEPTED;
    }

    public void reject() {
        // Verifies that the current request being rejected is a pending request
        if (this.status != MatchRequestStatus.PENDING) {
            throw new IllegalStateException("Only PENDING requests can be rejected");
        }

        // Changes the MatchRequest object's 'status' to "REJECTED"
        // MatchRequestService will call JPA (Hibernate) to UPDATE the appropriate row
        this.status = MatchRequestStatus.REJECTED;
    }
}

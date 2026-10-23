package com.studybuddy.entity;

import jakarta.persistence.*;

/**
 * Two students who are study buddies, created when one accepts the other's
 * match request. While it exists, each can see the other's contact number;
 * ending the connection deletes the row, which hides the numbers again.
 * <p>
 * The pair is unordered: student1 is whoever sent the original request, but
 * nothing depends on that, so use involves() and buddyOf() rather than
 * checking student1 / student2 directly.
 */
@Entity
@Table(name = "study_buddy_connection")
public class StudyBuddyConnection extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // LAZY: the Student is only loaded from H2 when it's actually read.
    // updatable = false: a connection never changes partner; it is ended and a new one made.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student1_id", nullable = false, updatable = false)
    private Student student1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student2_id", nullable = false, updatable = false)
    private Student student2;

    // Required by JPA (Hibernate) to rebuild connections read back from H2
    protected StudyBuddyConnection() {}

    public StudyBuddyConnection(Student student1, Student student2) {
        if (student1.equals(student2)) {
            throw new IllegalArgumentException("A student can't be their own study buddy");
        }
        this.student1 = student1;
        this.student2 = student2;
    }

    public boolean involves(Student student) {
        return student1.equals(student) || student2.equals(student);
    }

    // The other student in the pair, from the given student's point of view.
    public Student buddyOf(Student student) {
        if (student1.equals(student)) {
            return student2;
        }
        if (student2.equals(student)) {
            return student1;
        }
        throw new IllegalArgumentException("Student " + student.getUserId() + " is not part of this connection");
    }

    public Long getId() {
        return id;
    }

    public Student getStudent1() {
        return student1;
    }

    public Student getStudent2() {
        return student2;
    }
}

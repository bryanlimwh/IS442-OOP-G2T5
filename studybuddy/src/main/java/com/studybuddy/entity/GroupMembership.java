package com.studybuddy.entity;

import java.time.LocalDateTime;

import com.studybuddy.entity.enums.MembershipStatus;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Association class linking a {@link Student} to a {@link StudyGroup}. It
 * records where that link stands (pending, accepted, rejected, removed, or
 * ended by the group closing), when the request was made and answered, and
 * whether the student has removed the closed group from their My groups page.
 * <p>
 * The constructor and status changes are package-private, so every change
 * goes through {@link StudyGroup}, which checks the group's rules first.
 * A student has at most one row per group; asking again reuses that row.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"student_id", "study_group_id"}))
public class GroupMembership {

    private static final String ALREADY_ANSWERED = "This request has already been answered";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long membershipId;

    @ManyToOne
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    // Owning side of StudyGroup.memberships: this is where the study_group_id FK lives.
    @ManyToOne
    @JoinColumn(name = "study_group_id", nullable = false)
    private StudyGroup studyGroup;

    @Enumerated(EnumType.STRING)
    private MembershipStatus status;

    private LocalDateTime requestedAt;  // the time the student asked to join; reset on a re-request
    private LocalDateTime respondedAt;  // the time the leader accepted or rejected; null while pending

    // Only this student's view: the group and its history stay, and other students still see it.
    private boolean hiddenFromMyGroups;

    /**
     * No-arg constructor required by JPA.
     */
    protected GroupMembership() {
    }

    /**
     * Creates a pending request.
     * <p>
     * Package-private (no {@code public}) so memberships can only be created
     * by {@link StudyGroup#requestToJoin} (which checks the group is active,
     * not full and the student isn't already in it) or by the StudyGroup
     * constructor for the leader.
     */
    GroupMembership(Student student, StudyGroup studyGroup) {
        this.student = student;
        this.studyGroup = studyGroup;
        this.status = MembershipStatus.PENDING;
        this.requestedAt = LocalDateTime.now();
    }

    // ---- Behaviour ----

    /**
     * PENDING -> GROUP_CLOSED, when the group closes before the leader has
     * answered. Kept apart from REJECTED so the student's My groups page can
     * say the group closed instead of reading as the leader turning them down.
     * <p>
     * Package-private (no {@code public}) so it can only be reached through
     * {@link StudyGroup#close}.
     */
    void endBecauseGroupClosed() {
        requireStatus(MembershipStatus.PENDING, "Only a pending request can end when the group closes");
        this.status = MembershipStatus.GROUP_CLOSED;
        this.respondedAt = LocalDateTime.now();
    }

    /**
     * Takes the group off this student's My groups page. Nothing else changes:
     * the status and times stay as history.
     * <p>
     * Package-private (no {@code public}) so it can only be reached through
     * {@link StudyGroup#removeFromMyGroups}, which checks the group is closed.
     */
    void hideFromMyGroups() {
        this.hiddenFromMyGroups = true;
    }

    /**
     * PENDING -> ACCEPTED.
     * <p>
     * Package-private (no {@code public}) so it can only be reached through
     * {@link StudyGroup#acceptRequest}, which first checks the group is active,
     * not full and owns this membership.
     */
    void accept() {
        requireStatus(MembershipStatus.PENDING, ALREADY_ANSWERED);
        this.status = MembershipStatus.ACCEPTED;
        this.respondedAt = LocalDateTime.now();
    }

    /**
     * PENDING -> REJECTED.
     * <p>
     * Package-private (no {@code public}) so it can only be reached through
     * {@link StudyGroup#rejectRequest}, which checks the group owns this
     * membership, or {@link StudyGroup#close}.
     */
    void reject() {
        requireStatus(MembershipStatus.PENDING, ALREADY_ANSWERED);
        this.status = MembershipStatus.REJECTED;
        this.respondedAt = LocalDateTime.now();
    }

    /**
     * ACCEPTED -> REMOVED. Leaves respondedAt alone so the time the student
     * was accepted is kept.
     * <p>
     * Package-private (no {@code public}) so it can only be reached through
     * {@link StudyGroup#removeMember}, which stops the leader from being removed.
     */
    void remove() {
        requireStatus(MembershipStatus.ACCEPTED, "This student isn't a member of the group any more");
        this.status = MembershipStatus.REMOVED;
    }

    /**
     * REJECTED or REMOVED -> PENDING, for a student asking to join again.
     * Starts the request afresh.
     * <p>
     * Package-private (no {@code public}) so it can only be reached through
     * {@link StudyGroup#requestToJoin}, which first checks the group is
     * active and not full.
     */
    void reRequest() {
        if (status != MembershipStatus.REJECTED && status != MembershipStatus.REMOVED) {
            throw new IllegalStateException("Cannot re-request a membership that is " + status);
        }
        this.status = MembershipStatus.PENDING;
        this.requestedAt = LocalDateTime.now();
        this.respondedAt = null;
    }

    public boolean isPending() {
        return status == MembershipStatus.PENDING;
    }

    public boolean isAccepted() {
        return status == MembershipStatus.ACCEPTED;
    }

    // The message is shown to the leader as it is, e.g. after pressing Accept twice
    private void requireStatus(MembershipStatus expected, String message) {
        if (status != expected) {
            throw new IllegalStateException(message);
        }
    }

    // ---- Getters (no Lombok in this project) ----
    // No setters: status and timestamps only change through the methods above.

    public Long getMembershipId() {
        return membershipId;
    }

    public Student getStudent() {
        return student;
    }

    public StudyGroup getStudyGroup() {
        return studyGroup;
    }

    public MembershipStatus getStatus() {
        return status;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public LocalDateTime getRespondedAt() {
        return respondedAt;
    }

    public boolean isHiddenFromMyGroups() {
        return hiddenFromMyGroups;
    }
}

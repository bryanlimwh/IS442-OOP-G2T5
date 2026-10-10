package com.studybuddy.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.studybuddy.entity.enums.GroupStatus;
import com.studybuddy.entity.enums.MembershipStatus;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;

/**
 * A study group for one course, created and run by a leader {@link Student}.
 * <p>
 * Acts as the aggregate root for its {@link GroupMembership} rows: every
 * join request, accept, reject and removal goes through this class, so the
 * group's rules (not full, not closed, leader can't be removed) can't be
 * skipped. The leader is itself an ACCEPTED member and counts towards
 * {@code maxSize}.
 * <p>
 * Checking that the person making a change is the leader is not done here,
 * because an entity doesn't know who is logged in. That belongs in the service.
 */
@Entity
public class StudyGroup {

    // A group needs the leader plus at least one other member. Public so the form's @Min uses the same number.
    public static final int MIN_GROUP_SIZE = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long groupId;

    private String name;

    @Column(length = 1000)
    private String description;

    // Fixed once the group is created: there is no way to change it.
    @ManyToOne
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Enumerated(EnumType.STRING)
    private StudyGoal studyGoal;

    @Enumerated(EnumType.STRING)
    private StudyMode studyMode;

    private int maxSize; // includes the leader

    @ManyToOne
    @JoinColumn(name = "leader_id", nullable = false)
    private Student leader;

    @Enumerated(EnumType.STRING)
    private GroupStatus status;

    private LocalDateTime createdAt; // set once, in the constructor

    // One-way: the study_group_id FK sits on availability_slot, so AvailabilitySlot needs no change.
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "study_group_id")
    private List<AvailabilitySlot> availability = new ArrayList<>();

    // Every request ever made to this group, whatever its status, including the leader's own row.
    @OneToMany(mappedBy = "studyGroup", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<GroupMembership> memberships = new ArrayList<>();

    /**
     * No-arg constructor required by JPA.
     */
    protected StudyGroup() {
    }

    /**
     * Creates an ACTIVE group, makes the leader its first ACCEPTED member and
     * adds the group to the leader's groupsLed.
     *
     * @throws IllegalArgumentException if the name is blank or maxSize is below 2
     */
    public StudyGroup(String name, String description, Course course, StudyGoal studyGoal,
            StudyMode studyMode, int maxSize, Student leader) {
        validateName(name);
        validateMaxSize(maxSize);
        this.name = name;
        this.description = description;
        this.course = Objects.requireNonNull(course, "course is required");
        this.studyGoal = studyGoal;
        this.studyMode = studyMode;
        this.maxSize = maxSize;
        this.leader = Objects.requireNonNull(leader, "leader is required");
        this.status = GroupStatus.ACTIVE;
        this.createdAt = LocalDateTime.now();

        GroupMembership leaderMembership = new GroupMembership(leader, this);
        leaderMembership.accept();
        memberships.add(leaderMembership);

        // Keep the other side of the relationship in step, so leader.isLeader() is true straight away.
        leader.addGroupLed(this);
    }

    // ---- Behaviour ----

    /**
     * Records a student's request to join. A student who was rejected or
     * removed before gets their old row back as PENDING.
     *
     * @return the pending membership
     * @throws IllegalStateException if the group is closed or full, or the
     *         student is already pending or a member
     * @throws IllegalArgumentException if the student is the leader
     */
    public GroupMembership requestToJoin(Student student) {
        requireActive();
        if (isFull()) {
            throw new IllegalStateException("Group is full");
        }
        if (isLedBy(student)) {
            throw new IllegalArgumentException("The leader is already in the group");
        }

        Optional<GroupMembership> existing = findMembershipOf(student);
        if (existing.isPresent()) {
            GroupMembership membership = existing.get();
            if (membership.isPending() || membership.isAccepted()) {
                throw new IllegalStateException("Student has already requested or joined this group");
            }
            membership.reRequest();
            return membership;
        }

        GroupMembership membership = new GroupMembership(student, this);
        memberships.add(membership);
        return membership;
    }

    /**
     * Accepts a pending request.
     *
     * @throws IllegalStateException if the group is closed or full, or the request isn't pending
     * @throws IllegalArgumentException if the membership belongs to another group
     */
    public void acceptRequest(GroupMembership membership) {
        requireActive();
        requireOwnMembership(membership);
        if (isFull()) {
            throw new IllegalStateException("Group is full");
        }
        membership.accept();
    }

    /**
     * Rejects a pending request.
     *
     * @throws IllegalStateException if the request isn't pending
     * @throws IllegalArgumentException if the membership belongs to another group
     */
    public void rejectRequest(GroupMembership membership) {
        requireOwnMembership(membership);
        membership.reject();
    }

    /**
     * Removes an accepted member. The leader can't be removed.
     *
     * @throws IllegalStateException if the membership isn't accepted
     * @throws IllegalArgumentException if the membership belongs to another
     *         group or is the leader's
     */
    public void removeMember(GroupMembership membership) {
        requireOwnMembership(membership);
        if (isLedBy(membership.getStudent())) {
            throw new IllegalArgumentException("The leader can't be removed from their own group");
        }
        membership.remove();
    }

    /**
     * Updates the editable details. The course can't be changed.
     *
     * @throws IllegalStateException if the group is closed
     * @throws IllegalArgumentException if the name is blank, or maxSize is
     *         below 2 or below the current member count
     */
    public void updateDetails(String name, String description, StudyGoal studyGoal,
            StudyMode studyMode, int maxSize) {
        requireActive();
        validateName(name);
        validateMaxSize(maxSize);
        if (maxSize < getMemberCount()) {
            throw new IllegalArgumentException(
                    "Max size can't be below the current member count (" + getMemberCount() + ")");
        }
        this.name = name;
        this.description = description;
        this.studyGoal = studyGoal;
        this.studyMode = studyMode;
        this.maxSize = maxSize;
    }

    /**
     * Replaces the group's weekly availability. Refills the existing list
     * rather than swapping in a new one, which Hibernate's orphan removal needs.
     *
     * @throws IllegalStateException if the group is closed
     */
    public void replaceAvailability(List<AvailabilitySlot> slots) {
        requireActive();
        // Copy first, in case the caller passed this group's own list.
        List<AvailabilitySlot> newSlots = new ArrayList<>(slots);
        availability.clear();
        availability.addAll(newSlots);
    }

    /**
     * @throws IllegalStateException if the group is closed
     */
    public void addAvailabilitySlot(AvailabilitySlot slot) {
        requireActive();
        availability.add(slot);
    }

    /**
     * Closes the group. Every request still pending ends as GROUP_CLOSED
     * rather than REJECTED, because the leader never answered it.
     *
     * @throws IllegalStateException if the group is already closed
     */
    public void close() {
        requireActive();
        this.status = GroupStatus.CLOSED;
        for (GroupMembership membership : memberships) {
            if (membership.isPending()) {
                membership.endBecauseGroupClosed();
            }
        }
    }

    /**
     * Takes this closed group off one student's My groups page, whether they
     * led it, joined it or were still waiting. Only the student's own view
     * changes; the group and everyone's history stay.
     *
     * @throws IllegalStateException if the group is still open (there is still something to do there)
     * @throws IllegalArgumentException if the student has no link to this group
     */
    public void removeFromMyGroups(Student student) {
        if (isActive()) {
            throw new IllegalStateException("Only a closed group can be removed from My groups");
        }
        GroupMembership membership = findMembershipOf(student)
                .orElseThrow(() -> new IllegalArgumentException("You aren't linked to this group"));
        membership.hideFromMyGroups();
    }

    /**
     * True once the student has removed this (closed) group from their My groups page.
     */
    public boolean isHiddenFromMyGroupsOf(Student student) {
        return findMembershipOf(student).map(GroupMembership::isHiddenFromMyGroups).orElse(false);
    }

    public boolean isActive() {
        return status == GroupStatus.ACTIVE;
    }

    public boolean isFull() {
        return getMemberCount() >= maxSize;
    }

    /**
     * Number of ACCEPTED members, including the leader.
     */
    public int getMemberCount() {
        return (int) memberships.stream().filter(GroupMembership::isAccepted).count();
    }

    public List<Student> getAcceptedMembers() {
        return memberships.stream()
                .filter(GroupMembership::isAccepted)
                .map(GroupMembership::getStudent)
                .toList();
    }

    /**
     * Requests waiting for the leader, for the "view join requests" page.
     */
    public List<GroupMembership> getPendingRequests() {
        return memberships.stream()
                .filter(GroupMembership::isPending)
                .toList();
    }

    public boolean isLedBy(Student student) {
        return leader.equals(student);
    }

    public boolean hasMember(Student student) {
        return findMembershipOf(student).map(GroupMembership::isAccepted).orElse(false);
    }

    /**
     * Where the student's request or membership stands, or empty if they have
     * never asked to join. Read-only: a status can still only change through
     * this class's behaviour methods.
     */
    public Optional<MembershipStatus> getMembershipStatusOf(Student student) {
        return findMembershipOf(student).map(GroupMembership::getStatus);
    }

    private Optional<GroupMembership> findMembershipOf(Student student) {
        return memberships.stream()
                .filter(membership -> membership.getStudent().equals(student))
                .findFirst();
    }

    private void requireActive() {
        if (!isActive()) {
            throw new IllegalStateException("Group is closed");
        }
    }

    private void requireOwnMembership(GroupMembership membership) {
        if (!memberships.contains(membership)) {
            throw new IllegalArgumentException("Membership does not belong to this group");
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Group name is required");
        }
    }

    private static void validateMaxSize(int maxSize) {
        if (maxSize < MIN_GROUP_SIZE) {
            throw new IllegalArgumentException("Max size must be at least " + MIN_GROUP_SIZE);
        }
    }

    // ---- Getters (no Lombok in this project) ----
    // No setters: details change through updateDetails() so its checks can't be skipped.

    public Long getGroupId() {
        return groupId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Course getCourse() {
        return course;
    }

    public StudyGoal getStudyGoal() {
        return studyGoal;
    }

    public StudyMode getStudyMode() {
        return studyMode;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public Student getLeader() {
        return leader;
    }

    public GroupStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public List<AvailabilitySlot> getAvailability() {
        return Collections.unmodifiableList(availability);
    }

    public List<GroupMembership> getMemberships() {
        return Collections.unmodifiableList(memberships);
    }
}

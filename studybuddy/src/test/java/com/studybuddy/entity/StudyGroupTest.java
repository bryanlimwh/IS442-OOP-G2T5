package com.studybuddy.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.studybuddy.entity.enums.GroupStatus;
import com.studybuddy.entity.enums.MembershipStatus;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;

/**
 * StudyGroup's own rules, with no Spring and no database. Students and courses are never
 * saved, so they have no ids; User.equals still tells them apart (same object or not).
 * The group starts as IS442 "IS442 Revision", led by Alex, maximum size 4.
 */
class StudyGroupTest {

    private final Course is442 = new Course("IS442", "Object Oriented Programming");
    private final Student alex = student("alex@test.com", "Alex Tan");
    private final Student ben = student("ben@test.com", "Ben Koh");
    private final Student chloe = student("chloe@test.com", "Chloe Lim");
    private final Student daniel = student("daniel@test.com", "Daniel Ng");

    private final StudyGroup group = newGroup(4);

    // L1, L2, GRP-1
    @Test
    void newGroupIsActiveWithLeaderAsOnlyMember() {
        assertThat(group.getStatus()).isEqualTo(GroupStatus.ACTIVE);
        assertThat(group.getName()).isEqualTo("IS442 Revision");
        assertThat(group.getDescription()).isEqualTo("Past papers");
        assertThat(group.getCourse()).isSameAs(is442);
        assertThat(group.getStudyGoal()).isEqualTo(StudyGoal.EXAM_PREPARATION);
        assertThat(group.getStudyMode()).isEqualTo(StudyMode.ONLINE);
        assertThat(group.getMaxSize()).isEqualTo(4);
        assertThat(group.getCreatedAt()).isNotNull();

        assertThat(group.getMemberCount()).isEqualTo(1);
        assertThat(group.getAcceptedMembers()).containsExactly(alex);
        assertThat(group.getMembershipStatusOf(alex)).contains(MembershipStatus.ACCEPTED);
        assertThat(group.getPendingRequests()).isEmpty();
        assertThat(group.isLedBy(alex)).isTrue();
        assertThat(alex.isLeader()).isTrue();
        assertThat(alex.getGroupsLed()).containsExactly(group);
    }

    // GRP-1
    @Test
    void blankNameIsRefused() {
        for (String name : new String[] { null, "", "   " }) {
            assertThatThrownBy(() -> new StudyGroup(name, null, is442, StudyGoal.EXAM_PREPARATION,
                    StudyMode.ONLINE, 4, ben))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Group name is required");
        }
        // A refused group isn't added to the leader's groups
        assertThat(ben.isLeader()).isFalse();
    }

    // GRP-1: the leader counts, so 2 is the smallest group
    @Test
    void sizeBelowTwoIsRefusedAndTwoIsAllowed() {
        assertThatThrownBy(() -> newGroup(1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Max size must be at least 2");
        assertThatThrownBy(() -> newGroup(0)).isInstanceOf(IllegalArgumentException.class);

        assertThat(newGroup(2).getMaxSize()).isEqualTo(2);
    }

    // GRP-1
    @Test
    void missingCourseOrLeaderIsRefused() {
        assertThatThrownBy(() -> new StudyGroup("No course", null, null, StudyGoal.EXAM_PREPARATION,
                StudyMode.ONLINE, 4, ben))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("course is required");
        assertThatThrownBy(() -> new StudyGroup("No leader", null, is442, StudyGoal.EXAM_PREPARATION,
                StudyMode.ONLINE, 4, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("leader is required");
    }

    // L4, GRP-4
    @Test
    void requestToJoinAddsPendingRequest() {
        GroupMembership request = group.requestToJoin(ben);

        assertThat(request.getStatus()).isEqualTo(MembershipStatus.PENDING);
        assertThat(request.getStudent()).isSameAs(ben);
        assertThat(request.getStudyGroup()).isSameAs(group);
        assertThat(request.getRequestedAt()).isNotNull();
        assertThat(request.getRespondedAt()).isNull();
        assertThat(group.getPendingRequests()).containsExactly(request);
        // A pending request doesn't take a place
        assertThat(group.getMemberCount()).isEqualTo(1);
        assertThat(group.hasMember(ben)).isFalse();
    }

    // GRP-4
    @Test
    void leaderCannotRequestToJoinOwnGroup() {
        assertThatThrownBy(() -> group.requestToJoin(alex))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The leader is already in the group");

        assertThat(group.getMemberships()).hasSize(1);
    }

    // GRP-4
    @Test
    void duplicatePendingOrAcceptedRequestIsRefused() {
        group.requestToJoin(ben);
        addMember(group, chloe);

        assertThatThrownBy(() -> group.requestToJoin(ben))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Student has already requested or joined this group");
        assertThatThrownBy(() -> group.requestToJoin(chloe))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Student has already requested or joined this group");

        assertThat(group.getMemberships()).hasSize(3);
        assertThat(group.getMembershipStatusOf(ben)).contains(MembershipStatus.PENDING);
        assertThat(group.getMembershipStatusOf(chloe)).contains(MembershipStatus.ACCEPTED);
    }

    // GRP-4: asking again reuses the student's old row
    @Test
    void rejectedOrRemovedStudentCanAskAgain() {
        GroupMembership rejected = group.requestToJoin(ben);
        group.rejectRequest(rejected);
        GroupMembership removed = addMember(group, chloe);
        group.removeMember(removed);

        assertThat(group.requestToJoin(ben)).isSameAs(rejected);
        assertThat(group.requestToJoin(chloe)).isSameAs(removed);

        assertThat(rejected.getStatus()).isEqualTo(MembershipStatus.PENDING);
        assertThat(rejected.getRespondedAt()).isNull();
        assertThat(removed.getStatus()).isEqualTo(MembershipStatus.PENDING);
        assertThat(removed.getRespondedAt()).isNull();
        assertThat(group.getMemberships()).hasSize(3);
        assertThat(group.getPendingRequests()).containsExactlyInAnyOrder(rejected, removed);
    }

    // GRP-4, GRP-5: maximum size 2 means the leader plus one member
    @Test
    void fullGroupRefusesRequestsAndAccepts() {
        StudyGroup pair = newGroup(2);
        GroupMembership waiting = pair.requestToJoin(chloe);
        // One place left: still not full, so Ben can ask and be accepted
        assertThat(pair.isFull()).isFalse();
        addMember(pair, ben);

        assertThat(pair.isFull()).isTrue();
        assertThat(pair.getMemberCount()).isEqualTo(2);
        assertThatThrownBy(() -> pair.requestToJoin(daniel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Group is full");
        assertThatThrownBy(() -> pair.acceptRequest(waiting))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Group is full. Remove a member to accept new members.");

        // A request made before the group filled up stays waiting
        assertThat(waiting.getStatus()).isEqualTo(MembershipStatus.PENDING);
        assertThat(pair.getMembershipStatusOf(daniel)).isEmpty();
    }

    // GRP-5
    @Test
    void acceptAndRejectNeedPendingRequest() {
        GroupMembership accepted = addMember(group, ben);
        GroupMembership rejected = group.requestToJoin(chloe);
        group.rejectRequest(rejected);

        assertThat(accepted.getRespondedAt()).isNotNull();
        assertThatThrownBy(() -> group.acceptRequest(accepted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("This request has already been answered");
        assertThatThrownBy(() -> group.rejectRequest(accepted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("This request has already been answered");
        assertThatThrownBy(() -> group.acceptRequest(rejected))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("This request has already been answered");

        assertThat(accepted.getStatus()).isEqualTo(MembershipStatus.ACCEPTED);
        assertThat(rejected.getStatus()).isEqualTo(MembershipStatus.REJECTED);
    }

    // GRP-5
    @Test
    void membershipOfAnotherGroupIsRefused() {
        StudyGroup other = new StudyGroup("Other group", null, is442, StudyGoal.EXAM_PREPARATION,
                StudyMode.ONLINE, 4, daniel);
        GroupMembership pendingElsewhere = other.requestToJoin(ben);
        GroupMembership memberElsewhere = addMember(other, chloe);

        assertThatThrownBy(() -> group.acceptRequest(pendingElsewhere))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Membership does not belong to this group");
        assertThatThrownBy(() -> group.rejectRequest(pendingElsewhere))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Membership does not belong to this group");
        assertThatThrownBy(() -> group.removeMember(memberElsewhere))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Membership does not belong to this group");

        assertThat(pendingElsewhere.getStatus()).isEqualTo(MembershipStatus.PENDING);
        assertThat(memberElsewhere.getStatus()).isEqualTo(MembershipStatus.ACCEPTED);
        assertThat(group.getMemberCount()).isEqualTo(1);
    }

    // L6, GRP-5
    @Test
    void removeMemberEndsMembershipButNotTheLeaders() {
        GroupMembership member = addMember(group, ben);
        GroupMembership leaderRow = group.getAcceptedMemberships().get(0);

        group.removeMember(member);

        assertThat(member.getStatus()).isEqualTo(MembershipStatus.REMOVED);
        // The accept time is kept: no removal time is stored
        assertThat(member.getRespondedAt()).isNotNull();
        assertThat(group.getMemberCount()).isEqualTo(1);
        assertThat(group.hasMember(ben)).isFalse();

        assertThatThrownBy(() -> group.removeMember(leaderRow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The leader can't be removed from their own group");
        assertThat(group.hasMember(alex)).isTrue();
    }

    // L6, GRP-5
    @Test
    void removeNeedsAcceptedMember() {
        GroupMembership pending = group.requestToJoin(ben);
        GroupMembership removed = addMember(group, chloe);
        group.removeMember(removed);

        assertThatThrownBy(() -> group.removeMember(pending))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("This student isn't a member of the group any more");
        assertThatThrownBy(() -> group.removeMember(removed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("This student isn't a member of the group any more");

        assertThat(pending.getStatus()).isEqualTo(MembershipStatus.PENDING);
    }

    // L3, GRP-3
    @Test
    void updateDetailsChangesEverythingButCourse() {
        group.updateDetails("OOP Finals", "Mock exams", StudyGoal.PROBLEM_SOLVING, StudyMode.IN_PERSON, 6);

        assertThat(group.getName()).isEqualTo("OOP Finals");
        assertThat(group.getDescription()).isEqualTo("Mock exams");
        assertThat(group.getStudyGoal()).isEqualTo(StudyGoal.PROBLEM_SOLVING);
        assertThat(group.getStudyMode()).isEqualTo(StudyMode.IN_PERSON);
        assertThat(group.getMaxSize()).isEqualTo(6);
        assertThat(group.getCourse()).isSameAs(is442);
    }

    // GRP-3, GRP-1: three members (Alex, Ben, Chloe)
    @Test
    void updateSizeCannotGoBelowMemberCount() {
        addMember(group, ben);
        addMember(group, chloe);

        // Equal to the member count is allowed, and makes the group full
        group.updateDetails("IS442 Revision", "Past papers", StudyGoal.EXAM_PREPARATION, StudyMode.ONLINE, 3);
        assertThat(group.getMaxSize()).isEqualTo(3);
        assertThat(group.isFull()).isTrue();

        assertThatThrownBy(() -> group.updateDetails("Renamed", "Changed", StudyGoal.CONCEPT_REVIEW,
                StudyMode.IN_PERSON, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Max size can't be below the current member count (3)");
        assertThatThrownBy(() -> group.updateDetails("   ", "Changed", StudyGoal.CONCEPT_REVIEW,
                StudyMode.IN_PERSON, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Group name is required");
        assertThatThrownBy(() -> group.updateDetails("Renamed", "Changed", StudyGoal.CONCEPT_REVIEW,
                StudyMode.IN_PERSON, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Max size must be at least 2");

        // Nothing changed by the refused updates
        assertThat(group.getName()).isEqualTo("IS442 Revision");
        assertThat(group.getDescription()).isEqualTo("Past papers");
        assertThat(group.getStudyGoal()).isEqualTo(StudyGoal.EXAM_PREPARATION);
        assertThat(group.getMaxSize()).isEqualTo(3);
    }

    // L2, L3
    @Test
    void replaceAvailabilityReplacesEverySlot() {
        group.addAvailabilitySlot(slot(DayOfWeek.MONDAY, 10, 12));
        group.addAvailabilitySlot(slot(DayOfWeek.TUESDAY, 10, 12));

        group.replaceAvailability(List.of(slot(DayOfWeek.FRIDAY, 14, 16)));

        assertThat(group.getAvailability()).extracting(AvailabilitySlot::getDayOfWeek)
                .containsExactly(DayOfWeek.FRIDAY);

        // Passing the group's own list doesn't empty it
        group.replaceAvailability(group.getAvailability());
        assertThat(group.getAvailability()).hasSize(1);
    }

    // L3, GRP-6
    @Test
    void closeEndsPendingRequestsAsGroupClosed() {
        GroupMembership pending = group.requestToJoin(ben);
        GroupMembership member = addMember(group, chloe);
        GroupMembership rejected = group.requestToJoin(daniel);
        group.rejectRequest(rejected);

        group.close();

        assertThat(group.getStatus()).isEqualTo(GroupStatus.CLOSED);
        assertThat(group.isActive()).isFalse();
        assertThat(pending.getStatus()).isEqualTo(MembershipStatus.GROUP_CLOSED);
        assertThat(pending.getRespondedAt()).isNotNull();
        assertThat(group.getPendingRequests()).isEmpty();
        // Members and earlier answers stay as they were
        assertThat(member.getStatus()).isEqualTo(MembershipStatus.ACCEPTED);
        assertThat(rejected.getStatus()).isEqualTo(MembershipStatus.REJECTED);
        assertThat(group.getMemberCount()).isEqualTo(2);
    }

    // GRP-6, GRP-3, GRP-4, GRP-5: reject on a closed group is refused as "already answered", not
    // "Group is closed" (N-B3), pins current behaviour, update if fixed
    @Test
    void closedGroupIsFrozen() {
        GroupMembership endedRequest = group.requestToJoin(ben);
        GroupMembership member = addMember(group, chloe);
        group.addAvailabilitySlot(slot(DayOfWeek.MONDAY, 10, 12));
        group.close();

        assertThatThrownBy(() -> group.requestToJoin(daniel))
                .isInstanceOf(IllegalStateException.class).hasMessage("Group is closed");
        assertThatThrownBy(() -> group.acceptRequest(endedRequest))
                .isInstanceOf(IllegalStateException.class).hasMessage("Group is closed");
        assertThatThrownBy(() -> group.removeMember(member))
                .isInstanceOf(IllegalStateException.class).hasMessage("Group is closed");
        assertThatThrownBy(() -> group.updateDetails("Renamed", null, StudyGoal.CONCEPT_REVIEW,
                StudyMode.ONLINE, 4))
                .isInstanceOf(IllegalStateException.class).hasMessage("Group is closed");
        assertThatThrownBy(() -> group.replaceAvailability(List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessage("Group is closed");
        assertThatThrownBy(() -> group.addAvailabilitySlot(slot(DayOfWeek.TUESDAY, 10, 12)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Group is closed");
        assertThatThrownBy(group::close)
                .isInstanceOf(IllegalStateException.class).hasMessage("Group is closed");
        assertThatThrownBy(() -> group.rejectRequest(endedRequest))
                .isInstanceOf(IllegalStateException.class).hasMessage("This request has already been answered");

        assertThat(endedRequest.getStatus()).isEqualTo(MembershipStatus.GROUP_CLOSED);
        assertThat(member.getStatus()).isEqualTo(MembershipStatus.ACCEPTED);
        assertThat(group.getName()).isEqualTo("IS442 Revision");
        assertThat(group.getAvailability()).hasSize(1);
    }

    // GRP-6
    @Test
    void removeFromMyGroupsOnlyForClosedGroupAndLinkedStudent() {
        GroupMembership member = addMember(group, ben);

        assertThatThrownBy(() -> group.removeFromMyGroups(ben))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only a closed group can be removed from My groups");
        assertThat(member.isHiddenFromMyGroups()).isFalse();

        group.close();
        assertThatThrownBy(() -> group.removeFromMyGroups(daniel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("You aren't linked to this group");

        group.removeFromMyGroups(ben);
        assertThat(group.isHiddenFromMyGroupsOf(ben)).isTrue();
        // Only Ben's view changes
        assertThat(group.isHiddenFromMyGroupsOf(alex)).isFalse();
        assertThat(member.getStatus()).isEqualTo(MembershipStatus.ACCEPTED);
    }

    private StudyGroup newGroup(int maxSize) {
        return new StudyGroup("IS442 Revision", "Past papers", is442, StudyGoal.EXAM_PREPARATION,
                StudyMode.ONLINE, maxSize, alex);
    }

    private static GroupMembership addMember(StudyGroup group, Student student) {
        GroupMembership membership = group.requestToJoin(student);
        group.acceptRequest(membership);
        return membership;
    }

    private static AvailabilitySlot slot(DayOfWeek day, int startHour, int endHour) {
        return new AvailabilitySlot(day, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0));
    }

    private static Student student(String email, String name) {
        return new Student(email, "dummyHash", name, "School of Computing and Information Systems",
                "Information Systems", 2, "91234567");
    }
}

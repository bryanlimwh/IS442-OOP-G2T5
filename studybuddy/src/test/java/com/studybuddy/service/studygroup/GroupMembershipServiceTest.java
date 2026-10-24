package com.studybuddy.service.studygroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.studybuddy.entity.Course;
import com.studybuddy.entity.GroupMembership;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyGroup;
import com.studybuddy.entity.enums.MembershipStatus;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;
import com.studybuddy.exception.MembershipNotFoundException;
import com.studybuddy.exception.NotGroupLeaderException;
import com.studybuddy.exception.StudentNotFoundException;
import com.studybuddy.exception.StudyGroupNotFoundException;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.GroupMembershipRepository;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.repository.StudyGroupRepository;

import jakarta.persistence.EntityManager;

/**
 * GroupMembershipService (with the real StudyGroupService for its leader check) against an
 * empty in-memory H2 (no seeders); each test is rolled back. Course IS442, taken by Alex,
 * Ben, Chloe and Emily; Daniel takes nothing. Alex leads "IS442 Revision" (maximum size 4,
 * so Alex plus three). A new request only gets its id when it is written to the database,
 * so request() flushes; flushAndClear() also forgets cached objects, so the checks after it
 * read what was really saved.
 */
@DataJpaTest
@Import({ GroupMembershipService.class, StudyGroupService.class })
class GroupMembershipServiceTest {

    private static final long UNKNOWN_ID = 999_999L;
    private static final String ALEX = "alex@test.com";
    private static final String BEN = "ben@test.com";
    private static final String CHLOE = "chloe@test.com";
    private static final String DANIEL = "daniel@test.com";
    private static final String EMILY = "emily@test.com";

    @Autowired
    private GroupMembershipService service;
    @Autowired
    private StudyGroupService studyGroupService;
    @Autowired
    private StudyGroupRepository studyGroupRepository;
    @Autowired
    private GroupMembershipRepository groupMembershipRepository;
    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private CourseRepository courseRepository;
    @Autowired
    private EntityManager entityManager;

    private Course is442;
    private Student alex;
    private Student ben;
    private Student chloe;
    private Student daniel;
    private Student emily;
    private Long groupId;

    @BeforeEach
    void setUp() {
        is442 = courseRepository.save(new Course("IS442", "Object Oriented Programming"));
        alex = saveStudent(ALEX, "Alex Tan", true);
        ben = saveStudent(BEN, "Ben Koh", true);
        chloe = saveStudent(CHLOE, "Chloe Lim", true);
        daniel = saveStudent(DANIEL, "Daniel Ng", false);
        emily = saveStudent(EMILY, "Emily Wong", true);
        groupId = saveGroup("IS442 Revision", alex, 4);
    }

    // L4, GRP-4
    @Test
    void requestToJoinCreatesPendingRequestTheLeaderSees() {
        Long id = request(BEN);
        flushAndClear();

        GroupMembership saved = groupMembershipRepository.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(MembershipStatus.PENDING);
        assertThat(saved.getRequestedAt()).isNotNull();
        assertThat(saved.getRespondedAt()).isNull();
        assertThat(service.getPendingRequests(groupId, ALEX)).extracting(GroupMembership::getMembershipId)
                .containsExactly(id);
        assertThat(service.findPendingRequestsBy(BEN)).extracting(GroupMembership::getMembershipId)
                .containsExactly(id);
        // Waiting doesn't take a place
        assertThat(studyGroupService.getGroup(groupId).getMemberCount()).isEqualTo(1);
    }

    // L4: the student who has waited longest is first
    @Test
    void pendingRequestsAreOldestFirst() {
        Long first = request(CHLOE);
        Long second = request(BEN);
        Long third = request(EMILY);
        flushAndClear();

        assertThat(service.getPendingRequests(groupId, ALEX)).extracting(GroupMembership::getMembershipId)
                .containsExactly(first, second, third);
    }

    // GRP-4
    @Test
    void leaderCannotJoinOwnGroup() {
        assertThatThrownBy(() -> service.requestToJoin(groupId, ALEX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The leader is already in the group");

        assertThat(groupMembershipRepository.count()).isEqualTo(1);
    }

    // GRP-4
    @Test
    void duplicatePendingOrAcceptedRequestIsRefused() {
        request(BEN);
        accept(request(CHLOE));

        assertThatThrownBy(() -> service.requestToJoin(groupId, BEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Student has already requested or joined this group");
        assertThatThrownBy(() -> service.requestToJoin(groupId, CHLOE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Student has already requested or joined this group");
        flushAndClear();

        assertThat(groupMembershipRepository.count()).isEqualTo(3);
    }

    // GRP-4: asking again reuses the student's row, so there is still one per student
    @Test
    void rejectedOrRemovedStudentCanAskAgain() {
        Long rejected = request(BEN);
        service.rejectRequest(groupId, rejected, ALEX);
        Long removed = request(CHLOE);
        accept(removed);
        service.removeMember(groupId, removed, ALEX);
        flushAndClear();

        assertThat(service.requestToJoin(groupId, BEN).getMembershipId()).isEqualTo(rejected);
        assertThat(service.requestToJoin(groupId, CHLOE).getMembershipId()).isEqualTo(removed);
        flushAndClear();

        assertThat(groupMembershipRepository.count()).isEqualTo(3);
        assertThat(groupMembershipRepository.findById(rejected).orElseThrow().getRespondedAt()).isNull();
        assertThat(service.getPendingRequests(groupId, ALEX)).extracting(GroupMembership::getMembershipId)
                .containsExactlyInAnyOrder(rejected, removed);
    }

    // GRP-4, GRP-6
    @Test
    void fullOrClosedGroupCannotBeJoined() {
        Long pairId = saveGroup("Pair", chloe, 2);
        accept(pairId, request(pairId, BEN), CHLOE);
        studyGroupService.closeGroup(groupId, ALEX);
        flushAndClear();

        assertThatThrownBy(() -> service.requestToJoin(pairId, EMILY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Group is full");
        assertThatThrownBy(() -> service.requestToJoin(groupId, EMILY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Group is closed");
        flushAndClear();

        assertThat(service.findPendingRequestsBy(EMILY)).isEmpty();
    }

    // GRP-4, K8: pins current behaviour, update if fixed. Daniel doesn't take IS442 but can join.
    @Test
    void joiningDoesNotRequireTakingTheCourse() {
        Long id = request(DANIEL);
        accept(id);
        flushAndClear();

        assertThat(studyGroupService.getGroup(groupId).hasMember(daniel)).isTrue();
        assertThat(studentRepository.findByEmail(DANIEL).orElseThrow().getCoursesTaken()).isEmpty();
    }

    // L5, GRP-5
    @Test
    void leaderAcceptsRequest() {
        Long id = request(BEN);

        GroupMembership accepted = service.acceptRequest(groupId, id, ALEX);
        flushAndClear();

        assertThat(accepted.getStudent().getName()).isEqualTo("Ben Koh");
        GroupMembership saved = groupMembershipRepository.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(MembershipStatus.ACCEPTED);
        assertThat(saved.getRespondedAt()).isNotNull();
        assertThat(studyGroupService.getGroup(groupId).getMemberCount()).isEqualTo(2);
        assertThat(service.getPendingRequests(groupId, ALEX)).isEmpty();
        assertThat(service.getMembers(groupId, ALEX)).extracting(m -> m.getStudent().getName())
                .containsExactly("Alex Tan", "Ben Koh");
        assertThat(service.findGroupsJoinedBy(BEN)).extracting(StudyGroup::getGroupId).containsExactly(groupId);
        assertThat(service.findPendingRequestsBy(BEN)).isEmpty();
    }

    // L5, GRP-5
    @Test
    void leaderRejectsRequest() {
        Long id = request(BEN);

        GroupMembership rejected = service.rejectRequest(groupId, id, ALEX);
        flushAndClear();

        assertThat(rejected.getStudent().getName()).isEqualTo("Ben Koh");
        GroupMembership saved = groupMembershipRepository.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(MembershipStatus.REJECTED);
        assertThat(saved.getRespondedAt()).isNotNull();
        assertThat(studyGroupService.getGroup(groupId).getMemberCount()).isEqualTo(1);
        assertThat(service.getPendingRequests(groupId, ALEX)).isEmpty();
        assertThat(service.findGroupsJoinedBy(BEN)).isEmpty();
        assertThat(service.findPastRequestsBy(BEN)).extracting(GroupMembership::getMembershipId).containsExactly(id);
    }

    // GRP-5: maximum size 4, so the third accepted student fills it
    @Test
    void acceptingIntoFullGroupIsRefused() {
        Long benId = request(BEN);
        Long chloeId = request(CHLOE);
        Long emilyId = request(EMILY);
        Long danielId = request(DANIEL);
        accept(benId);
        accept(chloeId);
        // One place left: still accepted
        accept(emilyId);

        assertThatThrownBy(() -> service.acceptRequest(groupId, danielId, ALEX))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Group is full. Remove a member to accept new members.");
        flushAndClear();

        assertThat(groupMembershipRepository.findById(danielId).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.PENDING);
        assertThat(studyGroupService.getGroup(groupId).getMemberCount()).isEqualTo(4);
        // A full group can still turn a request down
        service.rejectRequest(groupId, danielId, ALEX);
    }

    // GRP-5
    @Test
    void answeringTwiceIsRefused() {
        Long accepted = request(BEN);
        accept(accepted);
        Long rejected = request(CHLOE);
        service.rejectRequest(groupId, rejected, ALEX);

        assertThatThrownBy(() -> service.acceptRequest(groupId, accepted, ALEX))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("This request has already been answered");
        assertThatThrownBy(() -> service.rejectRequest(groupId, accepted, ALEX))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("This request has already been answered");
        assertThatThrownBy(() -> service.acceptRequest(groupId, rejected, ALEX))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("This request has already been answered");
        flushAndClear();

        assertThat(groupMembershipRepository.findById(accepted).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.ACCEPTED);
        assertThat(groupMembershipRepository.findById(rejected).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.REJECTED);
    }

    // GRP-5, GRP-2: a membership id from another group, sent with Alex's group id
    @Test
    void membershipOfAnotherGroupIsRefused() {
        Long bensGroup = saveGroup("Ben's group", ben, 4);
        Long pendingElsewhere = request(bensGroup, CHLOE);
        Long memberElsewhere = request(bensGroup, EMILY);
        accept(bensGroup, memberElsewhere, BEN);

        assertThatThrownBy(() -> service.acceptRequest(groupId, pendingElsewhere, ALEX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Membership does not belong to this group");
        assertThatThrownBy(() -> service.rejectRequest(groupId, pendingElsewhere, ALEX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Membership does not belong to this group");
        assertThatThrownBy(() -> service.removeMember(groupId, memberElsewhere, ALEX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Membership does not belong to this group");
        // Alex can't use Ben's group id either
        assertThatThrownBy(() -> service.acceptRequest(bensGroup, pendingElsewhere, ALEX))
                .isInstanceOf(NotGroupLeaderException.class);
        flushAndClear();

        assertThat(groupMembershipRepository.findById(pendingElsewhere).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.PENDING);
        assertThat(groupMembershipRepository.findById(memberElsewhere).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.ACCEPTED);
        assertThat(studyGroupService.getGroup(groupId).getMemberCount()).isEqualTo(1);
    }

    // GRP-2, L4, L5, L6: Ben is a member, Chloe is waiting, Daniel has no link
    @Test
    void onlyTheLeaderManagesMembers() {
        Long benId = request(BEN);
        accept(benId);
        Long chloeId = request(CHLOE);

        for (String email : List.of(BEN, CHLOE, DANIEL)) {
            assertThatThrownBy(() -> service.getPendingRequests(groupId, email))
                    .isInstanceOf(NotGroupLeaderException.class);
            assertThatThrownBy(() -> service.getMembers(groupId, email))
                    .isInstanceOf(NotGroupLeaderException.class);
            assertThatThrownBy(() -> service.acceptRequest(groupId, chloeId, email))
                    .isInstanceOf(NotGroupLeaderException.class);
            assertThatThrownBy(() -> service.rejectRequest(groupId, chloeId, email))
                    .isInstanceOf(NotGroupLeaderException.class);
            assertThatThrownBy(() -> service.removeMember(groupId, benId, email))
                    .isInstanceOf(NotGroupLeaderException.class);
        }
        flushAndClear();

        assertThat(groupMembershipRepository.findById(benId).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.ACCEPTED);
        assertThat(groupMembershipRepository.findById(chloeId).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.PENDING);
    }

    // GRP-2
    @Test
    void unknownGroupMembershipOrStudentIsNotFound() {
        Long benId = request(BEN);

        assertThatThrownBy(() -> service.requestToJoin(UNKNOWN_ID, BEN)).isInstanceOf(StudyGroupNotFoundException.class);
        assertThatThrownBy(() -> service.acceptRequest(UNKNOWN_ID, benId, ALEX))
                .isInstanceOf(StudyGroupNotFoundException.class);
        assertThatThrownBy(() -> service.getPendingRequests(UNKNOWN_ID, ALEX))
                .isInstanceOf(StudyGroupNotFoundException.class);
        assertThatThrownBy(() -> service.removeFromMyGroups(UNKNOWN_ID, BEN))
                .isInstanceOf(StudyGroupNotFoundException.class);
        assertThatThrownBy(() -> service.acceptRequest(groupId, UNKNOWN_ID, ALEX))
                .isInstanceOf(MembershipNotFoundException.class);
        assertThatThrownBy(() -> service.rejectRequest(groupId, UNKNOWN_ID, ALEX))
                .isInstanceOf(MembershipNotFoundException.class);
        assertThatThrownBy(() -> service.removeMember(groupId, UNKNOWN_ID, ALEX))
                .isInstanceOf(MembershipNotFoundException.class);
        assertThatThrownBy(() -> service.requestToJoin(groupId, "ghost@test.com"))
                .isInstanceOf(StudentNotFoundException.class);
        assertThatThrownBy(() -> service.findGroupsJoinedBy("ghost@test.com"))
                .isInstanceOf(StudentNotFoundException.class);
    }

    // L6, GRP-5
    @Test
    void leaderRemovesMember() {
        Long benId = request(BEN);
        accept(benId);

        GroupMembership removed = service.removeMember(groupId, benId, ALEX);
        flushAndClear();

        assertThat(removed.getStudent().getName()).isEqualTo("Ben Koh");
        assertThat(groupMembershipRepository.findById(benId).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.REMOVED);
        assertThat(studyGroupService.getGroup(groupId).getMemberCount()).isEqualTo(1);
        assertThat(service.getMembers(groupId, ALEX)).extracting(m -> m.getStudent().getName())
                .containsExactly("Alex Tan");
        assertThat(service.findGroupsJoinedBy(BEN)).isEmpty();
        assertThat(service.findPastRequestsBy(BEN)).extracting(GroupMembership::getMembershipId)
                .containsExactly(benId);
    }

    // L6, GRP-5
    @Test
    void leaderCannotBeRemovedAndPendingRequestCannotBeRemoved() {
        Long leaderRow = service.getMembers(groupId, ALEX).get(0).getMembershipId();
        Long chloeId = request(CHLOE);

        assertThatThrownBy(() -> service.removeMember(groupId, leaderRow, ALEX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The leader can't be removed from their own group");
        assertThatThrownBy(() -> service.removeMember(groupId, chloeId, ALEX))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("This student isn't a member of the group any more");
        flushAndClear();

        assertThat(studyGroupService.getGroup(groupId).hasMember(alex)).isTrue();
        assertThat(groupMembershipRepository.findById(chloeId).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.PENDING);
    }

    // GRP-6, GRP-4, GRP-5: reject on a closed group is refused as "already answered", not
    // "Group is closed" (N-B3), pins current behaviour, update if fixed
    @Test
    void closedGroupIsFrozenForMembership() {
        Long benId = request(BEN);
        accept(benId);
        Long chloeId = request(CHLOE);
        studyGroupService.closeGroup(groupId, ALEX);
        flushAndClear();

        assertThatThrownBy(() -> service.requestToJoin(groupId, EMILY))
                .isInstanceOf(IllegalStateException.class).hasMessage("Group is closed");
        assertThatThrownBy(() -> service.acceptRequest(groupId, chloeId, ALEX))
                .isInstanceOf(IllegalStateException.class).hasMessage("Group is closed");
        assertThatThrownBy(() -> service.removeMember(groupId, benId, ALEX))
                .isInstanceOf(IllegalStateException.class).hasMessage("Group is closed");
        assertThatThrownBy(() -> service.rejectRequest(groupId, chloeId, ALEX))
                .isInstanceOf(IllegalStateException.class).hasMessage("This request has already been answered");
        flushAndClear();

        assertThat(groupMembershipRepository.findById(benId).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.ACCEPTED);
        assertThat(groupMembershipRepository.findById(chloeId).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.GROUP_CLOSED);
        // The leader can still look at a closed group's members
        assertThat(service.getMembers(groupId, ALEX)).hasSize(2);
        assertThat(service.getPendingRequests(groupId, ALEX)).isEmpty();
    }

    // GRP-6: the student sees the request ended because the group closed, not a decline
    @Test
    void closingMovesPendingRequestsToGroupClosed() {
        Long benId = request(BEN);
        studyGroupService.closeGroup(groupId, ALEX);
        flushAndClear();

        assertThat(service.findPendingRequestsBy(BEN))
                .extracting(GroupMembership::getMembershipId, GroupMembership::getStatus)
                .containsExactly(tuple(benId, MembershipStatus.GROUP_CLOSED));
        assertThat(service.findPastRequestsBy(BEN)).isEmpty();
        assertThat(service.getViewerRole(groupId, BEN)).isEqualTo(ViewerRole.GROUP_CLOSED);
        assertThat(ViewerRole.GROUP_CLOSED.canRequestToJoin()).isFalse();
    }

    // GRP-6
    @Test
    void removeFromMyGroupsOnlyWorksForClosedGroups() {
        accept(request(BEN));

        assertThatThrownBy(() -> service.removeFromMyGroups(groupId, BEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only a closed group can be removed from My groups");
        assertThatThrownBy(() -> service.removeFromMyGroups(groupId, ALEX))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only a closed group can be removed from My groups");

        studyGroupService.closeGroup(groupId, ALEX);
        assertThatThrownBy(() -> service.removeFromMyGroups(groupId, DANIEL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("You aren't linked to this group");
        flushAndClear();

        assertThat(service.findGroupsJoinedBy(BEN)).extracting(StudyGroup::getGroupId).containsExactly(groupId);
    }

    // GRP-6: the leader, a member and a student whose request ended each remove it for themselves only
    @Test
    void removeFromMyGroupsHidesTheGroupForThatStudentOnly() {
        accept(request(BEN));
        accept(request(CHLOE));
        request(EMILY);
        studyGroupService.closeGroup(groupId, ALEX);
        flushAndClear();

        service.removeFromMyGroups(groupId, ALEX);
        service.removeFromMyGroups(groupId, BEN);
        service.removeFromMyGroups(groupId, EMILY);
        flushAndClear();

        assertThat(studyGroupService.findGroupsLedBy(ALEX)).isEmpty();
        assertThat(service.findGroupsJoinedBy(BEN)).isEmpty();
        assertThat(service.findPendingRequestsBy(EMILY)).isEmpty();
        // Chloe didn't remove it, and the group itself is unchanged
        assertThat(service.findGroupsJoinedBy(CHLOE)).extracting(StudyGroup::getGroupId).containsExactly(groupId);
        assertThat(studyGroupService.getGroup(groupId).getMemberCount()).isEqualTo(3);
        assertThat(service.getMembers(groupId, ALEX)).hasSize(3);
    }

    // L4, L6: the leader first, then members in the order they joined (not the order they asked)
    @Test
    void membersAreLeaderFirstThenInJoinOrder() {
        Long chloeId = request(CHLOE);
        Long benId = request(BEN);
        accept(benId);
        // Equal accept times fall back to the request order, so make sure the clock has moved on
        waitForClockTick();
        accept(chloeId);
        flushAndClear();

        assertThat(service.getMembers(groupId, ALEX)).extracting(m -> m.getStudent().getName())
                .containsExactly("Alex Tan", "Ben Koh", "Chloe Lim");
    }

    // L5: the groups a student joined, open first; groups they lead are left out
    @Test
    void joinedGroupsListOpenFirstAndLeaveOutLedGroups() {
        Long closedGroup = saveGroup("Chloe's group", chloe, 4);
        accept(closedGroup, request(closedGroup, BEN), CHLOE);
        studyGroupService.closeGroup(closedGroup, CHLOE);
        accept(request(BEN));
        saveGroup("Ben's own group", ben, 4);
        flushAndClear();

        assertThat(service.findGroupsJoinedBy(BEN)).extracting(StudyGroup::getGroupId)
                .containsExactly(groupId, closedGroup);
        assertThat(service.findGroupsJoinedBy(ALEX)).isEmpty();
    }

    // L5, L6: declines and removals, newest answer first, for open groups only
    @Test
    void pastRequestsListDeclinesAndRemovalsForOpenGroupsOnly() {
        Long chloesGroup = saveGroup("Chloe's group", chloe, 4);
        Long removed = request(chloesGroup, BEN);
        accept(chloesGroup, removed, CHLOE);
        service.removeMember(chloesGroup, removed, CHLOE);
        Long rejected = request(BEN);
        service.rejectRequest(groupId, rejected, ALEX);
        Long closedGroup = saveGroup("Emily's group", emily, 4);
        service.rejectRequest(closedGroup, request(closedGroup, BEN), EMILY);
        studyGroupService.closeGroup(closedGroup, EMILY);
        flushAndClear();

        assertThat(service.findPastRequestsBy(BEN)).extracting(GroupMembership::getMembershipId)
                .containsExactly(rejected, removed);
    }

    // GRP-4: what the group's page offers each student
    @Test
    void viewerRoleForEveryKindOfStudent() {
        accept(request(BEN));
        request(CHLOE);
        service.rejectRequest(groupId, request(DANIEL), ALEX);
        Long emilyId = request(EMILY);
        accept(emilyId);
        service.removeMember(groupId, emilyId, ALEX);
        Student frank = saveStudent("frank@test.com", "Frank Lee", true);
        flushAndClear();

        assertThat(service.getViewerRole(groupId, ALEX)).isEqualTo(ViewerRole.LEADER);
        assertThat(service.getViewerRole(groupId, BEN)).isEqualTo(ViewerRole.MEMBER);
        assertThat(service.getViewerRole(groupId, CHLOE)).isEqualTo(ViewerRole.PENDING);
        assertThat(service.getViewerRole(groupId, DANIEL)).isEqualTo(ViewerRole.REJECTED);
        assertThat(service.getViewerRole(groupId, EMILY)).isEqualTo(ViewerRole.REMOVED);
        assertThat(service.getViewerRole(groupId, frank.getEmail())).isEqualTo(ViewerRole.NONE);

        Long otherGroup = saveGroup("Other", ben, 4);
        Map<Long, ViewerRole> roles = service.getViewerRoles(
                List.of(studyGroupService.getGroup(groupId), studyGroupService.getGroup(otherGroup)), BEN);
        assertThat(roles).containsEntry(groupId, ViewerRole.MEMBER).containsEntry(otherGroup, ViewerRole.LEADER);

        // Only students who never asked, or were declined or removed, can ask (again)
        assertThat(ViewerRole.values()).filteredOn(ViewerRole::canRequestToJoin)
                .containsExactlyInAnyOrder(ViewerRole.NONE, ViewerRole.REJECTED, ViewerRole.REMOVED);
    }

    // The clock here can tick only once a millisecond, so two quick actions may get the same time
    private static void waitForClockTick() {
        LocalDateTime start = LocalDateTime.now();
        while (!LocalDateTime.now().isAfter(start)) {
            Thread.onSpinWait();
        }
    }

    // Sends the student's request to Alex's group and returns its id
    private Long request(String email) {
        return request(groupId, email);
    }

    private Long request(Long group, String email) {
        GroupMembership membership = service.requestToJoin(group, email);
        entityManager.flush();
        return membership.getMembershipId();
    }

    private void accept(Long membershipId) {
        accept(groupId, membershipId, ALEX);
    }

    private void accept(Long group, Long membershipId, String leaderEmail) {
        service.acceptRequest(group, membershipId, leaderEmail);
    }

    // The leader is read again, in case an earlier flushAndClear() left the field's copy detached
    private Long saveGroup(String name, Student leader, int maxSize) {
        Student current = studentRepository.findByEmail(leader.getEmail()).orElseThrow();
        return studyGroupRepository.save(new StudyGroup(name, null, is442, StudyGoal.EXAM_PREPARATION,
                StudyMode.ONLINE, maxSize, current)).getGroupId();
    }

    private Student saveStudent(String email, String name, boolean takesIs442) {
        Student student = new Student(email, "dummyHash", name, "School of Computing and Information Systems",
                "Information Systems", 2, "91234567");
        if (takesIs442) {
            student.addCourse(is442);
        }
        return studentRepository.save(student);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}

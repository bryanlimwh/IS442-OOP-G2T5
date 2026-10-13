package com.studybuddy.service.studygroup;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.studybuddy.entity.GroupMembership;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyGroup;
import com.studybuddy.entity.enums.MembershipStatus;
import com.studybuddy.exception.MembershipNotFoundException;
import com.studybuddy.exception.NotGroupLeaderException;
import com.studybuddy.exception.StudentNotFoundException;
import com.studybuddy.exception.StudyGroupNotFoundException;
import com.studybuddy.repository.GroupMembershipRepository;
import com.studybuddy.repository.StudentRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles who is in a study group: students asking to join, the leader
 * accepting, rejecting and removing them, and students removing closed
 * groups from their My groups page. The group's own details are handled
 * by {@link StudyGroupService}; the two are split by the Single Responsibility
 * Principle, because membership is started by any student while the details
 * are only changed by the leader.
 * <p>
 * Every change goes through a {@link StudyGroup} method, never straight to
 * {@link GroupMembership}: {@code StudyGroup} is the aggregate root, and its
 * checks (not closed, not full, the leader can't be removed) can't be skipped.
 * Leader checks reuse {@link StudyGroupService#getGroupLedBy} instead of
 * repeating it.
 * <p>
 * The caller is identified by the email they logged in with. Exceptions thrown
 * by the entity for bad input ({@code IllegalArgumentException}) or the wrong
 * state ({@code IllegalStateException}) are not caught here; the controller shows them.
 */
@Service
public class GroupMembershipService {

    // Orders for the My groups page. A tie on time falls back to the id (the
    // later one first), so the order is the same on every run.
    private static final Comparator<GroupMembership> OPEN_GROUP_FIRST =
            Comparator.comparing(membership -> !membership.getStudyGroup().isActive());
    private static final Comparator<GroupMembership> NEWEST_REQUEST_FIRST = Comparator
            .comparing(GroupMembership::getRequestedAt, Comparator.reverseOrder())
            .thenComparing(GroupMembership::getMembershipId, Comparator.reverseOrder());
    private static final Comparator<GroupMembership> NEWEST_ANSWER_FIRST = Comparator
            .comparing(GroupMembership::getRespondedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(GroupMembership::getMembershipId, Comparator.reverseOrder());

    // Orders for the leader's Manage members page: the longest-waiting request and the earliest member first.
    private static final Comparator<GroupMembership> OLDEST_REQUEST_FIRST = Comparator
            .comparing(GroupMembership::getRequestedAt)
            .thenComparing(GroupMembership::getMembershipId);
    private static final Comparator<GroupMembership> EARLIEST_ANSWER_FIRST = Comparator
            .comparing(GroupMembership::getRespondedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(GroupMembership::getMembershipId);

    private final GroupMembershipRepository groupMembershipRepository;
    private final StudentRepository studentRepository;
    private final StudyGroupService studyGroupService;

    public GroupMembershipService(GroupMembershipRepository groupMembershipRepository,
            StudentRepository studentRepository, StudyGroupService studyGroupService) {
        this.groupMembershipRepository = groupMembershipRepository;
        this.studentRepository = studentRepository;
        this.studyGroupService = studyGroupService;
    }

    /**
     * Sends the student's request to join a group. A student who was rejected
     * or removed before gets their old request back as pending.
     *
     * @return the pending membership
     * @throws StudyGroupNotFoundException if no group has this id
     * @throws StudentNotFoundException if no student has this email
     */
    @Transactional
    public GroupMembership requestToJoin(Long groupId, String studentEmail) {
        StudyGroup group = studyGroupService.getGroup(groupId);
        Student student = findStudentByEmail(studentEmail);
        // No save() needed: the new row is in the group's memberships list, which
        // cascades to the database when the transaction ends.
        return group.requestToJoin(student);
    }

    /**
     * Accepts a pending request, making the student a member.
     * <p>
     * The group id comes from the page's URL. Checking the leader against the
     * group (not against whichever group the membership id points to) and then
     * letting {@link StudyGroup} refuse a membership from another group means
     * a changed id in the form can't reach someone else's group.
     *
     * @return the accepted membership, so the page can name the student
     * @throws StudyGroupNotFoundException if no group has this id
     * @throws NotGroupLeaderException if the caller doesn't lead the group
     * @throws MembershipNotFoundException if no membership has this id
     */
    @Transactional
    public GroupMembership acceptRequest(Long groupId, Long membershipId, String leaderEmail) {
        StudyGroup group = studyGroupService.getGroupLedBy(groupId, leaderEmail);
        GroupMembership membership = findMembership(membershipId);
        group.acceptRequest(membership);
        return membership;
    }

    /**
     * Rejects a pending request. The student can ask to join again later.
     *
     * @return the rejected membership, so the page can name the student
     * @throws StudyGroupNotFoundException if no group has this id
     * @throws NotGroupLeaderException if the caller doesn't lead the group
     * @throws MembershipNotFoundException if no membership has this id
     */
    @Transactional
    public GroupMembership rejectRequest(Long groupId, Long membershipId, String leaderEmail) {
        StudyGroup group = studyGroupService.getGroupLedBy(groupId, leaderEmail);
        GroupMembership membership = findMembership(membershipId);
        group.rejectRequest(membership);
        return membership;
    }

    /**
     * Removes an accepted member from an open group. The leader can't be removed.
     *
     * @return the removed membership, so the page can name the student
     * @throws StudyGroupNotFoundException if no group has this id
     * @throws NotGroupLeaderException if the caller doesn't lead the group
     * @throws MembershipNotFoundException if no membership has this id
     */
    @Transactional
    public GroupMembership removeMember(Long groupId, Long membershipId, String leaderEmail) {
        StudyGroup group = studyGroupService.getGroupLedBy(groupId, leaderEmail);
        GroupMembership membership = findMembership(membershipId);
        group.removeMember(membership);
        return membership;
    }

    /**
     * Takes a closed group off the student's own My groups page, whether they
     * led it, joined it or were still waiting on it. The student acts only on
     * their own link to the group, found from their login, so no leader check
     * is needed.
     *
     * @throws StudyGroupNotFoundException if no group has this id
     * @throws StudentNotFoundException if no student has this email
     */
    @Transactional
    public void removeFromMyGroups(Long groupId, String studentEmail) {
        StudyGroup group = studyGroupService.getGroup(groupId);
        group.removeFromMyGroups(findStudentByEmail(studentEmail));
    }

    /**
     * Requests waiting for the leader to accept or reject, for the leader's
     * "Manage members" page. Each one carries the membership id that its
     * Accept and Reject buttons send back.
     * <p>
     * Oldest first: a full group keeps requests waiting, so the student who
     * has waited longest is at the top when a place opens up.
     *
     * @throws StudyGroupNotFoundException if no group has this id
     * @throws NotGroupLeaderException if the caller doesn't lead the group
     */
    @Transactional(readOnly = true)
    public List<GroupMembership> getPendingRequests(Long groupId, String leaderEmail) {
        return studyGroupService.getGroupLedBy(groupId, leaderEmail).getPendingRequests().stream()
                .sorted(OLDEST_REQUEST_FIRST)
                .toList();
    }

    /**
     * The group's accepted members for the leader's "Manage members" page:
     * the leader first, then everyone else in the order they joined.
     *
     * @throws StudyGroupNotFoundException if no group has this id
     * @throws NotGroupLeaderException if the caller doesn't lead the group
     */
    @Transactional(readOnly = true)
    public List<GroupMembership> getMembers(Long groupId, String leaderEmail) {
        StudyGroup group = studyGroupService.getGroupLedBy(groupId, leaderEmail);
        return group.getAcceptedMemberships().stream()
                .sorted(Comparator.comparing((GroupMembership membership) -> !group.isLedBy(membership.getStudent()))
                        .thenComparing(EARLIEST_ANSWER_FIRST))
                .toList();
    }

    /**
     * Groups the student has joined as a member, open or closed, except closed
     * groups they have removed from My groups. Groups the student leads are
     * left out (the leader is also an accepted member); those come from
     * {@link StudyGroupService#findGroupsLedBy}, so a "My groups" page doesn't
     * list them twice.
     * <p>
     * Open groups come first, then closed ones, each most recently joined first.
     *
     * @throws StudentNotFoundException if no student has this email
     */
    @Transactional(readOnly = true)
    public List<StudyGroup> findGroupsJoinedBy(String studentEmail) {
        Student student = findStudentByEmail(studentEmail);
        return groupMembershipRepository.findByStudentAndStatus(student, MembershipStatus.ACCEPTED).stream()
                .filter(membership -> !membership.getStudyGroup().isLedBy(student))
                .filter(membership -> !membership.isHiddenFromMyGroups())
                .sorted(OPEN_GROUP_FIRST.thenComparing(NEWEST_ANSWER_FIRST))
                .map(GroupMembership::getStudyGroup)
                .toList();
    }

    /**
     * The student's requests still waiting for a leader to answer, plus those
     * that ended because the group closed first (GROUP_CLOSED), for the
     * "Pending group join requests" section of My groups. Keeping the closed
     * ones there tells the student what happened to their request; they can
     * then remove the group from My groups.
     * <p>
     * Requests to open groups come first, each part newest first.
     *
     * @throws StudentNotFoundException if no student has this email
     */
    @Transactional(readOnly = true)
    public List<GroupMembership> findPendingRequestsBy(String studentEmail) {
        Student student = findStudentByEmail(studentEmail);
        return groupMembershipRepository
                .findByStudentAndStatusIn(student, List.of(MembershipStatus.PENDING, MembershipStatus.GROUP_CLOSED))
                .stream()
                .filter(membership -> !membership.isHiddenFromMyGroups())
                .sorted(OPEN_GROUP_FIRST.thenComparing(NEWEST_REQUEST_FIRST))
                .toList();
    }

    /**
     * The student's declined requests and removals, most recent answer first,
     * for the My groups page. Only open groups are included: the student can
     * still ask to join those again, while a closed group's history has
     * nothing left to act on, so the list doesn't keep growing.
     * <p>
     * A removal is sorted by its accept time, because no removal time is stored.
     *
     * @throws StudentNotFoundException if no student has this email
     */
    @Transactional(readOnly = true)
    public List<GroupMembership> findPastRequestsBy(String studentEmail) {
        Student student = findStudentByEmail(studentEmail);
        return groupMembershipRepository
                .findByStudentAndStatusIn(student, List.of(MembershipStatus.REJECTED, MembershipStatus.REMOVED))
                .stream()
                .filter(membership -> membership.getStudyGroup().isActive())
                .sorted(NEWEST_ANSWER_FIRST)
                .toList();
    }

    /**
     * How the student is linked to the group, so its page can show the right
     * thing: leader tools, "you're a member", "request sent", or a join button
     * (with a confirmation first if they were rejected or removed before).
     *
     * @throws StudyGroupNotFoundException if no group has this id
     * @throws StudentNotFoundException if no student has this email
     */
    @Transactional(readOnly = true)
    public ViewerRole getViewerRole(Long groupId, String studentEmail) {
        return roleOf(studyGroupService.getGroup(groupId), findStudentByEmail(studentEmail));
    }

    /**
     * The student's role in each of the given groups, keyed by group id, for
     * the Browse page's labels and its "only groups I can join" filter. The
     * student is looked up once for the whole list.
     *
     * @throws StudentNotFoundException if no student has this email
     */
    @Transactional(readOnly = true)
    public Map<Long, ViewerRole> getViewerRoles(List<StudyGroup> groups, String studentEmail) {
        Student student = findStudentByEmail(studentEmail);
        Map<Long, ViewerRole> roles = new HashMap<>();
        for (StudyGroup group : groups) {
            roles.put(group.getGroupId(), roleOf(group, student));
        }
        return roles;
    }

    private ViewerRole roleOf(StudyGroup group, Student student) {
        // Checked first: the leader is also an ACCEPTED member, but needs their own answer.
        if (group.isLedBy(student)) {
            return ViewerRole.LEADER;
        }
        return group.getMembershipStatusOf(student)
                .map(ViewerRole::fromStatus)
                .orElse(ViewerRole.NONE);
    }

    private GroupMembership findMembership(Long membershipId) {
        return groupMembershipRepository.findById(membershipId)
                .orElseThrow(() -> new MembershipNotFoundException(membershipId));
    }

    // Finds the logged-in student's account from the email they logged in with.
    private Student findStudentByEmail(String email) {
        return studentRepository.findByEmail(email).orElseThrow(() -> new StudentNotFoundException(email));
    }
}

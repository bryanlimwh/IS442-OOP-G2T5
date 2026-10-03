package com.studybuddy.service.studygroup;

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
 * Handles who is in a study group: students asking to join, and the leader
 * accepting, rejecting and removing them. The group's own details are handled
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
     *
     * @throws MembershipNotFoundException if no membership has this id
     * @throws NotGroupLeaderException if the caller doesn't lead the group
     */
    @Transactional
    public void acceptRequest(Long membershipId, String leaderEmail) {
        GroupMembership membership = findMembership(membershipId);
        StudyGroup group = studyGroupService.getGroupLedBy(membership.getStudyGroup().getGroupId(), leaderEmail);
        group.acceptRequest(membership);
    }

    /**
     * Rejects a pending request.
     *
     * @throws MembershipNotFoundException if no membership has this id
     * @throws NotGroupLeaderException if the caller doesn't lead the group
     */
    @Transactional
    public void rejectRequest(Long membershipId, String leaderEmail) {
        GroupMembership membership = findMembership(membershipId);
        StudyGroup group = studyGroupService.getGroupLedBy(membership.getStudyGroup().getGroupId(), leaderEmail);
        group.rejectRequest(membership);
    }

    /**
     * Removes an accepted member from the group. The leader can't be removed.
     *
     * @throws MembershipNotFoundException if no membership has this id
     * @throws NotGroupLeaderException if the caller doesn't lead the group
     */
    @Transactional
    public void removeMember(Long membershipId, String leaderEmail) {
        GroupMembership membership = findMembership(membershipId);
        StudyGroup group = studyGroupService.getGroupLedBy(membership.getStudyGroup().getGroupId(), leaderEmail);
        group.removeMember(membership);
    }

    /**
     * Requests waiting for the leader to accept or reject, for the leader's
     * "manage members" page. Each one carries the membership id that its
     * Accept and Reject buttons send back.
     *
     * @throws StudyGroupNotFoundException if no group has this id
     * @throws NotGroupLeaderException if the caller doesn't lead the group
     */
    @Transactional(readOnly = true)
    public List<GroupMembership> getPendingRequests(Long groupId, String leaderEmail) {
        return studyGroupService.getGroupLedBy(groupId, leaderEmail).getPendingRequests();
    }

    /**
     * Groups the student has joined as a member, open or closed. Groups the
     * student leads are left out (the leader is also an accepted member);
     * those come from {@link StudyGroupService#findGroupsLedBy}, so a
     * "My groups" page doesn't list them twice.
     *
     * @throws StudentNotFoundException if no student has this email
     */
    @Transactional(readOnly = true)
    public List<StudyGroup> findGroupsJoinedBy(String studentEmail) {
        Student student = findStudentByEmail(studentEmail);
        return groupMembershipRepository.findByStudentAndStatus(student, MembershipStatus.ACCEPTED).stream()
                .map(GroupMembership::getStudyGroup)
                .filter(group -> !group.isLedBy(student))
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

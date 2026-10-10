package com.studybuddy.service.studygroup;

import java.util.Comparator;
import java.util.List;

import com.studybuddy.dto.StudyGroupForm;
import com.studybuddy.entity.Course;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyGroup;
import com.studybuddy.entity.enums.GroupStatus;
import com.studybuddy.exception.CourseNotFoundException;
import com.studybuddy.exception.NotGroupLeaderException;
import com.studybuddy.exception.StudentNotFoundException;
import com.studybuddy.exception.StudyGroupNotFoundException;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.repository.StudyGroupRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates study groups and manages their details: editing (including the
 * weekly time slots) and closing. Joining and membership are handled by {@code GroupMembershipService},
 * because they change for different reasons and are started by different users
 * (any student, rather than the leader). This is the Single Responsibility Principle.
 * <p>
 * Each method that changes a group follows the same steps: load the group,
 * check that the caller is its leader, then call one {@link StudyGroup} method.
 * The group's own rules (not closed, name not blank, size not below the
 * member count) live in the entity, so they aren't repeated here.
 * <p>
 * The caller is identified by the email they logged in with, as in the
 * team's other services. Exceptions thrown by the entity for bad input
 * ({@code IllegalArgumentException}) or the wrong state
 * ({@code IllegalStateException}) are not caught here; the controller shows them.
 */
@Service
public class StudyGroupService {

    // Closed groups sink below open ones. A tie on creation time falls back to
    // the id (the later one first), so the order is the same on every run.
    private static final Comparator<StudyGroup> OPEN_THEN_NEWEST = Comparator
            .comparing((StudyGroup group) -> !group.isActive())
            .thenComparing(StudyGroup::getCreatedAt, Comparator.reverseOrder())
            .thenComparing(StudyGroup::getGroupId, Comparator.reverseOrder());

    private final StudyGroupRepository studyGroupRepository;
    private final StudentRepository studentRepository;
    private final CourseRepository courseRepository;

    public StudyGroupService(StudyGroupRepository studyGroupRepository, StudentRepository studentRepository,
            CourseRepository courseRepository) {
        this.studyGroupRepository = studyGroupRepository;
        this.studentRepository = studentRepository;
        this.courseRepository = courseRepository;
    }

    /**
     * Creates an active group with the caller as its leader and first member,
     * with the form's weekly time slots. The form must already have passed
     * {@link StudyGroupFormValidator}.
     *
     * @throws StudentNotFoundException if no student has the leader's email
     * @throws CourseNotFoundException if the form's course doesn't exist
     */
    @Transactional
    public StudyGroup createGroup(String leaderEmail, StudyGroupForm form) {
        Student leader = findStudentByEmail(leaderEmail);
        Course course = courseRepository.findById(form.getCourseId())
                .orElseThrow(() -> new CourseNotFoundException(form.getCourseId()));
        StudyGroup group = new StudyGroup(form.getName(), form.getDescription(), course,
                form.getStudyGoal(), form.getStudyMode(), form.getMaxSize(), leader);
        form.toAvailabilitySlots().forEach(group::addAvailabilitySlot);
        // The only explicit save: the group is new, so Hibernate isn't tracking it yet.
        return studyGroupRepository.save(group);
    }

    /**
     * Updates the group's name, description, goal, mode, maximum size and
     * weekly time slots, all in one transaction, so a rejected change (e.g. a
     * size below the member count) leaves the slots untouched too. The form's
     * course is ignored, because a group's course can't be changed.
     *
     * @throws NotGroupLeaderException if the caller doesn't lead the group
     */
    @Transactional
    public void updateGroup(Long groupId, String leaderEmail, StudyGroupForm form) {
        StudyGroup group = getGroupLedBy(groupId, leaderEmail);
        group.updateDetails(form.getName(), form.getDescription(), form.getStudyGoal(),
                form.getStudyMode(), form.getMaxSize());
        group.replaceAvailability(form.toAvailabilitySlots());
    }

    /**
     * Closes the group. Every pending join request ends as GROUP_CLOSED, so
     * the students can see the group closed rather than being turned down.
     *
     * @return how many pending requests ended, for the leader's confirmation message
     * @throws NotGroupLeaderException if the caller doesn't lead the group
     */
    @Transactional
    public int closeGroup(Long groupId, String leaderEmail) {
        StudyGroup group = getGroupLedBy(groupId, leaderEmail);
        int endedRequests = group.getPendingRequests().size();
        group.close();
        return endedRequests;
    }

    /**
     * Loads one group, for its details page. Anyone logged in may view a group.
     *
     * @throws StudyGroupNotFoundException if no group has this id
     */
    @Transactional(readOnly = true)
    public StudyGroup getGroup(Long groupId) {
        return studyGroupRepository.findById(groupId)
                .orElseThrow(() -> new StudyGroupNotFoundException(groupId));
    }

    /**
     * Loads a group and checks that the student leads it. This is the one
     * leader check for both group services: {@code GroupMembershipService}
     * calls it rather than repeating it.
     *
     * @throws StudyGroupNotFoundException if no group has this id
     * @throws StudentNotFoundException if no student has this email
     * @throws NotGroupLeaderException if the student doesn't lead the group
     */
    @Transactional(readOnly = true)
    public StudyGroup getGroupLedBy(Long groupId, String studentEmail) {
        StudyGroup group = getGroup(groupId);
        Student student = findStudentByEmail(studentEmail);
        if (!group.isLedBy(student)) {
            throw new NotGroupLeaderException(groupId, student.getUserId());
        }
        return group;
    }

    /**
     * Every group that is still open, for students to browse, in the chosen
     * order with full groups last.
     */
    @Transactional(readOnly = true)
    public List<StudyGroup> findActiveGroups(GroupSort sort) {
        return sortForBrowse(studyGroupRepository.findByStatus(GroupStatus.ACTIVE), sort);
    }

    /**
     * Open groups for one course, for the browse page's course filter, in the
     * chosen order with full groups last.
     *
     * @throws CourseNotFoundException if no course has this id
     */
    @Transactional(readOnly = true)
    public List<StudyGroup> findActiveGroupsByCourse(Long courseId, GroupSort sort) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new CourseNotFoundException(courseId));
        return sortForBrowse(studyGroupRepository.findByCourseAndStatus(course, GroupStatus.ACTIVE), sort);
    }

    /**
     * Every group the student leads, for the My groups page: open groups
     * first, then closed ones, each newest first. Closed groups the leader
     * has removed from My groups are left out.
     *
     * @throws StudentNotFoundException if no student has this email
     */
    @Transactional(readOnly = true)
    public List<StudyGroup> findGroupsLedBy(String studentEmail) {
        Student leader = findStudentByEmail(studentEmail);
        return studyGroupRepository.findByLeader(leader).stream()
                .filter(group -> !group.isHiddenFromMyGroupsOf(leader))
                .sorted(OPEN_THEN_NEWEST)
                .toList();
    }

    // Full groups go last whatever the order: nobody can join them until a place opens up.
    private static List<StudyGroup> sortForBrowse(List<StudyGroup> groups, GroupSort sort) {
        return groups.stream()
                .sorted(Comparator.comparing(StudyGroup::isFull).thenComparing(sort.comparator()))
                .toList();
    }

    // Finds the logged-in student's account from the email they logged in with.
    private Student findStudentByEmail(String email) {
        return studentRepository.findByEmail(email).orElseThrow(() -> new StudentNotFoundException(email));
    }
}

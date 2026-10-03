package com.studybuddy.service.studygroup;

import java.util.List;

import com.studybuddy.dto.StudyGroupForm;
import com.studybuddy.entity.AvailabilitySlot;
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
 * Creates study groups and manages their details: editing, availability and
 * closing. Joining and membership are handled by {@code GroupMembershipService},
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
     * Creates an active group with the caller as its leader and first member.
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
        // The only explicit save: the group is new, so Hibernate isn't tracking it yet.
        return studyGroupRepository.save(group);
    }

    /**
     * Updates the group's name, description, goal, mode and maximum size. The
     * form's course is ignored, because a group's course can't be changed.
     *
     * @throws NotGroupLeaderException if the caller doesn't lead the group
     */
    @Transactional
    public void updateGroup(Long groupId, String leaderEmail, StudyGroupForm form) {
        StudyGroup group = getGroupLedBy(groupId, leaderEmail);
        group.updateDetails(form.getName(), form.getDescription(), form.getStudyGoal(),
                form.getStudyMode(), form.getMaxSize());
    }

    /**
     * Replaces the group's weekly availability with the given slots.
     *
     * @throws NotGroupLeaderException if the caller doesn't lead the group
     */
    @Transactional
    public void replaceAvailability(Long groupId, String leaderEmail, List<AvailabilitySlot> slots) {
        StudyGroup group = getGroupLedBy(groupId, leaderEmail);
        group.replaceAvailability(slots);
    }

    /**
     * Closes the group, which also rejects every pending join request.
     *
     * @throws NotGroupLeaderException if the caller doesn't lead the group
     */
    @Transactional
    public void closeGroup(Long groupId, String leaderEmail) {
        StudyGroup group = getGroupLedBy(groupId, leaderEmail);
        group.close();
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
     * Every group that is still open, for students to browse.
     */
    @Transactional(readOnly = true)
    public List<StudyGroup> findActiveGroups() {
        return studyGroupRepository.findByStatus(GroupStatus.ACTIVE);
    }

    /**
     * Open groups for one course, for the browse page's course filter.
     *
     * @throws CourseNotFoundException if no course has this id
     */
    @Transactional(readOnly = true)
    public List<StudyGroup> findActiveGroupsByCourse(Long courseId) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new CourseNotFoundException(courseId));
        return studyGroupRepository.findByCourseAndStatus(course, GroupStatus.ACTIVE);
    }

    /**
     * Every group the student leads, open or closed.
     *
     * @throws StudentNotFoundException if no student has this email
     */
    @Transactional(readOnly = true)
    public List<StudyGroup> findGroupsLedBy(String studentEmail) {
        return studyGroupRepository.findByLeader(findStudentByEmail(studentEmail));
    }

    // Finds the logged-in student's account from the email they logged in with.
    private Student findStudentByEmail(String email) {
        return studentRepository.findByEmail(email).orElseThrow(() -> new StudentNotFoundException(email));
    }
}

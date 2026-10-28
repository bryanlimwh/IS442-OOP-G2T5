package com.studybuddy.service.studygroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.studybuddy.dto.SlotForm;
import com.studybuddy.dto.StudyGroupForm;
import com.studybuddy.entity.AvailabilitySlot;
import com.studybuddy.entity.Course;
import com.studybuddy.entity.GroupMembership;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyGroup;
import com.studybuddy.entity.enums.GroupStatus;
import com.studybuddy.entity.enums.MembershipStatus;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;
import com.studybuddy.exception.CourseNotFoundException;
import com.studybuddy.exception.NotGroupLeaderException;
import com.studybuddy.exception.StudentNotFoundException;
import com.studybuddy.exception.StudyGroupNotFoundException;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.repository.StudyGroupRepository;
import com.studybuddy.service.auth.AccountCredentials;
import com.studybuddy.service.student.StudentProfileService;

import jakarta.persistence.EntityManager;

/**
 * StudyGroupService against an empty in-memory H2 (no seeders); each test is rolled back.
 * Courses IS442 and CS201; students Alex (takes IS442), Ben and Chloe (take IS442 and CS201)
 * and Daniel (takes nothing). The form starts as "IS442 Revision", IS442, Exam preparation,
 * Online, size 4, one slot Mon 10:00-12:00. StudentProfileService is only here for the TD-1
 * course-drop test. flushAndClear() writes changes to the database and forgets cached
 * objects, so the checks after it read what was really saved.
 */
@DataJpaTest
@Import({ StudyGroupService.class, StudentProfileService.class, AccountCredentials.class })
class StudyGroupServiceTest {

    private static final long UNKNOWN_ID = 999_999L;

    @TestConfiguration
    static class PasswordConfig {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }
    }

    @Autowired
    private StudyGroupService service;
    @Autowired
    private StudentProfileService studentProfileService;
    @Autowired
    private StudyGroupRepository studyGroupRepository;
    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private CourseRepository courseRepository;
    @Autowired
    private EntityManager entityManager;

    private Course is442;
    private Course cs201;
    private Student alex;
    private Student ben;
    private Student chloe;
    private Student daniel;

    @BeforeEach
    void setUp() {
        is442 = courseRepository.save(new Course("IS442", "Object Oriented Programming"));
        cs201 = courseRepository.save(new Course("CS201", "Data Structures and Algorithms"));
        alex = saveStudent("alex@test.com", "Alex Tan", is442);
        ben = saveStudent("ben@test.com", "Ben Koh", is442, cs201);
        chloe = saveStudent("chloe@test.com", "Chloe Lim", is442, cs201);
        daniel = saveStudent("daniel@test.com", "Daniel Ng");
    }

    // L1, L2, GRP-1
    @Test
    void createGroupSavesDetailsSlotsAndLeader() {
        StudyGroupForm form = form("IS442 Revision", is442, 4,
                slot(DayOfWeek.MONDAY, 10, 12), slot(DayOfWeek.WEDNESDAY, 14, 16));
        form.setDescription("Past papers");

        Long id = service.createGroup("alex@test.com", form).getGroupId();
        flushAndClear();

        StudyGroup saved = service.getGroup(id);
        assertThat(saved.getName()).isEqualTo("IS442 Revision");
        assertThat(saved.getDescription()).isEqualTo("Past papers");
        assertThat(saved.getCourse().getCourseCode()).isEqualTo("IS442");
        assertThat(saved.getStudyGoal()).isEqualTo(StudyGoal.EXAM_PREPARATION);
        assertThat(saved.getStudyMode()).isEqualTo(StudyMode.ONLINE);
        assertThat(saved.getMaxSize()).isEqualTo(4);
        assertThat(saved.getStatus()).isEqualTo(GroupStatus.ACTIVE);
        assertThat(saved.getLeader().getEmail()).isEqualTo("alex@test.com");
        assertThat(saved.getAcceptedMembers()).extracting(Student::getName).containsExactly("Alex Tan");
        assertThat(saved.getAvailability()).extracting(AvailabilitySlot::getDayOfWeek)
                .containsExactlyInAnyOrder(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);
        assertThat(studentRepository.findByEmail("alex@test.com").orElseThrow().isLeader()).isTrue();
    }

    // L1, GRP-1
    @Test
    void createGroupWithUnknownCourseOrStudentSavesNothing() {
        StudyGroupForm unknownCourse = form("IS442 Revision", is442, 4, slot(DayOfWeek.MONDAY, 10, 12));
        unknownCourse.setCourseId(UNKNOWN_ID);

        assertThatThrownBy(() -> service.createGroup("alex@test.com", unknownCourse))
                .isInstanceOf(CourseNotFoundException.class);
        assertThatThrownBy(() -> service.createGroup("ghost@test.com", form("IS442 Revision", is442, 4)))
                .isInstanceOf(StudentNotFoundException.class);

        assertThat(studyGroupRepository.count()).isZero();
    }

    // L1, GRP-1
    @Test
    void createGroupRejectsBadDetailsAndSavesNothing() {
        assertThatThrownBy(() -> service.createGroup("alex@test.com", form("   ", is442, 4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Group name is required");
        assertThatThrownBy(() -> service.createGroup("alex@test.com", form("IS442 Revision", is442, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Max size must be at least 2");

        assertThat(studyGroupRepository.count()).isZero();
    }

    // GRP-1, K4: pins current behaviour, update if fixed. The service and entity don't check the
    // length; StudyGroupForm's @Size does, so in the app this only happens if the form is skipped.
    @Test
    void nameOver255CharactersFailsInDatabase() {
        // 255 is the column size and still fits
        assertThat(service.createGroup("alex@test.com", form("N".repeat(255), is442, 4)).getGroupId()).isNotNull();

        assertThatThrownBy(() -> service.createGroup("ben@test.com", form("N".repeat(256), is442, 4)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // GRP-1, K4: pins current behaviour, update if fixed. As above, StudyGroupForm's @Size is the only check.
    @Test
    void descriptionOver1000CharactersFailsInDatabase() {
        StudyGroupForm fits = form("Fits", is442, 4);
        fits.setDescription("D".repeat(1000));
        assertThat(service.createGroup("alex@test.com", fits).getGroupId()).isNotNull();

        StudyGroupForm tooLong = form("Too long", is442, 4);
        tooLong.setDescription("D".repeat(1001));
        assertThatThrownBy(() -> service.createGroup("ben@test.com", tooLong))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // L1, TD-1: intended: a leader or member need not take the course (team decision). Daniel takes no
    // courses but can lead a CS201 group.
    @Test
    void createGroupDoesNotRequireTakingTheCourse() {
        Long id = service.createGroup("daniel@test.com", form("CS201 Practice", cs201, 4)).getGroupId();
        flushAndClear();

        assertThat(service.getGroup(id).getLeader().getEmail()).isEqualTo("daniel@test.com");
        assertThat(studentRepository.findByEmail("daniel@test.com").orElseThrow().getCoursesTaken()).isEmpty();
    }

    // L1, TD-1: intended: a leader or member need not take the course (team decision). Ben drops CS201
    // while leading a CS201 group.
    @Test
    void leaderCanDropTheCourseAndStillLeadTheGroup() {
        Long id = service.createGroup("ben@test.com", form("CS201 Practice", cs201, 4)).getGroupId();
        flushAndClear();

        studentProfileService.removeCourse("ben@test.com", cs201.getCourseId());
        flushAndClear();

        assertThat(studentRepository.findByEmail("ben@test.com").orElseThrow().getCoursesTaken())
                .extracting(Course::getCourseCode).containsExactly("IS442");
        StudyGroup group = service.getGroup(id);
        assertThat(group.isActive()).isTrue();
        assertThat(group.getLeader().getEmail()).isEqualTo("ben@test.com");
        assertThat(group.getCourse().getCourseCode()).isEqualTo("CS201");
    }

    // L3, GRP-3: the form's course is ignored
    @Test
    void updateGroupChangesDetailsAndSlotsButNotCourse() {
        Long id = createIs442Group("alex@test.com", 4);
        flushAndClear();

        StudyGroupForm changes = form("OOP Finals", cs201, 6, slot(DayOfWeek.FRIDAY, 14, 16),
                slot(DayOfWeek.SATURDAY, 9, 11));
        changes.setDescription("Mock exams");
        changes.setStudyGoal(StudyGoal.PROBLEM_SOLVING);
        changes.setStudyMode(StudyMode.IN_PERSON);
        service.updateGroup(id, "alex@test.com", changes);
        flushAndClear();

        StudyGroup saved = service.getGroup(id);
        assertThat(saved.getName()).isEqualTo("OOP Finals");
        assertThat(saved.getDescription()).isEqualTo("Mock exams");
        assertThat(saved.getStudyGoal()).isEqualTo(StudyGoal.PROBLEM_SOLVING);
        assertThat(saved.getStudyMode()).isEqualTo(StudyMode.IN_PERSON);
        assertThat(saved.getMaxSize()).isEqualTo(6);
        assertThat(saved.getCourse().getCourseCode()).isEqualTo("IS442");
        assertThat(saved.getAvailability()).extracting(AvailabilitySlot::getDayOfWeek)
                .containsExactlyInAnyOrder(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);
        // The old Monday slot is deleted, not left without a group
        assertThat(entityManager.createQuery("select count(s) from AvailabilitySlot s", Long.class)
                .getSingleResult()).isEqualTo(2);
    }

    // GRP-3: three members (Alex, Ben, Chloe)
    @Test
    void updateSizeCannotGoBelowMemberCount() {
        Long id = createIs442Group("alex@test.com", 4);
        StudyGroup group = service.getGroup(id);
        addMember(group, ben);
        addMember(group, chloe);
        flushAndClear();

        // Equal to the member count is allowed
        service.updateGroup(id, "alex@test.com", form("IS442 Revision", is442, 3, slot(DayOfWeek.MONDAY, 10, 12)));
        flushAndClear();
        assertThat(service.getGroup(id).getMaxSize()).isEqualTo(3);
        assertThat(service.getGroup(id).isFull()).isTrue();

        assertThatThrownBy(() -> service.updateGroup(id, "alex@test.com",
                form("Renamed", is442, 2, slot(DayOfWeek.TUESDAY, 18, 20))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Max size can't be below the current member count (3)");
        flushAndClear();

        // Neither the details nor the slots changed
        StudyGroup saved = service.getGroup(id);
        assertThat(saved.getName()).isEqualTo("IS442 Revision");
        assertThat(saved.getMaxSize()).isEqualTo(3);
        assertThat(saved.getAvailability()).extracting(AvailabilitySlot::getDayOfWeek)
                .containsExactly(DayOfWeek.MONDAY);
    }

    // GRP-2, L3: Ben is a member, Chloe has asked to join, Daniel has no link
    @Test
    void onlyTheLeaderCanUpdateOrClose() {
        Long id = createIs442Group("alex@test.com", 4);
        StudyGroup group = service.getGroup(id);
        addMember(group, ben);
        group.requestToJoin(chloe);
        flushAndClear();

        for (String email : List.of("ben@test.com", "chloe@test.com", "daniel@test.com")) {
            assertThatThrownBy(() -> service.updateGroup(id, email, form("Taken over", is442, 9)))
                    .isInstanceOf(NotGroupLeaderException.class);
            assertThatThrownBy(() -> service.closeGroup(id, email))
                    .isInstanceOf(NotGroupLeaderException.class);
            assertThatThrownBy(() -> service.getGroupLedBy(id, email))
                    .isInstanceOf(NotGroupLeaderException.class);
        }
        flushAndClear();

        StudyGroup saved = service.getGroup(id);
        assertThat(saved.getName()).isEqualTo("IS442 Revision");
        assertThat(saved.getMaxSize()).isEqualTo(4);
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getPendingRequests()).hasSize(1);
        assertThat(service.getGroupLedBy(id, "alex@test.com").getGroupId()).isEqualTo(id);
    }

    // GRP-6, L3: Ben and Chloe pending, Daniel a member
    @Test
    void closeGroupEndsPendingRequestsAndReturnsHowMany() {
        Long id = createIs442Group("alex@test.com", 4);
        StudyGroup group = service.getGroup(id);
        group.requestToJoin(ben);
        group.requestToJoin(chloe);
        addMember(group, daniel);
        flushAndClear();

        assertThat(service.closeGroup(id, "alex@test.com")).isEqualTo(2);
        flushAndClear();

        StudyGroup saved = service.getGroup(id);
        assertThat(saved.getStatus()).isEqualTo(GroupStatus.CLOSED);
        assertThat(saved.getMembershipStatusOf(ben)).contains(MembershipStatus.GROUP_CLOSED);
        assertThat(saved.getMembershipStatusOf(chloe)).contains(MembershipStatus.GROUP_CLOSED);
        assertThat(saved.getMembershipStatusOf(daniel)).contains(MembershipStatus.ACCEPTED);
        assertThat(saved.getMemberCount()).isEqualTo(2);
    }

    // GRP-6: nothing waiting gives 0
    @Test
    void closeGroupWithNoRequestsReturnsZero() {
        Long id = createIs442Group("alex@test.com", 4);

        assertThat(service.closeGroup(id, "alex@test.com")).isZero();
    }

    // GRP-3, GRP-6, TD-4: closing is permanent
    @Test
    void closedGroupCannotBeUpdatedOrClosedAgain() {
        Long id = createIs442Group("alex@test.com", 4);
        service.closeGroup(id, "alex@test.com");
        flushAndClear();

        assertThatThrownBy(() -> service.updateGroup(id, "alex@test.com", form("Reopened", is442, 5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Group is closed");
        assertThatThrownBy(() -> service.closeGroup(id, "alex@test.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Group is closed");
        flushAndClear();

        StudyGroup saved = service.getGroup(id);
        assertThat(saved.getName()).isEqualTo("IS442 Revision");
        assertThat(saved.getAvailability()).hasSize(1);
        assertThat(saved.getStatus()).isEqualTo(GroupStatus.CLOSED);
    }

    // GRP-2
    @Test
    void unknownGroupOrStudentIsNotFound() {
        Long id = createIs442Group("alex@test.com", 4);

        assertThatThrownBy(() -> service.getGroup(UNKNOWN_ID)).isInstanceOf(StudyGroupNotFoundException.class);
        assertThatThrownBy(() -> service.getGroupLedBy(UNKNOWN_ID, "alex@test.com"))
                .isInstanceOf(StudyGroupNotFoundException.class);
        assertThatThrownBy(() -> service.updateGroup(UNKNOWN_ID, "alex@test.com", form("X", is442, 4)))
                .isInstanceOf(StudyGroupNotFoundException.class);
        assertThatThrownBy(() -> service.closeGroup(UNKNOWN_ID, "alex@test.com"))
                .isInstanceOf(StudyGroupNotFoundException.class);
        assertThatThrownBy(() -> service.closeGroup(id, "ghost@test.com"))
                .isInstanceOf(StudentNotFoundException.class);
        assertThatThrownBy(() -> service.findGroupsLedBy("ghost@test.com"))
                .isInstanceOf(StudentNotFoundException.class);
    }

    // GRP-4, GRP-6: Browse lists open groups only, full ones last whatever the order
    @Test
    void browseListsOpenGroupsWithFullOnesLast() {
        Long full = createGroup("ben@test.com", "Alpha full", is442, 2);
        addMember(service.getGroup(full), chloe);
        Long older = createGroup("alex@test.com", "Charlie", is442, 5);
        Long closed = createGroup("chloe@test.com", "Closed", is442, 4);
        service.closeGroup(closed, "chloe@test.com");
        Long newer = createGroup("daniel@test.com", "bravo", cs201, 3);
        flushAndClear();

        assertThat(ids(service.findActiveGroups(GroupSort.NEWEST))).containsExactly(newer, older, full);
        assertThat(ids(service.findActiveGroups(GroupSort.NAME))).containsExactly(newer, older, full);
        // Places left: Charlie 4, bravo 2
        assertThat(ids(service.findActiveGroups(GroupSort.MOST_PLACES))).containsExactly(older, newer, full);
        assertThat(ids(service.findActiveGroups(GroupSort.COURSE))).containsExactly(newer, older, full);
    }

    // GRP-4
    @Test
    void browseByCourseListsThatCourseOnly() {
        Long is442Group = createGroup("alex@test.com", "IS442 Revision", is442, 4);
        createGroup("ben@test.com", "CS201 Practice", cs201, 4);
        Long closed = createGroup("chloe@test.com", "IS442 Closed", is442, 4);
        service.closeGroup(closed, "chloe@test.com");
        flushAndClear();

        assertThat(ids(service.findActiveGroupsByCourse(is442.getCourseId(), GroupSort.NEWEST)))
                .containsExactly(is442Group);
        assertThatThrownBy(() -> service.findActiveGroupsByCourse(UNKNOWN_ID, GroupSort.NEWEST))
                .isInstanceOf(CourseNotFoundException.class);
    }

    // L3, GRP-6: My groups lists open groups first, each newest first, and leaves out closed ones
    // the leader removed from My groups
    @Test
    void groupsLedByListsOpenFirstAndHidesRemovedClosedGroups() {
        Long closedOld = createGroup("alex@test.com", "Closed old", is442, 4);
        Long open = createGroup("alex@test.com", "Open", is442, 4);
        Long closedNew = createGroup("alex@test.com", "Closed new", is442, 4);
        Long hidden = createGroup("alex@test.com", "Hidden", is442, 4);
        createGroup("ben@test.com", "Ben's", is442, 4);
        service.closeGroup(closedOld, "alex@test.com");
        service.closeGroup(closedNew, "alex@test.com");
        service.closeGroup(hidden, "alex@test.com");
        service.getGroup(hidden).removeFromMyGroups(alex);
        flushAndClear();

        assertThat(ids(service.findGroupsLedBy("alex@test.com"))).containsExactly(open, closedNew, closedOld);
    }

    private Long createIs442Group(String leaderEmail, int maxSize) {
        return service.createGroup(leaderEmail, form("IS442 Revision", is442, maxSize, slot(DayOfWeek.MONDAY, 10, 12)))
                .getGroupId();
    }

    private Long createGroup(String leaderEmail, String name, Course course, int maxSize) {
        return service.createGroup(leaderEmail, form(name, course, maxSize, slot(DayOfWeek.MONDAY, 10, 12)))
                .getGroupId();
    }

    private static void addMember(StudyGroup group, Student student) {
        GroupMembership request = group.requestToJoin(student);
        group.acceptRequest(request);
    }

    private static List<Long> ids(List<StudyGroup> groups) {
        return groups.stream().map(StudyGroup::getGroupId).toList();
    }

    private static StudyGroupForm form(String name, Course course, int maxSize, SlotForm... slots) {
        StudyGroupForm form = new StudyGroupForm();
        form.setName(name);
        form.setCourseId(course.getCourseId());
        form.setStudyGoal(StudyGoal.EXAM_PREPARATION);
        form.setStudyMode(StudyMode.ONLINE);
        form.setMaxSize(maxSize);
        form.setSlots(new ArrayList<>(List.of(slots)));
        return form;
    }

    private static SlotForm slot(DayOfWeek day, int startHour, int endHour) {
        SlotForm slot = new SlotForm();
        slot.setDayOfWeek(day);
        slot.setStartTime(LocalTime.of(startHour, 0));
        slot.setEndTime(LocalTime.of(endHour, 0));
        return slot;
    }

    private Student saveStudent(String email, String name, Course... courses) {
        Student student = new Student(email, "dummyHash", name, "School of Computing and Information Systems",
                "Information Systems", 2, "91234567");
        for (Course course : courses) {
            student.addCourse(course);
        }
        return studentRepository.save(student);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}

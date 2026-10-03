package com.studybuddy.config;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.studybuddy.entity.AvailabilitySlot;
import com.studybuddy.entity.Course;
import com.studybuddy.entity.GroupMembership;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyGroup;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.repository.StudyGroupRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds six study groups so the demo has one example of every state a group
 * or join request can be in. Runs after StudentDataSeeder and CourseDataSeeder,
 * which this seeder depends on.
 * <p>
 * Alex Tan (the demo login) leads one group, is a member of another, has a
 * pending request to a third and can ask to join a fourth, so logging in as
 * Alex shows both the leader's and the member's side. Ben Koh leads the group
 * Alex is in. Every other leader and member is picked by course: the first
 * students (in seeded order) who take that course. A leader of one group
 * may be a member of another, as a real student could be.
 * <p>
 * Groups are built through StudyGroup's own methods (requestToJoin,
 * acceptRequest, ...), so the seed data passes the same rules as a real user;
 * an impossible setup, such as too many members, fails at startup.
 * <p>
 * With the current StudentDataSeeder (fixed random seed) this gives:
 * <pre>
 * 1. IS442   led by Alex Tan       accepted: Ben Koh, Chloe Lim        pending: Farhan Ismail, Jasmine Teo
 * 2. CS201   led by Ben Koh        accepted: Alex Tan, Emily Wong
 * 3. IS111   led by Farhan Ismail  accepted: Isaac Chua                pending: Alex Tan
 * 4. IS112   led by Daniel Ng      accepted: Hannah Goh                (has space for Alex to request)
 * 5. IS216   led by Grace Lee      accepted: Daniel Ng, Ryan Low (FULL) rejected: Sarah Koh, removed: Timothy Ho
 * 6. ECON101 led by Emily Wong     accepted: Grace Lee, Isaac Chua     (CLOSED)
 * </pre>
 */
@Component
@Order(5)
public class StudyGroupDataSeeder implements CommandLineRunner {

    private final StudyGroupRepository studyGroupRepository;
    private final StudentRepository studentRepository;
    private final CourseRepository courseRepository;

    public StudyGroupDataSeeder(StudyGroupRepository studyGroupRepository, StudentRepository studentRepository,
            CourseRepository courseRepository) {
        this.studyGroupRepository = studyGroupRepository;
        this.studentRepository = studentRepository;
        this.courseRepository = courseRepository;
    }

    // Transactional so the students' lazy collections stay attached to an open
    // Hibernate session: the StudyGroup constructor adds the group to the
    // leader's groupsLed, and picking members reads each student's coursesTaken.
    @Override
    @Transactional
    public void run(String... args) {
        if (studyGroupRepository.count() > 0) {
            return;
        }
        Map<String, Course> coursesByCode = courseRepository.findAll().stream()
                .collect(Collectors.toMap(Course::getCourseCode, Function.identity()));
        // Sorted by id, i.e. the order StudentDataSeeder created them, so the same students are picked every run.
        List<Student> students = studentRepository.findAll().stream()
                .sorted(Comparator.comparing(Student::getUserId))
                .toList();
        Student alex = findByEmail(students, StudentDataSeeder.DEMO_STUDENT_EMAIL);
        Student ben = findByEmail(students, StudentDataSeeder.APPENDIX_B_STUDENT_EMAIL);

        Course is442 = coursesByCode.get("IS442");
        Course cs201 = coursesByCode.get("CS201");
        Course is111 = coursesByCode.get("IS111");
        Course is112 = coursesByCode.get("IS112");
        Course is216 = coursesByCode.get("IS216");
        Course econ101 = coursesByCode.get("ECON101");

        // Choose every leader first, so nobody is picked to lead two groups.
        Set<Student> leaders = new HashSet<>(List.of(alex, ben));
        Student is111Leader = pickLeader(students, is111, leaders);
        Student is112Leader = pickLeader(students, is112, leaders);
        Student is216Leader = pickLeader(students, is216, leaders);
        Student econ101Leader = pickLeader(students, econ101, leaders);

        List<StudyGroup> groups = List.of(
                alexsGroup(is442, alex, ben, takers(students, is442, alex, ben)),
                bensGroup(cs201, ben, alex, takers(students, cs201, alex, ben)),
                groupAlexIsWaitingFor(is111, is111Leader, alex, takers(students, is111, alex, ben, is111Leader)),
                groupWithSpace(is112, is112Leader, takers(students, is112, alex, ben, is112Leader)),
                fullGroup(is216, is216Leader, takers(students, is216, alex, ben, is216Leader)),
                closedGroup(econ101, econ101Leader, takers(students, econ101, alex, ben, econ101Leader)));

        // Saving a group also saves its memberships and slots (cascade).
        studyGroupRepository.saveAll(groups);
        System.out.println("Seeded " + groups.size() + " study groups");
    }

    // 1. Alex leads, with room for one more and two requests waiting: the leader's "manage members" demo.
    // The slots are the Wednesday and Friday times from the brief's Appendix A.
    private StudyGroup alexsGroup(Course course, Student alex, Student ben, List<Student> others) {
        StudyGroup group = new StudyGroup("IS442 OOP Exam Prep",
                "Weekly revision of OOP concepts and past-year papers before the IS442 final.",
                course, StudyGoal.EXAM_PREPARATION, StudyMode.IN_PERSON, 4, alex);
        addSlot(group, DayOfWeek.WEDNESDAY, 19, 21);
        addSlot(group, DayOfWeek.FRIDAY, 14, 16);
        addMember(group, ben);
        addMember(group, others.get(0));
        group.requestToJoin(others.get(1));
        group.requestToJoin(others.get(2));
        return group;
    }

    // 2. Ben leads and Alex is an accepted member: the "groups I've joined" demo.
    private StudyGroup bensGroup(Course course, Student ben, Student alex, List<Student> others) {
        StudyGroup group = new StudyGroup("CS201 DSA Problem Solvers",
                "Working through practice problems on trees, graphs and dynamic programming together.",
                course, StudyGoal.PROBLEM_SOLVING, StudyMode.ONLINE, 5, ben);
        addSlot(group, DayOfWeek.TUESDAY, 20, 22);
        addMember(group, alex);
        addMember(group, others.get(0));
        return group;
    }

    // 3. Alex has asked to join and is waiting for the leader: the "request pending" demo.
    private StudyGroup groupAlexIsWaitingFor(Course course, Student leader, Student alex, List<Student> others) {
        StudyGroup group = new StudyGroup("IS111 Python Basics",
                "Going over the week's lecture examples and lab exercises for anyone new to programming.",
                course, StudyGoal.CONCEPT_REVIEW, StudyMode.EITHER, 4, leader);
        addSlot(group, DayOfWeek.MONDAY, 10, 12);
        addMember(group, others.get(0));
        group.requestToJoin(alex);
        return group;
    }

    // 4. Open with spare places and Alex not in it: the "request to join" demo.
    private StudyGroup groupWithSpace(Course course, Student leader, List<Student> others) {
        StudyGroup group = new StudyGroup("IS112 SQL Practice",
                "Practising SQL queries and database design questions.",
                course, StudyGoal.PROBLEM_SOLVING, StudyMode.ONLINE, 4, leader);
        addSlot(group, DayOfWeek.THURSDAY, 19, 21);
        addMember(group, others.get(0));
        return group;
    }

    // 5. Full (3 of 3), with a removed and a rejected request in its history: the "group is full" demo.
    private StudyGroup fullGroup(Course course, Student leader, List<Student> others) {
        StudyGroup group = new StudyGroup("IS216 Web App Project Team",
                "Planning and reviewing our IS216 project together.",
                course, StudyGoal.PROJECT_DISCUSSION, StudyMode.IN_PERSON, 3, leader);
        addSlot(group, DayOfWeek.SATURDAY, 10, 13);
        // Removed before the group fills up: removeMember needs an accepted member, and accepting needs a free place.
        GroupMembership removed = addMember(group, others.get(3));
        group.removeMember(removed);
        group.rejectRequest(group.requestToJoin(others.get(2)));
        addMember(group, others.get(0));
        addMember(group, others.get(1));
        return group;
    }

    // 6. Closed by its leader: hidden from browsing, still listed under the leader's groups.
    private StudyGroup closedGroup(Course course, Student leader, List<Student> others) {
        StudyGroup group = new StudyGroup("ECON101 Midterm Revision",
                "Revised for the ECON101 midterm together. The midterm is over, so the group is closed.",
                course, StudyGoal.EXAM_PREPARATION, StudyMode.IN_PERSON, 5, leader);
        addSlot(group, DayOfWeek.FRIDAY, 16, 18);
        addMember(group, others.get(0));
        addMember(group, others.get(1));
        group.close();
        return group;
    }

    // The first student taking the course who doesn't already lead a group. Records them as a leader.
    private Student pickLeader(List<Student> students, Course course, Set<Student> leaders) {
        Student leader = students.stream()
                .filter(student -> student.getCoursesTaken().contains(course))
                .filter(student -> !leaders.contains(student))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No student available to lead " + course.getCourseCode()));
        leaders.add(leader);
        return leader;
    }

    // Students taking the course, in seeded order, leaving out the ones given.
    private List<Student> takers(List<Student> students, Course course, Student... excluded) {
        List<Student> excludedList = List.of(excluded);
        return students.stream()
                .filter(student -> student.getCoursesTaken().contains(course))
                .filter(student -> !excludedList.contains(student))
                .toList();
    }

    // Sends a join request and accepts it straight away.
    private GroupMembership addMember(StudyGroup group, Student student) {
        GroupMembership membership = group.requestToJoin(student);
        group.acceptRequest(membership);
        return membership;
    }

    private void addSlot(StudyGroup group, DayOfWeek day, int startHour, int endHour) {
        group.addAvailabilitySlot(new AvailabilitySlot(day, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0)));
    }

    private Student findByEmail(List<Student> students, String email) {
        return students.stream()
                .filter(student -> student.getEmail().equals(email))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Seeded student not found: " + email));
    }
}

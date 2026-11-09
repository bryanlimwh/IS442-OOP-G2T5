package com.studybuddy.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.studybuddy.entity.Course;
import com.studybuddy.entity.GroupMembership;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyGroup;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;

/**
 * The text GroupMemberSummary gives the Manage members page. Plain unit test, nothing saved.
 * Alex leads "IS442 Revision" (IS442, size 4); Ben takes IS442, Daniel takes no courses.
 */
class GroupMemberSummaryTest {

    private final Course is442 = new Course("IS442", "Object Oriented Programming");
    private final Student alex = student("alex@test.com", "Alex Tan");
    private final Student ben = student("ben@test.com", "Ben Koh");
    private final Student daniel = student("daniel@test.com", "Daniel Ng");
    private final StudyGroup group = new StudyGroup("IS442 Revision", "Past papers", is442,
            StudyGoal.EXAM_PREPARATION, StudyMode.ONLINE, 4, alex);

    // GRP-2, TD-1: a member need not take any course, so their courses line is left empty
    @Test
    void memberWithNoCoursesHasAnEmptyCoursesLine() {
        ben.addCourse(is442);

        assertThat(GroupMemberSummary.from(join(daniel)).coursesLine()).isEmpty();
        assertThat(GroupMemberSummary.from(join(ben)).coursesLine()).isEqualTo("Takes IS442");
    }

    private GroupMembership join(Student student) {
        GroupMembership membership = group.requestToJoin(student);
        group.acceptRequest(membership);
        return membership;
    }

    private static Student student(String email, String name) {
        return new Student(email, "dummyHash", name, "School of Computing and Information Systems",
                "Information Systems", 2, "91234567");
    }
}

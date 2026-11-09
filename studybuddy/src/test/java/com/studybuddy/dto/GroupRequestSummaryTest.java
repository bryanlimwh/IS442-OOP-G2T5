package com.studybuddy.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.studybuddy.entity.Course;
import com.studybuddy.entity.GroupMembership;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyGroup;
import com.studybuddy.entity.enums.MembershipStatus;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;

/**
 * GroupRequestSummary, the student's own join request on the My groups page. Plain unit test,
 * nothing saved. Alex leads "IS442 Revision" (IS442) with room for 2: Alex and one more.
 */
class GroupRequestSummaryTest {

    private final Course is442 = new Course("IS442", "Object Oriented Programming");
    private final Student alex = student("alex@test.com", "Alex Tan");
    private final Student ben = student("ben@test.com", "Ben Koh");
    private final Student chloe = student("chloe@test.com", "Chloe Lim");
    private final StudyGroup group = new StudyGroup("IS442 Revision", "Past papers", is442,
            StudyGoal.EXAM_PREPARATION, StudyMode.ONLINE, 2, alex);

    // GRP-4, TD-3: a request sent while there was room stays waiting once the group fills, and shows the group as full
    @Test
    void requestToAGroupThatHasSinceFilledShowsFull() {
        GroupMembership bensRequest = group.requestToJoin(ben);
        assertThat(GroupRequestSummary.from(bensRequest).full()).isFalse();

        group.acceptRequest(group.requestToJoin(chloe));
        GroupRequestSummary summary = GroupRequestSummary.from(bensRequest);

        assertThat(summary.status()).isEqualTo(MembershipStatus.PENDING);
        assertThat(summary.memberCount()).isEqualTo(2);
        assertThat(summary.full()).isTrue();
    }

    private static Student student(String email, String name) {
        return new Student(email, "dummyHash", name, "School of Computing and Information Systems",
                "Information Systems", 2, "91234567");
    }
}

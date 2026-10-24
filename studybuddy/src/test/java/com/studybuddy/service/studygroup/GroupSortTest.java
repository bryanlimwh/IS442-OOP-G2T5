package com.studybuddy.service.studygroup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.studybuddy.entity.Course;
import com.studybuddy.entity.GroupMembership;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyGroup;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;

/**
 * The Browse page's "Sort by" options, with no Spring. Groups are never saved, so each
 * test uses values that differ (a tie would fall back to the group id, which is null here).
 * NEWEST and "full groups last" are checked on saved groups in StudyGroupServiceTest.
 */
class GroupSortTest {

    private final Course is442 = new Course("IS442", "Object Oriented Programming");
    private final Course cs201 = new Course("CS201", "Data Structures and Algorithms");

    // GRP-4: an unknown or missing name in the URL falls back, so the Browse page still loads
    @Test
    void fromNameFallsBackForUnknownNames() {
        assertThat(GroupSort.fromName("NAME", GroupSort.NEWEST)).isEqualTo(GroupSort.NAME);
        assertThat(GroupSort.fromName("MOST_PLACES", GroupSort.NEWEST)).isEqualTo(GroupSort.MOST_PLACES);

        assertThat(GroupSort.fromName(null, GroupSort.NEWEST)).isEqualTo(GroupSort.NEWEST);
        assertThat(GroupSort.fromName("bogus", GroupSort.COURSE)).isEqualTo(GroupSort.COURSE);
        // Exact names only
        assertThat(GroupSort.fromName("name", GroupSort.NEWEST)).isEqualTo(GroupSort.NEWEST);
    }

    // GRP-4: the order of the groups students can join (no rule fixes the order itself)
    @Test
    void eachOptionSortsByItsOwnValue() {
        StudyGroup beta = group("beta", is442, 4, 1);
        StudyGroup alpha = group("Alpha", is442, 6, 1);
        StudyGroup gamma = group("Gamma", cs201, 5, 3);

        // Case doesn't matter for names
        assertThat(sorted(GroupSort.NAME, beta, gamma, alpha)).containsExactly(alpha, beta, gamma);
        // Places left: Alpha 5, Beta 3, Gamma 2
        assertThat(sorted(GroupSort.MOST_PLACES, gamma, beta, alpha)).containsExactly(alpha, beta, gamma);
        // Course code first, then name
        assertThat(sorted(GroupSort.COURSE, beta, alpha, gamma)).containsExactly(gamma, alpha, beta);

        assertThat(GroupSort.NAME.getLabel()).isEqualTo("Name (A–Z)");
    }

    private static List<StudyGroup> sorted(GroupSort sort, StudyGroup... groups) {
        return Stream.of(groups).sorted(sort.comparator()).toList();
    }

    // A group with the given number of members, the leader included
    private static StudyGroup group(String name, Course course, int maxSize, int members) {
        StudyGroup group = new StudyGroup(name, null, course, StudyGoal.EXAM_PREPARATION, StudyMode.ONLINE,
                maxSize, student(name + ".leader@test.com"));
        for (int i = 1; i < members; i++) {
            GroupMembership request = group.requestToJoin(student(name + ".member" + i + "@test.com"));
            group.acceptRequest(request);
        }
        return group;
    }

    private static Student student(String email) {
        return new Student(email, "dummyHash", email, "School of Computing and Information Systems",
                "Information Systems", 2, "91234567");
    }
}

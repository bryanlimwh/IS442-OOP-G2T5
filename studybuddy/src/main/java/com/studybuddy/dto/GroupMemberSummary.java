package com.studybuddy.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.studybuddy.entity.GroupMembership;
import com.studybuddy.matching.StudentPublicProfile;

/**
 * A read-only copy of one join request or member, for the leader's "Manage
 * members" page.
 * <p>
 * The student is copied as a {@link StudentPublicProfile} (name, school,
 * programme, year and courses), the same record the matches page uses, so the
 * contact number never reaches the page. The membership id is kept because it
 * is what the Accept, Reject and Remove buttons send back.
 */
public record GroupMemberSummary(
        Long membershipId,
        StudentPublicProfile student,
        boolean leader,
        LocalDateTime requestedAt,
        LocalDateTime respondedAt) {

    // e.g. "9 Oct 2026, 19:05": the same format as GroupRequestSummary
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    public static GroupMemberSummary from(GroupMembership membership) {
        return new GroupMemberSummary(membership.getMembershipId(),
                StudentPublicProfile.from(membership.getStudent()),
                membership.getStudyGroup().isLedBy(membership.getStudent()),
                membership.getRequestedAt(), membership.getRespondedAt());
    }

    // "Sent on 9 Oct 2026, 19:05", on a join request
    public String requestedOn() {
        return requestedAt.format(DATE_TIME_FORMAT);
    }

    // "Joined on 9 Oct 2026, 19:05", on a member. The leader was accepted when they created the group.
    public String joinedOn() {
        return respondedAt.format(DATE_TIME_FORMAT);
    }

    // "Information Systems, Year 3 · School of Computing and Information Systems", as on the matches page
    public String studiesLine() {
        return student.programme() + ", Year " + student.yearOfStudy() + " · " + student.school();
    }

    // "Takes IS442, CS201", or empty if the student hasn't listed any courses
    public String coursesLine() {
        return student.courseCodes().isEmpty() ? "" : "Takes " + String.join(", ", student.courseCodes());
    }
}

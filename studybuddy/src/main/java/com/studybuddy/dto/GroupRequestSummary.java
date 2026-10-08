package com.studybuddy.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.studybuddy.entity.GroupMembership;
import com.studybuddy.entity.StudyGroup;
import com.studybuddy.entity.enums.MembershipStatus;

/**
 * A read-only copy of one of the student's own join requests, for the
 * "Pending group join requests" and "Declined or removed" sections of the
 * My groups page.
 * <p>
 * Like {@link StudyGroupSummary}, it is copied out of the entity so the page
 * never holds a live {@code GroupMembership} (and through it, other students).
 * Only the group's name, course and member count are copied.
 */
public record GroupRequestSummary(
        Long groupId,
        String groupName,
        String courseCode,
        String courseName,
        int memberCount,
        int maxSize,
        MembershipStatus status,
        LocalDateTime requestedAt,
        LocalDateTime respondedAt) {

    // e.g. "9 Oct 2026, 19:05": 24-hour, like the time slots
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    public static GroupRequestSummary from(GroupMembership membership) {
        StudyGroup group = membership.getStudyGroup();
        return new GroupRequestSummary(group.getGroupId(), group.getName(),
                group.getCourse().getCourseCode(), group.getCourse().getCourseName(),
                group.getMemberCount(), group.getMaxSize(),
                membership.getStatus(), membership.getRequestedAt(), membership.getRespondedAt());
    }

    // Same rule as StudyGroupSummary, so the member-count fragment can show either record
    public boolean full() {
        return memberCount >= maxSize;
    }

    // "Sent on 9 Oct 2026, 19:05"
    public String requestedOn() {
        return requestedAt.format(DATE_TIME_FORMAT);
    }

    // "Declined on 9 Oct 2026, 19:05". Not used for removals: respondedAt then still holds the accept time.
    public String respondedOn() {
        return respondedAt.format(DATE_TIME_FORMAT);
    }

    public boolean removed() {
        return status == MembershipStatus.REMOVED;
    }
}

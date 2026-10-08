package com.studybuddy.dto;

import java.time.format.TextStyle;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.studybuddy.entity.AvailabilitySlot;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyGroup;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;

/**
 * A read-only copy of a study group, for display on the study group pages.
 * <p>
 * Copied out of the entity so a page never holds a live {@code StudyGroup}:
 * its leader and members are {@code Student}s, whose contact numbers the brief
 * says must stay private. Only names are copied. This follows the team's
 * {@code PreferenceSummary} and {@code AccountStatus} records.
 */
public record StudyGroupSummary(
        Long groupId,
        String name,
        String description,
        String courseCode,
        String courseName,
        StudyGoal studyGoal,
        StudyMode studyMode,
        String leaderName,
        List<String> memberNames,
        int memberCount,
        int maxSize,
        boolean active,
        List<String> sessions,
        int pendingRequestCount) {

    public static StudyGroupSummary from(StudyGroup group) {
        List<String> sessions = group.getAvailability().stream()
                .sorted(Comparator.comparing(AvailabilitySlot::getDayOfWeek)
                        .thenComparing(AvailabilitySlot::getStartTime))
                .map(StudyGroupSummary::describe)
                .toList();
        List<String> memberNames = group.getAcceptedMembers().stream()
                .map(Student::getName)
                .toList();
        return new StudyGroupSummary(group.getGroupId(), group.getName(), group.getDescription(),
                group.getCourse().getCourseCode(), group.getCourse().getCourseName(),
                group.getStudyGoal(), group.getStudyMode(), group.getLeader().getName(),
                memberNames, group.getMemberCount(), group.getMaxSize(), group.isActive(), sessions,
                group.getPendingRequests().size());
    }

    public boolean full() {
        return memberCount >= maxSize;
    }

    // "1 session a week", "2 sessions a week"
    public String sessionsPerWeek() {
        return sessions.size() + (sessions.size() == 1 ? " session a week" : " sessions a week");
    }

    // "1 request waiting", "2 requests waiting", for the leader on the My groups page
    public String requestsWaiting() {
        return pendingRequestCount + (pendingRequestCount == 1 ? " request waiting" : " requests waiting");
    }

    // e.g. "Wed 19:00-21:00", the same format as the matches page
    private static String describe(AvailabilitySlot slot) {
        return slot.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " "
                + slot.getStartTime() + "-" + slot.getEndTime();
    }
}

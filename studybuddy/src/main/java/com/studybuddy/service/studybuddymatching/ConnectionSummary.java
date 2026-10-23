package com.studybuddy.service.studybuddymatching;

import java.time.LocalDateTime;

import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyBuddyConnection;
import com.studybuddy.matching.StudentPublicProfile;

/**
 * One of the logged-in student's study buddies, as shown on the connections page.
 * <p>
 * Unlike a public profile, this includes the buddy's contact number: being
 * connected is exactly what the brief says unlocks it.
 */
public record ConnectionSummary(
        Long connectionId,
        StudentPublicProfile buddy,
        String buddyContactNumber,
        LocalDateTime connectedSince) {

    /**
     * @param viewer the logged-in student, who must be one of the pair
     */
    public static ConnectionSummary from(StudyBuddyConnection connection, Student viewer) {
        Student buddy = connection.buddyOf(viewer);
        return new ConnectionSummary(connection.getId(), StudentPublicProfile.from(buddy),
                buddy.getContactNumber(), connection.getCreatedAt());
    }
}

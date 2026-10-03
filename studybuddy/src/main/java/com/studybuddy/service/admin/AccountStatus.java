package com.studybuddy.service.admin;

import java.time.LocalDateTime;

import com.studybuddy.entity.Student;
import com.studybuddy.entity.User;
import com.studybuddy.entity.enums.Role;

/**
 * What the admin account pages send back, instead of the User entity itself.
 * <p>
 * Why this exists: User has a public getter for the password hash (AuthService
 * needs it), and Student has one for the contact number. Returning the entity
 * from a controller turns every getter into JSON, so both would be sent to the
 * browser. This record only carries the account status and usage figures the
 * brief asks an admin to see.
 *
 * @param usage the student's activity counts; null for Admin accounts, which
 *              have no study activity
 */
public record AccountStatus(
        Long userId,
        String email,
        String displayName,
        Role role,
        boolean active,
        LocalDateTime createdAt,
        StudentUsage usage) {

    /**
     * Basic usage figures for a student account.
     */
    public record StudentUsage(
            int coursesTaken,
            int studyPreferences,
            int groupsLed,
            int matchRequestsSent,
            int matchRequestsReceived) {
    }

    public static AccountStatus from(User user, StudentUsage usage) {
        return new AccountStatus(user.getUserId(), user.getEmail(), user.getDisplayName(),
                user.getRole(), user.isActive(), user.getCreatedAt(), usage);
    }

    // Admins have no usage figures, so callers only build one for a Student.
    public static StudentUsage usageOf(Student student, int matchRequestsSent, int matchRequestsReceived) {
        return new StudentUsage(student.getCoursesTaken().size(), student.getPreferences().size(),
                student.getGroupsLed().size(), matchRequestsSent, matchRequestsReceived);
    }
}

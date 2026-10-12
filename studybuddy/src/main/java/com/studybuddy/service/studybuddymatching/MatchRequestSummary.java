package com.studybuddy.service.studybuddymatching;

import java.time.LocalDateTime;

import com.studybuddy.entity.MatchRequest;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.enums.MatchRequestStatus;
import com.studybuddy.matching.StudentPublicProfile;

/**
 * What the match request API sends back, instead of the MatchRequest entity itself.
 * 
 * Why this exists: a MatchRequest holds the full sender and recipient Student objects,
 * and Student has public getters for the contact number and (through User) the password hash.
 * 
 * Returning the entity from a controller turns every getter into JSON,
 * so both would be sent to whoever called the API.
 * 
 * This record only carries fields that are safe to show:
 * - each student's public profile (name, school, programme, year, courses)
 * - the other student's contact number, but only once the request is accepted, as the brief requires. Until then it is null
 */

public record MatchRequestSummary(
        Long requestId,
        StudentPublicProfile sender,
        StudentPublicProfile recipient,
        String message,
        MatchRequestStatus status,
        LocalDateTime createdAt,
        String buddyContactNumber) {

    /**
     * @param viewer the logged-in student asking for this request (its sender or recipient)
     */
    public static MatchRequestSummary from(MatchRequest request, Student viewer) {
        // "Buddy" is whichever of the two students is not the person viewing.
        Student buddy = request.getSender().equals(viewer) ? request.getRecipient() : request.getSender();
        // Contact numbers are only revealed after the request has been accepted.
        String buddyContactNumber = request.getStatus() == MatchRequestStatus.ACCEPTED
                ? buddy.getContactNumber()
                : null;

        return new MatchRequestSummary(request.getId(),
                StudentPublicProfile.from(request.getSender()),
                StudentPublicProfile.from(request.getRecipient()),
                request.getMessage(), request.getStatus(), request.getCreatedAt(), buddyContactNumber);
    }
}

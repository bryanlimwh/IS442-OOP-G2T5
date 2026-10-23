package com.studybuddy.service.studybuddymatching;

import java.time.LocalDateTime;

import com.studybuddy.entity.MatchRequest;
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
 * This record only carries fields that are safe to show: each student's public
 * profile (name, school, programme, year, courses), never a contact number.
 * Accepting a request makes the two students study buddies, and contact numbers
 * are shown on the connections page instead (see ConnectionSummary), so ending
 * the connection hides them again.
 */

public record MatchRequestSummary(
        Long requestId,
        StudentPublicProfile sender,
        StudentPublicProfile recipient,
        String message,
        MatchRequestStatus status,
        LocalDateTime createdAt) {

    public static MatchRequestSummary from(MatchRequest request) {
        return new MatchRequestSummary(request.getId(),
                StudentPublicProfile.from(request.getSender()),
                StudentPublicProfile.from(request.getRecipient()),
                request.getMessage(), request.getStatus(), request.getCreatedAt());
    }
}

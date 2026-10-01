package com.studybuddy.controller;

import java.security.Principal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.studybuddy.service.studybuddymatching.MatchRequestService;
import com.studybuddy.service.studybuddymatching.MatchRequestSummary;

// The URLs no longer contain a user id (previously e.g. /api/match-requests/{user_id}/sent).
// Instead, every method takes the logged-in user from Principal, which Spring Security fills in
// from the login session. That way a student can't change a number in the URL to see or act on
// another student's requests.
//
// Every method returns MatchRequestSummary rather than the MatchRequest entity, so the JSON
// response never contains contact numbers (until accepted) or password hashes.
@RestController
@RequestMapping("/api/match-requests")
public class MatchRequestController {

    // Stores the reference to a MatchRequestService object
    // to allow for system functionality related to match requests
    private final MatchRequestService service;

    // Spring will automatically create the MatchRequestService object
    // and inject it when creating this controller
    public MatchRequestController(MatchRequestService service) {
        this.service = service;
    }

    // Handles POST requests to "/api/match-requests/create?recipientId=5&message=Hi"
    // The sender is always the logged-in student; the message is optional.
    @PostMapping("/create")
    public boolean createMatchRequest(@RequestParam Long recipientId,
            @RequestParam(required = false) String message, Principal principal) {
        return service.createRequest(principal.getName(), recipientId, message);
    }

    // Handles GET requests to "/api/match-requests/sent"
    // Returns the requests the logged-in student has sent
    @GetMapping("/sent")
    public List<MatchRequestSummary> getAllSentMatchRequests(Principal principal) {
        return service.getMatchRequestBySender(principal.getName());
    }

    // Handles GET requests to "/api/match-requests/received"
    // Returns the requests the logged-in student has received
    @GetMapping("/received")
    public List<MatchRequestSummary> getAllReceivedMatchRequest(Principal principal) {
        return service.getMatchRequestByRecipient(principal.getName());
    }

    // Handles GET requests to "/api/match-requests/{request_id}"
    // @PathVariable reads the {request_id} from the URL, e.g. /api/match-requests/5 -> request_id = 5
    // Only the request's sender or recipient gets it; anyone else gets 403 Forbidden.
    @GetMapping("/{request_id}")
    public MatchRequestSummary getMatchRequest(@PathVariable("request_id") Long requestId, Principal principal) {
        return service.getRequestById(principal.getName(), requestId);
    }

    // Handles PUT requests to "/api/match-requests/{request_id}/accept"
    // e.g. PUT /api/match-requests/5/accept
    // Only the student the request was sent to can accept it; anyone else gets 403 Forbidden.
    @PutMapping("/{request_id}/accept")
    public MatchRequestSummary accept(@PathVariable("request_id") Long requestId, Principal principal) {
        // Update the 'status' column of the row in the
        // 'match_request' table whose 'id' column = request_id
        return service.acceptRequest(principal.getName(), requestId);
    }
}

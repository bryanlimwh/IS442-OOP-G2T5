package com.studybuddy.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.studybuddy.entity.Student;
import com.studybuddy.entity.MatchRequest;
import com.studybuddy.service.studybuddymatching.MatchRequestService;

import java.util.*;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;



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

    
    @PostMapping("/create")
    public boolean createMatchRequest(Student sender, Student recipient, String message) {
        return service.createRequest(sender, recipient, message);
    }

    @GetMapping("/{sender_id}/sent")
    // @PathVariable reads the {request_id} from the URL
    // e.g. /api/match-requests/1/sent → sender_id = 1
    public List<MatchRequest> getAllSentMatchRequests(@PathVariable Long sender_id) {
        return service.getMatchRequestBySender(sender_id);
    }

    @GetMapping("/{recipient_id}/received")
    public List<MatchRequest> getAllReceivedMatchRequest(@PathVariable Long recipient_id) {
        return service.getMatchRequestByRecipient(recipient_id);
    }

    @GetMapping("/{user_id}/{request_id}")
    public MatchRequest getMatchRequest(@PathVariable Long user_id, @PathVariable Long request_id) {
        return service.getRequestById(user_id, request_id);
    }
    

    // Handles PUT requests to "/api/match-requests/{user_id}/{id}/accept"
    // e.g. PUT /api/match-requests/1/5/accept
    @PutMapping("/{user_id}/{request_id}/accept")
    public MatchRequest accept(@PathVariable Long user_id, @PathVariable Long request_id) { 
        // Update the 'status' column of the row in the
        // 'match_request' table whose 'id' column = request_id
        return service.acceptRequest(request_id);
    }
}

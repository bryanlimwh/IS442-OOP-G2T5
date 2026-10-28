package com.studybuddy.controller;

import java.security.Principal;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.studybuddy.exception.DuplicateMatchRequestException;
import com.studybuddy.exception.MatchRequestNotFoundException;
import com.studybuddy.exception.NotMatchRequestParticipantException;
import com.studybuddy.exception.StudentNotFoundException;
import com.studybuddy.service.studybuddymatching.MatchRequestService;

// The match requests page: shows the logged-in student's received and sent requests,
// and handles sending, accepting and declining requests from HTML forms.
// Only students reach this page: SecurityConfig restricts /match-requests/** to the STUDENT role.
//
// The URLs never contain a student id. Every method takes the logged-in student from Principal,
// which Spring Security fills in from the login session, so a student can't change a number
// in the URL to act as another student.
//
// Each action redirects back to the list with a one-time success or error message
// (Post/Redirect/Get), so refreshing the page doesn't repeat the action.
// HTML forms only support GET and POST, so accept/decline use POST instead of PUT.
@Controller
@RequestMapping("/match-requests")
public class MatchRequestController {

    // Thymeleaf template rendered by the GET method (templates/student/match-requests.html)
    private static final String VIEW = "student/match-requests";
    // Where every action sends the browser afterwards
    private static final String REDIRECT_TO_LIST = "redirect:/match-requests";
    // The database column size. The page's box stops at it too, but a hand-made POST doesn't
    private static final int MAX_MESSAGE_LENGTH = 255;

    // Stores the reference to a MatchRequestService object
    // to allow for system functionality related to match requests
    private final MatchRequestService service;

    // Spring will automatically create the MatchRequestService object
    // and inject it when creating this controller
    public MatchRequestController(MatchRequestService service) {
        this.service = service;
    }

    // Handles GET requests to "/match-requests"
    // Adds the logged-in student's received and sent requests to the Model for the page to display
    @GetMapping
    public String showRequests(Principal principal, Model model) {
        // principal.getName() retrieves the email the student logged in with
        model.addAttribute("received", service.getMatchRequestByRecipient(principal.getName()));
        model.addAttribute("sent", service.getMatchRequestBySender(principal.getName()));
        return VIEW;
    }

    // Handles POST requests to "/match-requests"
    // Submitted by the "Send request" form on the matches page (fields: recipientId, message)
    // The sender is always the logged-in student; the message is optional.
    @PostMapping
    public String createRequest(@RequestParam Long recipientId, @RequestParam(required = false) String message,
            Principal principal, RedirectAttributes redirectAttributes) {
        if (message != null && message.length() > MAX_MESSAGE_LENGTH) {
            redirectAttributes.addFlashAttribute("error",
                    "Messages can be at most " + MAX_MESSAGE_LENGTH + " characters.");
            return REDIRECT_TO_LIST;
        }
        try {
            service.createRequest(principal.getName(), recipientId, message);
            redirectAttributes.addFlashAttribute("success", "Request sent.");
        } catch (DuplicateMatchRequestException | IllegalArgumentException e) {
            // The service's messages are already readable, e.g. a request already exists,
            // or the student tried to send a request to themselves
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (StudentNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That student no longer exists.");
        }
        return REDIRECT_TO_LIST;
    }

    // Handles POST requests to "/match-requests/{requestId}/accept"
    // Only the student the request was sent to can accept it.
    @PostMapping("/{requestId}/accept")
    public String accept(@PathVariable Long requestId, Principal principal, RedirectAttributes redirectAttributes) {
        try {
            // Updates the request's 'status' column to "ACCEPTED"
            service.acceptRequest(principal.getName(), requestId);
            redirectAttributes.addFlashAttribute("success",
                    "Request accepted. You're now study buddies: their contact number is on the Study buddies page.");
        } catch (MatchRequestNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That request no longer exists.");
        } catch (NotMatchRequestParticipantException e) {
            redirectAttributes.addFlashAttribute("error", "Only the student who received this request can accept it.");
        } catch (IllegalStateException e) {
            // Thrown by MatchRequest.accept() when the request is no longer PENDING
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return REDIRECT_TO_LIST;
    }

    // Handles POST requests to "/match-requests/{requestId}/reject"
    // Only the student the request was sent to can decline it.
    @PostMapping("/{requestId}/reject")
    public String reject(@PathVariable Long requestId, Principal principal, RedirectAttributes redirectAttributes) {
        try {
            // Updates the request's 'status' column to "REJECTED"
            service.rejectRequest(principal.getName(), requestId);
            redirectAttributes.addFlashAttribute("success", "Request declined.");
        } catch (MatchRequestNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That request no longer exists.");
        } catch (NotMatchRequestParticipantException e) {
            redirectAttributes.addFlashAttribute("error", "Only the student who received this request can decline it.");
        } catch (IllegalStateException e) {
            // Thrown by MatchRequest.reject() when the request is no longer PENDING
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return REDIRECT_TO_LIST;
    }
}

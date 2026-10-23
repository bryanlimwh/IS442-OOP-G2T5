package com.studybuddy.controller;

import java.security.Principal;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.studybuddy.exception.ConnectionNotFoundException;
import com.studybuddy.service.studybuddymatching.StudyBuddyConnectionService;

// The study buddies page: lists the logged-in student's connections, with contact numbers,
// and lets them end one. Only students reach it: SecurityConfig restricts /connections/** to STUDENT.
// Like MatchRequestController, the student always comes from Principal, never from the URL.
@Controller
@RequestMapping("/connections")
public class StudyBuddyConnectionController {

    private static final String VIEW = "student/connections";
    private static final String REDIRECT_TO_LIST = "redirect:/connections";

    private final StudyBuddyConnectionService connectionService;

    public StudyBuddyConnectionController(StudyBuddyConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @GetMapping
    public String showConnections(Principal principal, Model model) {
        model.addAttribute("connections", connectionService.getConnections(principal.getName()));
        return VIEW;
    }

    // POST rather than DELETE: HTML forms only support GET and POST.
    @PostMapping("/{connectionId}/end")
    public String endConnection(@PathVariable Long connectionId, Principal principal,
            RedirectAttributes redirectAttributes) {
        try {
            connectionService.endConnection(principal.getName(), connectionId);
            redirectAttributes.addFlashAttribute("success",
                    "Connection ended. You can no longer see each other's contact numbers.");
        } catch (ConnectionNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That connection no longer exists.");
        }
        return REDIRECT_TO_LIST;
    }
}

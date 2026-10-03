package com.studybuddy.controller;

import java.security.Principal;

import com.studybuddy.exception.StudyGroupNotFoundException;
import com.studybuddy.service.studygroup.GroupMembershipService;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The study group actions about who is in a group: asking to join (and, later,
 * the leader accepting, rejecting and removing members). Each action calls
 * {@link GroupMembershipService}, then redirects back to the group's page with
 * a one-time success or error message (Post/Redirect/Get), so refreshing the
 * page doesn't repeat the action.
 */
@Controller
@RequestMapping("/groups")
public class GroupMembershipController {

    private final GroupMembershipService groupMembershipService;

    public GroupMembershipController(GroupMembershipService groupMembershipService) {
        this.groupMembershipService = groupMembershipService;
    }

    @PostMapping("/{groupId}/join")
    public String requestToJoin(@PathVariable Long groupId, Principal principal,
            RedirectAttributes redirectAttributes) {
        try {
            groupMembershipService.requestToJoin(groupId, principal.getName());
            redirectAttributes.addFlashAttribute("success", "Request sent. The group's leader will review it.");
        } catch (StudyGroupNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That group no longer exists.");
            return "redirect:/groups";
        } catch (IllegalStateException | IllegalArgumentException e) {
            // The entity's own messages are already readable, e.g. "Group is full" or "Group is closed".
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/groups/" + groupId;
    }
}

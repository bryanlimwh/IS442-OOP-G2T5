package com.studybuddy.controller;

import java.security.Principal;

import com.studybuddy.exception.StudyGroupNotFoundException;
import com.studybuddy.service.studygroup.GroupMembershipService;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The study group actions about who is in a group: asking to join, removing a
 * closed group from My groups (and, later, the leader accepting, rejecting and
 * removing members). Each action calls {@link GroupMembershipService}, then
 * redirects back with a one-time success or error message (Post/Redirect/Get),
 * so refreshing the page doesn't repeat the action.
 */
@Controller
@RequestMapping("/groups")
public class GroupMembershipController {

    private final GroupMembershipService groupMembershipService;

    public GroupMembershipController(GroupMembershipService groupMembershipService) {
        this.groupMembershipService = groupMembershipService;
    }

    // from=mine (sent when the page was opened from My groups) is passed back on the redirect,
    // so the group's page keeps its "← My groups" link.
    @PostMapping("/{groupId}/join")
    public String requestToJoin(@PathVariable Long groupId, @RequestParam(required = false) String from,
            Principal principal, RedirectAttributes redirectAttributes) {
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
        // addAttribute (not addFlashAttribute) puts it in the redirect's URL: /groups/4?from=mine
        if (StudyGroupController.FROM_MY_GROUPS.equals(from)) {
            redirectAttributes.addAttribute("from", from);
        }
        return "redirect:/groups/" + groupId;
    }

    // The "Remove" button on a closed group's card on My groups, so closed groups don't pile up there.
    @PostMapping("/{groupId}/remove-from-my-groups")
    public String removeFromMyGroups(@PathVariable Long groupId, Principal principal,
            RedirectAttributes redirectAttributes) {
        try {
            groupMembershipService.removeFromMyGroups(groupId, principal.getName());
            redirectAttributes.addFlashAttribute("success", "Removed from My groups.");
        } catch (StudyGroupNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That group no longer exists.");
        } catch (IllegalStateException | IllegalArgumentException e) {
            // e.g. "Only a closed group can be removed from My groups"
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/groups/mine";
    }
}

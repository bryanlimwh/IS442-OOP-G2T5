package com.studybuddy.controller;

import java.security.Principal;
import java.util.List;
import java.util.function.Supplier;

import com.studybuddy.dto.GroupMemberSummary;
import com.studybuddy.dto.StudyGroupSummary;
import com.studybuddy.entity.GroupMembership;
import com.studybuddy.exception.MembershipNotFoundException;
import com.studybuddy.exception.NotGroupLeaderException;
import com.studybuddy.exception.StudyGroupNotFoundException;
import com.studybuddy.service.studygroup.GroupMembershipService;
import com.studybuddy.service.studygroup.StudyGroupService;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The study group actions about who is in a group: asking to join, cancelling
 * a request that is still waiting, leaving, removing a
 * closed group from My groups, and the leader's Manage members page, where
 * they accept or reject join requests and remove members. Each action calls
 * {@link GroupMembershipService}, then redirects back with a one-time success
 * or error message (Post/Redirect/Get), so refreshing the page doesn't repeat
 * the action.
 */
@Controller
@RequestMapping("/groups")
public class GroupMembershipController {

    private static final String MEMBERSHIP_GONE = "That request or member no longer exists.";

    private final GroupMembershipService groupMembershipService;
    private final StudyGroupService studyGroupService;

    public GroupMembershipController(GroupMembershipService groupMembershipService,
            StudyGroupService studyGroupService) {
        this.groupMembershipService = groupMembershipService;
        this.studyGroupService = studyGroupService;
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
            redirectAttributes.addFlashAttribute("error", StudyGroupController.GROUP_GONE);
            return "redirect:/groups";
        } catch (IllegalStateException | IllegalArgumentException e) {
            // The entity's own messages are already readable, e.g. "Group is full" or "Group is closed".
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return redirectKeepingFrom("/groups/" + groupId, from, redirectAttributes);
    }

    // "Cancel request" on the group's page or on a pending card on My groups. backToMyGroups is sent by
    // the My groups card, so the student stays on that page; otherwise it's back to the group's page.
    @PostMapping("/{groupId}/cancel-request")
    public String cancelRequest(@PathVariable Long groupId, @RequestParam(required = false) String from,
            @RequestParam(defaultValue = "false") boolean backToMyGroups,
            Principal principal, RedirectAttributes redirectAttributes) {
        try {
            groupMembershipService.cancelRequest(groupId, principal.getName());
            redirectAttributes.addFlashAttribute("success", "Request cancelled. You can ask to join again later.");
        } catch (StudyGroupNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", StudyGroupController.GROUP_GONE);
            return "redirect:/groups";
        } catch (IllegalStateException | IllegalArgumentException e) {
            // e.g. "Only a request that is still waiting can be cancelled" if the leader answered it first
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        if (backToMyGroups) {
            return "redirect:/groups/mine";
        }
        return redirectKeepingFrom("/groups/" + groupId, from, redirectAttributes);
    }

    // "Leave group" on the group's page. The reason is optional; only the leader sees it.
    @PostMapping("/{groupId}/leave")
    public String leaveGroup(@PathVariable Long groupId, @RequestParam(required = false) String reason,
            @RequestParam(required = false) String from, Principal principal, RedirectAttributes redirectAttributes) {
        try {
            groupMembershipService.leaveGroup(groupId, principal.getName(), reason);
            redirectAttributes.addFlashAttribute("success", "You left the group. You can ask to join again later.");
        } catch (StudyGroupNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", StudyGroupController.GROUP_GONE);
            return "redirect:/groups";
        } catch (IllegalStateException | IllegalArgumentException e) {
            // e.g. "The leader can't leave their own group" or "The reason can be at most 500 characters"
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return redirectKeepingFrom("/groups/" + groupId, from, redirectAttributes);
    }

    // The "Remove" button on a closed group's card on My groups, so closed groups don't pile up there.
    @PostMapping("/{groupId}/remove-from-my-groups")
    public String removeFromMyGroups(@PathVariable Long groupId, Principal principal,
            RedirectAttributes redirectAttributes) {
        try {
            groupMembershipService.removeFromMyGroups(groupId, principal.getName());
            redirectAttributes.addFlashAttribute("success", "Removed from My groups.");
        } catch (StudyGroupNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", StudyGroupController.GROUP_GONE);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // e.g. "Only a closed group can be removed from My groups"
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/groups/mine";
    }

    // ---- The leader's Manage members page ----

    // Join requests (Accept / Reject) above the members (Remove), then members who left and why. Only the leader may open it;
    // anyone else goes back to the group's page with a message. It's only linked from the
    // My groups trail, so the page always leads back there and needs no from=mine of its own.
    @GetMapping("/{groupId}/members")
    public String showMembers(@PathVariable Long groupId, Principal principal, Model model,
            RedirectAttributes redirectAttributes) {
        String leaderEmail = principal.getName();
        try {
            model.addAttribute("group", StudyGroupSummary.from(studyGroupService.getGroupLedBy(groupId, leaderEmail)));
            model.addAttribute("requests", toSummaries(groupMembershipService.getPendingRequests(groupId, leaderEmail)));
            model.addAttribute("members", toSummaries(groupMembershipService.getMembers(groupId, leaderEmail)));
            model.addAttribute("leftMembers", toSummaries(groupMembershipService.getMembersWhoLeft(groupId, leaderEmail)));
        } catch (StudyGroupNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", StudyGroupController.GROUP_GONE);
            return "redirect:/groups";
        } catch (NotGroupLeaderException e) {
            redirectAttributes.addFlashAttribute("error", StudyGroupController.NOT_LEADER);
            return "redirect:/groups/" + groupId;
        }
        return "studygroup/members";
    }

    // The group id is in the URL as well as the membership id: the service checks the caller leads
    // that group, and the group refuses a membership that isn't its own.
    @PostMapping("/{groupId}/members/{membershipId}/accept")
    public String acceptRequest(@PathVariable Long groupId, @PathVariable Long membershipId,
            Principal principal, RedirectAttributes redirectAttributes) {
        return manageMembers(groupId, redirectAttributes, () -> {
            GroupMembership membership = groupMembershipService.acceptRequest(groupId, membershipId, principal.getName());
            return studentName(membership) + " is now a member of the group.";
        });
    }

    @PostMapping("/{groupId}/members/{membershipId}/reject")
    public String rejectRequest(@PathVariable Long groupId, @PathVariable Long membershipId,
            Principal principal, RedirectAttributes redirectAttributes) {
        return manageMembers(groupId, redirectAttributes, () -> {
            GroupMembership membership = groupMembershipService.rejectRequest(groupId, membershipId, principal.getName());
            return studentName(membership) + "'s request was rejected.";
        });
    }

    @PostMapping("/{groupId}/members/{membershipId}/remove")
    public String removeMember(@PathVariable Long groupId, @PathVariable Long membershipId,
            Principal principal, RedirectAttributes redirectAttributes) {
        return manageMembers(groupId, redirectAttributes, () -> {
            GroupMembership membership = groupMembershipService.removeMember(groupId, membershipId, principal.getName());
            return studentName(membership) + " was removed from the group.";
        });
    }

    // Runs one Accept / Reject / Remove, which returns its success message, and turns whatever
    // went wrong into a message instead. Then back to Manage members, or wherever still makes sense.
    private String manageMembers(Long groupId, RedirectAttributes redirectAttributes, Supplier<String> action) {
        try {
            redirectAttributes.addFlashAttribute("success", action.get());
        } catch (StudyGroupNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", StudyGroupController.GROUP_GONE);
            return "redirect:/groups";
        } catch (NotGroupLeaderException e) {
            redirectAttributes.addFlashAttribute("error", StudyGroupController.NOT_LEADER);
            return "redirect:/groups/" + groupId;
        } catch (MembershipNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", MEMBERSHIP_GONE);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // The entity's own messages are already readable, e.g. "Group is full" or
            // "This request has already been answered" (after pressing Accept twice).
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/groups/" + groupId + "/members";
    }

    private static List<GroupMemberSummary> toSummaries(List<GroupMembership> memberships) {
        return memberships.stream().map(GroupMemberSummary::from).toList();
    }

    private static String studentName(GroupMembership membership) {
        return membership.getStudent().getName();
    }

    // addAttribute (not addFlashAttribute) puts from=mine in the redirect's URL, e.g. /groups/4?from=mine,
    // so the page keeps its "← My groups" link.
    private static String redirectKeepingFrom(String path, String from, RedirectAttributes redirectAttributes) {
        if (StudyGroupController.FROM_MY_GROUPS.equals(from)) {
            redirectAttributes.addAttribute("from", from);
        }
        return "redirect:" + path;
    }
}

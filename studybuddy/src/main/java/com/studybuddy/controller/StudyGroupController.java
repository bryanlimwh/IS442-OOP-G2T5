package com.studybuddy.controller;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import com.studybuddy.dto.GroupRequestSummary;
import com.studybuddy.dto.StudyGroupSummary;
import com.studybuddy.entity.StudyGroup;
import com.studybuddy.exception.CourseNotFoundException;
import com.studybuddy.exception.StudyGroupNotFoundException;
import com.studybuddy.service.course.CourseService;
import com.studybuddy.service.studygroup.GroupMembershipService;
import com.studybuddy.service.studygroup.StudyGroupService;
import com.studybuddy.service.studygroup.ViewerRole;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The study group pages: browsing the open groups, the student's own groups
 * and requests ("My groups"), and viewing one group.
 * Joining and managing members is handled by {@link GroupMembershipController},
 * mirroring the split between {@link StudyGroupService} and {@link GroupMembershipService}.
 * <p>
 * Pages receive {@link StudyGroupSummary} records, never the entities, so a
 * student's contact number can't reach a page by mistake.
 */
@Controller
@RequestMapping("/groups")
public class StudyGroupController {

    // The "from" value on links out of My groups. Package-private so GroupMembershipController keeps it on redirects.
    static final String FROM_MY_GROUPS = "mine";

    private final StudyGroupService studyGroupService;
    private final GroupMembershipService groupMembershipService;
    private final CourseService courseService;

    public StudyGroupController(StudyGroupService studyGroupService, GroupMembershipService groupMembershipService,
            CourseService courseService) {
        this.studyGroupService = studyGroupService;
        this.groupMembershipService = groupMembershipService;
        this.courseService = courseService;
    }

    // The filters use GET, like the matches page, so a filtered list can be refreshed or bookmarked.
    // joinableOnly ("Only show groups I can join") keeps just the groups where the details page
    // would offer a working join button: the student may ask (see ViewerRole) and there's room.
    @GetMapping
    public String browseGroups(@RequestParam(required = false) Long courseId,
            @RequestParam(defaultValue = "false") boolean joinableOnly, Principal principal, Model model,
            RedirectAttributes redirectAttributes) {
        List<StudyGroup> groups;
        try {
            groups = courseId == null
                    ? studyGroupService.findActiveGroups()
                    : studyGroupService.findActiveGroupsByCourse(courseId);
        } catch (CourseNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That course doesn't exist. Showing all groups instead.");
            return "redirect:/groups";
        }
        Map<Long, ViewerRole> viewerRoles = groupMembershipService.getViewerRoles(groups, principal.getName());
        List<StudyGroupSummary> summaries = groups.stream()
                .map(StudyGroupSummary::from)
                .filter(group -> !joinableOnly
                        || (viewerRoles.get(group.groupId()).canRequestToJoin() && !group.full()))
                .toList();
        long fullCount = summaries.stream().filter(StudyGroupSummary::full).count();

        model.addAttribute("groups", summaries);
        model.addAttribute("viewerRoles", viewerRoles);
        model.addAttribute("spaceCount", summaries.size() - fullCount);
        model.addAttribute("fullCount", fullCount);
        model.addAttribute("courses", courseService.getAllCourses());
        model.addAttribute("selectedCourseId", courseId);
        model.addAttribute("joinableOnly", joinableOnly);
        return "studygroup/browse";
    }

    // The student's own groups and requests, in four sections. Spring matches the literal
    // "/mine" before the "/{groupId}" pattern, so it's never read as a group id.
    @GetMapping("/mine")
    public String showMyGroups(Principal principal, Model model) {
        String studentEmail = principal.getName();
        model.addAttribute("groupsLed", studyGroupService.findGroupsLedBy(studentEmail).stream()
                .map(StudyGroupSummary::from)
                .toList());
        model.addAttribute("groupsJoined", groupMembershipService.findGroupsJoinedBy(studentEmail).stream()
                .map(StudyGroupSummary::from)
                .toList());
        model.addAttribute("pendingRequests", groupMembershipService.findPendingRequestsBy(studentEmail).stream()
                .map(GroupRequestSummary::from)
                .toList());
        model.addAttribute("pastRequests", groupMembershipService.findPastRequestsBy(studentEmail).stream()
                .map(GroupRequestSummary::from)
                .toList());
        return "studygroup/my-groups";
    }

    // from=mine means the student came from My groups, so the page offers a link back there as well.
    @GetMapping("/{groupId}")
    public String showGroup(@PathVariable Long groupId, @RequestParam(required = false) String from,
            Principal principal, Model model, RedirectAttributes redirectAttributes) {
        model.addAttribute("fromMyGroups", FROM_MY_GROUPS.equals(from));
        try {
            model.addAttribute("group", StudyGroupSummary.from(studyGroupService.getGroup(groupId)));
            model.addAttribute("viewerRole", groupMembershipService.getViewerRole(groupId, principal.getName()));
        } catch (StudyGroupNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That group no longer exists.");
            return "redirect:/groups";
        }
        return "studygroup/details";
    }
}

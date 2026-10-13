package com.studybuddy.controller;

import java.security.Principal;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;

import com.studybuddy.config.StudyGroupSettings;
import com.studybuddy.dto.GroupRequestSummary;
import com.studybuddy.dto.StudyGroupForm;
import com.studybuddy.dto.StudyGroupSummary;
import com.studybuddy.entity.StudyGroup;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;
import com.studybuddy.exception.CourseNotFoundException;
import com.studybuddy.exception.NotGroupLeaderException;
import com.studybuddy.exception.StudyGroupNotFoundException;
import com.studybuddy.service.course.CourseService;
import com.studybuddy.service.studygroup.GroupMembershipService;
import com.studybuddy.service.studygroup.GroupSort;
import com.studybuddy.service.studygroup.StudyGroupFormValidator;
import com.studybuddy.service.studygroup.StudyGroupService;
import com.studybuddy.service.studygroup.ViewerRole;

import jakarta.validation.Valid;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The study group pages: browsing the open groups, the student's own groups
 * and requests ("My groups"), viewing one group, and the leader's pages for
 * creating, editing and closing a group.
 * Joining and managing members is handled by {@link GroupMembershipController},
 * mirroring the split between {@link StudyGroupService} and {@link GroupMembershipService}.
 * <p>
 * Pages receive {@link StudyGroupSummary} records, never the entities, so a
 * student's contact number can't reach a page by mistake. Every change ends
 * with a redirect and a one-time message (Post/Redirect/Get), so refreshing
 * the page doesn't repeat it.
 */
@Controller
@RequestMapping("/groups")
public class StudyGroupController {

    // The "from" value on links out of My groups. Package-private so GroupMembershipController keeps it on redirects.
    static final String FROM_MY_GROUPS = "mine";

    // Package-private so GroupMembershipController's Manage members page uses the same wording.
    static final String GROUP_GONE = "That group no longer exists.";
    static final String NOT_LEADER = "Only the group's leader can do that.";

    private static final String FORM_VIEW = "studygroup/form";

    private final StudyGroupService studyGroupService;
    private final GroupMembershipService groupMembershipService;
    private final CourseService courseService;
    private final StudyGroupFormValidator studyGroupFormValidator;
    private final StudyGroupSettings studyGroupSettings;

    public StudyGroupController(StudyGroupService studyGroupService, GroupMembershipService groupMembershipService,
            CourseService courseService, StudyGroupFormValidator studyGroupFormValidator,
            StudyGroupSettings studyGroupSettings) {
        this.studyGroupService = studyGroupService;
        this.groupMembershipService = groupMembershipService;
        this.courseService = courseService;
        this.studyGroupFormValidator = studyGroupFormValidator;
        this.studyGroupSettings = studyGroupSettings;
    }

    // Adds the time-slot checks to the form's @Valid, next to its annotations. Named "form" so it
    // only applies to the create / edit form, not to this controller's other parameters.
    @InitBinder("form")
    void addTimeSlotChecks(WebDataBinder binder) {
        binder.addValidators(studyGroupFormValidator);
    }

    // The filters use GET, like the matches page, so a filtered list can be refreshed or bookmarked.
    // joinableOnly ("Only show groups I can join") keeps just the groups where the details page
    // would offer a working join button: the student may ask (see ViewerRole) and there's room.
    // sort is the "Sort by" choice; without one, the order set in application.properties is used.
    @GetMapping
    public String browseGroups(@RequestParam(required = false) Long courseId,
            @RequestParam(defaultValue = "false") boolean joinableOnly, @RequestParam(required = false) String sort,
            Principal principal, Model model, RedirectAttributes redirectAttributes) {
        GroupSort chosenSort = GroupSort.fromName(sort, studyGroupSettings.defaultSort());
        List<StudyGroup> groups;
        try {
            groups = courseId == null
                    ? studyGroupService.findActiveGroups(chosenSort)
                    : studyGroupService.findActiveGroupsByCourse(courseId, chosenSort);
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
        model.addAttribute("sorts", GroupSort.values());
        model.addAttribute("selectedSort", chosenSort);
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
            redirectAttributes.addFlashAttribute("error", GROUP_GONE);
            return "redirect:/groups";
        }
        return "studygroup/details";
    }

    // ---- Creating, editing and closing a group (the leader's pages) ----

    // Spring matches the literal "/new" before "/{groupId}", as with "/mine".
    @GetMapping("/new")
    public String showCreateForm(@RequestParam(required = false) String from, Model model) {
        model.addAttribute("form", StudyGroupForm.blank());
        return showForm(null, from, model);
    }

    @PostMapping
    public String createGroup(@Valid @ModelAttribute("form") StudyGroupForm form, BindingResult bindingResult,
            @RequestParam(required = false) String from, Principal principal, Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return showForm(null, from, model);
        }
        StudyGroup group;
        try {
            group = studyGroupService.createGroup(principal.getName(), form);
        } catch (CourseNotFoundException e) {
            // Shown under the Course dropdown, like the form's other messages
            bindingResult.rejectValue("courseId", "courseId.notFound", "That course no longer exists. Please choose another.");
            return showForm(null, from, model);
        } catch (IllegalArgumentException e) {
            // The entity's own messages are already readable, e.g. "Group name is required".
            model.addAttribute("error", e.getMessage());
            return showForm(null, from, model);
        }
        redirectAttributes.addFlashAttribute("success",
                "Group created. Other students can now find it on Study groups and ask to join.");
        // Always onto the My groups trail, even when created from Browse: that's where the leader's tools show
        return redirectToGroup(group.getGroupId(), FROM_MY_GROUPS, redirectAttributes);
    }

    @GetMapping("/{groupId}/edit")
    public String showEditForm(@PathVariable Long groupId, @RequestParam(required = false) String from,
            Principal principal, Model model, RedirectAttributes redirectAttributes) {
        StudyGroup group = findGroupToEdit(groupId, principal, redirectAttributes);
        if (group == null) {
            return redirectToGroup(groupId, from, redirectAttributes);
        }
        model.addAttribute("form", StudyGroupForm.from(group));
        return showForm(StudyGroupSummary.from(group), from, model);
    }

    @PostMapping("/{groupId}/edit")
    public String updateGroup(@PathVariable Long groupId, @Valid @ModelAttribute("form") StudyGroupForm form,
            BindingResult bindingResult, @RequestParam(required = false) String from, Principal principal,
            Model model, RedirectAttributes redirectAttributes) {
        StudyGroup group = findGroupToEdit(groupId, principal, redirectAttributes);
        if (group == null) {
            return redirectToGroup(groupId, from, redirectAttributes);
        }
        // Checked here too so the message sits under the size box with the others. The entity
        // still refuses it in updateDetails(), in case the member count changes in between.
        int memberCount = group.getMemberCount();
        if (form.getMaxSize() != null && form.getMaxSize() < memberCount) {
            bindingResult.rejectValue("maxSize", "maxSize.belowMemberCount", "The group has " + memberCount
                    + " members, so the maximum size can't be below " + memberCount);
        }
        if (bindingResult.hasErrors()) {
            return showForm(StudyGroupSummary.from(group), from, model);
        }
        try {
            studyGroupService.updateGroup(groupId, principal.getName(), form);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // e.g. "Max size can't be below the current member count (3)". The rollback detaches the group
            // loaded above (its lazy lists can no longer load), so the page reads a fresh copy.
            model.addAttribute("error", e.getMessage());
            return showForm(StudyGroupSummary.from(studyGroupService.getGroup(groupId)), from, model);
        }
        redirectAttributes.addFlashAttribute("success", "Changes saved.");
        return redirectToGroup(groupId, from, redirectAttributes);
    }

    // "Add slot" and "Remove" are submit buttons, so the slot rows work without JavaScript: the form comes
    // back with one row more or fewer, keeping everything typed so far, and nothing is saved. The params
    // condition makes Spring pick these over the save handlers above when one of those buttons was pressed.
    // BindingResult is only there so a badly typed field shows on the form instead of an error page.
    @PostMapping(value = {"", "/{groupId}/edit"}, params = "addSlot")
    public String addSlotRow(@PathVariable(required = false) Long groupId,
            @ModelAttribute("form") StudyGroupForm form, BindingResult bindingResult,
            @RequestParam(required = false) String from, Principal principal, Model model,
            RedirectAttributes redirectAttributes) {
        form.addSlot(studyGroupSettings.maxAvailabilitySlots());
        return showFormAgain(groupId, from, principal, model, redirectAttributes);
    }

    @PostMapping(value = {"", "/{groupId}/edit"}, params = "removeSlot")
    public String removeSlotRow(@PathVariable(required = false) Long groupId,
            @ModelAttribute("form") StudyGroupForm form, BindingResult bindingResult,
            @RequestParam int removeSlot, @RequestParam(required = false) String from, Principal principal,
            Model model, RedirectAttributes redirectAttributes) {
        form.removeSlot(removeSlot);
        return showFormAgain(groupId, from, principal, model, redirectAttributes);
    }

    @PostMapping("/{groupId}/close")
    public String closeGroup(@PathVariable Long groupId, @RequestParam(required = false) String from,
            Principal principal, RedirectAttributes redirectAttributes) {
        try {
            int endedRequests = studyGroupService.closeGroup(groupId, principal.getName());
            redirectAttributes.addFlashAttribute("success", closedMessage(endedRequests));
        } catch (StudyGroupNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", GROUP_GONE);
            return "redirect:/groups";
        } catch (NotGroupLeaderException e) {
            redirectAttributes.addFlashAttribute("error", NOT_LEADER);
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("error", "This group is already closed.");
        }
        return redirectToGroup(groupId, from, redirectAttributes);
    }

    // The create / edit form, with everything its dropdowns and hints need. editing is null when creating.
    private String showForm(StudyGroupSummary editing, String from, Model model) {
        model.addAttribute("editing", editing);
        model.addAttribute("fromMyGroups", FROM_MY_GROUPS.equals(from));
        model.addAttribute("courses", courseService.getAllCourses());
        model.addAttribute("studyGoals", StudyGoal.values());
        model.addAttribute("studyModes", StudyMode.values());
        model.addAttribute("days", DayOfWeek.values());
        model.addAttribute("timeOptions", studyGroupSettings.timeOptions());
        model.addAttribute("maxSlots", studyGroupSettings.maxAvailabilitySlots());
        // The entity refuses a size below the members already in the group, so the size field starts there
        model.addAttribute("minSize", editing == null
                ? StudyGroup.MIN_GROUP_SIZE
                : Math.max(StudyGroup.MIN_GROUP_SIZE, editing.memberCount()));
        return FORM_VIEW;
    }

    // After Add slot / Remove: the create form as it is, or the edit form if the student may still edit the group.
    private String showFormAgain(Long groupId, String from, Principal principal, Model model,
            RedirectAttributes redirectAttributes) {
        if (groupId == null) {
            return showForm(null, from, model);
        }
        StudyGroup group = findGroupToEdit(groupId, principal, redirectAttributes);
        if (group == null) {
            return redirectToGroup(groupId, from, redirectAttributes);
        }
        return showForm(StudyGroupSummary.from(group), from, model);
    }

    // The group the student wants to edit, or null after adding a message saying why they can't.
    private StudyGroup findGroupToEdit(Long groupId, Principal principal, RedirectAttributes redirectAttributes) {
        try {
            StudyGroup group = studyGroupService.getGroupLedBy(groupId, principal.getName());
            if (group.isActive()) {
                return group;
            }
            redirectAttributes.addFlashAttribute("error", "This group is closed, so it can't be edited.");
        } catch (StudyGroupNotFoundException e) {
            // The group's page then sends the student on to Browse with this message.
            redirectAttributes.addFlashAttribute("error", GROUP_GONE);
        } catch (NotGroupLeaderException e) {
            redirectAttributes.addFlashAttribute("error", NOT_LEADER);
        }
        return null;
    }

    // Back to the group's page, keeping from=mine so it still offers "← My groups".
    private String redirectToGroup(Long groupId, String from, RedirectAttributes redirectAttributes) {
        if (FROM_MY_GROUPS.equals(from)) {
            redirectAttributes.addAttribute("from", from);
        }
        return "redirect:/groups/" + groupId;
    }

    // "Group closed." or "Group closed. 2 pending requests ended without an answer."
    private static String closedMessage(int endedRequests) {
        if (endedRequests == 0) {
            return "Group closed.";
        }
        return "Group closed. " + endedRequests
                + (endedRequests == 1 ? " pending request ended" : " pending requests ended") + " without an answer.";
    }
}

package com.studybuddy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.validation.BindingResult;

import com.studybuddy.config.SecurityConfig;
import com.studybuddy.dto.StudyGroupForm;
import com.studybuddy.dto.StudyGroupSummary;
import com.studybuddy.entity.AvailabilitySlot;
import com.studybuddy.entity.Course;
import com.studybuddy.entity.GroupMembership;
import com.studybuddy.entity.Student;
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

/**
 * Web-layer test for the study group pages (browse, My groups, details, create / edit / close),
 * rendered with the real templates. The services are mocked (the rules are in StudyGroupServiceTest
 * and StudyGroupTest); the real StudyGroupFormValidator and the settings from application.properties
 * (3 slots, 08:00 to 23:00, Browse order NEWEST) are used. Logged in as student alex@test.com, who
 * leads group 1 "IS442 Revision" (IS442, size 4, members Alex, Ben, Chloe, Mon 10:00-12:00).
 */
@WebMvcTest(StudyGroupController.class)
@Import({ SecurityConfig.class, StudyGroupFormValidator.class })
@WithMockUser(username = StudyGroupControllerTest.ALEX, roles = "STUDENT")
class StudyGroupControllerTest {

    static final String ALEX = "alex@test.com";
    private static final String BASE = "/groups";
    private static final String NOT_LEADER = "Only the group's leader can do that.";
    private static final String GROUP_GONE = "That group no longer exists.";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StudyGroupService studyGroupService;
    @MockitoBean
    private GroupMembershipService groupMembershipService;
    @MockitoBean
    private CourseService courseService;

    private final Course is442 = course(1L, "IS442", "Object Oriented Programming");
    private final Student alex = student(1L, ALEX, "Alex Tan");
    private final Student ben = student(2L, "ben@test.com", "Ben Koh");
    private final Student chloe = student(3L, "chloe@test.com", "Chloe Lim");
    private StudyGroup alexsGroup;

    @BeforeEach
    void setUp() {
        alexsGroup = group(1L, "IS442 Revision", alex, 4, ben, chloe);
        when(courseService.getAllCourses()).thenReturn(List.of(is442));
    }

    // ================= Browse and My groups =================

    // GRP-4, L1: open groups in the default order (NEWEST, from application.properties), with roles and counts
    @Test
    void browseListsOpenGroupsWithViewerRoles() throws Exception {
        StudyGroup full = group(2L, "Full Group", ben, 2, chloe);
        when(studyGroupService.findActiveGroups(GroupSort.NEWEST)).thenReturn(List.of(alexsGroup, full));
        when(groupMembershipService.getViewerRoles(List.of(alexsGroup, full), ALEX))
                .thenReturn(Map.of(1L, ViewerRole.LEADER, 2L, ViewerRole.NONE));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(view().name("studygroup/browse"))
                .andExpect(model().attribute("groups",
                        List.of(StudyGroupSummary.from(alexsGroup), StudyGroupSummary.from(full))))
                .andExpect(model().attribute("spaceCount", 1L))
                .andExpect(model().attribute("fullCount", 1L))
                .andExpect(model().attribute("selectedSort", GroupSort.NEWEST))
                .andExpect(model().attribute("joinableOnly", false));
    }

    // GRP-4: the course filter and sort reach the service; an unknown sort falls back to NEWEST
    @Test
    void browseUsesCourseFilterAndSort() throws Exception {
        when(groupMembershipService.getViewerRoles(List.of(), ALEX)).thenReturn(Map.of());

        mockMvc.perform(get(BASE).param("courseId", "1").param("sort", "NAME"))
                .andExpect(model().attribute("selectedSort", GroupSort.NAME))
                .andExpect(model().attribute("selectedCourseId", 1L));
        mockMvc.perform(get(BASE).param("sort", "bogus")).andExpect(model().attribute("selectedSort", GroupSort.NEWEST));

        verify(studyGroupService).findActiveGroupsByCourse(1L, GroupSort.NAME);
        verify(studyGroupService).findActiveGroups(GroupSort.NEWEST);
    }

    // GRP-4
    @Test
    void browseUnknownCourseShowsAllGroupsInstead() throws Exception {
        when(studyGroupService.findActiveGroupsByCourse(99L, GroupSort.NEWEST)).thenThrow(new CourseNotFoundException(99L));

        mockMvc.perform(get(BASE).param("courseId", "99"))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "That course doesn't exist. Showing all groups instead."));
    }

    // GRP-4: "Only show groups I can join" keeps groups with room that the student may ask to join
    @Test
    void joinableOnlyKeepsGroupsTheStudentCanJoin() throws Exception {
        StudyGroup never = group(2L, "Never asked", ben, 4);
        StudyGroup member = group(3L, "Already in", ben, 4, alex);
        StudyGroup declined = group(4L, "Declined before", chloe, 4);
        StudyGroup full = group(5L, "Full", chloe, 2, ben);
        List<StudyGroup> all = List.of(never, member, declined, full);
        when(studyGroupService.findActiveGroups(GroupSort.NEWEST)).thenReturn(all);
        when(groupMembershipService.getViewerRoles(all, ALEX)).thenReturn(Map.of(2L, ViewerRole.NONE,
                3L, ViewerRole.MEMBER, 4L, ViewerRole.REJECTED, 5L, ViewerRole.NONE));

        mockMvc.perform(get(BASE).param("joinableOnly", "true"))
                .andExpect(model().attribute("groups",
                        List.of(StudyGroupSummary.from(never), StudyGroupSummary.from(declined))))
                .andExpect(model().attribute("joinableOnly", true));
    }

    // L3, L4, GRP-6: the four sections of My groups, all for the logged-in student
    @Test
    void myGroupsShowsTheStudentsFourLists() throws Exception {
        StudyGroup joined = group(2L, "Ben's group", ben, 5, alex);
        when(studyGroupService.findGroupsLedBy(ALEX)).thenReturn(List.of(alexsGroup));
        when(groupMembershipService.findGroupsJoinedBy(ALEX)).thenReturn(List.of(joined));

        mockMvc.perform(get(BASE + "/mine"))
                .andExpect(status().isOk())
                .andExpect(view().name("studygroup/my-groups"))
                .andExpect(model().attribute("groupsLed", List.of(StudyGroupSummary.from(alexsGroup))))
                .andExpect(model().attribute("groupsJoined", List.of(StudyGroupSummary.from(joined))))
                .andExpect(model().attribute("pendingRequests", List.of()))
                .andExpect(model().attribute("pastRequests", List.of()));

        verify(groupMembershipService).findPendingRequestsBy(ALEX);
        verify(groupMembershipService).findPastRequestsBy(ALEX);
    }

    // L1, GRP-4: from=mine keeps the "My groups" link
    @Test
    void groupPageShowsGroupAndViewerRole() throws Exception {
        when(studyGroupService.getGroup(1L)).thenReturn(alexsGroup);
        when(groupMembershipService.getViewerRole(1L, ALEX)).thenReturn(ViewerRole.LEADER);

        mockMvc.perform(get(BASE + "/1").param("from", "mine"))
                .andExpect(status().isOk())
                .andExpect(view().name("studygroup/details"))
                .andExpect(model().attribute("group", StudyGroupSummary.from(alexsGroup)))
                .andExpect(model().attribute("viewerRole", ViewerRole.LEADER))
                .andExpect(model().attribute("fromMyGroups", true));
    }

    // A full group keeps pending requests as a waiting list, so a waiting student is told why
    @Test
    void pendingStudentOnFullGroupIsToldTheyAreWaiting() throws Exception {
        StudyGroup fullGroup = group(5L, "Full Group", ben, 2, chloe);
        when(studyGroupService.getGroup(5L)).thenReturn(fullGroup);
        when(groupMembershipService.getViewerRole(5L, ALEX)).thenReturn(ViewerRole.PENDING);

        mockMvc.perform(get(BASE + "/5"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "The group is full right now. The leader can still accept you if a place opens up.")));
    }

    // ...but not while there's still room
    @Test
    void pendingStudentOnOpenGroupSeesNoFullNote() throws Exception {
        StudyGroup openGroup = group(6L, "Open Group", ben, 4, chloe);
        when(studyGroupService.getGroup(6L)).thenReturn(openGroup);
        when(groupMembershipService.getViewerRole(6L, ALEX)).thenReturn(ViewerRole.PENDING);

        mockMvc.perform(get(BASE + "/6"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Waiting for the group's leader to respond.")))
                .andExpect(content().string(not(containsString("The group is full right now."))));
    }

    // GRP-8: a waiting student can cancel from the group's page; the form keeps from=mine
    @Test
    void pendingStudentCanCancelFromTheGroupPage() throws Exception {
        StudyGroup openGroup = group(6L, "Open Group", ben, 4, chloe);
        when(studyGroupService.getGroup(6L)).thenReturn(openGroup);
        when(groupMembershipService.getViewerRole(6L, ALEX)).thenReturn(ViewerRole.PENDING);

        mockMvc.perform(get(BASE + "/6").param("from", "mine"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/groups/6/cancel-request\"")))
                .andExpect(content().string(containsString("<input type=\"hidden\" name=\"from\" value=\"mine\">")))
                .andExpect(content().string(not(containsString("name=\"backToMyGroups\""))));
        // A member has nothing to cancel
        when(groupMembershipService.getViewerRole(6L, ALEX)).thenReturn(ViewerRole.MEMBER);
        mockMvc.perform(get(BASE + "/6"))
                .andExpect(content().string(not(containsString("cancel-request"))));
    }

    // GRP-8: My groups offers Cancel on a waiting request, not on one whose group closed
    @Test
    void myGroupsOffersCancelOnlyOnWaitingRequests() throws Exception {
        StudyGroup waiting = group(7L, "Waiting Group", ben, 4);
        StudyGroup closed = group(8L, "Closed Group", chloe, 4);
        GroupMembership waitingRequest = waiting.requestToJoin(alex);
        GroupMembership closedRequest = closed.requestToJoin(alex);
        closed.close();
        when(groupMembershipService.findPendingRequestsBy(ALEX)).thenReturn(List.of(waitingRequest, closedRequest));

        mockMvc.perform(get(BASE + "/mine"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/groups/7/cancel-request\"")))
                .andExpect(content().string(containsString("name=\"backToMyGroups\" value=\"true\"")))
                .andExpect(content().string(not(containsString("/groups/8/cancel-request"))));
    }

    // L1
    @Test
    void unknownGroupPageGoesBackToBrowse() throws Exception {
        when(studyGroupService.getGroup(99L)).thenThrow(new StudyGroupNotFoundException(99L));

        mockMvc.perform(get(BASE + "/99"))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", GROUP_GONE));
    }

    // ================= Create =================

    // L1, L2, GRP-7: one empty slot row, the configured times and slot limit
    @Test
    void createFormStartsBlank() throws Exception {
        mockMvc.perform(get(BASE + "/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("studygroup/form"))
                .andExpect(model().attribute("form", hasProperty("slots", hasSize(1))))
                .andExpect(model().attribute("minSize", 2))
                .andExpect(model().attribute("maxSlots", 3))
                .andExpect(model().attribute("timeOptions", hasSize(61)));
    }

    // L1, L2: the new group's page, on the My groups trail
    @Test
    void createGroupRedirectsToItsPage() throws Exception {
        StudyGroup created = group(7L, "New Group", alex, 3);
        when(studyGroupService.createGroup(eq(ALEX), any())).thenReturn(created);

        mockMvc.perform(validGroupForm(post(BASE)))
                .andExpect(redirectedUrl(BASE + "/7?from=mine"))
                .andExpect(flash().attribute("success",
                        "Group created. Other students can now find it on Study groups and ask to join."));

        ArgumentCaptor<StudyGroupForm> form = ArgumentCaptor.forClass(StudyGroupForm.class);
        verify(studyGroupService).createGroup(eq(ALEX), form.capture());
        assertThat(form.getValue().getName()).isEqualTo("New Group");
        assertThat(form.getValue().getCourseId()).isEqualTo(1L);
        assertThat(form.getValue().getMaxSize()).isEqualTo(3);
        assertThat(form.getValue().toAvailabilitySlots()).extracting(AvailabilitySlot::getStartTime)
                .containsExactly(LocalTime.of(10, 0));
    }

    // L2, GRP-1, GRP-7: annotation and slot errors come back together, and nothing is saved
    @Test
    void invalidCreateShowsEveryErrorOnTheForm() throws Exception {
        MvcResult result = mockMvc.perform(post(BASE).param("name", " ").param("courseId", "1")
                        .param("studyGoal", "EXAM_PREPARATION").param("studyMode", "ONLINE").param("maxSize", "1")
                        .param("slots[0].dayOfWeek", "MONDAY").param("slots[0].startTime", "10:00")
                        .param("slots[0].endTime", "12:00").param("slots[1].dayOfWeek", "MONDAY")
                        .param("slots[1].startTime", "11:00").param("slots[1].endTime", "13:00").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("studygroup/form"))
                .andReturn();

        BindingResult errors = (BindingResult) result.getModelAndView().getModel()
                .get(BindingResult.MODEL_KEY_PREFIX + "form");
        assertThat(errors.getFieldError("name").getDefaultMessage()).isEqualTo("Please enter a group name");
        assertThat(errors.getFieldError("maxSize").getDefaultMessage())
                .isEqualTo("A group needs room for at least 2 members, including you");
        assertThat(errors.getFieldError("slots").getDefaultMessage()).isEqualTo("Slots 1 and 2 overlap");
        verify(studyGroupService, never()).createGroup(anyString(), any());
    }

    // L1, GRP-1: a course deleted meanwhile is shown under the Course dropdown
    @Test
    void createForUnknownCourseShowsErrorUnderCourse() throws Exception {
        when(studyGroupService.createGroup(eq(ALEX), any())).thenThrow(new CourseNotFoundException(1L));

        MvcResult result = mockMvc.perform(validGroupForm(post(BASE)))
                .andExpect(view().name("studygroup/form"))
                .andReturn();

        BindingResult errors = (BindingResult) result.getModelAndView().getModel()
                .get(BindingResult.MODEL_KEY_PREFIX + "form");
        assertThat(errors.getFieldError("courseId").getDefaultMessage())
                .isEqualTo("That course no longer exists. Please choose another.");
    }

    // GRP-1: the entity's own refusal is shown on the form
    @Test
    void createRefusedByTheGroupShowsItsMessage() throws Exception {
        when(studyGroupService.createGroup(eq(ALEX), any()))
                .thenThrow(new IllegalArgumentException("Max size must be at least 2"));

        mockMvc.perform(validGroupForm(post(BASE)))
                .andExpect(view().name("studygroup/form"))
                .andExpect(model().attribute("error", "Max size must be at least 2"));
    }

    // GRP-7: "+ Add slot" and "Remove" change the rows and save nothing
    @Test
    void addAndRemoveSlotRowsSaveNothing() throws Exception {
        MvcResult added = mockMvc.perform(validGroupForm(post(BASE)).param("addSlot", "true"))
                .andExpect(view().name("studygroup/form")).andReturn();
        assertThat(((StudyGroupForm) added.getModelAndView().getModel().get("form")).getSlots()).hasSize(2);

        // Already at the maximum of 3: no fourth row
        MvcResult atMax = mockMvc.perform(validGroupForm(post(BASE)).param("addSlot", "true")
                        .param("slots[1].dayOfWeek", "TUESDAY").param("slots[2].dayOfWeek", "FRIDAY"))
                .andReturn();
        assertThat(((StudyGroupForm) atMax.getModelAndView().getModel().get("form")).getSlots()).hasSize(3);

        MvcResult removed = mockMvc.perform(validGroupForm(post(BASE)).param("slots[1].dayOfWeek", "TUESDAY")
                        .param("removeSlot", "0"))
                .andReturn();
        assertThat(((StudyGroupForm) removed.getModelAndView().getModel().get("form")).getSlots())
                .extracting(slot -> slot.getDayOfWeek()).containsExactly(DayOfWeek.TUESDAY);

        verify(studyGroupService, never()).createGroup(anyString(), any());
    }

    // ================= Edit and close =================

    // L3: the edit form is filled in; the size can't go below the 3 members
    @Test
    void editFormIsFilledForTheLeader() throws Exception {
        when(studyGroupService.getGroupLedBy(1L, ALEX)).thenReturn(alexsGroup);

        mockMvc.perform(get(BASE + "/1/edit").param("from", "mine"))
                .andExpect(status().isOk())
                .andExpect(view().name("studygroup/form"))
                .andExpect(model().attribute("form", hasProperty("name",
                        is("IS442 Revision"))))
                .andExpect(model().attribute("editing", StudyGroupSummary.from(alexsGroup)))
                .andExpect(model().attribute("minSize", 3));
    }

    // L3, GRP-2, GRP-6: someone else's, a closed or a missing group can't be edited
    @Test
    void editFormRefusedWithReason() throws Exception {
        when(studyGroupService.getGroupLedBy(2L, ALEX)).thenThrow(new NotGroupLeaderException(2L, 1L));
        StudyGroup closed = group(3L, "Closed", alex, 4);
        closed.close();
        when(studyGroupService.getGroupLedBy(3L, ALEX)).thenReturn(closed);
        when(studyGroupService.getGroupLedBy(99L, ALEX)).thenThrow(new StudyGroupNotFoundException(99L));

        mockMvc.perform(get(BASE + "/2/edit")).andExpect(redirectedUrl(BASE + "/2"))
                .andExpect(flash().attribute("error", NOT_LEADER));
        mockMvc.perform(get(BASE + "/3/edit").param("from", "mine")).andExpect(redirectedUrl(BASE + "/3?from=mine"))
                .andExpect(flash().attribute("error", "This group is closed, so it can't be edited."));
        // The group's page then sends the student on to Browse
        mockMvc.perform(get(BASE + "/99/edit")).andExpect(redirectedUrl(BASE + "/99"))
                .andExpect(flash().attribute("error", GROUP_GONE));
    }

    // L3
    @Test
    void updateGroupRedirectsWithChangesSaved() throws Exception {
        when(studyGroupService.getGroupLedBy(1L, ALEX)).thenReturn(alexsGroup);

        mockMvc.perform(validGroupForm(post(BASE + "/1/edit"), 5).param("from", "mine"))
                .andExpect(redirectedUrl(BASE + "/1?from=mine"))
                .andExpect(flash().attribute("success", "Changes saved."));

        verify(studyGroupService).updateGroup(eq(1L), eq(ALEX), any());
    }

    // GRP-3: the controller's own check puts the message under the size box
    @Test
    void updateBelowMemberCountShowsErrorUnderSize() throws Exception {
        when(studyGroupService.getGroupLedBy(1L, ALEX)).thenReturn(alexsGroup);

        MvcResult result = mockMvc.perform(validGroupForm(post(BASE + "/1/edit"), 2))
                .andExpect(view().name("studygroup/form"))
                .andReturn();

        BindingResult errors = (BindingResult) result.getModelAndView().getModel()
                .get(BindingResult.MODEL_KEY_PREFIX + "form");
        assertThat(errors.getFieldError("maxSize").getDefaultMessage())
                .isEqualTo("The group has 3 members, so the maximum size can't be below 3");
        verify(studyGroupService, never()).updateGroup(anyLong(), anyString(), any());
    }

    // GRP-3, GRP-6: a refusal from the service is shown on the form, read again from the database
    @Test
    void updateRefusedByServiceShowsItsMessage() throws Exception {
        when(studyGroupService.getGroupLedBy(1L, ALEX)).thenReturn(alexsGroup);
        when(studyGroupService.getGroup(1L)).thenReturn(alexsGroup);
        doThrow(new IllegalArgumentException("Max size can't be below the current member count (3)"))
                .when(studyGroupService).updateGroup(eq(1L), eq(ALEX), any());

        mockMvc.perform(validGroupForm(post(BASE + "/1/edit"), 4))
                .andExpect(view().name("studygroup/form"))
                .andExpect(model().attribute("error", "Max size can't be below the current member count (3)"));

        verify(studyGroupService).getGroup(1L);
    }

    // L3, GRP-6: the message says how many waiting requests ended
    @Test
    void closeGroupSaysHowManyRequestsEnded() throws Exception {
        when(studyGroupService.closeGroup(1L, ALEX)).thenReturn(0, 1, 2);

        mockMvc.perform(post(BASE + "/1/close").param("from", "mine").with(csrf()))
                .andExpect(redirectedUrl(BASE + "/1?from=mine"))
                .andExpect(flash().attribute("success", "Group closed."));
        mockMvc.perform(post(BASE + "/1/close").with(csrf()))
                .andExpect(flash().attribute("success", "Group closed. 1 pending request ended without an answer."));
        mockMvc.perform(post(BASE + "/1/close").with(csrf()))
                .andExpect(flash().attribute("success", "Group closed. 2 pending requests ended without an answer."));
    }

    // GRP-2, GRP-6
    @Test
    void closeRefusedWithReason() throws Exception {
        when(studyGroupService.closeGroup(2L, ALEX)).thenThrow(new NotGroupLeaderException(2L, 1L));
        when(studyGroupService.closeGroup(3L, ALEX)).thenThrow(new IllegalStateException("Group is closed"));
        when(studyGroupService.closeGroup(99L, ALEX)).thenThrow(new StudyGroupNotFoundException(99L));

        mockMvc.perform(post(BASE + "/2/close").with(csrf())).andExpect(redirectedUrl(BASE + "/2"))
                .andExpect(flash().attribute("error", NOT_LEADER));
        mockMvc.perform(post(BASE + "/3/close").with(csrf())).andExpect(redirectedUrl(BASE + "/3"))
                .andExpect(flash().attribute("error", "This group is already closed."));
        mockMvc.perform(post(BASE + "/99/close").with(csrf())).andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", GROUP_GONE));
    }

    // D3, GRP-2: an "email" field can't create, edit or close as another student
    @Test
    void emailParameterIsIgnored() throws Exception {
        when(studyGroupService.createGroup(eq(ALEX), any())).thenReturn(group(7L, "New Group", alex, 3));
        when(studyGroupService.getGroupLedBy(1L, ALEX)).thenReturn(alexsGroup);

        mockMvc.perform(validGroupForm(post(BASE)).param("email", "ben@test.com"));
        mockMvc.perform(validGroupForm(post(BASE + "/1/edit")).param("email", "ben@test.com"));
        mockMvc.perform(post(BASE + "/1/close").param("email", "ben@test.com").with(csrf()));

        verify(studyGroupService).createGroup(eq(ALEX), any());
        verify(studyGroupService).updateGroup(eq(1L), eq(ALEX), any());
        verify(studyGroupService).closeGroup(1L, ALEX);
        verify(studyGroupService, never()).getGroupLedBy(1L, "ben@test.com");
    }

    // D3, SEC-2: anonymous, admin and token-less requests are refused before the controller runs
    @Test
    void refusedRequestsNeverReachTheService() throws Exception {
        List<Supplier<MockHttpServletRequestBuilder>> routes = List.of(() -> get(BASE), () -> get(BASE + "/mine"),
                () -> get(BASE + "/new"), () -> get(BASE + "/1"), () -> get(BASE + "/1/edit"),
                () -> validGroupForm(post(BASE)), () -> validGroupForm(post(BASE + "/1/edit")),
                () -> post(BASE + "/1/close").with(csrf()));
        for (Supplier<MockHttpServletRequestBuilder> route : routes) {
            mockMvc.perform(route.get().with(anonymous())).andExpect(redirectedUrl("/login"));
            mockMvc.perform(route.get().with(user("admin@test.com").roles("ADMIN"))).andExpect(status().isForbidden());
        }
        mockMvc.perform(post(BASE).param("name", "No token")).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/1/edit").param("name", "No token")).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/1/close")).andExpect(status().isForbidden());

        verifyNoInteractions(studyGroupService, groupMembershipService);
    }

    // A valid create / edit form: "New Group", IS442, Exam preparation, Online, size 3 (or the one given), Mon 10:00-12:00
    private static MockHttpServletRequestBuilder validGroupForm(MockHttpServletRequestBuilder request) {
        return validGroupForm(request, 3);
    }

    private static MockHttpServletRequestBuilder validGroupForm(MockHttpServletRequestBuilder request, int maxSize) {
        return request.param("name", "New Group").param("courseId", "1").param("studyGoal", "EXAM_PREPARATION")
                .param("studyMode", "ONLINE").param("maxSize", String.valueOf(maxSize))
                .param("slots[0].dayOfWeek", "MONDAY")
                .param("slots[0].startTime", "10:00").param("slots[0].endTime", "12:00").with(csrf());
    }

    // An open group with its id, one Monday slot, and the given members accepted
    private StudyGroup group(long id, String name, Student leader, int maxSize, Student... members) {
        StudyGroup group = new StudyGroup(name, "Past papers", is442, StudyGoal.EXAM_PREPARATION, StudyMode.ONLINE,
                maxSize, leader);
        ReflectionTestUtils.setField(group, "groupId", id);
        group.addAvailabilitySlot(new AvailabilitySlot(DayOfWeek.MONDAY, LocalTime.of(10, 0), LocalTime.of(12, 0)));
        for (Student member : members) {
            group.acceptRequest(group.requestToJoin(member));
        }
        return group;
    }

    private static Student student(long id, String email, String name) {
        Student student = new Student(email, "dummyHash", name, "SCIS", "Information Systems", 2, "91234567");
        ReflectionTestUtils.setField(student, "userId", id);
        return student;
    }

    private static Course course(long id, String code, String name) {
        Course course = new Course(code, name);
        ReflectionTestUtils.setField(course, "courseId", id);
        return course;
    }
}

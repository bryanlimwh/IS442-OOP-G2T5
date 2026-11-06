package com.studybuddy.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.studybuddy.config.SecurityConfig;
import com.studybuddy.dto.GroupMemberSummary;
import com.studybuddy.dto.StudyGroupSummary;
import com.studybuddy.entity.AvailabilitySlot;
import com.studybuddy.entity.Course;
import com.studybuddy.entity.GroupMembership;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyGroup;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;
import com.studybuddy.exception.MembershipNotFoundException;
import com.studybuddy.exception.NotGroupLeaderException;
import com.studybuddy.exception.StudyGroupNotFoundException;
import com.studybuddy.service.studygroup.GroupMembershipService;
import com.studybuddy.service.studygroup.StudyGroupService;

/**
 * Web-layer test for joining a group, cancelling a request, removing a closed one from My groups, and the leader's
 * Manage members page (accept, reject, remove), rendered with the real templates. The services
 * are mocked (the rules are in GroupMembershipServiceTest). Logged in as student alex@test.com,
 * who leads group 1 "IS442 Revision" (size 4): members Alex and Chloe Lim (membership 12),
 * Farhan Ismail waiting (membership 13).
 */
@WebMvcTest(GroupMembershipController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = GroupMembershipControllerTest.ALEX, roles = "STUDENT")
class GroupMembershipControllerTest {

    static final String ALEX = "alex@test.com";
    private static final String BASE = "/groups";
    private static final String NOT_LEADER = "Only the group's leader can do that.";
    private static final String GROUP_GONE = "That group no longer exists.";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GroupMembershipService groupMembershipService;
    @MockitoBean
    private StudyGroupService studyGroupService;

    private StudyGroup group;
    private GroupMembership chloesMembership;
    private GroupMembership farhansRequest;

    @BeforeEach
    void setUp() {
        Course is442 = new Course("IS442", "Object Oriented Programming");
        ReflectionTestUtils.setField(is442, "courseId", 1L);
        group = new StudyGroup("IS442 Revision", "Past papers", is442, StudyGoal.EXAM_PREPARATION, StudyMode.ONLINE,
                4, student(1L, ALEX, "Alex Tan"));
        ReflectionTestUtils.setField(group, "groupId", 1L);
        group.addAvailabilitySlot(new AvailabilitySlot(DayOfWeek.MONDAY, LocalTime.of(10, 0), LocalTime.of(12, 0)));
        ReflectionTestUtils.setField(group.getAcceptedMemberships().get(0), "membershipId", 11L);
        chloesMembership = group.requestToJoin(student(3L, "chloe@test.com", "Chloe Lim"));
        group.acceptRequest(chloesMembership);
        ReflectionTestUtils.setField(chloesMembership, "membershipId", 12L);
        farhansRequest = group.requestToJoin(student(4L, "farhan@test.com", "Farhan Ismail"));
        ReflectionTestUtils.setField(farhansRequest, "membershipId", 13L);
    }

    // ================= Joining and My groups =================

    // GRP-4, L4
    @Test
    void requestToJoinRedirectsWithSuccess() throws Exception {
        mockMvc.perform(post(BASE + "/1/join").with(csrf()))
                .andExpect(redirectedUrl(BASE + "/1"))
                .andExpect(flash().attribute("success", "Request sent. The group's leader will review it."));
        // Opened from My groups: the group's page keeps its "My groups" link
        mockMvc.perform(post(BASE + "/1/join").param("from", "mine").with(csrf()))
                .andExpect(redirectedUrl(BASE + "/1?from=mine"));

        verify(groupMembershipService, times(2)).requestToJoin(1L, ALEX);
    }

    // GRP-4: the group's own refusal is shown as it is
    @Test
    void refusedJoinShowsTheGroupsMessage() throws Exception {
        when(groupMembershipService.requestToJoin(2L, ALEX)).thenThrow(new IllegalStateException("Group is full"));
        when(groupMembershipService.requestToJoin(3L, ALEX))
                .thenThrow(new IllegalArgumentException("The leader is already in the group"));
        when(groupMembershipService.requestToJoin(99L, ALEX)).thenThrow(new StudyGroupNotFoundException(99L));

        mockMvc.perform(post(BASE + "/2/join").with(csrf())).andExpect(redirectedUrl(BASE + "/2"))
                .andExpect(flash().attribute("error", "Group is full"));
        mockMvc.perform(post(BASE + "/3/join").with(csrf()))
                .andExpect(flash().attribute("error", "The leader is already in the group"));
        mockMvc.perform(post(BASE + "/99/join").with(csrf())).andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", GROUP_GONE));
    }

    // GRP-8: back to the group's page, or to My groups when cancelled from there
    @Test
    void cancelRequestRedirectsWithSuccess() throws Exception {
        mockMvc.perform(post(BASE + "/1/cancel-request").with(csrf()))
                .andExpect(redirectedUrl(BASE + "/1"))
                .andExpect(flash().attribute("success", "Request cancelled. You can ask to join again later."));
        mockMvc.perform(post(BASE + "/1/cancel-request").param("from", "mine").with(csrf()))
                .andExpect(redirectedUrl(BASE + "/1?from=mine"));
        mockMvc.perform(post(BASE + "/1/cancel-request").param("backToMyGroups", "true").with(csrf()))
                .andExpect(redirectedUrl(BASE + "/mine"));

        verify(groupMembershipService, times(3)).cancelRequest(1L, ALEX);
    }

    // GRP-8: e.g. the leader answered first
    @Test
    void cancelRequestRefusedWithReason() throws Exception {
        when(groupMembershipService.cancelRequest(1L, ALEX))
                .thenThrow(new IllegalStateException("Only a request that is still waiting can be cancelled"));
        when(groupMembershipService.cancelRequest(99L, ALEX)).thenThrow(new StudyGroupNotFoundException(99L));

        mockMvc.perform(post(BASE + "/1/cancel-request").with(csrf()))
                .andExpect(redirectedUrl(BASE + "/1"))
                .andExpect(flash().attribute("error", "Only a request that is still waiting can be cancelled"));
        mockMvc.perform(post(BASE + "/99/cancel-request").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", GROUP_GONE));
    }

    // GRP-6
    @Test
    void removeFromMyGroupsRedirectsWithSuccess() throws Exception {
        mockMvc.perform(post(BASE + "/1/remove-from-my-groups").with(csrf()))
                .andExpect(redirectedUrl(BASE + "/mine"))
                .andExpect(flash().attribute("success", "Removed from My groups."));

        verify(groupMembershipService).removeFromMyGroups(1L, ALEX);
    }

    // GRP-6: only a closed group, and only one the student is linked to
    @Test
    void removeFromMyGroupsRefusedWithReason() throws Exception {
        doThrow(new IllegalStateException("Only a closed group can be removed from My groups"))
                .when(groupMembershipService).removeFromMyGroups(1L, ALEX);
        doThrow(new StudyGroupNotFoundException(99L)).when(groupMembershipService).removeFromMyGroups(99L, ALEX);

        mockMvc.perform(post(BASE + "/1/remove-from-my-groups").with(csrf()))
                .andExpect(redirectedUrl(BASE + "/mine"))
                .andExpect(flash().attribute("error", "Only a closed group can be removed from My groups"));
        mockMvc.perform(post(BASE + "/99/remove-from-my-groups").with(csrf()))
                .andExpect(redirectedUrl(BASE + "/mine"))
                .andExpect(flash().attribute("error", GROUP_GONE));
    }

    // ================= Manage members =================

    // L4, L6: join requests above the members, for the leader only
    @Test
    void membersPageShowsRequestsAndMembers() throws Exception {
        when(studyGroupService.getGroupLedBy(1L, ALEX)).thenReturn(group);
        when(groupMembershipService.getPendingRequests(1L, ALEX)).thenReturn(List.of(farhansRequest));
        when(groupMembershipService.getMembers(1L, ALEX)).thenReturn(group.getAcceptedMemberships());

        mockMvc.perform(get(BASE + "/1/members"))
                .andExpect(status().isOk())
                .andExpect(view().name("studygroup/members"))
                .andExpect(model().attribute("group", StudyGroupSummary.from(group)))
                .andExpect(model().attribute("requests", List.of(GroupMemberSummary.from(farhansRequest))))
                .andExpect(model().attribute("members", group.getAcceptedMemberships().stream()
                        .map(GroupMemberSummary::from).toList()));
    }

    // L4, GRP-2
    @Test
    void membersPageRefusedForNonLeaderOrMissingGroup() throws Exception {
        when(studyGroupService.getGroupLedBy(2L, ALEX)).thenThrow(new NotGroupLeaderException(2L, 1L));
        when(studyGroupService.getGroupLedBy(99L, ALEX)).thenThrow(new StudyGroupNotFoundException(99L));

        mockMvc.perform(get(BASE + "/2/members")).andExpect(redirectedUrl(BASE + "/2"))
                .andExpect(flash().attribute("error", NOT_LEADER));
        mockMvc.perform(get(BASE + "/99/members")).andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", GROUP_GONE));
    }

    // L5, GRP-5: the message names the student
    @Test
    void acceptAndRejectNameTheStudent() throws Exception {
        when(groupMembershipService.acceptRequest(1L, 13L, ALEX)).thenReturn(farhansRequest);
        when(groupMembershipService.rejectRequest(1L, 13L, ALEX)).thenReturn(farhansRequest);

        mockMvc.perform(post(BASE + "/1/members/13/accept").with(csrf()))
                .andExpect(redirectedUrl(BASE + "/1/members"))
                .andExpect(flash().attribute("success", "Farhan Ismail is now a member of the group."));
        mockMvc.perform(post(BASE + "/1/members/13/reject").with(csrf()))
                .andExpect(redirectedUrl(BASE + "/1/members"))
                .andExpect(flash().attribute("success", "Farhan Ismail's request was rejected."));
    }

    // L6, GRP-5
    @Test
    void removeMemberNamesTheStudent() throws Exception {
        when(groupMembershipService.removeMember(1L, 12L, ALEX)).thenReturn(chloesMembership);

        mockMvc.perform(post(BASE + "/1/members/12/remove").with(csrf()))
                .andExpect(redirectedUrl(BASE + "/1/members"))
                .andExpect(flash().attribute("success", "Chloe Lim was removed from the group."));
    }

    // GRP-2, GRP-5: each kind of refusal gets its own message and destination
    @Test
    void manageActionsRefusedWithReason() throws Exception {
        when(groupMembershipService.acceptRequest(2L, 13L, ALEX)).thenThrow(new NotGroupLeaderException(2L, 1L));
        when(groupMembershipService.acceptRequest(99L, 13L, ALEX)).thenThrow(new StudyGroupNotFoundException(99L));
        when(groupMembershipService.acceptRequest(1L, 77L, ALEX)).thenThrow(new MembershipNotFoundException(77L));
        when(groupMembershipService.acceptRequest(1L, 13L, ALEX))
                .thenThrow(new IllegalStateException("Group is full. Remove a member to accept new members."));
        when(groupMembershipService.removeMember(1L, 11L, ALEX))
                .thenThrow(new IllegalArgumentException("The leader can't be removed from their own group"));

        mockMvc.perform(post(BASE + "/2/members/13/accept").with(csrf())).andExpect(redirectedUrl(BASE + "/2"))
                .andExpect(flash().attribute("error", NOT_LEADER));
        mockMvc.perform(post(BASE + "/99/members/13/accept").with(csrf())).andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", GROUP_GONE));
        mockMvc.perform(post(BASE + "/1/members/77/accept").with(csrf())).andExpect(redirectedUrl(BASE + "/1/members"))
                .andExpect(flash().attribute("error", "That request or member no longer exists."));
        mockMvc.perform(post(BASE + "/1/members/13/accept").with(csrf())).andExpect(redirectedUrl(BASE + "/1/members"))
                .andExpect(flash().attribute("error", "Group is full. Remove a member to accept new members."));
        mockMvc.perform(post(BASE + "/1/members/11/remove").with(csrf()))
                .andExpect(flash().attribute("error", "The leader can't be removed from their own group"));
    }

    // D3, GRP-2: an "email" field can't join or manage as another student
    @Test
    void emailParameterIsIgnored() throws Exception {
        when(groupMembershipService.acceptRequest(1L, 13L, ALEX)).thenReturn(farhansRequest);
        when(groupMembershipService.removeMember(1L, 12L, ALEX)).thenReturn(chloesMembership);

        mockMvc.perform(post(BASE + "/1/join").param("email", "ben@test.com").with(csrf()));
        mockMvc.perform(post(BASE + "/1/remove-from-my-groups").param("email", "ben@test.com").with(csrf()));
        mockMvc.perform(post(BASE + "/1/cancel-request").param("email", "ben@test.com").with(csrf()));
        mockMvc.perform(post(BASE + "/1/members/13/accept").param("email", "ben@test.com").with(csrf()));
        mockMvc.perform(post(BASE + "/1/members/12/remove").param("email", "ben@test.com").with(csrf()));

        verify(groupMembershipService).requestToJoin(1L, ALEX);
        verify(groupMembershipService).removeFromMyGroups(1L, ALEX);
        verify(groupMembershipService).cancelRequest(1L, ALEX);
        verify(groupMembershipService).acceptRequest(1L, 13L, ALEX);
        verify(groupMembershipService).removeMember(1L, 12L, ALEX);
        verify(groupMembershipService, never()).acceptRequest(anyLong(), anyLong(), eq("ben@test.com"));
    }

    // D3, SEC-2: anonymous, admin and token-less requests are refused before the controller runs
    @Test
    void refusedRequestsNeverReachTheService() throws Exception {
        List<Supplier<MockHttpServletRequestBuilder>> posts = List.of(() -> post(BASE + "/1/join"),
                () -> post(BASE + "/1/remove-from-my-groups"), () -> post(BASE + "/1/cancel-request"),
                () -> post(BASE + "/1/members/13/accept"),
                () -> post(BASE + "/1/members/13/reject"), () -> post(BASE + "/1/members/12/remove"));
        mockMvc.perform(get(BASE + "/1/members").with(anonymous())).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get(BASE + "/1/members").with(user("admin@test.com").roles("ADMIN")))
                .andExpect(status().isForbidden());
        for (Supplier<MockHttpServletRequestBuilder> route : posts) {
            mockMvc.perform(route.get().with(anonymous()).with(csrf())).andExpect(redirectedUrl("/login"));
            mockMvc.perform(route.get().with(user("admin@test.com").roles("ADMIN")).with(csrf()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(route.get()).andExpect(status().isForbidden());
        }

        verifyNoInteractions(groupMembershipService, studyGroupService);
    }

    private static Student student(long id, String email, String name) {
        Student student = new Student(email, "dummyHash", name, "SCIS", "Information Systems", 2, "91234567");
        ReflectionTestUtils.setField(student, "userId", id);
        return student;
    }
}

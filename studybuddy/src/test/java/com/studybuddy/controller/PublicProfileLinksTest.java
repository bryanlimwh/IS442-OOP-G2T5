package com.studybuddy.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.studybuddy.config.SecurityConfig;
import com.studybuddy.entity.AvailabilitySlot;
import com.studybuddy.entity.Course;
import com.studybuddy.entity.GroupMembership;
import com.studybuddy.entity.MatchingConfig;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyGroup;
import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.MatchRequestStatus;
import com.studybuddy.entity.enums.MatchingStrategyType;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;
import com.studybuddy.matching.MatchResult;
import com.studybuddy.matching.PreferenceSummary;
import com.studybuddy.matching.ScoreBreakdown;
import com.studybuddy.matching.StudentPublicProfile;
import com.studybuddy.service.matching.MatchingConfigService;
import com.studybuddy.service.matching.MatchingService;
import com.studybuddy.service.studybuddymatching.ConnectionSummary;
import com.studybuddy.service.studybuddymatching.MatchRequestService;
import com.studybuddy.service.studybuddymatching.MatchRequestSummary;
import com.studybuddy.service.studybuddymatching.StudyBuddyConnectionService;
import com.studybuddy.service.studygroup.GroupMembershipService;
import com.studybuddy.service.studygroup.StudyGroupService;

/**
 * Another student's name links to their public profile (/students/{id}) on every page that lists
 * students: Find matches, Match requests, Study buddies and a group's Manage members. Rendered with
 * the real templates; the services are mocked. Logged in as student alex@test.com; the other
 * student is Ben Koh, student 2.
 */
@WebMvcTest({ MatchingController.class, MatchRequestController.class, StudyBuddyConnectionController.class,
        GroupMembershipController.class })
@Import(SecurityConfig.class)
@WithMockUser(username = "alex@test.com", roles = "STUDENT")
class PublicProfileLinksTest {

    private static final String BENS_PROFILE_LINK = "<a href=\"/students/2\">Ben Koh</a>";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MatchingService matchingService;
    @MockitoBean
    private MatchingConfigService matchingConfigService;
    @MockitoBean
    private MatchRequestService matchRequestService;
    @MockitoBean
    private StudyBuddyConnectionService connectionService;
    @MockitoBean
    private GroupMembershipService groupMembershipService;
    @MockitoBean
    private StudyGroupService studyGroupService;

    private final StudentPublicProfile alex = new StudentPublicProfile(1L, "Alex Tan", "SCIS", "IS", 2, List.of("IS442"));
    private final StudentPublicProfile ben = new StudentPublicProfile(2L, "Ben Koh", "SCIS", "IS", 3, List.of("IS442"));

    // S6, S3: each match card's name
    @Test
    void matchCardNameLinksToTheProfile() throws Exception {
        PreferenceSummary is442 = new PreferenceSummary(1L, "IS442", "Object Oriented Programming", StudyMode.ONLINE,
                Arrangement.EITHER, StudyGoal.EXAM_PREPARATION, List.of("Wed 19:00-21:00"));
        when(matchingService.getPreferences(any())).thenReturn(List.of(is442));
        when(matchingService.findMatches(any(), any())).thenReturn(List.of(
                new MatchResult(ben, is442, new ScoreBreakdown(1, 1, 1, 1, 1, 120), 100)));
        when(matchingConfigService.getCurrent())
                .thenReturn(new MatchingConfig(MatchingStrategyType.BALANCED, 20, 20, 20, 20, 20, 120));

        mockMvc.perform(get("/matches")).andExpect(status().isOk())
                .andExpect(content().string(containsString(BENS_PROFILE_LINK)));
    }

    // S6, S8: the other student on a received and on a sent request
    @Test
    void requestNamesLinkToTheProfile() throws Exception {
        LocalDateTime sentAt = LocalDateTime.of(2026, 10, 20, 9, 0);
        when(matchRequestService.getMatchRequestByRecipient("alex@test.com")).thenReturn(List.of(
                new MatchRequestSummary(5L, ben, alex, "Hi", MatchRequestStatus.PENDING, sentAt)));
        when(matchRequestService.getMatchRequestBySender("alex@test.com")).thenReturn(List.of(
                new MatchRequestSummary(6L, alex, new StudentPublicProfile(3L, "Chloe Lim", "SCIS", "IS", 2, List.of()),
                        null, MatchRequestStatus.PENDING, sentAt)));

        mockMvc.perform(get("/match-requests")).andExpect(status().isOk())
                .andExpect(content().string(containsString(BENS_PROFILE_LINK)))
                .andExpect(content().string(containsString("<a href=\"/students/3\">Chloe Lim</a>")));
    }

    // S6, S9: each buddy's name
    @Test
    void buddyNameLinksToTheProfile() throws Exception {
        when(connectionService.getConnections("alex@test.com")).thenReturn(List.of(
                new ConnectionSummary(3L, ben, "92345678", LocalDateTime.of(2026, 10, 13, 9, 0))));

        mockMvc.perform(get("/connections")).andExpect(status().isOk())
                .andExpect(content().string(containsString(BENS_PROFILE_LINK)));
    }

    // S6, L4: members and students asking to join, on Manage members
    @Test
    void memberAndRequestNamesLinkToTheProfile() throws Exception {
        Course is442 = new Course("IS442", "Object Oriented Programming");
        ReflectionTestUtils.setField(is442, "courseId", 1L);
        StudyGroup group = new StudyGroup("IS442 Revision", null, is442, StudyGoal.EXAM_PREPARATION, StudyMode.ONLINE,
                4, student(1L, "alex@test.com", "Alex Tan"));
        ReflectionTestUtils.setField(group, "groupId", 1L);
        group.addAvailabilitySlot(new AvailabilitySlot(DayOfWeek.MONDAY, LocalTime.of(10, 0), LocalTime.of(12, 0)));
        GroupMembership bensMembership = group.requestToJoin(student(2L, "ben@test.com", "Ben Koh"));
        group.acceptRequest(bensMembership);
        GroupMembership chloesRequest = group.requestToJoin(student(3L, "chloe@test.com", "Chloe Lim"));
        when(studyGroupService.getGroupLedBy(1L, "alex@test.com")).thenReturn(group);
        when(groupMembershipService.getPendingRequests(1L, "alex@test.com")).thenReturn(List.of(chloesRequest));
        when(groupMembershipService.getMembers(1L, "alex@test.com")).thenReturn(group.getAcceptedMemberships());

        mockMvc.perform(get("/groups/1/members")).andExpect(status().isOk())
                .andExpect(content().string(containsString(BENS_PROFILE_LINK)))
                .andExpect(content().string(containsString("<a href=\"/students/3\">Chloe Lim</a>")));
    }

    private static Student student(long id, String email, String name) {
        Student student = new Student(email, "dummyHash", name, "SCIS", "IS", 2, "91234567");
        ReflectionTestUtils.setField(student, "userId", id);
        return student;
    }
}

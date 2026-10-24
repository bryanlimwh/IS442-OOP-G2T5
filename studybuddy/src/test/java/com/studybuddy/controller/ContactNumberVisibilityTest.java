package com.studybuddy.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
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
import com.studybuddy.entity.MatchRequest;
import com.studybuddy.entity.MatchingConfig;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyBuddyConnection;
import com.studybuddy.entity.StudyPreference;
import com.studybuddy.entity.enums.Arrangement;
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

/**
 * Where a contact number may appear in the rendered HTML: never on the matches or match requests
 * pages, only on Study buddies, and there only the buddy's own. The services are mocked, but what
 * they return is built from real Student entities through the same from() methods the services
 * use, so a summary that started carrying the number would show up here. Each student has a
 * distinctive number: Alex (logged in) 91110001, Ben (Alex's buddy) 92220002, Chloe 93330003.
 */
@WebMvcTest({ MatchingController.class, MatchRequestController.class, StudyBuddyConnectionController.class })
@Import(SecurityConfig.class)
@WithMockUser(username = "alex@test.com", roles = "STUDENT")
class ContactNumberVisibilityTest {

    private static final String ALEX_CONTACT = "91110001";
    private static final String BEN_CONTACT = "92220002";
    private static final String CHLOE_CONTACT = "93330003";

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

    private final Course is442 = course(1L, "IS442", "Object Oriented Programming");
    private Student alex;
    private Student ben;
    private Student chloe;

    @BeforeEach
    void setUp() {
        alex = student(1L, "alex@test.com", "Alex Tan", ALEX_CONTACT);
        ben = student(2L, "ben@test.com", "Ben Koh", BEN_CONTACT);
        chloe = student(3L, "chloe@test.com", "Chloe Lim", CHLOE_CONTACT);
    }

    // S6, PRF-3, REQ-4: match cards show public details only
    @Test
    void matchesPageNeverShowsContactNumbers() throws Exception {
        when(matchingService.getPreferences(any())).thenReturn(List.of(PreferenceSummary.from(preference(alex))));
        when(matchingService.findMatches(any(), any())).thenReturn(List.of(
                result(ben, 92), result(chloe, 70)));
        when(matchingConfigService.getCurrent())
                .thenReturn(new MatchingConfig(MatchingStrategyType.BALANCED, 20, 20, 20, 20, 20, 120));

        mockMvc.perform(get("/matches"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("Ben Koh"), containsString("Chloe Lim"),
                        not(containsString(BEN_CONTACT)), not(containsString(CHLOE_CONTACT)),
                        not(containsString(ALEX_CONTACT)))));
    }

    // S7, S8, REQ-4: requests never show a number, even once accepted (it's on Study buddies only)
    @Test
    void matchRequestsPageNeverShowsContactNumbers() throws Exception {
        MatchRequest fromBen = request(5L, ben, alex, "Study IS442 together?");
        fromBen.accept();
        when(matchRequestService.getMatchRequestByRecipient("alex@test.com"))
                .thenReturn(List.of(MatchRequestSummary.from(fromBen)));
        when(matchRequestService.getMatchRequestBySender("alex@test.com"))
                .thenReturn(List.of(MatchRequestSummary.from(request(6L, alex, chloe, "Revise together?"))));

        mockMvc.perform(get("/match-requests"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("Ben Koh"), containsString("Chloe Lim"),
                        not(containsString(BEN_CONTACT)), not(containsString(CHLOE_CONTACT)),
                        not(containsString(ALEX_CONTACT)))));
    }

    // S8, S9, REQ-4: the buddy's number is shown, the student's own and strangers' are not
    @Test
    void connectionsPageShowsOnlyTheBuddysNumber() throws Exception {
        StudyBuddyConnection connection = new StudyBuddyConnection(alex, ben);
        ReflectionTestUtils.setField(connection, "id", 3L);
        ReflectionTestUtils.setField(connection, "createdAt", LocalDateTime.of(2026, 10, 13, 9, 0));
        when(connectionService.getConnections("alex@test.com"))
                .thenReturn(List.of(ConnectionSummary.from(connection, alex)));

        mockMvc.perform(get("/connections"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("Ben Koh"), containsString(BEN_CONTACT),
                        not(containsString(ALEX_CONTACT)), not(containsString(CHLOE_CONTACT)))));
    }

    private MatchResult result(Student student, double score) {
        return new MatchResult(StudentPublicProfile.from(student), PreferenceSummary.from(preference(student)),
                new ScoreBreakdown(1, 1, 1, 1, 1, 120), score);
    }

    private StudyPreference preference(Student student) {
        StudyPreference preference = new StudyPreference(is442, StudyMode.ONLINE, Arrangement.EITHER,
                StudyGoal.EXAM_PREPARATION);
        preference.addAvailabilitySlot(new AvailabilitySlot(DayOfWeek.WEDNESDAY, LocalTime.of(19, 0),
                LocalTime.of(21, 0)));
        student.addPreference(preference);
        return preference;
    }

    private static MatchRequest request(long id, Student sender, Student recipient, String message) {
        MatchRequest request = new MatchRequest(sender, recipient, message);
        ReflectionTestUtils.setField(request, "id", id);
        ReflectionTestUtils.setField(request, "createdAt", LocalDateTime.of(2026, 10, 20, 9, 0));
        return request;
    }

    private Student student(long id, String email, String name, String contact) {
        Student student = new Student(email, "dummyHash", name, "School of Computing and Information Systems",
                "Information Systems", 2, contact);
        ReflectionTestUtils.setField(student, "userId", id);
        student.addCourse(is442);
        return student;
    }

    private static Course course(long id, String code, String name) {
        Course course = new Course(code, name);
        ReflectionTestUtils.setField(course, "courseId", id);
        return course;
    }
}

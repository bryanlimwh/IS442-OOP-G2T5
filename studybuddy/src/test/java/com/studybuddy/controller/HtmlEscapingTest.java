package com.studybuddy.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
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
import com.studybuddy.entity.Course;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyGroup;
import com.studybuddy.entity.enums.MatchRequestStatus;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;
import com.studybuddy.matching.StudentPublicProfile;
import com.studybuddy.service.course.CourseService;
import com.studybuddy.service.student.StudentProfileService;
import com.studybuddy.service.studybuddymatching.MatchRequestService;
import com.studybuddy.service.studybuddymatching.MatchRequestSummary;
import com.studybuddy.service.studygroup.GroupMembershipService;
import com.studybuddy.service.studygroup.StudyGroupFormValidator;
import com.studybuddy.service.studygroup.StudyGroupService;
import com.studybuddy.service.studygroup.ViewerRole;

/**
 * Text that students type is shown escaped, so a script in it can't run in another student's
 * browser. No template uses th:utext or an unescaped inline expression ([(...)]); these tests
 * render the real templates with a script tag in each kind of typed text and check the page
 * holds only the escaped form. The services are mocked. Logged in as student alex@test.com.
 */
@WebMvcTest({ MatchRequestController.class, StudyGroupController.class, StudentProfileController.class,
        PublicProfileController.class })
@Import({ SecurityConfig.class, StudyGroupFormValidator.class })
@WithMockUser(username = "alex@test.com", roles = "STUDENT")
class HtmlEscapingTest {

    private static final String SCRIPT = "<script>alert(1)</script>";
    private static final String ESCAPED = "&lt;script&gt;alert(1)&lt;/script&gt;";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MatchRequestService matchRequestService;
    @MockitoBean
    private StudyGroupService studyGroupService;
    @MockitoBean
    private GroupMembershipService groupMembershipService;
    @MockitoBean
    private StudentProfileService studentProfileService;
    @MockitoBean
    private CourseService courseService;

    // S7, S8: the optional message on a match request
    @Test
    void matchRequestMessageIsEscaped() throws Exception {
        StudentPublicProfile alex = new StudentPublicProfile(1L, "Alex Tan", "SCIS", "IS", 2, List.of("IS442"));
        StudentPublicProfile ben = new StudentPublicProfile(2L, "Ben Koh", "SCIS", "IS", 3, List.of("IS442"));
        when(matchRequestService.getMatchRequestByRecipient("alex@test.com")).thenReturn(List.of(
                new MatchRequestSummary(5L, ben, alex, "Hi " + SCRIPT, MatchRequestStatus.PENDING,
                        LocalDateTime.of(2026, 10, 20, 9, 0))));

        mockMvc.perform(get("/match-requests"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("Hi " + ESCAPED), not(containsString(SCRIPT)))));
    }

    // L1, L2: a group's name and description, on the group's page
    @Test
    void groupNameAndDescriptionAreEscaped() throws Exception {
        Course is442 = new Course("IS442", "Object Oriented Programming");
        ReflectionTestUtils.setField(is442, "courseId", 1L);
        Student ben = new Student("ben@test.com", "dummyHash", "Ben Koh", "SCIS", "IS", 3, "92345678");
        ReflectionTestUtils.setField(ben, "userId", 2L);
        StudyGroup group = new StudyGroup("Revision " + SCRIPT, "About " + SCRIPT, is442, StudyGoal.EXAM_PREPARATION,
                StudyMode.ONLINE, 4, ben);
        ReflectionTestUtils.setField(group, "groupId", 1L);
        when(studyGroupService.getGroup(1L)).thenReturn(group);
        when(groupMembershipService.getViewerRole(1L, "alex@test.com")).thenReturn(ViewerRole.NONE);

        mockMvc.perform(get("/groups/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("Revision " + ESCAPED),
                        containsString("About " + ESCAPED), not(containsString(SCRIPT)))));
    }

    // S1: the student's name, which the profile form shows inside an input's value
    @Test
    void profileNameIsEscaped() throws Exception {
        when(studentProfileService.getProfile("alex@test.com")).thenReturn(new Student("alex@test.com", "dummyHash",
                "Alex " + SCRIPT, "SCIS", "IS", 2, "91234567"));
        when(studentProfileService.getCoursesTaken("alex@test.com")).thenReturn(List.of());
        when(courseService.getAllCourses()).thenReturn(List.of());

        mockMvc.perform(get("/student/profile"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("Alex " + ESCAPED), not(containsString(SCRIPT)))));
    }

    // S6: another student's name and programme on their public profile page
    @Test
    void publicProfileNameAndProgrammeAreEscaped() throws Exception {
        when(studentProfileService.getPublicProfile(2L)).thenReturn(new StudentPublicProfile(2L, "Ben " + SCRIPT,
                "SCIS", "IS " + SCRIPT, 3, List.of("IS442")));
        when(studentProfileService.getPublicPreferences(2L)).thenReturn(List.of());

        mockMvc.perform(get("/students/2"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("Ben " + ESCAPED), containsString("IS " + ESCAPED),
                        not(containsString(SCRIPT)))));
    }
}

package com.studybuddy.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.studybuddy.config.SecurityConfig;
import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;
import com.studybuddy.exception.StudentNotFoundException;
import com.studybuddy.matching.PreferenceSummary;
import com.studybuddy.matching.StudentPublicProfile;
import com.studybuddy.service.student.StudentProfileService;

/**
 * Web-layer test for another student's public profile page, rendered with the real template.
 * The service is mocked. Logged in as student alex@test.com, viewing Ben Koh (student 2).
 */
@WebMvcTest(PublicProfileController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "alex@test.com", roles = "STUDENT")
class PublicProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StudentProfileService studentProfileService;

    @Test
    void showsProfileAndPreferencesButNoContactDetails() throws Exception {
        when(studentProfileService.getPublicProfile(2L)).thenReturn(new StudentPublicProfile(2L, "Ben Koh",
                "School of Computing and Information Systems", "Information Systems", 3, List.of("IS442", "CS201")));
        when(studentProfileService.getPublicPreferences(2L)).thenReturn(List.of(new PreferenceSummary(1L, "IS442",
                "Object Oriented Programming", StudyMode.EITHER, Arrangement.ONE_TO_ONE, StudyGoal.EXAM_PREPARATION,
                List.of("Wed 19:00-21:00"))));

        mockMvc.perform(get("/students/2"))
                .andExpect(status().isOk())
                .andExpect(view().name("student/public-profile"))
                .andExpect(content().string(allOf(containsString("Ben Koh"), containsString("Information Systems, Year 3"),
                        containsString("IS442, CS201"), containsString("IS442 Object Oriented Programming"),
                        containsString("Wed 19:00-21:00"))))
                // The page is never given a contact number, so it can't appear
                .andExpect(content().string(not(containsString("92345678"))));
    }

    @Test
    void unknownStudentRedirectsToMatchesWithMessage() throws Exception {
        when(studentProfileService.getPublicProfile(99L)).thenThrow(new StudentNotFoundException(99L));

        mockMvc.perform(get("/students/99"))
                .andExpect(redirectedUrl("/matches"))
                .andExpect(flash().attribute("error", "That student's profile no longer exists."));
    }

    @Test
    void onlyStudentsCanViewProfiles() throws Exception {
        mockMvc.perform(get("/students/2").with(user("admin@test.com").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/students/2").with(anonymous()))
                .andExpect(status().is3xxRedirection());
    }
}

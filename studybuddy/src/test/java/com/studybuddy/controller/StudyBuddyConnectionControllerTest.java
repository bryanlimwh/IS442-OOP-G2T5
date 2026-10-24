package com.studybuddy.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.doThrow;
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

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.studybuddy.config.SecurityConfig;
import com.studybuddy.exception.ConnectionNotFoundException;
import com.studybuddy.matching.StudentPublicProfile;
import com.studybuddy.service.studybuddymatching.ConnectionSummary;
import com.studybuddy.service.studybuddymatching.StudyBuddyConnectionService;

/**
 * Web-layer test for the study buddies page, rendered with the real template. The service is
 * mocked (the rules are in StudyBuddyConnectionServiceTest). Logged in as student alex@test.com,
 * connected to Ben Koh (connection 3, contact 92345678).
 */
@WebMvcTest(StudyBuddyConnectionController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = StudyBuddyConnectionControllerTest.ALEX, roles = "STUDENT")
class StudyBuddyConnectionControllerTest {

    static final String ALEX = "alex@test.com";
    private static final String BASE = "/connections";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StudyBuddyConnectionService connectionService;

    // S9, S8: each buddy with their contact number
    @Test
    void pageListsLoggedInStudentsBuddies() throws Exception {
        ConnectionSummary ben = new ConnectionSummary(3L,
                new StudentPublicProfile(2L, "Ben Koh", "SCIS", "Information Systems", 3, List.of("IS442")),
                "92345678", LocalDateTime.of(2026, 10, 13, 9, 0));
        when(connectionService.getConnections(ALEX)).thenReturn(List.of(ben));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(view().name("student/connections"))
                .andExpect(model().attribute("connections", List.of(ben)))
                .andExpect(content().string(allOf(containsString("Ben Koh"), containsString("92345678"),
                        containsString("13 Oct 2026"))));
    }

    // S9, REQ-5
    @Test
    void endConnectionRedirectsWithSuccess() throws Exception {
        mockMvc.perform(post(BASE + "/3/end").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("success",
                        "Connection ended. You can no longer see each other's contact numbers."));

        verify(connectionService).endConnection(ALEX, 3L);
    }

    // REQ-5: someone else's connection looks the same as a missing one
    @Test
    void endUnknownConnectionShowsError() throws Exception {
        doThrow(new ConnectionNotFoundException(8L)).when(connectionService).endConnection(ALEX, 8L);

        mockMvc.perform(post(BASE + "/8/end").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "That connection no longer exists."));
    }

    // D3, REQ-5: an "email" field can't end a connection as another student
    @Test
    void emailParameterIsIgnored() throws Exception {
        mockMvc.perform(post(BASE + "/3/end").param("email", "ben@test.com").with(csrf()));
        mockMvc.perform(get(BASE).param("email", "ben@test.com"));

        verify(connectionService).endConnection(ALEX, 3L);
        verify(connectionService).getConnections(ALEX);
    }

    // D3, SEC-2: anonymous, admin and token-less requests are refused before the controller runs
    @Test
    void refusedRequestsNeverReachTheService() throws Exception {
        mockMvc.perform(get(BASE).with(anonymous())).andExpect(redirectedUrl("/login"));
        mockMvc.perform(post(BASE + "/3/end").with(anonymous()).with(csrf())).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get(BASE).with(user("admin@test.com").roles("ADMIN"))).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/3/end").with(user("admin@test.com").roles("ADMIN")).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/3/end")).andExpect(status().isForbidden());

        verifyNoInteractions(connectionService);
    }
}

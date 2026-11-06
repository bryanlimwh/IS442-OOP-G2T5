package com.studybuddy.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.studybuddy.config.SecurityConfig;
import com.studybuddy.entity.enums.MatchRequestStatus;
import com.studybuddy.matching.StudentPublicProfile;
import com.studybuddy.service.studybuddymatching.MatchRequestService;
import com.studybuddy.service.studybuddymatching.MatchRequestSummary;

/**
 * Adds to MatchRequestControllerTest (a teammate's file, left unchanged): the student always
 * acts as themselves, whatever the form sends, and refused POSTs never reach the service.
 * The service is mocked. Logged in as student alex@test.com.
 */
@WebMvcTest(MatchRequestController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = MatchRequestControllerPrincipalTest.ALEX, roles = "STUDENT")
class MatchRequestControllerPrincipalTest {

    static final String ALEX = "alex@test.com";
    private static final String BEN = "ben@test.com";
    private static final String BASE = "/match-requests";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MatchRequestService service;

    // D3, S7, S8, REQ-2: "email" and "senderEmail" fields can't make Alex act as Ben
    @Test
    void emailParameterIsIgnoredOnEveryAction() throws Exception {
        // The real service always returns the request it sent; the controller names the recipient from it
        StudentPublicProfile ben = new StudentPublicProfile(2L, "Ben Koh", "SCIS", "Information Systems", 3, List.of());
        when(service.createRequest(ALEX, 2L, "Hi")).thenReturn(new MatchRequestSummary(7L, null, ben, "Hi",
                MatchRequestStatus.PENDING, LocalDateTime.now()));
        mockMvc.perform(post(BASE).param("recipientId", "2").param("message", "Hi").param("email", BEN)
                .param("senderEmail", BEN).with(csrf()));
        mockMvc.perform(post(BASE + "/5/accept").param("email", BEN).with(csrf()));
        mockMvc.perform(post(BASE + "/6/reject").param("email", BEN).with(csrf()));
        mockMvc.perform(get(BASE).param("email", BEN));

        verify(service).createRequest(ALEX, 2L, "Hi");
        verify(service).acceptRequest(ALEX, 5L);
        verify(service).rejectRequest(ALEX, 6L);
        verify(service).getMatchRequestByRecipient(ALEX);
        verify(service).getMatchRequestBySender(ALEX);
        verify(service, never()).acceptRequest(eq(BEN), anyLong());
    }

    // D3, SEC-2: anonymous and admin POSTs are refused before the controller runs (the existing test
    // only checks the GET for these two)
    @Test
    void refusedPostsNeverReachTheService() throws Exception {
        for (String path : List.of(BASE, BASE + "/5/accept", BASE + "/5/reject")) {
            MockHttpServletRequestBuilder request = post(path).param("recipientId", "2");
            mockMvc.perform(request.with(anonymous()).with(csrf())).andExpect(redirectedUrl("/login"));
            mockMvc.perform(post(path).param("recipientId", "2").with(user("admin@test.com").roles("ADMIN"))
                    .with(csrf())).andExpect(status().isForbidden());
        }

        verifyNoInteractions(service);
    }
}

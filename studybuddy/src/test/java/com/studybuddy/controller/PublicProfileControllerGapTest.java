package com.studybuddy.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.studybuddy.config.SecurityConfig;
import com.studybuddy.service.student.StudentProfileService;

/**
 * Adds to PublicProfileControllerTest (a teammate's file, left unchanged): refused requests never
 * reach the service, and a hand-edited id. The service is mocked. Logged in as student alex@test.com.
 */
@WebMvcTest(PublicProfileController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "alex@test.com", roles = "STUDENT")
class PublicProfileControllerGapTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StudentProfileService studentProfileService;

    // D3, SEC-2, S6: anonymous users and admins are stopped before the controller asks for any profile
    @Test
    void refusedRequestsNeverReachTheService() throws Exception {
        mockMvc.perform(get("/students/2").with(anonymous())).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/students/2").with(user("admin@test.com").roles("ADMIN"))).andExpect(status().isForbidden());

        verifyNoInteractions(studentProfileService);
    }

    // S6, N-C1: pins current behaviour, update if fixed. An id that isn't a number (only by editing the
    // address) is a raw 400, not the "no longer exists" message
    @Test
    void nonNumericIdIsBadRequest() throws Exception {
        mockMvc.perform(get("/students/abc")).andExpect(status().isBadRequest());

        verify(studentProfileService, never()).getPublicProfile(any());
    }
}

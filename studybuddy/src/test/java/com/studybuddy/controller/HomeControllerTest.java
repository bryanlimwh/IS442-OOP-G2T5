package com.studybuddy.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.studybuddy.config.SecurityConfig;

/**
 * Web-layer test for the home page, rendered with the real template. Students and admins
 * get different feature links. HomeController uses no service.
 */
@WebMvcTest(HomeController.class)
@Import(SecurityConfig.class)
class HomeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // D3, S1, S3: a student gets the student links only
    @Test
    @WithMockUser(username = "alex@test.com", roles = "STUDENT")
    void studentSeesStudentLinks() throws Exception {
        mockMvc.perform(get("/home"))
                .andExpect(status().isOk())
                .andExpect(view().name("home"))
                .andExpect(model().attribute("username", "alex@test.com"))
                .andExpect(model().attribute("isAdmin", false))
                .andExpect(content().string(allOf(containsString("Welcome back"), containsString("alex@test.com"),
                        containsString("href=\"/student/profile\""), containsString("href=\"/matches\""),
                        containsString("href=\"/match-requests\""), containsString("href=\"/connections\""),
                        containsString("href=\"/groups\""), containsString("href=\"/courses\""),
                        not(containsString("href=\"/admin/accounts\"")),
                        not(containsString("href=\"/admin/matching\"")))));
    }

    // D3, A1, A3: an admin gets the admin links only
    @Test
    @WithMockUser(username = "admin@test.com", roles = "ADMIN")
    void adminSeesAdminLinks() throws Exception {
        mockMvc.perform(get("/home"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("username", "admin@test.com"))
                .andExpect(model().attribute("isAdmin", true))
                .andExpect(content().string(allOf(containsString("href=\"/admin/accounts\""),
                        containsString("href=\"/admin/matching\""), containsString("href=\"/courses\""),
                        not(containsString("href=\"/matches\"")), not(containsString("href=\"/groups\"")),
                        not(containsString("href=\"/student/profile\"")))));
    }

    // D3, SEC-2
    @Test
    @WithAnonymousUser
    void anonymousIsRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/home")).andExpect(redirectedUrl("/login"));
    }
}

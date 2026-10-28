package com.studybuddy.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.studybuddy.config.SecurityConfig;
import com.studybuddy.entity.enums.MatchRequestStatus;
import com.studybuddy.matching.StudentPublicProfile;
import com.studybuddy.service.studybuddymatching.MatchRequestService;
import com.studybuddy.service.studybuddymatching.MatchRequestSummary;

/**
 * Regression tests for bugs fixed in the match requests page (MatchRequestController and its
 * template). They were @Disabled known-bug tests until the fix; the class keeps its name so the
 * workbook Test IDs stay the same. The service is mocked; logged in as alex@test.com.
 */
@WebMvcTest(MatchRequestController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "alex@test.com", roles = "STUDENT")
class MatchRequestControllerKnownBugTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MatchRequestService service;

    // S7, S8, K12: fixed 2026-10-28. A message that is just 'no', 'false' or 'off' is shown like any other
    // (a plain th:if on the text read those words as false and hid them)
    @Test
    void shortMessagesThatReadAsFalseAreShown() throws Exception {
        StudentPublicProfile alex = new StudentPublicProfile(1L, "Alex Tan", "SCIS", "Information Systems", 2,
                List.of("IS442"));
        StudentPublicProfile ben = new StudentPublicProfile(2L, "Ben Koh", "SCIS", "Information Systems", 3,
                List.of("IS442"));
        LocalDateTime sentAt = LocalDateTime.of(2026, 10, 20, 9, 0);
        when(service.getMatchRequestByRecipient("alex@test.com")).thenReturn(List.of(
                new MatchRequestSummary(5L, ben, alex, "no", MatchRequestStatus.PENDING, sentAt),
                // An ordinary message, rendered as <p>...</p>, so a failure can only come from the three words
                new MatchRequestSummary(8L, ben, alex, "Study IS442 together?", MatchRequestStatus.PENDING, sentAt)));
        when(service.getMatchRequestBySender("alex@test.com")).thenReturn(List.of(
                new MatchRequestSummary(6L, alex, ben, "false", MatchRequestStatus.PENDING, sentAt),
                new MatchRequestSummary(7L, alex, ben, "off", MatchRequestStatus.REJECTED, sentAt)));

        mockMvc.perform(get("/match-requests"))
                .andExpect(content().string(allOf(containsString("<p>Study IS442 together?</p>"),
                        containsString("<p>no</p>"), containsString("<p>false</p>"),
                        containsString("<p>off</p>"))));
    }

    // S7, K4: fixed 2026-10-28. A message over 255 characters (only possible with a hand-made POST: the
    // page's box has maxlength 255) comes back as an error message, not a 500; the service isn't called
    @Test
    void messageTheDatabaseRefusesShowsAnError() throws Exception {
        String longMessage = "M".repeat(256);
        when(service.createRequest("alex@test.com", 2L, longMessage))
                .thenThrow(new DataIntegrityViolationException("Value too long for column MESSAGE"));

        mockMvc.perform(post("/match-requests").param("recipientId", "2").param("message", longMessage).with(csrf()))
                .andExpect(redirectedUrl("/match-requests"))
                .andExpect(flash().attribute("error", "Messages can be at most 255 characters."));
    }
}

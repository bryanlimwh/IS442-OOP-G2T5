package com.studybuddy.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.studybuddy.config.SecurityConfig;
import com.studybuddy.entity.enums.MatchRequestStatus;
import com.studybuddy.matching.StudentPublicProfile;
import com.studybuddy.service.studybuddymatching.MatchRequestService;
import com.studybuddy.service.studybuddymatching.MatchRequestSummary;

/**
 * Known bugs in the match requests page (MatchRequestController and its template). Each test
 * describes what SHOULD happen and fails on today's code, so it is @Disabled to keep the main
 * suite green. Remove @Disabled to check a fix. The service is mocked; logged in as alex@test.com.
 */
@WebMvcTest(MatchRequestController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "alex@test.com", roles = "STUDENT")
class MatchRequestControllerKnownBugTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MatchRequestService service;

    // S7, S8, K12
    @Test
    @Disabled("KNOWN BUG K12: a message that is just 'no', 'false' or 'off' should be shown like any other; "
            + "th:if treats those words as false and hides them")
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
}

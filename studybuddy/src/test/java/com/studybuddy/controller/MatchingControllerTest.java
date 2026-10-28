package com.studybuddy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.studybuddy.config.SecurityConfig;
import com.studybuddy.entity.MatchingConfig;
import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.MatchingStrategyType;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;
import com.studybuddy.exception.StudentNotFoundException;
import com.studybuddy.matching.MatchResult;
import com.studybuddy.matching.MatchSearch;
import com.studybuddy.matching.PreferenceSummary;
import com.studybuddy.matching.ScoreBreakdown;
import com.studybuddy.matching.StudentPublicProfile;
import com.studybuddy.service.matching.MatchingConfigService;
import com.studybuddy.service.matching.MatchingService;

/**
 * Web-layer test for the matches page, rendered with the real template. The services are mocked
 * (the matching itself is in MatchingServiceTest and CompatibilityCalculatorTest). Logged in as
 * student alex@test.com with an IS442 preference; one result, Ben Koh, scoring 92; the admin's
 * strategy is Balanced.
 */
@WebMvcTest(MatchingController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = MatchingControllerTest.ALEX, roles = "STUDENT")
class MatchingControllerTest {

    static final String ALEX = "alex@test.com";
    private static final String BASE = "/matches";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MatchingService matchingService;
    @MockitoBean
    private MatchingConfigService matchingConfigService;

    private final PreferenceSummary alexIs442 = new PreferenceSummary(1L, "IS442", "Object Oriented Programming",
            StudyMode.IN_PERSON, Arrangement.EITHER, StudyGoal.EXAM_PREPARATION, List.of("Wed 19:00-21:00"));
    private final MatchResult ben = new MatchResult(
            new StudentPublicProfile(2L, "Ben Koh", "SCIS", "Information Systems", 3, List.of("IS442")),
            new PreferenceSummary(1L, "IS442", "Object Oriented Programming", StudyMode.EITHER,
                    Arrangement.ONE_TO_ONE, StudyGoal.EXAM_PREPARATION, List.of("Wed 19:00-21:00")),
            new ScoreBreakdown(1, 1, 0.8, 1, 0.8, 120), 92);

    @BeforeEach
    void setUp() {
        when(matchingService.getPreferences(any())).thenReturn(List.of(alexIs442));
        when(matchingService.findMatches(any(), any())).thenReturn(List.of(ben));
        when(matchingConfigService.getCurrent())
                .thenReturn(new MatchingConfig(MatchingStrategyType.BALANCED, 20, 20, 20, 20, 20, 120));
    }

    // S3, S4: the ranked list for the logged-in student, and the filter choices (no EITHER)
    @Test
    void pageShowsRankedResultsForLoggedInStudent() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(view().name("student/matches"))
                .andExpect(model().attribute("myPreferences", List.of(alexIs442)))
                .andExpect(model().attribute("results", List.of(ben)))
                .andExpect(model().attribute("strategy", MatchingStrategyType.BALANCED))
                .andExpect(model().attribute("studyGoals", StudyGoal.values()))
                .andExpect(model().attribute("studyModes", List.of(StudyMode.IN_PERSON, StudyMode.ONLINE)))
                .andExpect(model().attribute("groupSizes", List.of(Arrangement.ONE_TO_ONE, Arrangement.SMALL_GROUP)))
                .andExpect(content().string(allOf(containsString("Ben Koh"), containsString("92"))));

        verify(matchingService).getPreferences(ALEX);
    }

    // S3, S5: every filter reaches the service
    @Test
    void filtersAreBoundToTheSearch() throws Exception {
        mockMvc.perform(get(BASE).param("courseId", "1").param("studyGoal", "EXAM_PREPARATION")
                        .param("studyMode", "ONLINE").param("groupSize", "SMALL_GROUP").param("sharedTimeOnly", "true"))
                .andExpect(status().isOk());

        verify(matchingService).findMatches(ALEX,
                new MatchSearch(1L, StudyGoal.EXAM_PREPARATION, StudyMode.ONLINE, Arrangement.SMALL_GROUP, true));
    }

    // S3, S5: no filters search everything; an unticked "shared time" box means false
    @Test
    void noFiltersSearchEverything() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isOk());

        ArgumentCaptor<MatchSearch> search = ArgumentCaptor.forClass(MatchSearch.class);
        verify(matchingService).findMatches(eq(ALEX), search.capture());
        assertThat(search.getValue()).isEqualTo(new MatchSearch(null, null, null, null, false));
    }

    // D3, S3: an "email" in the address can't search as another student
    @Test
    void emailParameterIsIgnored() throws Exception {
        mockMvc.perform(get(BASE).param("email", "ben@test.com")).andExpect(status().isOk());

        verify(matchingService).findMatches(eq(ALEX), any());
        verify(matchingService, never()).findMatches(eq("ben@test.com"), any());
    }

    // S5, N-C1: pins current behaviour, update if fixed. A filter value that isn't on the form (only
    // possible by editing the address) is a raw 400
    @Test
    void unknownFilterValueIsBadRequest() throws Exception {
        mockMvc.perform(get(BASE).param("studyGoal", "BOGUS")).andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE).param("courseId", "abc")).andExpect(status().isBadRequest());

        verify(matchingService, never()).findMatches(any(), any());
    }

    // K2, K16: pins current behaviour, update if fixed. A login email that no longer belongs to a
    // student gives a bare 404
    @Test
    void missingStudentAccountIsNotFound() throws Exception {
        when(matchingService.getPreferences(ALEX)).thenThrow(new StudentNotFoundException(ALEX));

        mockMvc.perform(get(BASE)).andExpect(status().isNotFound());
    }

    // D3, SEC-2: anonymous users and admins never reach the services
    @Test
    void refusedRequestsNeverReachTheService() throws Exception {
        mockMvc.perform(get(BASE).with(anonymous())).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get(BASE).with(user("admin@test.com").roles("ADMIN"))).andExpect(status().isForbidden());

        verifyNoInteractions(matchingService, matchingConfigService);
    }

    // S6, S3: the matches page shows a one-time error, e.g. after a link to a deleted student's profile
    @Test
    void oneTimeErrorIsShownOnThePage() throws Exception {
        mockMvc.perform(get(BASE).flashAttr("error", "That student's profile no longer exists."))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("That student&#39;s profile no longer exists.")));
    }
}

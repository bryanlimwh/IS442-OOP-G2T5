package com.studybuddy.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.studybuddy.config.SecurityConfig;
import com.studybuddy.entity.MatchingConfig;
import com.studybuddy.entity.enums.MatchingStrategyType;
import com.studybuddy.exception.InvalidMatchingConfigException;
import com.studybuddy.matching.MatchingConfigForm;
import com.studybuddy.service.matching.MatchingConfigService;

/**
 * Web-layer test for the admin's matching settings page, rendered with the real template. The
 * service is mocked (the rules are in MatchingConfigTest). Logged in as admin@test.com; the
 * settings in use are Balanced, every weight 20, 120-minute session.
 */
@WebMvcTest(AdminMatchingConfigController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "admin@test.com", roles = "ADMIN")
class AdminMatchingConfigControllerTest {

    private static final String BASE = "/admin/matching";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MatchingConfigService matchingConfigService;

    private final MatchingConfig current = new MatchingConfig(MatchingStrategyType.BALANCED, 20, 20, 20, 20, 20, 120);

    @BeforeEach
    void setUp() {
        when(matchingConfigService.getCurrent()).thenReturn(current);
    }

    // A3, A4: the form starts with the settings in use
    @Test
    void pageShowsCurrentSettings() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/matching-config"))
                .andExpect(model().attribute("form", MatchingConfigForm.from(current)))
                .andExpect(model().attribute("strategies", MatchingStrategyType.values()))
                .andExpect(model().attribute("current", current));
    }

    // A3, A4, MAT-1
    @Test
    void saveRedirectsWithSavedMessage() throws Exception {
        mockMvc.perform(settings("AVAILABILITY_FIRST", "0", "1", "0", "0", "0", "60"))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("saved", true));

        verify(matchingConfigService).update(
                new MatchingConfigForm(MatchingStrategyType.AVAILABILITY_FIRST, 0, 1, 0, 0, 0, 60));
    }

    // A3, MAT-1: the service's refusal is shown on the form, nothing is saved
    @Test
    void invalidSettingsShowServiceMessage() throws Exception {
        MatchingConfigForm allZero = new MatchingConfigForm(MatchingStrategyType.BALANCED, 0, 0, 0, 0, 0, 120);
        when(matchingConfigService.update(allZero))
                .thenThrow(new InvalidMatchingConfigException("At least one criterion needs a weight above 0."));

        mockMvc.perform(settings("BALANCED", "0", "0", "0", "0", "0", "120"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/matching-config"))
                .andExpect(model().attribute("error", "At least one criterion needs a weight above 0."))
                .andExpect(model().attribute("current", current));
    }

    // A3, MAT-1: a blank or non-numeric field never reaches the service
    @Test
    void blankOrNonNumericFieldShowsMessage() throws Exception {
        for (String weight : new String[] { "", "abc", "1.5" }) {
            mockMvc.perform(settings("BALANCED", weight, "20", "20", "20", "20", "120"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/matching-config"))
                    .andExpect(model().attribute("error", "Every weight and the session length must be a whole number."));
        }

        verify(matchingConfigService, never()).update(any());
    }

    // A4, MAT-1: no strategy chosen reaches the service as null, which refuses it
    @Test
    void missingStrategyIsLeftToTheService() throws Exception {
        MatchingConfigForm noStrategy = new MatchingConfigForm(null, 20, 20, 20, 20, 20, 120);
        when(matchingConfigService.update(noStrategy))
                .thenThrow(new InvalidMatchingConfigException("Choose a matching strategy."));

        mockMvc.perform(settings(null, "20", "20", "20", "20", "20", "120"))
                .andExpect(model().attribute("error", "Choose a matching strategy."));

        verify(matchingConfigService).update(noStrategy);
    }

    // D3, SEC-2: anonymous users, students and token-less posts never reach the service
    @Test
    void refusedRequestsNeverReachTheService() throws Exception {
        mockMvc.perform(get(BASE).with(anonymous())).andExpect(redirectedUrl("/login"));
        mockMvc.perform(settings("BALANCED", "20", "20", "20", "20", "20", "120").with(anonymous()))
                .andExpect(redirectedUrl("/login"));
        mockMvc.perform(get(BASE).with(user("alex@test.com").roles("STUDENT"))).andExpect(status().isForbidden());
        mockMvc.perform(settings("BALANCED", "20", "20", "20", "20", "20", "120")
                .with(user("alex@test.com").roles("STUDENT"))).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE).param("strategy", "BALANCED")).andExpect(status().isForbidden());

        verifyNoInteractions(matchingConfigService);
    }

    // The settings form as the page posts it (a null strategy leaves the field out)
    private static MockHttpServletRequestBuilder settings(String strategy, String course, String availability,
            String mode, String goal, String size, String minutes) {
        MockHttpServletRequestBuilder request = post(BASE).param("courseWeight", course)
                .param("availabilityWeight", availability).param("studyModeWeight", mode)
                .param("studyGoalWeight", goal).param("groupSizeWeight", size)
                .param("targetSessionMinutes", minutes).with(csrf());
        return strategy == null ? request : request.param("strategy", strategy);
    }
}

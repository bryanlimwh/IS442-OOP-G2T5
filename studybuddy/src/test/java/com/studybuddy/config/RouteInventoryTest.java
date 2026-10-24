package com.studybuddy.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.studybuddy.service.admin.AccountManagementService;
import com.studybuddy.service.course.CourseService;
import com.studybuddy.service.matching.MatchingConfigService;
import com.studybuddy.service.matching.MatchingService;
import com.studybuddy.service.student.StudentProfileService;
import com.studybuddy.service.student.StudyPreferenceService;
import com.studybuddy.service.studybuddymatching.MatchRequestService;
import com.studybuddy.service.studybuddymatching.StudyBuddyConnectionService;
import com.studybuddy.service.studygroup.GroupMembershipService;
import com.studybuddy.service.studygroup.StudyGroupFormValidator;
import com.studybuddy.service.studygroup.StudyGroupService;

/**
 * Keeps SecurityConfigTest's route table complete: every route the app's controllers map must
 * be in it, and nothing else. Loads every controller; the services are Mockito mocks that are
 * only declared so the controllers can be created (no behaviour is set or checked).
 */
@WebMvcTest
@Import({ SecurityConfig.class, StudyGroupFormValidator.class })
class RouteInventoryTest {

    @MockitoBean
    private AccountManagementService accountManagementService;
    @MockitoBean
    private CourseService courseService;
    @MockitoBean
    private MatchingConfigService matchingConfigService;
    @MockitoBean
    private MatchingService matchingService;
    @MockitoBean
    private StudentProfileService studentProfileService;
    @MockitoBean
    private StudyPreferenceService studyPreferenceService;
    @MockitoBean
    private MatchRequestService matchRequestService;
    @MockitoBean
    private StudyBuddyConnectionService studyBuddyConnectionService;
    @MockitoBean
    private StudyGroupService studyGroupService;
    @MockitoBean
    private GroupMembershipService groupMembershipService;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private MockMvc mockMvc;

    // D3, SEC-1: a route added or removed in a controller must be added to or removed from the matrix
    @Test
    void securityMatrixListsEveryControllerRoute() {
        Set<String> mapped = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            // Only the app's own controllers; Spring's /error controller isn't ours
            if (!handler.getBeanType().getPackageName().equals("com.studybuddy.controller")) {
                return;
            }
            info.getMethodsCondition().getMethods().forEach(method -> info.getPathPatternsCondition()
                    .getPatternValues().forEach(pattern -> mapped.add(method + " " + pattern)));
        });
        Set<String> listed = SecurityConfigTest.ROUTES.stream()
                .map(route -> route.method() + " " + route.pattern())
                .collect(Collectors.toCollection(TreeSet::new));

        for (String route : mapped) {
            assertThat(listed).as("Route table out of date: add %s to the SecurityConfigTest matrix and say "
                    + "which role may call it", route).contains(route);
        }
        for (String route : listed) {
            assertThat(mapped).as("Route table out of date: remove %s from the SecurityConfigTest matrix "
                    + "(no controller maps it any more)", route).contains(route);
        }
    }

    // SEC-1, N-C2: pins current behaviour, update if fixed. Nothing maps "/", so a logged-in user who types
    // the bare address gets a 404 (after login the app always goes to /home)
    @Test
    void rootAddressIsNotMapped() throws Exception {
        mockMvc.perform(get("/")).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/").with(user("alex@test.com").roles("STUDENT"))).andExpect(status().isNotFound());
        mockMvc.perform(get("/").with(user("admin@test.com").roles("ADMIN"))).andExpect(status().isNotFound());
    }
}

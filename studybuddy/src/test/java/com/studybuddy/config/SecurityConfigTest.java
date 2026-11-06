package com.studybuddy.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.studybuddy.controller.HomeController;

/**
 * The URL rules in SecurityConfig, checked on every route in the app with no services and no
 * mocks. Only HomeController is loaded (it needs nothing), so any other request that gets past
 * security finds no handler and ends as 404; one that doesn't is redirected to /login (302) or
 * refused (403). That keeps the URL rules apart from what each controller does, which the
 * controller tests check. RouteInventoryTest fails if a controller route is missing from ROUTES.
 */
@WebMvcTest(HomeController.class)
@Import(SecurityConfig.class)
class SecurityConfigTest {

    /** Who may call a route, by SecurityConfig's URL rules. */
    enum Access { PUBLIC, ANY_USER, STUDENT, ADMIN }

    /**
     * One route: its HTTP method, the pattern the controller maps and an example path for it.
     * Package-private so RouteInventoryTest can compare it with the real mappings.
     */
    record Route(String method, String pattern, String example, Access access) {
        Route(String method, String pattern, Access access) {
            this(method, pattern, pattern, access);
        }

        boolean isPost() {
            return method.equals("POST");
        }

        @Override
        public String toString() {
            return method + " " + example;
        }
    }

    static final List<Route> ROUTES = List.of(
            // AuthController
            new Route("GET", "/login", Access.PUBLIC),
            new Route("GET", "/register", Access.PUBLIC),
            new Route("POST", "/register", Access.PUBLIC),
            // HomeController
            new Route("GET", "/home", Access.ANY_USER),
            // CourseController. The POSTs are admin-only through @PreAuthorize on the controller, not
            // the URL rules, so here they count as "any logged-in user"; CourseControllerTest checks
            // that a student is refused.
            new Route("GET", "/courses", Access.ANY_USER),
            new Route("POST", "/courses", Access.ANY_USER),
            new Route("POST", "/courses/{courseId}/update", "/courses/1/update", Access.ANY_USER),
            new Route("POST", "/courses/{courseId}/delete", "/courses/1/delete", Access.ANY_USER),
            // AccountManagementController
            new Route("GET", "/admin/accounts", Access.ADMIN),
            new Route("POST", "/admin/accounts", Access.ADMIN),
            new Route("POST", "/admin/accounts/{userId}/email", "/admin/accounts/7/email", Access.ADMIN),
            new Route("POST", "/admin/accounts/{userId}/password", "/admin/accounts/7/password", Access.ADMIN),
            new Route("POST", "/admin/accounts/{userId}/deactivate", "/admin/accounts/7/deactivate", Access.ADMIN),
            new Route("POST", "/admin/accounts/{userId}/reactivate", "/admin/accounts/7/reactivate", Access.ADMIN),
            new Route("POST", "/admin/accounts/{userId}/delete", "/admin/accounts/7/delete", Access.ADMIN),
            // AdminMatchingConfigController
            new Route("GET", "/admin/matching", Access.ADMIN),
            new Route("POST", "/admin/matching", Access.ADMIN),
            // MatchingController
            new Route("GET", "/matches", Access.STUDENT),
            // MatchRequestController
            new Route("GET", "/match-requests", Access.STUDENT),
            new Route("POST", "/match-requests", Access.STUDENT),
            new Route("POST", "/match-requests/{requestId}/accept", "/match-requests/5/accept", Access.STUDENT),
            new Route("POST", "/match-requests/{requestId}/reject", "/match-requests/5/reject", Access.STUDENT),
            // StudyBuddyConnectionController
            new Route("GET", "/connections", Access.STUDENT),
            new Route("POST", "/connections/{connectionId}/end", "/connections/3/end", Access.STUDENT),
            new Route("GET", "/students/{studentId}", "/students/3", Access.STUDENT),
            // StudentProfileController
            new Route("GET", "/student/profile", Access.STUDENT),
            new Route("POST", "/student/profile", Access.STUDENT),
            new Route("POST", "/student/profile/courses", Access.STUDENT),
            new Route("POST", "/student/profile/courses/{courseId}/remove", "/student/profile/courses/1/remove",
                    Access.STUDENT),
            // StudyPreferenceController
            new Route("GET", "/student/preferences", Access.STUDENT),
            new Route("POST", "/student/preferences", Access.STUDENT),
            new Route("POST", "/student/preferences/{preferenceId}/update", "/student/preferences/4/update",
                    Access.STUDENT),
            new Route("POST", "/student/preferences/{preferenceId}/delete", "/student/preferences/4/delete",
                    Access.STUDENT),
            new Route("POST", "/student/preferences/{preferenceId}/slots", "/student/preferences/4/slots",
                    Access.STUDENT),
            new Route("POST", "/student/preferences/{preferenceId}/slots/{slotId}/remove",
                    "/student/preferences/4/slots/9/remove", Access.STUDENT),
            // StudyGroupController (the Add slot / Remove buttons post to "/groups" and "/groups/{id}/edit" too)
            new Route("GET", "/groups", Access.STUDENT),
            new Route("POST", "/groups", Access.STUDENT),
            new Route("GET", "/groups/mine", Access.STUDENT),
            new Route("GET", "/groups/new", Access.STUDENT),
            new Route("GET", "/groups/{groupId}", "/groups/1", Access.STUDENT),
            new Route("GET", "/groups/{groupId}/edit", "/groups/1/edit", Access.STUDENT),
            new Route("POST", "/groups/{groupId}/edit", "/groups/1/edit", Access.STUDENT),
            new Route("POST", "/groups/{groupId}/close", "/groups/1/close", Access.STUDENT),
            // GroupMembershipController
            new Route("POST", "/groups/{groupId}/join", "/groups/1/join", Access.STUDENT),
            new Route("POST", "/groups/{groupId}/cancel-request", "/groups/1/cancel-request", Access.STUDENT),
            new Route("POST", "/groups/{groupId}/remove-from-my-groups", "/groups/1/remove-from-my-groups",
                    Access.STUDENT),
            new Route("GET", "/groups/{groupId}/members", "/groups/1/members", Access.STUDENT),
            new Route("POST", "/groups/{groupId}/members/{membershipId}/accept", "/groups/1/members/2/accept",
                    Access.STUDENT),
            new Route("POST", "/groups/{groupId}/members/{membershipId}/reject", "/groups/1/members/2/reject",
                    Access.STUDENT),
            new Route("POST", "/groups/{groupId}/members/{membershipId}/remove", "/groups/1/members/2/remove",
                    Access.STUDENT));

    private static final RequestPostProcessor STUDENT = user("alex@test.com").roles("STUDENT");
    private static final RequestPostProcessor ADMIN = user("admin@test.com").roles("ADMIN");

    @Autowired
    private MockMvc mockMvc;

    static Stream<Route> protectedRoutes() {
        return ROUTES.stream().filter(route -> route.access() != Access.PUBLIC);
    }

    static Stream<Route> protectedPosts() {
        return protectedRoutes().filter(Route::isPost);
    }

    static Stream<Route> adminRoutes() {
        return ROUTES.stream().filter(route -> route.access() == Access.ADMIN);
    }

    static Stream<Route> studentRoutes() {
        return ROUTES.stream().filter(route -> route.access() == Access.STUDENT);
    }

    static Stream<Route> anyUserRoutes() {
        return ROUTES.stream().filter(route -> route.access() == Access.ANY_USER);
    }

    static Stream<Route> publicRoutes() {
        return ROUTES.stream().filter(route -> route.access() == Access.PUBLIC);
    }

    // D3, SEC-1, SEC-2
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedRoutes")
    void anonymousIsSentToLogin(Route route) throws Exception {
        mockMvc.perform(request(route).with(csrf())).andExpect(redirectedUrl("/login"));
    }

    // SEC-2, N-C3: the CSRF check runs before the login check, so an anonymous POST without a token
    // is refused rather than sent to /login, pins current behaviour, update if fixed
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedPosts")
    void anonymousPostWithoutCsrfIsForbidden(Route route) throws Exception {
        mockMvc.perform(request(route)).andExpect(status().isForbidden());
    }

    // D3, SEC-1, SEC-2: admin pages refuse students
    @ParameterizedTest(name = "{0}")
    @MethodSource("adminRoutes")
    void studentIsForbiddenFromAdminRoutes(Route route) throws Exception {
        mockMvc.perform(request(route).with(STUDENT).with(csrf())).andExpect(status().isForbidden());
    }

    // D3, SEC-1, SEC-2: student pages refuse admins
    @ParameterizedTest(name = "{0}")
    @MethodSource("studentRoutes")
    void adminIsForbiddenFromStudentRoutes(Route route) throws Exception {
        mockMvc.perform(request(route).with(ADMIN).with(csrf())).andExpect(status().isForbidden());
    }

    // D3, SEC-1: the right role gets through security (404 here only means no controller is loaded)
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedRoutes")
    void rightRoleGetsPastSecurity(Route route) throws Exception {
        RequestPostProcessor who = route.access() == Access.ADMIN ? ADMIN : STUDENT;

        assertPassedSecurity(mockMvc.perform(request(route).with(who).with(csrf())).andReturn().getResponse());
    }

    // D3, SEC-1: /home and /courses are for any logged-in user, admin or student
    @ParameterizedTest(name = "{0}")
    @MethodSource("anyUserRoutes")
    void anyLoggedInUserGetsPastSecurity(Route route) throws Exception {
        assertPassedSecurity(mockMvc.perform(request(route).with(STUDENT).with(csrf())).andReturn().getResponse());
        assertPassedSecurity(mockMvc.perform(request(route).with(ADMIN).with(csrf())).andReturn().getResponse());
    }

    // SEC-2: even the right role needs a CSRF token on every POST
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedPosts")
    void loggedInPostWithoutCsrfIsForbidden(Route route) throws Exception {
        RequestPostProcessor who = route.access() == Access.ADMIN ? ADMIN : STUDENT;

        mockMvc.perform(request(route).with(who)).andExpect(status().isForbidden());
    }

    // SEC-1: login and sign-up need no login; the sign-up POST still needs a CSRF token
    @ParameterizedTest(name = "{0}")
    @MethodSource("publicRoutes")
    void publicRoutesNeedNoLogin(Route route) throws Exception {
        assertPassedSecurity(mockMvc.perform(request(route).with(csrf())).andReturn().getResponse());
        if (route.isPost()) {
            mockMvc.perform(request(route)).andExpect(status().isForbidden());
        }
    }

    // SEC-1: static files and the error page are public
    @Test
    void staticFilesAndErrorPageNeedNoLogin() throws Exception {
        mockMvc.perform(get("/css/style.css")).andExpect(status().isOk());
        for (String path : List.of("/js/app.js", "/images/logo.png", "/error")) {
            assertPassedSecurity(mockMvc.perform(get(path)).andReturn().getResponse());
        }
    }

    // SEC-2: logging out is a POST with a CSRF token
    @Test
    void logoutNeedsCsrfToken() throws Exception {
        mockMvc.perform(post("/logout").with(STUDENT)).andExpect(status().isForbidden());
        mockMvc.perform(post("/logout").with(STUDENT).with(csrf())).andExpect(redirectedUrl("/login?logout"));
    }

    // SEC-3, K15: pins current behaviour, update if fixed. The H2 console chain lets anyone in, with no
    // CSRF check (404 here only because the console servlet isn't part of this test slice)
    @Test
    void h2ConsoleIsOpenWithoutLoginOrCsrf() throws Exception {
        mockMvc.perform(get("/h2-console/")).andExpect(status().isNotFound());
        mockMvc.perform(post("/h2-console/login.do")).andExpect(status().isNotFound());
        mockMvc.perform(post("/h2-console/login.do").with(STUDENT)).andExpect(status().isNotFound());
    }

    // SEC-3, K15: pins current behaviour, update if fixed. Only the console may be shown in a frame;
    // every other page keeps X-Frame-Options DENY
    @Test
    void h2ConsoleRelaxesFrameOptionsOnlyForItself() throws Exception {
        mockMvc.perform(get("/h2-console/")).andExpect(header().doesNotExist("X-Frame-Options"));
        mockMvc.perform(get("/login")).andExpect(header().string("X-Frame-Options", "DENY"));
        mockMvc.perform(get("/home").with(STUDENT)).andExpect(header().string("X-Frame-Options", "DENY"));
    }

    private static MockHttpServletRequestBuilder request(Route route) {
        return route.isPost() ? post(route.example()) : get(route.example());
    }

    // Neither refused (403) nor sent to the login page
    private static void assertPassedSecurity(MockHttpServletResponse response) {
        assertThat(response.getStatus()).isNotEqualTo(403);
        assertThat(response.getRedirectedUrl()).isNotEqualTo("/login");
    }
}

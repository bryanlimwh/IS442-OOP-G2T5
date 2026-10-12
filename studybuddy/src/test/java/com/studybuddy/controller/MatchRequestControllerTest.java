package com.studybuddy.controller;

// Mockito: when(...) sets what the mocked service returns/throws,
// verify(...) checks the controller called the service with the right arguments
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
// csrf() adds a valid CSRF token to simulated POST requests (Thymeleaf forms add it automatically)
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
// Builders for simulated HTTP requests
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
// Matchers for checking the HTTP response
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
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.studybuddy.config.SecurityConfig;
import com.studybuddy.entity.enums.MatchRequestStatus;
import com.studybuddy.exception.DuplicateMatchRequestException;
import com.studybuddy.exception.MatchRequestNotFoundException;
import com.studybuddy.exception.NotMatchRequestParticipantException;
import com.studybuddy.exception.StudentNotFoundException;
import com.studybuddy.matching.StudentPublicProfile;
import com.studybuddy.service.studybuddymatching.MatchRequestService;
import com.studybuddy.service.studybuddymatching.MatchRequestSummary;

/**
 * Web-layer test for the Thymeleaf MatchRequestController.
 *
 * Checks that each endpoint:
 * - is mapped to the correct URL and HTTP method
 * - passes the LOGGED-IN student's email (not a URL value) to the service
 * - renders the right view, or redirects back with the right success/error flash message
 * - is protected by Spring Security (login, STUDENT role and CSRF)
 *
 * The service is MOCKED, so no database is used. The business rules themselves
 * are already covered by MatchRequestServiceTest.
 */
// Starts only the web layer (DispatcherServlet, MatchRequestController, Thymeleaf).
// No services, repositories or H2 are created.
@WebMvcTest(MatchRequestController.class)
// Loads our real security rules, so tests check the same login/role/CSRF behaviour as the app
@Import(SecurityConfig.class)
// Every test runs as this logged-in student unless a test overrides it.
// principal.getName() in the controller will return this username.
@WithMockUser(username = MatchRequestControllerTest.ALEX, roles = "STUDENT")
class MatchRequestControllerTest {

    // Email of the simulated logged-in student
    static final String ALEX = "alex@test.com";

    // Page URL; every action redirects back here
    private static final String BASE = "/match-requests";

    // Sends simulated HTTP requests through Spring MVC without starting a real server
    @Autowired
    private MockMvc mockMvc;

    // Replaces the real MatchRequestService with a Mockito mock inside the Spring context.
    // Each test decides what it returns or throws.
    @MockitoBean
    private MatchRequestService service;

    // ---------- Helper method ----------
    // Builds a fake MatchRequestSummary (id 5, Alex -> Ben) for the mocked service to return
    private MatchRequestSummary summary(MatchRequestStatus status, String buddyContactNumber) {
        StudentPublicProfile alex = new StudentPublicProfile(1L, "Alex Tan", "SCIS", "Information Systems", 2, List.of("IS442"));
        StudentPublicProfile ben = new StudentPublicProfile(2L, "Ben Koh", "SCIS", "Information Systems", 3, List.of("IS442"));
        return new MatchRequestSummary(5L, alex, ben, "Study IS442 together?", status,
                LocalDateTime.now(), buddyContactNumber);
    }

    // ================= Security =================

    @Test
    @WithAnonymousUser // Overrides the class-level user: nobody is logged in
    void unauthenticatedRequestRedirectsToLogin() throws Exception {
        // Verifies Spring Security redirects to the login page instead of running the controller
        mockMvc.perform(get(BASE))
                .andExpect(status().is3xxRedirection())
                // Spring Security redirects to the relative path "/login" (no http://localhost prefix)
                .andExpect(redirectedUrl("/login"));

        // Verifies the service was never reached
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(username = "admin@test.com", roles = "ADMIN") // Overrides the class-level student
    void adminCannotAccessMatchRequestsPage() throws Exception {
        // Verifies SecurityConfig only lets STUDENT accounts use /match-requests/**
        mockMvc.perform(get(BASE))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void postWithoutCsrfTokenIsForbidden() throws Exception {
        // No .with(csrf()): Spring Security must block every state-changing form submission
        mockMvc.perform(post(BASE).param("recipientId", "2"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/5/accept"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/5/reject"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    // ================= GET /match-requests =================

    @Test
    void pageShowsLoggedInStudentsReceivedAndSentRequests() throws Exception {
        // Mock: Alex has received nothing and sent one PENDING request
        when(service.getMatchRequestByRecipient(ALEX)).thenReturn(List.of());
        when(service.getMatchRequestBySender(ALEX)).thenReturn(List.of(summary(MatchRequestStatus.PENDING, null)));

        // Verifies the page renders templates/student/match-requests.html with both lists in the Model
        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(view().name("student/match-requests"))
                .andExpect(model().attributeExists("received", "sent"));

        // Verifies both lists were looked up for the logged-in student
        verify(service).getMatchRequestByRecipient(ALEX);
        verify(service).getMatchRequestBySender(ALEX);
    }

    // ================= POST /match-requests (send) =================

    @Test
    void sendRequestRedirectsWithSuccessMessage() throws Exception {
        when(service.createRequest(ALEX, 2L, "Study?")).thenReturn(summary(MatchRequestStatus.PENDING, null));

        // Simulates submitting the "Send request" form on the matches page
        mockMvc.perform(post(BASE)
                        .param("recipientId", "2")
                        .param("message", "Study?")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("success", "Request sent."));

        // Verifies the SENDER is the logged-in student, taken from Principal
        verify(service).createRequest(ALEX, 2L, "Study?");
    }

    @Test
    void sendRequestWithoutMessageIsAllowed() throws Exception {
        // The brief says the message is optional
        when(service.createRequest(ALEX, 2L, null)).thenReturn(summary(MatchRequestStatus.PENDING, null));

        mockMvc.perform(post(BASE).param("recipientId", "2").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("success", "Request sent."));

        // Verifies a missing message reaches the service as null
        verify(service).createRequest(ALEX, 2L, null);
    }

    @Test
    void sendRequestWithoutRecipientIdReturnsBadRequest() throws Exception {
        // recipientId is a required @RequestParam, so Spring rejects the request before the controller runs
        mockMvc.perform(post(BASE).param("message", "Study?").with(csrf()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void duplicateRequestShowsServiceMessage() throws Exception {
        // Mock: the service detects an existing PENDING request
        DuplicateMatchRequestException e = DuplicateMatchRequestException.alreadySent(1L, 2L);
        when(service.createRequest(ALEX, 2L, null)).thenThrow(e);

        // Verifies the exception is caught and shown as a flash error, not an error page
        mockMvc.perform(post(BASE).param("recipientId", "2").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", e.getMessage()));
    }

    @Test
    void requestToSelfShowsServiceMessage() throws Exception {
        // Mock: the service rejects a request to yourself
        when(service.createRequest(ALEX, 1L, null)).thenThrow(new IllegalArgumentException("Same sender and recipient"));

        mockMvc.perform(post(BASE).param("recipientId", "1").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "Same sender and recipient"));
    }

    @Test
    void requestToUnknownStudentShowsError() throws Exception {
        // Mock: the recipient ID doesn't exist
        when(service.createRequest(ALEX, 999L, null)).thenThrow(new StudentNotFoundException(999L));

        mockMvc.perform(post(BASE).param("recipientId", "999").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "That student no longer exists."));
    }

    // ================= POST /match-requests/{id}/accept =================

    @Test
    void acceptRedirectsWithSuccessMessage() throws Exception {
        when(service.acceptRequest(ALEX, 5L)).thenReturn(summary(MatchRequestStatus.ACCEPTED, "92345678"));

        // Verifies {requestId} in the URL is read as 5 and the student is redirected back with a success message
        mockMvc.perform(post(BASE + "/5/accept").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("success", "Request accepted. Their contact number is now visible."));

        verify(service).acceptRequest(ALEX, 5L);
    }

    @Test
    void acceptUnknownRequestShowsError() throws Exception {
        when(service.acceptRequest(ALEX, 5L)).thenThrow(new MatchRequestNotFoundException(5L));

        mockMvc.perform(post(BASE + "/5/accept").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "That request no longer exists."));
    }

    @Test
    void acceptByNonRecipientShowsError() throws Exception {
        when(service.acceptRequest(ALEX, 5L)).thenThrow(new NotMatchRequestParticipantException(5L, 1L));

        mockMvc.perform(post(BASE + "/5/accept").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "Only the student who received this request can accept it."));
    }

    @Test
    void acceptNonPendingRequestShowsEntityMessage() throws Exception {
        // Mock: MatchRequest.accept() refuses because the request is no longer PENDING
        when(service.acceptRequest(ALEX, 5L)).thenThrow(new IllegalStateException("Only PENDING requests can be accepted"));

        mockMvc.perform(post(BASE + "/5/accept").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "Only PENDING requests can be accepted"));
    }

    // ================= POST /match-requests/{id}/reject =================

    @Test
    void rejectRedirectsWithSuccessMessage() throws Exception {
        when(service.rejectRequest(ALEX, 5L)).thenReturn(summary(MatchRequestStatus.REJECTED, null));

        mockMvc.perform(post(BASE + "/5/reject").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("success", "Request declined."));

        verify(service).rejectRequest(ALEX, 5L);
    }

    @Test
    void rejectUnknownRequestShowsError() throws Exception {
        when(service.rejectRequest(ALEX, 5L)).thenThrow(new MatchRequestNotFoundException(5L));

        mockMvc.perform(post(BASE + "/5/reject").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "That request no longer exists."));
    }

    @Test
    void rejectByNonRecipientShowsError() throws Exception {
        when(service.rejectRequest(ALEX, 5L)).thenThrow(new NotMatchRequestParticipantException(5L, 1L));

        mockMvc.perform(post(BASE + "/5/reject").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "Only the student who received this request can decline it."));
    }

    @Test
    void rejectNonPendingRequestShowsEntityMessage() throws Exception {
        // Mock: MatchRequest.reject() refuses because the request is no longer PENDING
        when(service.rejectRequest(ALEX, 5L)).thenThrow(new IllegalStateException("Only PENDING requests can be rejected"));

        mockMvc.perform(post(BASE + "/5/reject").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "Only PENDING requests can be rejected"));
    }
}

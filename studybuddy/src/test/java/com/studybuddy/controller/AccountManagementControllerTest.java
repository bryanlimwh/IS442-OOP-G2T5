package com.studybuddy.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.studybuddy.config.SecurityConfig;
import com.studybuddy.entity.enums.Role;
import com.studybuddy.exception.DuplicateEmailException;
import com.studybuddy.exception.InvalidAccountDetailsException;
import com.studybuddy.exception.LastAdminException;
import com.studybuddy.exception.UserNotFoundException;
import com.studybuddy.service.admin.AccountManagementService;
import com.studybuddy.service.admin.AccountStatus;

/**
 * Web-layer test for the admin account page. The service is mocked, so no database is used;
 * the account rules themselves are covered by AccountManagementServiceTest.
 * Checks the security rules, the service call each form makes, and the redirect and
 * one-time message that follow.
 */
@WebMvcTest(AccountManagementController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = AccountManagementControllerTest.ADMIN, roles = "ADMIN")
class AccountManagementControllerTest {

    static final String ADMIN = "admin@test.com";
    private static final String BASE = "/admin/accounts";
    private static final String DUPLICATE_EMAIL_MESSAGE = "Another account already uses that email.";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountManagementService service;

    // ---------- Helpers ----------

    private AccountStatus adminAccount(Long id, String email) {
        return new AccountStatus(id, email, "Admin: " + email, Role.ADMIN, true, LocalDateTime.now(), null);
    }

    private AccountStatus studentAccount(Long id, String email) {
        return new AccountStatus(id, email, "Alex Tan", Role.STUDENT, true, LocalDateTime.now(),
                new AccountStatus.StudentUsage(4, 4, 1, 1, 2));
    }

    // ================= Security =================

    // D3, SEC-2
    @Test
    @WithAnonymousUser
    void anonymousIsRedirectedToLogin() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        mockMvc.perform(post(BASE + "/7/delete").with(csrf()))
                .andExpect(redirectedUrl("/login"));

        verifyNoInteractions(service);
    }

    // A1, A2, D3, SEC-1
    @Test
    @WithMockUser(username = "alex@test.com", roles = "STUDENT")
    void studentIsForbiddenFromEveryRoute() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE).param("email", "x@test.com").param("password", "password123").with(csrf()))
                .andExpect(status().isForbidden());
        for (String action : List.of("email", "password", "deactivate", "reactivate", "delete")) {
            mockMvc.perform(post(BASE + "/7/" + action).with(csrf()))
                    .andExpect(status().isForbidden());
        }

        verifyNoInteractions(service);
    }

    // SEC-2
    @Test
    void postWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post(BASE).param("email", "x@test.com").param("password", "password123"))
                .andExpect(status().isForbidden());
        for (String action : List.of("email", "password", "deactivate", "reactivate", "delete")) {
            mockMvc.perform(post(BASE + "/7/" + action))
                    .andExpect(status().isForbidden());
        }

        verifyNoInteractions(service);
    }

    // ================= GET /admin/accounts =================

    // A2, ACC-7
    @Test
    void pageShowsAccountsAndMarksLoggedInAdmin() throws Exception {
        List<AccountStatus> accounts = List.of(adminAccount(1L, ADMIN), studentAccount(2L, "alex@test.com"));
        when(service.getAllUsers()).thenReturn(accounts);

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/accounts"))
                // AccountStatus records, never User entities, so no password hash can reach the page
                .andExpect(model().attribute("accounts", everyItem(instanceOf(AccountStatus.class))))
                .andExpect(model().attribute("accounts", accounts))
                .andExpect(model().attribute("currentEmail", ADMIN))
                .andExpect(content().string(containsString("(you)")))
                .andExpect(content().string(containsString("Requests sent 1 · received 2")));
    }

    // ================= POST /admin/accounts (create admin) =================

    // A1
    @Test
    void createAdminRedirectsWithSuccess() throws Exception {
        when(service.createAdminAccount("new.admin@test.com", "password123"))
                .thenReturn(adminAccount(5L, "new.admin@test.com"));

        mockMvc.perform(post(BASE).param("email", "new.admin@test.com").param("password", "password123").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("success",
                        "Admin account new.admin@test.com has been created. It can log in straight away."));

        verify(service).createAdminAccount("new.admin@test.com", "password123");
    }

    // A1, ACC-2
    @Test
    void createAdminWithInvalidDetailsRefillsEmailButNotPassword() throws Exception {
        when(service.createAdminAccount("new.admin@test.com", "short"))
                .thenThrow(new InvalidAccountDetailsException("Passwords must be at least 8 characters."));

        mockMvc.perform(post(BASE).param("email", "new.admin@test.com").param("password", "short").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "Passwords must be at least 8 characters."))
                .andExpect(flash().attribute("newAdminEmail", "new.admin@test.com"))
                // Only the error and the email: the password is never sent back
                .andExpect(flash().attributeCount(2));
    }

    // A1, ACC-3
    @Test
    void createAdminWithDuplicateEmailShowsMessage() throws Exception {
        when(service.createAdminAccount("alex@test.com", "password123"))
                .thenThrow(new DuplicateEmailException("alex@test.com"));

        mockMvc.perform(post(BASE).param("email", "alex@test.com").param("password", "password123").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "Another account already uses alex@test.com."))
                .andExpect(flash().attribute("newAdminEmail", "alex@test.com"));
    }

    // A1, ACC-3, K3: pins current behaviour, update if fixed. Every database refusal is reported
    // as a duplicate email, including values too long for their column.
    @Test
    void createAdminDatabaseRefusalShowsDuplicateEmailMessage() throws Exception {
        when(service.createAdminAccount("new.admin@test.com", "password123"))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        mockMvc.perform(post(BASE).param("email", "new.admin@test.com").param("password", "password123").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", DUPLICATE_EMAIL_MESSAGE))
                .andExpect(flash().attribute("newAdminEmail", "new.admin@test.com"));
    }

    // A1, ACC-1
    @Test
    void createAdminWithMissingFieldsSendsBlanksToService() throws Exception {
        when(service.createAdminAccount("", ""))
                .thenThrow(new InvalidAccountDetailsException("Enter an email address."));

        mockMvc.perform(post(BASE).with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "Enter an email address."));

        verify(service).createAdminAccount("", "");
    }

    // ================= POST /admin/accounts/{id}/email =================

    // A1
    @Test
    void updateEmailRedirectsWithSuccess() throws Exception {
        when(service.updateEmail(7L, "alex.new@test.com")).thenReturn(studentAccount(7L, "alex.new@test.com"));

        mockMvc.perform(post(BASE + "/7/email").param("email", "alex.new@test.com").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("success", "The account now logs in with alex.new@test.com."));

        verify(service).updateEmail(7L, "alex.new@test.com");
    }

    // A1
    @Test
    void updateEmailOfMissingAccountShowsError() throws Exception {
        when(service.updateEmail(7L, "alex.new@test.com")).thenThrow(new UserNotFoundException(7L));

        mockMvc.perform(post(BASE + "/7/email").param("email", "alex.new@test.com").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "That account no longer exists."));
    }

    // A1, ACC-1, ACC-3
    @Test
    void updateEmailRefusedByServiceShowsItsMessage() throws Exception {
        when(service.updateEmail(7L, "alex@")).thenThrow(
                new InvalidAccountDetailsException("Enter a valid email address, like name@example.com."));
        when(service.updateEmail(7L, "ben@test.com")).thenThrow(new DuplicateEmailException("ben@test.com"));

        mockMvc.perform(post(BASE + "/7/email").param("email", "alex@").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "Enter a valid email address, like name@example.com."));
        mockMvc.perform(post(BASE + "/7/email").param("email", "ben@test.com").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "Another account already uses ben@test.com."));
    }

    // A1, ACC-3, K3: pins current behaviour, update if fixed
    @Test
    void updateEmailDatabaseRefusalShowsDuplicateEmailMessage() throws Exception {
        when(service.updateEmail(7L, "ben@test.com")).thenThrow(new DataIntegrityViolationException("unique constraint"));

        mockMvc.perform(post(BASE + "/7/email").param("email", "ben@test.com").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", DUPLICATE_EMAIL_MESSAGE));
    }

    // ================= POST /admin/accounts/{id}/password =================

    // A1
    @Test
    void resetPasswordRedirectsWithSuccess() throws Exception {
        when(service.resetPassword(7L, "brand-new-pass")).thenReturn(studentAccount(7L, "alex@test.com"));

        mockMvc.perform(post(BASE + "/7/password").param("password", "brand-new-pass").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("success", "The password for alex@test.com has been changed."));

        verify(service).resetPassword(7L, "brand-new-pass");
    }

    // A1
    @Test
    void resetPasswordOfMissingAccountShowsError() throws Exception {
        when(service.resetPassword(7L, "brand-new-pass")).thenThrow(new UserNotFoundException(7L));

        mockMvc.perform(post(BASE + "/7/password").param("password", "brand-new-pass").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "That account no longer exists."));
    }

    // A1, ACC-2
    @Test
    void resetPasswordWithInvalidPasswordShowsMessage() throws Exception {
        when(service.resetPassword(7L, "short"))
                .thenThrow(new InvalidAccountDetailsException("Passwords must be at least 8 characters."));

        mockMvc.perform(post(BASE + "/7/password").param("password", "short").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "Passwords must be at least 8 characters."));
    }

    // ================= POST /admin/accounts/{id}/deactivate and /reactivate =================

    // A1, ACC-6
    @Test
    void deactivateRedirectsWithSuccess() throws Exception {
        when(service.deactivateUser(7L)).thenReturn(studentAccount(7L, "alex@test.com"));

        mockMvc.perform(post(BASE + "/7/deactivate").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("success", "alex@test.com has been deactivated and can no longer log in."));

        verify(service).deactivateUser(7L);
    }

    // A1
    @Test
    void deactivateMissingAccountShowsError() throws Exception {
        when(service.deactivateUser(7L)).thenThrow(new UserNotFoundException(7L));

        mockMvc.perform(post(BASE + "/7/deactivate").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "That account no longer exists."));
    }

    // A1, ACC-4
    @Test
    void deactivateLastAdminShowsMessage() throws Exception {
        when(service.deactivateUser(1L)).thenThrow(new LastAdminException());

        mockMvc.perform(post(BASE + "/1/deactivate").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error",
                        "This is the last active admin account, so it can't be deactivated."));
    }

    // A1
    @Test
    void reactivateRedirectsWithSuccess() throws Exception {
        when(service.reactivateUser(7L)).thenReturn(studentAccount(7L, "alex@test.com"));

        mockMvc.perform(post(BASE + "/7/reactivate").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("success", "alex@test.com has been reactivated."));

        verify(service).reactivateUser(7L);
    }

    // A1
    @Test
    void reactivateMissingAccountShowsError() throws Exception {
        when(service.reactivateUser(7L)).thenThrow(new UserNotFoundException(7L));

        mockMvc.perform(post(BASE + "/7/reactivate").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "That account no longer exists."));
    }

    // ================= POST /admin/accounts/{id}/delete =================

    // A1
    @Test
    void deleteRedirectsWithSuccess() throws Exception {
        mockMvc.perform(post(BASE + "/7/delete").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("success", "The account has been deleted."));

        verify(service).deleteUser(7L);
    }

    // A1
    @Test
    void deleteMissingAccountShowsError() throws Exception {
        doThrow(new UserNotFoundException(7L)).when(service).deleteUser(7L);

        mockMvc.perform(post(BASE + "/7/delete").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "That account no longer exists."));
    }

    // A1, ACC-4
    @Test
    void deleteLastAdminShowsMessage() throws Exception {
        doThrow(new LastAdminException()).when(service).deleteUser(1L);

        mockMvc.perform(post(BASE + "/1/delete").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "This is the last active admin account, so it can't be deleted."));
    }

    // A1, ACC-5
    @Test
    void deleteStudentWithActivityShowsDeactivateInstead() throws Exception {
        doThrow(new DataIntegrityViolationException("foreign key")).when(service).deleteUser(7L);

        mockMvc.perform(post(BASE + "/7/delete").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "This student still has match requests, study groups or "
                        + "study buddies, so the account can't be deleted. Deactivate it instead."));
    }
}

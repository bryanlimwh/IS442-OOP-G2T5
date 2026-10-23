package com.studybuddy.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.logout;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.studybuddy.config.SecurityConfig;
import com.studybuddy.entity.Admin;
import com.studybuddy.entity.Student;
import com.studybuddy.exception.DuplicateEmailException;
import com.studybuddy.exception.InvalidAccountDetailsException;
import com.studybuddy.repository.UserRepository;
import com.studybuddy.service.auth.AuthService;
import com.studybuddy.service.student.StudentProfileService;

/**
 * Web-layer test for the login and sign-up pages.
 * <p>
 * Sign-up: StudentProfileService is mocked; its rules are covered by StudentProfileServiceTest.
 * <p>
 * Login: Spring Security handles POST /login itself. The real AuthService and SecurityConfig's
 * BCrypt encoder are used, and only UserRepository is mocked, so these tests go through the
 * same password and "account disabled" checks as the app.
 */
@WebMvcTest(AuthController.class)
@Import({ SecurityConfig.class, AuthService.class })
class AuthControllerTest {

    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private StudentProfileService studentProfileService;
    @MockitoBean
    private UserRepository userRepository;

    // ---------- Helpers ----------

    // The sign-up form as a student would fill it in
    private MockHttpServletRequestBuilder signUpForm() {
        return signUpForm("1", "98765432");
    }

    // The same form with the year and contact number typed as given
    private MockHttpServletRequestBuilder signUpForm(String yearOfStudy, String contactNumber) {
        return post("/register").with(csrf())
                .param("email", "new@test.com")
                .param("password", PASSWORD)
                .param("name", "New Student")
                .param("school", "SCIS")
                .param("programme", "Information Systems")
                .param("yearOfStudy", yearOfStudy)
                .param("contactNumber", contactNumber);
    }

    private void givenSignUpFails(RuntimeException e) {
        when(studentProfileService.createProfile(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString())).thenThrow(e);
    }

    private Student student(String email, boolean active) {
        Student student = new Student(email, passwordEncoder.encode(PASSWORD), "Alex Tan", "SCIS",
                "Information Systems", 2, "91234567");
        student.setActive(active);
        return student;
    }

    // ================= Public pages =================

    // S1, SEC-1
    @Test
    void loginAndRegisterPagesArePublic() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"));
        mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/register"));
    }

    // SEC-2
    @Test
    void registerWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(post("/register").param("email", "new@test.com").param("password", PASSWORD))
                .andExpect(status().isForbidden());

        verifyNoInteractions(studentProfileService);
    }

    // ================= POST /register =================

    // S1
    @Test
    void registerPassesEveryFieldAndRedirectsToLogin() throws Exception {
        mockMvc.perform(signUpForm())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?registered"));

        verify(studentProfileService).createProfile("new@test.com", PASSWORD, "New Student", "SCIS",
                "Information Systems", 1, "98765432");
    }

    // S1, ACC-2
    @Test
    void registerErrorRefillsEverythingButPassword() throws Exception {
        givenSignUpFails(new InvalidAccountDetailsException("Passwords must be at least 8 characters."));

        mockMvc.perform(signUpForm())
                .andExpect(status().isOk())
                .andExpect(view().name("auth/register"))
                .andExpect(model().attribute("error", "Passwords must be at least 8 characters."))
                .andExpect(model().attribute("email", "new@test.com"))
                .andExpect(model().attribute("name", "New Student"))
                .andExpect(model().attribute("school", "SCIS"))
                .andExpect(model().attribute("programme", "Information Systems"))
                .andExpect(model().attribute("yearOfStudy", 1))
                .andExpect(model().attribute("contactNumber", "98765432"))
                .andExpect(model().attributeDoesNotExist("password"));
    }

    // S1, ACC-3
    @Test
    void registerDuplicateEmailShowsMessage() throws Exception {
        givenSignUpFails(new DuplicateEmailException("new@test.com"));

        mockMvc.perform(signUpForm())
                .andExpect(view().name("auth/register"))
                .andExpect(model().attribute("error", "Another account already uses new@test.com."));
    }

    // S1, PRF-1
    @Test
    void registerInvalidProfileShowsMessage() throws Exception {
        givenSignUpFails(new IllegalArgumentException("Contact number must be 8 digits"));

        mockMvc.perform(signUpForm())
                .andExpect(view().name("auth/register"))
                .andExpect(model().attribute("error", "Contact number must be 8 digits"));
    }

    // S1, ACC-3, K3: pins current behaviour, update if fixed. Every database refusal is reported
    // as a duplicate email, including a name, school or programme too long for its column.
    @Test
    void registerDatabaseRefusalShowsDuplicateEmailMessage() throws Exception {
        givenSignUpFails(new DataIntegrityViolationException("value too long"));

        mockMvc.perform(signUpForm())
                .andExpect(view().name("auth/register"))
                .andExpect(model().attribute("error", "Another account already uses that email."));
    }

    // S1, PRF-1
    @Test
    void blankYearOfStudyIsSentAsZeroAndNotRefilled() throws Exception {
        givenSignUpFails(new IllegalArgumentException("Year of study must be between 1 and 5"));

        mockMvc.perform(signUpForm("", "98765432"))
                .andExpect(model().attribute("error", "Year of study must be between 1 and 5"))
                .andExpect(model().attribute("yearOfStudy", nullValue()));

        verify(studentProfileService).createProfile("new@test.com", PASSWORD, "New Student", "SCIS",
                "Information Systems", 0, "98765432");
    }

    // S1, PRF-1, K11: pins current behaviour, update if fixed. A non-numeric year is refused
    // by Spring before the controller runs, so the student gets a raw 400 instead of the form.
    @Test
    void nonNumericYearOfStudyIsBadRequest() throws Exception {
        mockMvc.perform(signUpForm("two", "98765432"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(studentProfileService);
    }

    // S1, PRF-1, K10: pins current behaviour, update if fixed. The contact number is passed on
    // exactly as typed (the profile page strips it, sign-up doesn't).
    @Test
    void contactNumberIsPassedOnAsTyped() throws Exception {
        mockMvc.perform(signUpForm("1", " 98765432 "))
                .andExpect(redirectedUrl("/login?registered"));

        verify(studentProfileService).createProfile("new@test.com", PASSWORD, "New Student", "SCIS",
                "Information Systems", 1, " 98765432 ");
    }

    // ================= Login and logout (Spring Security + AuthService) =================

    // ACC-6
    @Test
    void activeStudentCanLogIn() throws Exception {
        when(userRepository.findByEmail("alex@test.com")).thenReturn(student("alex@test.com", true));

        mockMvc.perform(formLogin("/login").userParameter("email").user("alex@test.com").password(PASSWORD))
                .andExpect(redirectedUrl("/home"))
                .andExpect(authenticated().withUsername("alex@test.com").withRoles("STUDENT"));
    }

    // ACC-6
    @Test
    void adminLogsInWithAdminRole() throws Exception {
        when(userRepository.findByEmail("admin@test.com"))
                .thenReturn(new Admin("admin@test.com", passwordEncoder.encode(PASSWORD)));

        mockMvc.perform(formLogin("/login").userParameter("email").user("admin@test.com").password(PASSWORD))
                .andExpect(redirectedUrl("/home"))
                .andExpect(authenticated().withUsername("admin@test.com").withRoles("ADMIN"));
    }

    // ACC-6
    @Test
    void wrongPasswordIsRefused() throws Exception {
        when(userRepository.findByEmail("alex@test.com")).thenReturn(student("alex@test.com", true));

        mockMvc.perform(formLogin("/login").userParameter("email").user("alex@test.com").password("wrongpass1"))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
    }

    // A1, ACC-6
    @Test
    void deactivatedAccountCannotLogIn() throws Exception {
        when(userRepository.findByEmail("alex@test.com")).thenReturn(student("alex@test.com", false));

        // Right password, but the account is deactivated
        mockMvc.perform(formLogin("/login").userParameter("email").user("alex@test.com").password(PASSWORD))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
    }

    // SEC-2
    @Test
    @WithMockUser(username = "alex@test.com", roles = "STUDENT")
    void logoutRedirectsToLoginPage() throws Exception {
        mockMvc.perform(logout())
                .andExpect(redirectedUrl("/login?logout"))
                .andExpect(unauthenticated());
    }
}

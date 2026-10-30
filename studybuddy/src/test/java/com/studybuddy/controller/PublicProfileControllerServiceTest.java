package com.studybuddy.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.studybuddy.config.SecurityConfig;
import com.studybuddy.entity.Student;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.repository.UserRepository;
import com.studybuddy.service.auth.AccountCredentials;
import com.studybuddy.service.student.StudentProfileService;

/**
 * The public profile page with the REAL StudentProfileService (unlike PublicProfileControllerTest and
 * PublicProfileControllerGapTest, which mock it), so the service's own rules reach the page. Only the
 * repositories are mocked: no database. Logged in as student alex@test.com; Ben Koh is student 2.
 */
@WebMvcTest(PublicProfileController.class)
@Import({ SecurityConfig.class, StudentProfileService.class, AccountCredentials.class })
@WithMockUser(username = "alex@test.com", roles = "STUDENT")
class PublicProfileControllerServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StudentRepository studentRepository;
    @MockitoBean
    private CourseRepository courseRepository;
    @MockitoBean
    private UserRepository userRepository;

    // S6, TD-6: a deactivated student's profile goes back to Find study buddies with the same message as an
    // unknown id (team decision 2026-10-30)
    @Test
    void deactivatedStudentsProfileRedirectsLikeAnUnknownId() throws Exception {
        Student ben = ben();
        ben.setActive(false);
        when(studentRepository.findById(2L)).thenReturn(Optional.of(ben));

        mockMvc.perform(get("/students/2"))
                .andExpect(redirectedUrl("/matches"))
                .andExpect(flash().attribute("error", "That student's profile no longer exists."));
    }

    // S6: the same student, still active, gets the page
    @Test
    void activeStudentsProfileIsShown() throws Exception {
        when(studentRepository.findById(2L)).thenReturn(Optional.of(ben()));

        mockMvc.perform(get("/students/2"))
                .andExpect(status().isOk())
                .andExpect(view().name("student/public-profile"));
    }

    private static Student ben() {
        Student ben = new Student("ben@test.com", "dummyHash", "Ben Koh", "SCIS", "Information Systems", 3,
                "92345678");
        ReflectionTestUtils.setField(ben, "userId", 2L);
        return ben;
    }
}

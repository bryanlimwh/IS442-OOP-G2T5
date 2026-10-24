package com.studybuddy.controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.studybuddy.config.SecurityConfig;
import com.studybuddy.service.course.CourseService;
import com.studybuddy.service.student.StudentProfileService;

/**
 * Known bugs in StudentProfileController. Each test describes what SHOULD happen and fails on
 * today's code, so it is @Disabled to keep the main suite green. Remove @Disabled to check a fix.
 */
@WebMvcTest(StudentProfileController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "alex@test.com", roles = "STUDENT")
class StudentProfileControllerKnownBugTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StudentProfileService studentProfileService;
    @MockitoBean
    private CourseService courseService;

    // S1, PRF-1, K4
    @Test
    @Disabled("KNOWN BUG K4: a profile value the database refuses (e.g. a name over 255 characters) should "
            + "bring the student back to the profile page with an error message, not a 500 error page")
    void profileValueTheDatabaseRefusesShowsAnError() throws Exception {
        String longName = "A".repeat(256);
        when(studentProfileService.updateProfile("alex@test.com", longName, "SCIS", "IS", 2, "91234567"))
                .thenThrow(new DataIntegrityViolationException("Value too long for column NAME"));

        mockMvc.perform(post("/student/profile").param("name", longName).param("school", "SCIS")
                        .param("programme", "IS").param("yearOfStudy", "2").param("contactNumber", "91234567")
                        .with(csrf()))
                .andExpect(redirectedUrl("/student/profile"))
                .andExpect(flash().attributeExists("error"));
    }
}

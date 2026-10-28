package com.studybuddy.controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

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
 * Regression tests for bugs fixed in StudentProfileController (they were @Disabled known-bug tests
 * until the fix; the class keeps its name so the workbook Test IDs stay the same).
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

    // S1, PRF-1, K4: fixed 2026-10-28. A name, school or programme over 255 characters brings the student
    // back to the profile page with a message, not a 500 error page; the service isn't called
    @Test
    void profileValueTheDatabaseRefusesShowsAnError() throws Exception {
        String longName = "A".repeat(256);
        when(studentProfileService.updateProfile("alex@test.com", longName, "SCIS", "IS", 2, "91234567"))
                .thenThrow(new DataIntegrityViolationException("Value too long for column NAME"));

        mockMvc.perform(post("/student/profile").param("name", longName).param("school", "SCIS")
                        .param("programme", "IS").param("yearOfStudy", "2").param("contactNumber", "91234567")
                        .with(csrf()))
                .andExpect(redirectedUrl("/student/profile"))
                .andExpect(flash().attribute("error", "Name, school and programme can be at most 255 characters."));
    }
}

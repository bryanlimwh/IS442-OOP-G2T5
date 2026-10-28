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

/**
 * Regression tests for bugs fixed in CourseController (they were @Disabled known-bug tests until
 * the fix; the class keeps its name so the workbook Test IDs stay the same).
 * <p>
 * The mocked service throws what the real one does for a code over 255 characters
 * (see CourseServiceTest.courseCodeOver255CharactersFailsInDatabase), so the tests also show the
 * controller now stops the value before the service is called.
 */
@WebMvcTest(CourseController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "admin@test.com", roles = "ADMIN")
class CourseControllerKnownBugTest {

    private static final String TOO_LONG = "C".repeat(256);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CourseService courseService;

    // CRS-1, K4: fixed 2026-10-28. A code or name over 255 characters gets a message, not a server error
    @Test
    void addCourseTheDatabaseRefusesShowsAnError() throws Exception {
        when(courseService.createCourse(TOO_LONG, "Test Course"))
                .thenThrow(new DataIntegrityViolationException("value too long"));

        mockMvc.perform(post("/courses").param("courseCode", TOO_LONG).param("courseName", "Test Course").with(csrf()))
                .andExpect(redirectedUrl("/courses"))
                .andExpect(flash().attribute("error", "Course code and course name can be at most 255 characters."));
    }

    // CRS-1, K4: fixed 2026-10-28. Renaming to over 255 characters returns to the edit form with a message
    @Test
    void updateCourseTheDatabaseRefusesShowsAnError() throws Exception {
        when(courseService.updateCourse(5L, TOO_LONG, "Test Course"))
                .thenThrow(new DataIntegrityViolationException("value too long"));

        mockMvc.perform(post("/courses/5/update").param("courseCode", TOO_LONG).param("courseName", "Test Course")
                .with(csrf()))
                .andExpect(redirectedUrl("/courses?edit=5"))
                .andExpect(flash().attribute("error", "Course code and course name can be at most 255 characters."));
    }
}

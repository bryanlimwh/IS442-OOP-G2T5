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

/**
 * Known bugs in CourseController. Each test describes what SHOULD happen and fails on today's
 * code, so it is @Disabled to keep the main suite green. Remove @Disabled to check a fix.
 * <p>
 * The mocked service throws what the real one does for a code over 255 characters
 * (see CourseServiceTest.courseCodeOver255CharactersFailsInDatabase).
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

    // CRS-1, K4
    @Test
    @Disabled("KNOWN BUG K4: adding a course the database can't store (code or name over 255 characters) "
            + "should redirect to /courses with an error message, not end in a server error")
    void addCourseTheDatabaseRefusesShowsAnError() throws Exception {
        when(courseService.createCourse(TOO_LONG, "Test Course"))
                .thenThrow(new DataIntegrityViolationException("value too long"));

        mockMvc.perform(post("/courses").param("courseCode", TOO_LONG).param("courseName", "Test Course").with(csrf()))
                .andExpect(redirectedUrl("/courses"))
                .andExpect(flash().attributeExists("error"));
    }

    // CRS-1, K4
    @Test
    @Disabled("KNOWN BUG K4: renaming a course to a value the database can't store (over 255 characters) "
            + "should redirect with an error message, not end in a server error")
    void updateCourseTheDatabaseRefusesShowsAnError() throws Exception {
        when(courseService.updateCourse(5L, TOO_LONG, "Test Course"))
                .thenThrow(new DataIntegrityViolationException("value too long"));

        mockMvc.perform(post("/courses/5/update").param("courseCode", TOO_LONG).param("courseName", "Test Course")
                .with(csrf()))
                .andExpect(flash().attributeExists("error"));
    }
}

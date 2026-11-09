package com.studybuddy.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.studybuddy.config.SecurityConfig;
import com.studybuddy.entity.Course;
import com.studybuddy.exception.CourseNotFoundException;
import com.studybuddy.service.course.CourseService;

/**
 * Web-layer test for the course list. Any logged-in user can view it; adding, editing and
 * deleting are admin-only through @PreAuthorize on the controller methods.
 * The service is mocked, so no database is used (see CourseServiceTest).
 */
@WebMvcTest(CourseController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "admin@test.com", roles = "ADMIN")
class CourseControllerTest {

    private static final String BASE = "/courses";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CourseService courseService;

    // A course as the database would return it, with its id set
    private Course course(long id, String code, String name) {
        Course course = new Course(code, name);
        ReflectionTestUtils.setField(course, "courseId", id);
        return course;
    }

    // ================= Security =================

    // CRS-1, SEC-2
    @Test
    @WithAnonymousUser
    void anonymousIsRedirectedToLogin() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(redirectedUrl("/login"));
        mockMvc.perform(post(BASE).param("courseCode", "IS999").param("courseName", "Test Course").with(csrf()))
                .andExpect(redirectedUrl("/login"));

        verifyNoInteractions(courseService);
    }

    // CRS-1
    @Test
    @WithMockUser(username = "alex@test.com", roles = "STUDENT")
    void studentSeesListWithoutAdminTools() throws Exception {
        when(courseService.getAllCourses()).thenReturn(List.of(course(1L, "IS442", "Object Oriented Programming")));

        // ?edit is ignored for a student
        mockMvc.perform(get(BASE).param("edit", "1"))
                .andExpect(status().isOk())
                .andExpect(view().name("courses"))
                .andExpect(model().attribute("isAdmin", false))
                .andExpect(model().attribute("editingId", nullValue()))
                .andExpect(content().string(containsString("IS442")))
                .andExpect(content().string(not(containsString("edit-code-1"))))
                .andExpect(content().string(not(containsString("new-courseCode"))));
    }

    // CRS-1
    @Test
    void adminSeesEditFormForChosenCourse() throws Exception {
        when(courseService.getAllCourses()).thenReturn(List.of(course(1L, "IS442", "Object Oriented Programming")));

        mockMvc.perform(get(BASE).param("edit", "1"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("isAdmin", true))
                .andExpect(model().attribute("editingId", 1L))
                .andExpect(content().string(containsString("edit-code-1")))
                .andExpect(content().string(containsString("new-courseCode")));
    }

    // CRS-1, D3
    @Test
    @WithMockUser(username = "alex@test.com", roles = "STUDENT")
    void studentCannotAddUpdateOrDeleteCourses() throws Exception {
        mockMvc.perform(post(BASE).param("courseCode", "IS999").param("courseName", "Test Course").with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/1/update").param("courseCode", "IS999").param("courseName", "Test Course")
                .with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/1/delete").with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(courseService);
    }

    // SEC-2
    @Test
    void postWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post(BASE).param("courseCode", "IS999").param("courseName", "Test Course"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/1/update").param("courseCode", "IS999").param("courseName", "Test Course"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/1/delete"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(courseService);
    }

    // ================= POST /courses (add) =================

    // CRS-1
    @Test
    void addCourseStripsAndRedirectsWithSuccess() throws Exception {
        when(courseService.createCourse("IS999", "Test Course")).thenReturn(course(11L, "IS999", "Test Course"));

        mockMvc.perform(post(BASE).param("courseCode", "  IS999 ").param("courseName", " Test Course ").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("success", "IS999 has been added."));

        verify(courseService).createCourse("IS999", "Test Course");
    }

    // CRS-1
    @Test
    void addCourseWithBlankFieldSavesNothing() throws Exception {
        mockMvc.perform(post(BASE).param("courseCode", "   ").param("courseName", "Test Course").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "Enter both a course code and a course name."));
        // Missing name
        mockMvc.perform(post(BASE).param("courseCode", "IS999").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "Enter both a course code and a course name."));

        verifyNoInteractions(courseService);
    }

    // CRS-1, K4: a course name over 255 characters is refused like an over-long code, on add and on update
    @Test
    void overLongCourseNameIsRefused() throws Exception {
        String name = "N".repeat(256);
        String tooLong = "Course code and course name can be at most 255 characters.";

        mockMvc.perform(post(BASE).param("courseCode", "IS999").param("courseName", name).with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", tooLong));
        mockMvc.perform(post(BASE + "/5/update").param("courseCode", "IS443").param("courseName", name).with(csrf()))
                .andExpect(redirectedUrl(BASE + "?edit=5"))
                .andExpect(flash().attribute("error", tooLong));

        verifyNoInteractions(courseService);
    }

    // ================= POST /courses/{id}/update =================

    // CRS-1
    @Test
    void updateCourseRedirectsWithSuccess() throws Exception {
        when(courseService.updateCourse(5L, "IS443", "New Name")).thenReturn(course(5L, "IS443", "New Name"));

        mockMvc.perform(post(BASE + "/5/update").param("courseCode", " IS443 ").param("courseName", "New Name ")
                .with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("success", "IS443 has been updated."));

        verify(courseService).updateCourse(5L, "IS443", "New Name");
    }

    // CRS-1
    @Test
    void updateCourseWithBlankFieldReturnsToEditForm() throws Exception {
        mockMvc.perform(post(BASE + "/5/update").param("courseCode", "IS443").param("courseName", " ").with(csrf()))
                .andExpect(redirectedUrl(BASE + "?edit=5"))
                .andExpect(flash().attribute("error", "Enter both a course code and a course name."));

        verifyNoInteractions(courseService);
    }

    // CRS-1
    @Test
    void updateUnknownCourseShowsError() throws Exception {
        when(courseService.updateCourse(5L, "IS443", "New Name")).thenThrow(new CourseNotFoundException(5L));

        mockMvc.perform(post(BASE + "/5/update").param("courseCode", "IS443").param("courseName", "New Name")
                .with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "That course no longer exists."));
    }

    // ================= POST /courses/{id}/delete =================

    // CRS-1
    @Test
    void deleteCourseRedirectsWithSuccess() throws Exception {
        mockMvc.perform(post(BASE + "/5/delete").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("success", "The course has been deleted."));

        verify(courseService).deleteCourse(5L);
    }

    // CRS-1
    @Test
    void deleteUnknownCourseShowsError() throws Exception {
        doThrow(new CourseNotFoundException(5L)).when(courseService).deleteCourse(5L);

        mockMvc.perform(post(BASE + "/5/delete").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "That course no longer exists."));
    }

    // CRS-1
    @Test
    void deleteCourseInUseShowsMessage() throws Exception {
        doThrow(new DataIntegrityViolationException("foreign key")).when(courseService).deleteCourse(5L);

        mockMvc.perform(post(BASE + "/5/delete").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error",
                        "Students, study preferences or study groups still use this course, so it can't be deleted."));
    }
}

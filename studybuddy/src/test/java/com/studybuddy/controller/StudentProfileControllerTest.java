package com.studybuddy.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.studybuddy.config.SecurityConfig;
import com.studybuddy.entity.Course;
import com.studybuddy.entity.Student;
import com.studybuddy.exception.CourseNotFoundException;
import com.studybuddy.exception.StudentNotFoundException;
import com.studybuddy.service.course.CourseService;
import com.studybuddy.service.student.StudentProfileService;

/**
 * Web-layer test for the student's own profile page, rendered with the real template. The
 * services are mocked (the rules are in StudentProfileServiceTest). Logged in as student
 * alex@test.com, who takes IS442; the course list has IS442 and CS201.
 */
@WebMvcTest(StudentProfileController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = StudentProfileControllerTest.ALEX, roles = "STUDENT")
class StudentProfileControllerTest {

    static final String ALEX = "alex@test.com";
    private static final String BASE = "/student/profile";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StudentProfileService studentProfileService;
    @MockitoBean
    private CourseService courseService;

    private final Course is442 = course(1L, "IS442", "Object Oriented Programming");
    private final Course cs201 = course(2L, "CS201", "Data Structures and Algorithms");

    // S1
    @Test
    void pageShowsOwnProfileAndOnlyOtherCoursesToAdd() throws Exception {
        Student alex = new Student(ALEX, "dummyHash", "Alex Tan", "SCIS", "Information Systems", 2, "91234567");
        when(studentProfileService.getProfile(ALEX)).thenReturn(alex);
        when(studentProfileService.getCoursesTaken(ALEX)).thenReturn(List.of(is442));
        when(courseService.getAllCourses()).thenReturn(List.of(is442, cs201));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(view().name("student/profile"))
                .andExpect(model().attribute("student", alex))
                .andExpect(model().attribute("coursesTaken", List.of(is442)))
                .andExpect(model().attribute("otherCourses", List.of(cs201)));
    }

    // S1, PRF-1: the values are stripped before the service sees them
    @Test
    void updateProfileStripsValuesAndRedirectsWithSuccess() throws Exception {
        mockMvc.perform(post(BASE).param("name", "  Alex Tan ").param("school", " SCIS ")
                        .param("programme", " Computer Science ").param("yearOfStudy", "3")
                        .param("contactNumber", " 87654321 ").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("success", "Your profile has been updated."));

        verify(studentProfileService).updateProfile(ALEX, "Alex Tan", "SCIS", "Computer Science", 3, "87654321");
    }

    // S1, PRF-1: the service's message gets a full stop
    @Test
    void invalidProfileShowsServiceMessage() throws Exception {
        when(studentProfileService.updateProfile(ALEX, "Alex Tan", "SCIS", "IS", 2, "1234"))
                .thenThrow(new IllegalArgumentException("Contact number must be 8 digits"));

        mockMvc.perform(post(BASE).param("name", "Alex Tan").param("school", "SCIS").param("programme", "IS")
                        .param("yearOfStudy", "2").param("contactNumber", "1234").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "Contact number must be 8 digits."));
    }

    // S1, PRF-1: missing fields reach the service as blanks and year 0, which it refuses
    @Test
    void missingFieldsAreSentAsBlanks() throws Exception {
        mockMvc.perform(post(BASE).with(csrf())).andExpect(redirectedUrl(BASE));

        verify(studentProfileService).updateProfile(ALEX, "", "", "", 0, "");
    }

    // S1, K11: pins current behaviour, update if fixed. A non-numeric year is a raw 400, not a message
    @Test
    void nonNumericYearOfStudyIsBadRequest() throws Exception {
        mockMvc.perform(post(BASE).param("name", "Alex Tan").param("yearOfStudy", "two").with(csrf()))
                .andExpect(status().isBadRequest());

        verify(studentProfileService, never()).updateProfile(anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString());
    }

    // S1, PRF-2
    @Test
    void addCourseRedirectsWithSuccess() throws Exception {
        mockMvc.perform(post(BASE + "/courses").param("courseId", "2").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("success", "The course has been added."));

        verify(studentProfileService).addCourse(ALEX, 2L);
    }

    // S1
    @Test
    void addCourseWithoutChoosingOneSavesNothing() throws Exception {
        mockMvc.perform(post(BASE + "/courses").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "Choose a course to add."));

        verifyNoInteractions(studentProfileService);
    }

    // S1
    @Test
    void addUnknownCourseShowsError() throws Exception {
        doThrow(new CourseNotFoundException(99L)).when(studentProfileService).addCourse(ALEX, 99L);

        mockMvc.perform(post(BASE + "/courses").param("courseId", "99").with(csrf()))
                .andExpect(flash().attribute("error", "That course no longer exists."));
    }

    // PRF-2
    @Test
    void addCourseAlreadyTakenShowsServiceMessage() throws Exception {
        doThrow(new IllegalStateException("You are already taking IS442")).when(studentProfileService)
                .addCourse(ALEX, 1L);

        mockMvc.perform(post(BASE + "/courses").param("courseId", "1").with(csrf()))
                .andExpect(flash().attribute("error", "You are already taking IS442."));
    }

    // S1, PRF-2
    @Test
    void removeCourseRedirectsWithSuccess() throws Exception {
        mockMvc.perform(post(BASE + "/courses/1/remove").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("success", "The course has been removed."));

        verify(studentProfileService).removeCourse(ALEX, 1L);
    }

    // PRF-2
    @Test
    void removeCourseWithPreferenceShowsServiceMessage() throws Exception {
        doThrow(new IllegalStateException("Delete your IS442 study preference before removing the course"))
                .when(studentProfileService).removeCourse(ALEX, 1L);

        mockMvc.perform(post(BASE + "/courses/1/remove").with(csrf()))
                .andExpect(flash().attribute("error",
                        "Delete your IS442 study preference before removing the course."));
    }

    // S1
    @Test
    void removeUnknownCourseShowsError() throws Exception {
        doThrow(new CourseNotFoundException(99L)).when(studentProfileService).removeCourse(ALEX, 99L);

        mockMvc.perform(post(BASE + "/courses/99/remove").with(csrf()))
                .andExpect(flash().attribute("error", "That course no longer exists."));
    }

    // D3, S1: an "email" field in the form can't make the change for another student
    @Test
    void emailParameterIsIgnored() throws Exception {
        mockMvc.perform(post(BASE).param("email", "ben@test.com").param("name", "Hacked").param("school", "S")
                .param("programme", "P").param("yearOfStudy", "1").param("contactNumber", "99999999").with(csrf()));
        mockMvc.perform(post(BASE + "/courses").param("email", "ben@test.com").param("courseId", "2").with(csrf()));
        mockMvc.perform(post(BASE + "/courses/1/remove").param("email", "ben@test.com").with(csrf()));

        verify(studentProfileService).updateProfile(ALEX, "Hacked", "S", "P", 1, "99999999");
        verify(studentProfileService).addCourse(ALEX, 2L);
        verify(studentProfileService).removeCourse(ALEX, 1L);
        verify(studentProfileService, never()).addCourse("ben@test.com", 2L);
    }

    // K2, K16: pins current behaviour, update if fixed. If the logged-in email no longer belongs to a
    // student (e.g. an admin changed it), the page is a bare 404
    @Test
    void missingStudentAccountIsNotFound() throws Exception {
        when(studentProfileService.getCoursesTaken(ALEX)).thenThrow(new StudentNotFoundException(ALEX));

        mockMvc.perform(get(BASE)).andExpect(status().isNotFound());
    }

    // D3, SEC-2: anonymous, admin and token-less requests are refused before the controller runs
    @Test
    void refusedRequestsNeverReachTheService() throws Exception {
        for (Supplier<MockHttpServletRequestBuilder> route : List.<Supplier<MockHttpServletRequestBuilder>>of(
                () -> get(BASE), () -> post(BASE).param("name", "X"),
                () -> post(BASE + "/courses").param("courseId", "2"), () -> post(BASE + "/courses/1/remove"))) {
            mockMvc.perform(route.get().with(anonymous()).with(csrf())).andExpect(redirectedUrl("/login"));
            mockMvc.perform(route.get().with(user("admin@test.com").roles("ADMIN")).with(csrf()))
                    .andExpect(status().isForbidden());
        }
        for (MockHttpServletRequestBuilder request : List.of(post(BASE).param("name", "X"),
                post(BASE + "/courses").param("courseId", "2"), post(BASE + "/courses/1/remove"))) {
            mockMvc.perform(request).andExpect(status().isForbidden());
        }

        verifyNoInteractions(studentProfileService, courseService);
    }

    private static Course course(long id, String code, String name) {
        Course course = new Course(code, name);
        ReflectionTestUtils.setField(course, "courseId", id);
        return course;
    }
}

package com.studybuddy.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.DayOfWeek;
import java.time.LocalTime;
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
import com.studybuddy.entity.AvailabilitySlot;
import com.studybuddy.entity.Course;
import com.studybuddy.entity.StudyPreference;
import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;
import com.studybuddy.exception.CourseNotFoundException;
import com.studybuddy.exception.StudyPreferenceNotFoundException;
import com.studybuddy.service.student.StudentProfileService;
import com.studybuddy.service.student.StudyPreferenceService;
import com.studybuddy.service.studygroup.StudyGroupFormValidator;

/**
 * Web-layer test for the study preferences page, rendered with the real template. The services
 * are mocked (the rules are in StudyPreferenceServiceTest); the From / To times come from the real
 * study group settings in application.properties. Logged in as student alex@test.com, who takes
 * IS442 and CS201 and has one preference (id 4, IS442, slot id 9 Mon 10:00-12:00).
 */
@WebMvcTest(StudyPreferenceController.class)
@Import({ SecurityConfig.class, StudyGroupFormValidator.class })
@WithMockUser(username = StudyPreferenceControllerTest.ALEX, roles = "STUDENT")
class StudyPreferenceControllerTest {

    static final String ALEX = "alex@test.com";
    private static final String BASE = "/student/preferences";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StudyPreferenceService studyPreferenceService;
    @MockitoBean
    private StudentProfileService studentProfileService;

    private final Course is442 = course(1L, "IS442", "Object Oriented Programming");
    private final Course cs201 = course(2L, "CS201", "Data Structures and Algorithms");

    // S2, PRE-1
    @Test
    void pageShowsPreferencesAndOnlyCoursesWithoutOne() throws Exception {
        StudyPreference preference = is442Preference();
        when(studyPreferenceService.getPreferences(ALEX)).thenReturn(List.of(preference));
        when(studentProfileService.getCoursesTaken(ALEX)).thenReturn(List.of(is442, cs201));

        mockMvc.perform(get(BASE).param("edit", "4"))
                .andExpect(status().isOk())
                .andExpect(view().name("student/preferences"))
                .andExpect(model().attribute("preferences", List.of(preference)))
                .andExpect(model().attribute("coursesWithoutPreference", List.of(cs201)))
                .andExpect(model().attribute("editingId", 4L))
                // The same 08:00 to 23:00 quarter-hour list as the study group form
                .andExpect(model().attribute("timeOptions", hasSize(61)));
    }

    // S2: slots show as chips in the short "Mon 10:00-12:00" format used on every other page,
    // and the edit form's Save and Done are full-size buttons in their own row
    @Test
    void slotsShowAsChipsAndEditButtonsHaveTheirOwnRow() throws Exception {
        when(studyPreferenceService.getPreferences(ALEX)).thenReturn(List.of(is442Preference()));
        when(studentProfileService.getCoursesTaken(ALEX)).thenReturn(List.of(is442));

        mockMvc.perform(get(BASE))
                .andExpect(content().string(allOf(
                        containsString("<li class=\"slot-chip\">Mon 10:00-12:00</li>"),
                        not(containsString("Monday 10:00")))));
        mockMvc.perform(get(BASE).param("edit", "4"))
                .andExpect(content().string(allOf(
                        containsString("<td class=\"preference-edit\" colspan=\"6\">"),
                        containsString("<span class=\"slot-chip\">Mon 10:00-12:00</span>"),
                        containsString("<div class=\"button-row\">"))));
    }

    // S2, PRE-1: straight to the new preference's edit form
    @Test
    void createPreferenceRedirectsToItsEditForm() throws Exception {
        when(studyPreferenceService.createPreference(ALEX, 1L, StudyMode.ONLINE, Arrangement.ONE_TO_ONE,
                StudyGoal.CONCEPT_REVIEW)).thenReturn(is442Preference());

        mockMvc.perform(post(BASE).param("courseId", "1").param("studyMode", "ONLINE")
                        .param("arrangement", "ONE_TO_ONE").param("studyGoal", "CONCEPT_REVIEW").with(csrf()))
                .andExpect(redirectedUrl(BASE + "?edit=4"))
                .andExpect(flash().attribute("success",
                        "Your IS442 preference has been added. Add the times you're free below."));
    }

    // S2, PRE-1
    @Test
    void createWithoutCourseSavesNothing() throws Exception {
        mockMvc.perform(post(BASE).param("studyMode", "ONLINE").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "Choose a course for the preference."));

        verifyNoInteractions(studyPreferenceService);
    }

    // S2
    @Test
    void createForUnknownCourseShowsError() throws Exception {
        when(studyPreferenceService.createPreference(ALEX, 99L, null, null, null))
                .thenThrow(new CourseNotFoundException(99L));

        mockMvc.perform(post(BASE).param("courseId", "99").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "That course no longer exists."));
    }

    // PRE-1: the service's message gets a full stop
    @Test
    void refusedCreateShowsServiceMessage() throws Exception {
        when(studyPreferenceService.createPreference(ALEX, 2L, null, null, null))
                .thenThrow(new IllegalArgumentException("Choose a study mode, group size and study goal"));
        when(studyPreferenceService.createPreference(ALEX, 1L, StudyMode.ONLINE, Arrangement.EITHER,
                StudyGoal.EXAM_PREPARATION))
                .thenThrow(new IllegalStateException("You already have a preference for IS442"));

        mockMvc.perform(post(BASE).param("courseId", "2").with(csrf()))
                .andExpect(flash().attribute("error", "Choose a study mode, group size and study goal."));
        mockMvc.perform(post(BASE).param("courseId", "1").param("studyMode", "ONLINE").param("arrangement", "EITHER")
                        .param("studyGoal", "EXAM_PREPARATION").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("error", "You already have a preference for IS442."));
    }

    // S2
    @Test
    void updatePreferenceRedirectsWithSuccess() throws Exception {
        when(studyPreferenceService.updatePreference(4L, ALEX, StudyMode.IN_PERSON, Arrangement.SMALL_GROUP,
                StudyGoal.PROBLEM_SOLVING)).thenReturn(is442Preference());

        mockMvc.perform(post(BASE + "/4/update").param("studyMode", "IN_PERSON").param("arrangement", "SMALL_GROUP")
                        .param("studyGoal", "PROBLEM_SOLVING").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("success", "Your IS442 preference has been updated."));
    }

    // PRE-1: back to the same edit form to try again
    @Test
    void updateWithMissingChoiceReturnsToEditForm() throws Exception {
        when(studyPreferenceService.updatePreference(4L, ALEX, null, Arrangement.SMALL_GROUP, null))
                .thenThrow(new IllegalArgumentException("Choose a study mode, group size and study goal"));

        mockMvc.perform(post(BASE + "/4/update").param("arrangement", "SMALL_GROUP").with(csrf()))
                .andExpect(redirectedUrl(BASE + "?edit=4"))
                .andExpect(flash().attribute("error", "Choose a study mode, group size and study goal."));
    }

    // PRE-2: another student's preference looks the same as a missing one
    @Test
    void unknownPreferenceShowsErrorOnEveryAction() throws Exception {
        StudyPreferenceNotFoundException notFound = new StudyPreferenceNotFoundException(8L);
        when(studyPreferenceService.updatePreference(8L, ALEX, null, null, null)).thenThrow(notFound);
        doThrow(notFound).when(studyPreferenceService).deletePreference(8L, ALEX);
        when(studyPreferenceService.addAvailabilitySlot(8L, ALEX, DayOfWeek.MONDAY, LocalTime.of(10, 0),
                LocalTime.of(12, 0))).thenThrow(notFound);
        doThrow(notFound).when(studyPreferenceService).removeAvailabilitySlot(8L, ALEX, 9L);

        for (MockHttpServletRequestBuilder request : List.of(post(BASE + "/8/update"), post(BASE + "/8/delete"),
                post(BASE + "/8/slots").param("dayOfWeek", "MONDAY").param("startTime", "10:00")
                        .param("endTime", "12:00"),
                post(BASE + "/8/slots/9/remove"))) {
            mockMvc.perform(request.with(csrf()))
                    .andExpect(redirectedUrl(BASE))
                    .andExpect(flash().attribute("error", "That study preference no longer exists."));
        }
    }

    // S2
    @Test
    void deletePreferenceRedirectsWithSuccess() throws Exception {
        mockMvc.perform(post(BASE + "/4/delete").with(csrf()))
                .andExpect(redirectedUrl(BASE))
                .andExpect(flash().attribute("success", "The study preference has been deleted."));

        verify(studyPreferenceService).deletePreference(4L, ALEX);
    }

    // S2, PRE-3: the HH:mm times are read as times
    @Test
    void addSlotReadsTimesAndReturnsToEditForm() throws Exception {
        mockMvc.perform(post(BASE + "/4/slots").param("dayOfWeek", "MONDAY").param("startTime", "10:00")
                        .param("endTime", "12:00").with(csrf()))
                .andExpect(redirectedUrl(BASE + "?edit=4"))
                .andExpect(flash().attribute("success", "The time slot has been added."));

        verify(studyPreferenceService).addAvailabilitySlot(4L, ALEX, DayOfWeek.MONDAY, LocalTime.of(10, 0),
                LocalTime.of(12, 0));
    }

    // PRE-3
    @Test
    void refusedSlotShowsServiceMessageOnEditForm() throws Exception {
        when(studyPreferenceService.addAvailabilitySlot(4L, ALEX, DayOfWeek.MONDAY, LocalTime.of(11, 0),
                LocalTime.of(13, 0)))
                .thenThrow(new IllegalArgumentException("This slot overlaps one you already have on MONDAY"));

        mockMvc.perform(post(BASE + "/4/slots").param("dayOfWeek", "MONDAY").param("startTime", "11:00")
                        .param("endTime", "13:00").with(csrf()))
                .andExpect(redirectedUrl(BASE + "?edit=4"))
                .andExpect(flash().attribute("error", "This slot overlaps one you already have on MONDAY."));
    }

    // PRE-3, N-C1: pins current behaviour, update if fixed. A time that isn't HH:mm (only possible by
    // editing the form) is a raw 400, not a message
    @Test
    void timeNotInHourMinuteFormatIsBadRequest() throws Exception {
        mockMvc.perform(post(BASE + "/4/slots").param("dayOfWeek", "MONDAY").param("startTime", "25:00")
                        .param("endTime", "26:00").with(csrf()))
                .andExpect(status().isBadRequest());

        verify(studyPreferenceService, never()).addAvailabilitySlot(any(), any(), any(), any(), any());
    }

    // S2, PRE-3
    @Test
    void removeSlotReturnsToEditForm() throws Exception {
        mockMvc.perform(post(BASE + "/4/slots/9/remove").with(csrf()))
                .andExpect(redirectedUrl(BASE + "?edit=4"))
                .andExpect(flash().attribute("success", "The time slot has been removed."));

        verify(studyPreferenceService).removeAvailabilitySlot(4L, ALEX, 9L);
    }

    // PRE-3
    @Test
    void removeUnknownSlotShowsError() throws Exception {
        doThrow(new IllegalArgumentException("This preference has no slot 77")).when(studyPreferenceService)
                .removeAvailabilitySlot(4L, ALEX, 77L);

        mockMvc.perform(post(BASE + "/4/slots/77/remove").with(csrf()))
                .andExpect(redirectedUrl(BASE + "?edit=4"))
                .andExpect(flash().attribute("error", "That time slot no longer exists."));
    }

    // D3, PRE-2: an "email" field in the form can't act for another student
    @Test
    void emailParameterIsIgnored() throws Exception {
        mockMvc.perform(post(BASE + "/4/delete").param("email", "ben@test.com").with(csrf()));
        mockMvc.perform(post(BASE + "/4/slots/9/remove").param("email", "ben@test.com").with(csrf()));
        mockMvc.perform(get(BASE).param("email", "ben@test.com"));

        verify(studyPreferenceService).deletePreference(4L, ALEX);
        verify(studyPreferenceService).removeAvailabilitySlot(4L, ALEX, 9L);
        verify(studyPreferenceService).getPreferences(ALEX);
        verify(studyPreferenceService, never()).getPreferences("ben@test.com");
    }

    // D3, SEC-2: anonymous, admin and token-less requests are refused before the controller runs
    @Test
    void refusedRequestsNeverReachTheService() throws Exception {
        List<Supplier<MockHttpServletRequestBuilder>> routes = List.of(() -> get(BASE),
                () -> post(BASE).param("courseId", "1"), () -> post(BASE + "/4/update"),
                () -> post(BASE + "/4/delete"), () -> post(BASE + "/4/slots"), () -> post(BASE + "/4/slots/9/remove"));
        for (Supplier<MockHttpServletRequestBuilder> route : routes) {
            mockMvc.perform(route.get().with(anonymous()).with(csrf())).andExpect(redirectedUrl("/login"));
            mockMvc.perform(route.get().with(user("admin@test.com").roles("ADMIN")).with(csrf()))
                    .andExpect(status().isForbidden());
        }
        for (Supplier<MockHttpServletRequestBuilder> route : routes.subList(1, routes.size())) {
            mockMvc.perform(route.get()).andExpect(status().isForbidden());
        }

        verifyNoInteractions(studyPreferenceService, studentProfileService);
    }

    // Preference 4: IS442, Online, One-to-one, Concept review, one slot (id 9) Mon 10:00-12:00
    private StudyPreference is442Preference() {
        StudyPreference preference = new StudyPreference(is442, StudyMode.ONLINE, Arrangement.ONE_TO_ONE,
                StudyGoal.CONCEPT_REVIEW);
        ReflectionTestUtils.setField(preference, "id", 4L);
        AvailabilitySlot slot = new AvailabilitySlot(DayOfWeek.MONDAY, LocalTime.of(10, 0), LocalTime.of(12, 0));
        ReflectionTestUtils.setField(slot, "id", 9L);
        preference.addAvailabilitySlot(slot);
        return preference;
    }

    private static Course course(long id, String code, String name) {
        Course course = new Course(code, name);
        ReflectionTestUtils.setField(course, "courseId", id);
        return course;
    }
}

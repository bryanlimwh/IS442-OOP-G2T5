package com.studybuddy.controller;

import java.security.Principal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.studybuddy.config.StudyGroupSettings;
import com.studybuddy.entity.StudyPreference;
import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;
import com.studybuddy.exception.CourseNotFoundException;
import com.studybuddy.exception.StudyPreferenceNotFoundException;
import com.studybuddy.service.student.StudentProfileService;
import com.studybuddy.service.student.StudyPreferenceService;

// A student managing their study preferences (one per course they take) and each
// preference's weekly availability slots, which matching uses to find study buddies.
// Only students reach this page (SecurityConfig restricts /student/** to the STUDENT role),
// and StudyPreferenceService only finds the logged-in student's own preferences.
// Each action redirects back to the list with a one-time success or error message (Post/Redirect/Get).
@Controller
@RequestMapping("/student/preferences")
public class StudyPreferenceController {

    private static final String VIEW = "student/preferences";
    private static final String REDIRECT_TO_LIST = "redirect:/student/preferences";

    private final StudyPreferenceService studyPreferenceService;
    private final StudentProfileService studentProfileService;
    // Reused for the From / To dropdowns, so slots offer the same times as the study group form
    private final StudyGroupSettings studyGroupSettings;

    public StudyPreferenceController(StudyPreferenceService studyPreferenceService,
            StudentProfileService studentProfileService, StudyGroupSettings studyGroupSettings) {
        this.studyPreferenceService = studyPreferenceService;
        this.studentProfileService = studentProfileService;
        this.studyGroupSettings = studyGroupSettings;
    }

    // ?edit={preferenceId} shows that preference's row as an edit form, so editing needs no separate page.
    @GetMapping
    public String showPreferences(@RequestParam(required = false) Long edit, Principal principal, Model model) {
        List<StudyPreference> preferences = studyPreferenceService.getPreferences(principal.getName());
        model.addAttribute("preferences", preferences);
        // The "Add a preference" dropdown only offers taken courses without a preference yet
        model.addAttribute("coursesWithoutPreference", studentProfileService.getCoursesTaken(principal.getName())
                .stream()
                .filter(course -> preferences.stream().noneMatch(p -> p.getCourse().equals(course)))
                .toList());
        model.addAttribute("editingId", edit);
        model.addAttribute("studyModes", StudyMode.values());
        model.addAttribute("arrangements", Arrangement.values());
        model.addAttribute("studyGoals", StudyGoal.values());
        model.addAttribute("days", DayOfWeek.values());
        model.addAttribute("timeOptions", studyGroupSettings.timeOptions());
        return VIEW;
    }

    @PostMapping
    public String createPreference(@RequestParam(required = false) Long courseId,
            @RequestParam(required = false) StudyMode studyMode,
            @RequestParam(required = false) Arrangement arrangement,
            @RequestParam(required = false) StudyGoal studyGoal,
            Principal principal, RedirectAttributes redirectAttributes) {
        if (courseId == null) {
            redirectAttributes.addFlashAttribute("error", "Choose a course for the preference.");
            return REDIRECT_TO_LIST;
        }
        try {
            StudyPreference preference = studyPreferenceService.createPreference(principal.getName(), courseId,
                    studyMode, arrangement, studyGoal);
            redirectAttributes.addFlashAttribute("success", "Your " + preference.getCourse().getCourseCode()
                    + " preference has been added. Add the times you're free below.");
            // Straight into its edit form, where availability slots are added
            return REDIRECT_TO_LIST + "?edit=" + preference.getId();
        } catch (CourseNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That course no longer exists.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage() + ".");
        }
        return REDIRECT_TO_LIST;
    }

    @PostMapping("/{preferenceId}/update")
    public String updatePreference(@PathVariable Long preferenceId,
            @RequestParam(required = false) StudyMode studyMode,
            @RequestParam(required = false) Arrangement arrangement,
            @RequestParam(required = false) StudyGoal studyGoal,
            Principal principal, RedirectAttributes redirectAttributes) {
        try {
            StudyPreference preference = studyPreferenceService.updatePreference(preferenceId, principal.getName(),
                    studyMode, arrangement, studyGoal);
            redirectAttributes.addFlashAttribute("success",
                    "Your " + preference.getCourse().getCourseCode() + " preference has been updated.");
        } catch (StudyPreferenceNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That study preference no longer exists.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage() + ".");
            // Back to the same edit form, so the student can try again
            return REDIRECT_TO_LIST + "?edit=" + preferenceId;
        }
        return REDIRECT_TO_LIST;
    }

    @PostMapping("/{preferenceId}/delete")
    public String deletePreference(@PathVariable Long preferenceId, Principal principal,
            RedirectAttributes redirectAttributes) {
        try {
            studyPreferenceService.deletePreference(preferenceId, principal.getName());
            redirectAttributes.addFlashAttribute("success", "The study preference has been deleted.");
        } catch (StudyPreferenceNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That study preference no longer exists.");
        }
        return REDIRECT_TO_LIST;
    }

    // Slots are added and removed from the preference's edit form, so both return there.
    @PostMapping("/{preferenceId}/slots")
    public String addSlot(@PathVariable Long preferenceId, @RequestParam(required = false) DayOfWeek dayOfWeek,
            @RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") LocalTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") LocalTime endTime,
            Principal principal, RedirectAttributes redirectAttributes) {
        try {
            studyPreferenceService.addAvailabilitySlot(preferenceId, principal.getName(),
                    dayOfWeek, startTime, endTime);
            redirectAttributes.addFlashAttribute("success", "The time slot has been added.");
        } catch (StudyPreferenceNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That study preference no longer exists.");
            return REDIRECT_TO_LIST;
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage() + ".");
        }
        return REDIRECT_TO_LIST + "?edit=" + preferenceId;
    }

    @PostMapping("/{preferenceId}/slots/{slotId}/remove")
    public String removeSlot(@PathVariable Long preferenceId, @PathVariable Long slotId, Principal principal,
            RedirectAttributes redirectAttributes) {
        try {
            studyPreferenceService.removeAvailabilitySlot(preferenceId, principal.getName(), slotId);
            redirectAttributes.addFlashAttribute("success", "The time slot has been removed.");
        } catch (StudyPreferenceNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That study preference no longer exists.");
            return REDIRECT_TO_LIST;
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", "That time slot no longer exists.");
        }
        return REDIRECT_TO_LIST + "?edit=" + preferenceId;
    }
}

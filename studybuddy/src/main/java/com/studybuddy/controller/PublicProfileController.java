package com.studybuddy.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.studybuddy.exception.StudentNotFoundException;
import com.studybuddy.service.student.StudentProfileService;

// Another student's public study profile: name, school, programme, year, courses and study preferences.
// Never the contact number or email - the page only receives StudentPublicProfile and PreferenceSummary,
// which don't have them. Contact numbers are shared on the Study buddies page once a request is accepted.
// Only students reach this page: SecurityConfig restricts /students/** to the STUDENT role.
@Controller
@RequestMapping("/students")
public class PublicProfileController {

    private final StudentProfileService studentProfileService;

    public PublicProfileController(StudentProfileService studentProfileService) {
        this.studentProfileService = studentProfileService;
    }

    @GetMapping("/{studentId}")
    public String showPublicProfile(@PathVariable Long studentId, Model model, RedirectAttributes redirectAttributes) {
        try {
            model.addAttribute("profile", studentProfileService.getPublicProfile(studentId));
            model.addAttribute("preferences", studentProfileService.getPublicPreferences(studentId));
        } catch (StudentNotFoundException e) {
            // e.g. an old link to an account that has since been deleted
            redirectAttributes.addFlashAttribute("error", "That student's profile no longer exists.");
            return "redirect:/matches";
        }
        return "student/public-profile";
    }
}

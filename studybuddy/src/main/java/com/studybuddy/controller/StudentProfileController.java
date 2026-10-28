package com.studybuddy.controller;

import java.security.Principal;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.studybuddy.entity.Course;
import com.studybuddy.exception.CourseNotFoundException;
import com.studybuddy.service.course.CourseService;
import com.studybuddy.service.student.StudentProfileService;

// A student viewing and editing their own details and the courses they take.
// Only students reach this page (SecurityConfig restricts /student/** to the STUDENT role),
// and the logged-in email is passed to StudentProfileService, so a student can only change their own profile.
// Each action redirects back to the profile with a one-time success or error message (Post/Redirect/Get).
@Controller
@RequestMapping("/student/profile")
public class StudentProfileController {

    private static final String VIEW = "student/profile";
    private static final String REDIRECT_TO_PROFILE = "redirect:/student/profile";
    // The database column size: checked here so a longer value gets a message instead of an error page
    private static final int MAX_LENGTH = 255;

    private final StudentProfileService studentProfileService;
    private final CourseService courseService;

    public StudentProfileController(StudentProfileService studentProfileService, CourseService courseService) {
        this.studentProfileService = studentProfileService;
        this.courseService = courseService;
    }

    @GetMapping
    public String showProfile(Principal principal, Model model) {
        List<Course> coursesTaken = studentProfileService.getCoursesTaken(principal.getName());
        model.addAttribute("student", studentProfileService.getProfile(principal.getName()));
        model.addAttribute("coursesTaken", coursesTaken);
        // The "Add a course" dropdown only offers courses the student doesn't take yet
        model.addAttribute("otherCourses", courseService.getAllCourses().stream()
                .filter(course -> !coursesTaken.contains(course))
                .toList());
        return VIEW;
    }

    @PostMapping
    public String updateProfile(@RequestParam(defaultValue = "") String name,
            @RequestParam(defaultValue = "") String school, @RequestParam(defaultValue = "") String programme,
            @RequestParam(defaultValue = "0") int yearOfStudy, @RequestParam(defaultValue = "") String contactNumber,
            Principal principal, RedirectAttributes redirectAttributes) {
        if (name.strip().length() > MAX_LENGTH || school.strip().length() > MAX_LENGTH
                || programme.strip().length() > MAX_LENGTH) {
            redirectAttributes.addFlashAttribute("error",
                    "Name, school and programme can be at most " + MAX_LENGTH + " characters.");
            return REDIRECT_TO_PROFILE;
        }
        try {
            studentProfileService.updateProfile(principal.getName(), name.strip(), school.strip(),
                    programme.strip(), yearOfStudy, contactNumber.strip());
            redirectAttributes.addFlashAttribute("success", "Your profile has been updated.");
        } catch (IllegalArgumentException e) {
            // The service's message says which detail is wrong, e.g. "Contact number must be 8 digits"
            redirectAttributes.addFlashAttribute("error", e.getMessage() + ".");
        }
        return REDIRECT_TO_PROFILE;
    }

    @PostMapping("/courses")
    public String addCourse(@RequestParam(required = false) Long courseId, Principal principal,
            RedirectAttributes redirectAttributes) {
        if (courseId == null) {
            redirectAttributes.addFlashAttribute("error", "Choose a course to add.");
            return REDIRECT_TO_PROFILE;
        }
        try {
            studentProfileService.addCourse(principal.getName(), courseId);
            redirectAttributes.addFlashAttribute("success", "The course has been added.");
        } catch (CourseNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That course no longer exists.");
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage() + ".");
        }
        return REDIRECT_TO_PROFILE;
    }

    @PostMapping("/courses/{courseId}/remove")
    public String removeCourse(@PathVariable Long courseId, Principal principal,
            RedirectAttributes redirectAttributes) {
        try {
            studentProfileService.removeCourse(principal.getName(), courseId);
            redirectAttributes.addFlashAttribute("success", "The course has been removed.");
        } catch (CourseNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That course no longer exists.");
        } catch (IllegalStateException e) {
            // e.g. the student still has a study preference for the course
            redirectAttributes.addFlashAttribute("error", e.getMessage() + ".");
        }
        return REDIRECT_TO_PROFILE;
    }
}

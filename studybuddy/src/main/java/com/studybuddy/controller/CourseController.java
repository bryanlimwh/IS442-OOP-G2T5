package com.studybuddy.controller;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.studybuddy.entity.Course;
import com.studybuddy.entity.enums.Role;
import com.studybuddy.exception.CourseNotFoundException;
import com.studybuddy.service.course.CourseService;

import jakarta.servlet.http.HttpServletRequest;

// The course list. Every logged-in user can view it (SecurityConfig's anyRequest().authenticated()),
// but only admins can add, edit or delete courses. Because the page itself is open, each of those
// actions is checked separately with @PreAuthorize; hiding the buttons alone wouldn't stop a student
// from sending the POST request directly. A student who tries gets 403 Forbidden.
// Each action redirects back to the list with a one-time success or error message (Post/Redirect/Get).
@Controller
@RequestMapping("/courses")
public class CourseController {

    private static final String VIEW = "courses";
    private static final String REDIRECT_TO_LIST = "redirect:/courses";
    // The database column size: checked here so a longer value gets a message instead of an error page
    private static final int MAX_LENGTH = 255;
    private static final String TOO_LONG = "Course code and course name can be at most " + MAX_LENGTH + " characters.";

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    // ?edit={courseId} shows that course's row as an edit form, so editing needs no separate page.
    @GetMapping
    public String showCourses(@RequestParam(required = false) Long edit, HttpServletRequest request,
            Model model) {
        boolean isAdmin = request.isUserInRole(Role.ADMIN.name());
        model.addAttribute("courses", courseService.getAllCourses());
        model.addAttribute("isAdmin", isAdmin);
        // Students can't edit, so ?edit is ignored for them
        model.addAttribute("editingId", isAdmin ? edit : null);
        return VIEW;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public String addCourse(@RequestParam(defaultValue = "") String courseCode,
            @RequestParam(defaultValue = "") String courseName, RedirectAttributes redirectAttributes) {
        if (courseCode.isBlank() || courseName.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Enter both a course code and a course name.");
            return REDIRECT_TO_LIST;
        }
        if (tooLong(courseCode, courseName)) {
            redirectAttributes.addFlashAttribute("error", TOO_LONG);
            return REDIRECT_TO_LIST;
        }
        Course course = courseService.createCourse(courseCode.strip(), courseName.strip());
        redirectAttributes.addFlashAttribute("success", course.getCourseCode() + " has been added.");
        return REDIRECT_TO_LIST;
    }

    @PostMapping("/{courseId}/update")
    @PreAuthorize("hasRole('ADMIN')")
    public String updateCourse(@PathVariable Long courseId, @RequestParam(defaultValue = "") String courseCode,
            @RequestParam(defaultValue = "") String courseName, RedirectAttributes redirectAttributes) {
        if (courseCode.isBlank() || courseName.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Enter both a course code and a course name.");
            // Back to the same edit form, so the admin can try again
            return REDIRECT_TO_LIST + "?edit=" + courseId;
        }
        if (tooLong(courseCode, courseName)) {
            redirectAttributes.addFlashAttribute("error", TOO_LONG);
            return REDIRECT_TO_LIST + "?edit=" + courseId;
        }
        try {
            Course course = courseService.updateCourse(courseId, courseCode.strip(), courseName.strip());
            redirectAttributes.addFlashAttribute("success", course.getCourseCode() + " has been updated.");
        } catch (CourseNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That course no longer exists.");
        }
        return REDIRECT_TO_LIST;
    }

    @PostMapping("/{courseId}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String deleteCourse(@PathVariable Long courseId, RedirectAttributes redirectAttributes) {
        try {
            courseService.deleteCourse(courseId);
            redirectAttributes.addFlashAttribute("success", "The course has been deleted.");
        } catch (CourseNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That course no longer exists.");
        } catch (DataIntegrityViolationException e) {
            // Students' course lists, study preferences and study groups point at the course,
            // so the database refuses to delete it while any of them do.
            redirectAttributes.addFlashAttribute("error",
                    "Students, study preferences or study groups still use this course, so it can't be deleted.");
        }
        return REDIRECT_TO_LIST;
    }

    // Compared after stripping, as that's what is saved
    private static boolean tooLong(String courseCode, String courseName) {
        return courseCode.strip().length() > MAX_LENGTH || courseName.strip().length() > MAX_LENGTH;
    }
}

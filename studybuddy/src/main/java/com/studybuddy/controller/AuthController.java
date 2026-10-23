package com.studybuddy.controller;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.studybuddy.exception.DuplicateEmailException;
import com.studybuddy.exception.InvalidAccountDetailsException;
import com.studybuddy.service.student.StudentProfileService;

// Serves the login and student sign-up pages. Submitting the login form (POST /login) and logging out (POST /logout)
// are handled by Spring Security itself, which checks the password via AuthService - so no methods are needed for those
@Controller
// Maps the controller to the root URL ("/") of the application
@RequestMapping("/")
public class AuthController {

    private static final String REGISTER_VIEW = "auth/register";

    private final StudentProfileService studentProfileService;

    public AuthController(StudentProfileService studentProfileService) {
        this.studentProfileService = studentProfileService;
    }

    // Handles GET request to "/login"
    // Spring Security redirects here whenever a logged-out user asks for a protected page
    @GetMapping("/login")
    public String showLoginPage() {
        // Return the name of the view (auth/login.html) to be rendered
        return "auth/login";
    }

    // Handles GET request to "/register": the student sign-up form
    @GetMapping("/register")
    public String showRegisterPage() {
        return REGISTER_VIEW;
    }

    // Handles POST request to "/register". On success the student is sent to the login page;
    // on an error the form is shown again with what they typed, except the password.
    // yearOfStudy defaults to 0 when left blank, which StudentProfileService rejects with a readable message.
    @PostMapping("/register")
    public String register(@RequestParam(defaultValue = "") String email,
            @RequestParam(defaultValue = "") String password,
            @RequestParam(defaultValue = "") String name,
            @RequestParam(defaultValue = "") String school,
            @RequestParam(defaultValue = "") String programme,
            @RequestParam(defaultValue = "0") int yearOfStudy,
            @RequestParam(defaultValue = "") String contactNumber,
            Model model) {
        try {
            studentProfileService.createProfile(email, password, name, school, programme, yearOfStudy, contactNumber);
            return "redirect:/login?registered";
        } catch (InvalidAccountDetailsException | DuplicateEmailException | IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
        } catch (DataIntegrityViolationException e) {
            // Two sign-ups with the same email at the same moment can both pass the service's
            // duplicate check; the unique constraint on user_account.email refuses the second.
            model.addAttribute("error", "Another account already uses that email.");
        }
        model.addAttribute("email", email);
        model.addAttribute("name", name);
        model.addAttribute("school", school);
        model.addAttribute("programme", programme);
        model.addAttribute("yearOfStudy", yearOfStudy == 0 ? null : yearOfStudy);
        model.addAttribute("contactNumber", contactNumber);
        return REGISTER_VIEW;
    }
}

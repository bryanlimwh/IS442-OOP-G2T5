package com.studybuddy.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

// Serves the login page. Submitting the login form (POST /login) and logging out (POST /logout)
// are handled by Spring Security itself, which checks the password via AuthService - so no methods are needed for those
@Controller
// Maps the controller to the root URL ("/") of the application
@RequestMapping("/")
public class AuthController {

    // Handles GET request to "/login"
    // Spring Security redirects here whenever a logged-out user asks for a protected page
    @GetMapping("/login")
    public String showLoginPage() {
        // Return the name of the view (auth/login.html) to be rendered
        return "auth/login";
    }
}

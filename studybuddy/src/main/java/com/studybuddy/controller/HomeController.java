package com.studybuddy.controller;

import java.security.Principal;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.studybuddy.entity.enums.Role;

import jakarta.servlet.http.HttpServletRequest;

// Controllers serve to receive HTTP requests and return an appropriate response; They contain the logic to process HTTP request to various URLs of the app
// Spring will create an object of this Controller (Java class) when run 
@Controller
// The class-level annotation @RequestMapping indicates which URL the controller's methods apply to
// Method-level annotations will indicate the method to execute for a specific URL and HTTP method under the URL specified by the @RequestMapping
// This maps the controller to the root URL ("/") of the application
@RequestMapping("/")
public class HomeController {

    // private final MatchService matchService;

    // // Spring injects the required service when creating this controller.
    // public MatchController(MatchService matchService) {
    //     this.matchService = matchService;
    // }

    // Handles GET request to "/home"
    @GetMapping("/home")
    // MVC Models are like a temporary data container between a controller and its returned HTML view
    // The Model interface holds named attributes and can be accessed like a Map<String, Object>
    public String showHomePage(Model model, Principal principal, HttpServletRequest request) {

        // Stores the account username in the model using the attribute name "username"
        // The Principal object represents the currently authenticated user
        // Retrieve the authenticated user's account username using principal.getName()
        model.addAttribute("username", principal.getName());
        // Admins and students see different feature links
        model.addAttribute("isAdmin", request.isUserInRole(Role.ADMIN.name()));
        // Return the name of the view (home.html) to be rendered
        return "home";
    }
}

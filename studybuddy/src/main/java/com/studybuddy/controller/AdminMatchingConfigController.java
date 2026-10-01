package com.studybuddy.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.studybuddy.domain.enums.MatchingStrategyType;
import com.studybuddy.exception.InvalidMatchingConfigException;
import com.studybuddy.matching.MatchingConfigForm;
import com.studybuddy.service.matching.MatchingConfigService;

// Lets an admin choose the matching strategy and how much each criterion counts.
// Only admins reach this page: SecurityConfig restricts /admin/** to the ADMIN role.
@Controller
@RequestMapping("/admin/matching")
public class AdminMatchingConfigController {

    private static final String VIEW = "admin/matching-config";

    private final MatchingConfigService matchingConfigService;

    public AdminMatchingConfigController(MatchingConfigService matchingConfigService) {
        this.matchingConfigService = matchingConfigService;
    }

    @GetMapping
    public String showSettings(Model model) {
        model.addAttribute("form", MatchingConfigForm.from(matchingConfigService.getCurrent()));
        return showForm(model);
    }

    @PostMapping
    public String saveSettings(@ModelAttribute("form") MatchingConfigForm form, BindingResult bindingResult,
            Model model, RedirectAttributes redirectAttributes) {
        // A blank or non-numeric field fails to bind before the service ever sees it.
        if (bindingResult.hasErrors()) {
            model.addAttribute("error", "Every weight and the session length must be a whole number.");
            return showForm(model);
        }
        try {
            matchingConfigService.update(form);
        } catch (InvalidMatchingConfigException e) {
            model.addAttribute("error", e.getMessage());
            return showForm(model);
        }
        // Redirect after saving, so refreshing the page doesn't submit the form again.
        redirectAttributes.addFlashAttribute("saved", true);
        return "redirect:/admin/matching";
    }

    // The form plus the settings currently saved, which may differ from the form after a rejected save.
    private String showForm(Model model) {
        model.addAttribute("strategies", MatchingStrategyType.values());
        model.addAttribute("current", matchingConfigService.getCurrent());
        return VIEW;
    }
}

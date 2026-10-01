package com.studybuddy.controller;

import java.security.Principal;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;

import com.studybuddy.domain.enums.Arrangement;
import com.studybuddy.domain.enums.StudyGoal;
import com.studybuddy.domain.enums.StudyMode;
import com.studybuddy.matching.MatchSearch;
import com.studybuddy.service.matching.MatchingConfigService;
import com.studybuddy.service.matching.MatchingService;

// Shows the logged-in student a ranked list of potential study buddies, with search filters.
// Only students reach this page: SecurityConfig restricts /matches/** to the STUDENT role.
@Controller
@RequestMapping("/matches")
public class MatchingController {

    private final MatchingService matchingService;
    private final MatchingConfigService matchingConfigService;

    public MatchingController(MatchingService matchingService, MatchingConfigService matchingConfigService) {
        this.matchingService = matchingService;
        this.matchingConfigService = matchingConfigService;
    }

    // The search form uses GET, so its choices arrive as query parameters and bind to MatchSearch.
    // That also means a search can be bookmarked or refreshed safely.
    @GetMapping
    public String showMatches(@ModelAttribute("search") MatchSearch search, Principal principal, Model model) {
        String email = principal.getName();
        model.addAttribute("myPreferences", matchingService.getPreferences(email));
        model.addAttribute("results", matchingService.findMatches(email, search));
        model.addAttribute("strategy", matchingConfigService.getCurrent().getStrategy());

        // Filter choices. EITHER isn't offered: filtering for "either" would filter nothing.
        model.addAttribute("studyGoals", StudyGoal.values());
        model.addAttribute("studyModes", List.of(StudyMode.IN_PERSON, StudyMode.ONLINE));
        model.addAttribute("groupSizes", List.of(Arrangement.ONE_TO_ONE, Arrangement.SMALL_GROUP));
        return "student/matches";
    }
}

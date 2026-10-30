package com.studybuddy.controller;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;

import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.MatchRequestStatus;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;
import com.studybuddy.matching.BuddyStatus;
import com.studybuddy.matching.MatchSearch;
import com.studybuddy.service.matching.MatchingConfigService;
import com.studybuddy.service.matching.MatchingService;
import com.studybuddy.service.studybuddymatching.MatchRequestService;
import com.studybuddy.service.studybuddymatching.StudyBuddyConnectionService;

// Shows the logged-in student a ranked list of potential study buddies, with search filters.
// Only students reach this page: SecurityConfig restricts /matches/** to the STUDENT role.
@Controller
@RequestMapping("/matches")
public class MatchingController {

    private final MatchingService matchingService;
    private final MatchingConfigService matchingConfigService;
    private final StudyBuddyConnectionService connectionService;
    private final MatchRequestService matchRequestService;

    public MatchingController(MatchingService matchingService, MatchingConfigService matchingConfigService,
            StudyBuddyConnectionService connectionService, MatchRequestService matchRequestService) {
        this.matchingService = matchingService;
        this.matchingConfigService = matchingConfigService;
        this.connectionService = connectionService;
        this.matchRequestService = matchRequestService;
    }

    // The search form uses GET, so its choices arrive as query parameters and bind to MatchSearch.
    // That also means a search can be bookmarked or refreshed safely.
    @GetMapping
    public String showMatches(@ModelAttribute("search") MatchSearch search, Principal principal, Model model) {
        String email = principal.getName();
        model.addAttribute("myPreferences", matchingService.getPreferences(email));
        model.addAttribute("results", matchingService.findMatches(email, search));
        model.addAttribute("strategy", matchingConfigService.getCurrent().getStrategy());
        model.addAttribute("buddyStatuses", buddyStatuses(email));

        // Filter choices. EITHER isn't offered: filtering for "either" would filter nothing.
        model.addAttribute("studyGoals", StudyGoal.values());
        model.addAttribute("studyModes", List.of(StudyMode.IN_PERSON, StudyMode.ONLINE));
        model.addAttribute("groupSizes", List.of(Arrangement.ONE_TO_ONE, Arrangement.SMALL_GROUP));
        return "student/matches";
    }

    // Student id -> how the searcher is already linked to them (team decision TD-5): those students stay
    // listed, but their card shows a label instead of "Send request". Students with no link, or only a
    // rejected request, aren't in the map and keep the button, since a new request is allowed.
    private Map<Long, BuddyStatus> buddyStatuses(String email) {
        Map<Long, BuddyStatus> statuses = new HashMap<>();
        matchRequestService.getMatchRequestBySender(email).stream()
                .filter(request -> request.status() == MatchRequestStatus.PENDING)
                .forEach(request -> statuses.put(request.recipient().studentId(), BuddyStatus.REQUEST_SENT));
        matchRequestService.getMatchRequestByRecipient(email).stream()
                .filter(request -> request.status() == MatchRequestStatus.PENDING)
                .forEach(request -> statuses.put(request.sender().studentId(), BuddyStatus.REQUEST_RECEIVED));
        connectionService.getConnections(email)
                .forEach(connection -> statuses.put(connection.buddy().studentId(), BuddyStatus.CONNECTED));
        return statuses;
    }
}

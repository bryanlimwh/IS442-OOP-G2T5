package com.studybuddy.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.studybuddy.repository.MatchRequestRepository;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.service.studybuddymatching.MatchRequestService;

// Seeds a few match requests around the demo student (Alex Tan), so every part of
// the request and connection pages has something to show at the start of a demo:
// - Ben Koh -> Alex, accepted, so Alex already has one study buddy
// - Chloe Lim -> Alex, pending, for Alex to accept or decline
// - Alex -> Daniel Ng, pending, under Alex's sent requests
//
// Goes through MatchRequestService rather than saving rows directly, so accepting
// creates the StudyBuddyConnection exactly as it does in the app. Runs after
// StudentDataSeeder, which creates these students.
@Component
@Order(6)
public class MatchRequestDataSeeder implements CommandLineRunner {

    // Two of StudentDataSeeder's generated students (name "Chloe Lim" -> chloe.lim@studybuddy.com)
    private static final String CHLOE_EMAIL = "chloe.lim@studybuddy.com";
    private static final String DANIEL_EMAIL = "daniel.ng@studybuddy.com";

    private final MatchRequestService matchRequestService;
    private final MatchRequestRepository matchRequestRepository;
    private final StudentRepository studentRepository;

    public MatchRequestDataSeeder(MatchRequestService matchRequestService,
            MatchRequestRepository matchRequestRepository, StudentRepository studentRepository) {
        this.matchRequestService = matchRequestService;
        this.matchRequestRepository = matchRequestRepository;
        this.studentRepository = studentRepository;
    }

    @Override
    public void run(String... args) {
        if (matchRequestRepository.count() > 0) {
            return;
        }

        Long alexId = idOf(StudentDataSeeder.DEMO_STUDENT_EMAIL);
        Long danielId = idOf(DANIEL_EMAIL);

        Long benRequestId = matchRequestService.createRequest(StudentDataSeeder.APPENDIX_B_STUDENT_EMAIL, alexId,
                "We're both free Wednesday evenings. Want to prepare for the IS442 exam together?").requestId();
        matchRequestService.acceptRequest(StudentDataSeeder.DEMO_STUDENT_EMAIL, benRequestId);

        matchRequestService.createRequest(CHLOE_EMAIL, alexId, "Keen to study together this term?");
        matchRequestService.createRequest(StudentDataSeeder.DEMO_STUDENT_EMAIL, danielId, null);

        System.out.println("Seeded 3 match requests and 1 study buddy connection for " + StudentDataSeeder.DEMO_STUDENT_EMAIL);
    }

    private Long idOf(String email) {
        return studentRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Seeded student " + email + " not found"))
                .getUserId();
    }
}

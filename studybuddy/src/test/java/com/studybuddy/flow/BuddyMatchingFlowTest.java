package com.studybuddy.flow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Full-stack flows for finding study buddies, sending, accepting and declining requests,
 * seeing a buddy's contact number and ending the connection.
 * Replaces MAN-05 to MAN-11, MAN-41 and MAN-42.
 * <p>
 * Every test builds the same small world in its own new course, so the search only finds
 * these students: Alex searches; Ben matches Alex like the Appendix A example (92 with the
 * default settings), Carl partly (70) and Dana hardly (20).
 */
class BuddyMatchingFlowTest extends FlowTestSupport {

    private record World(Browser admin, long courseId, String code, Browser alex, Browser ben, Browser carl,
            Browser dana) {
    }

    private World world() {
        Browser admin = newAdmin();
        String code = "MAT" + unique();
        long courseId = createCourse(admin, code);
        // Same course, 2 hours shared, Ben "either" for mode and size, same goal: (1 + 1 + 0.8 + 1 + 0.8) / 5 = 92
        Browser alex = studentWithPreference("Alex Appendix", courseId, code, "IN_PERSON", "ONE_TO_ONE",
                "EXAM_PREPARATION", "WEDNESDAY", "19:00", "21:00");
        Browser ben = studentWithPreference("Ben Bright", courseId, code, "EITHER", "EITHER",
                "EXAM_PREPARATION", "WEDNESDAY", "19:00", "21:00");
        // Same course, 1 hour shared, same mode and size, other goal: (1 + 0.5 + 1 + 0 + 1) / 5 = 70
        Browser carl = studentWithPreference("Carl Crest", courseId, code, "IN_PERSON", "ONE_TO_ONE",
                "PROBLEM_SOLVING", "WEDNESDAY", "19:00", "20:00");
        // Same course only: 1 / 5 = 20
        Browser dana = studentWithPreference("Dana Dusk", courseId, code, "ONLINE", "SMALL_GROUP",
                "CONCEPT_REVIEW", "MONDAY", "10:00", "12:00");
        return new World(admin, courseId, code, alex, ben, carl, dana);
    }

    private static long studentId(Browser onMatches, String name) {
        return onMatches.id("href=\"/students/(\\d+)\">" + name + "<");
    }

    private static String card(Browser onMatches, String name) {
        return onMatches.sectionAfter(">" + name + "<", "<li class=\"match-card\"");
    }

    private static void sendRequest(Browser from, World world, String toName, String message) {
        from.open("/matches?courseId=" + world.courseId());
        from.submit("/match-requests", "recipientId", String.valueOf(studentId(from, toName)), "message", message);
    }

    // S3, S4, MAT-2: a course search lists every match ranked by score, the Appendix A pair scoring 92 (MAN-05)
    @Test
    void courseSearchRanksStudentsByScore() {
        World world = world();
        Browser alex = world.alex();
        alex.open("/matches?courseId=" + world.courseId());

        String text = alex.text();
        assertThat(text).contains("3 students, ranked by Balanced matching.");
        assertThat(text).contains("Ben Bright", "Carl Crest", "Dana Dusk").doesNotContain("Alex Appendix");
        assertThat(text.indexOf("Ben Bright")).isLessThan(text.indexOf("Carl Crest"));
        assertThat(text.indexOf("Carl Crest")).isLessThan(text.indexOf("Dana Dusk"));
        assertThat(card(alex, "Ben Bright")).contains(">92<");
        assertThat(card(alex, "Carl Crest")).contains(">70<");
        assertThat(card(alex, "Dana Dusk")).contains(">20<");
    }

    // S5, S3: the goal, shared-time and study mode filters each narrow the list (MAN-06)
    @Test
    void filtersNarrowTheList() {
        World world = world();
        Browser alex = world.alex();
        String search = "/matches?courseId=" + world.courseId();

        alex.open(search + "&studyGoal=EXAM_PREPARATION");
        assertThat(alex.text()).contains("1 student,", "Ben Bright").doesNotContain("Carl Crest", "Dana Dusk");

        alex.open(search + "&sharedTimeOnly=true");
        assertThat(alex.text()).contains("Ben Bright", "Carl Crest").doesNotContain("Dana Dusk");

        // Students who chose "In person or online" pass either mode filter
        alex.open(search + "&studyMode=ONLINE");
        assertThat(alex.text()).contains("Ben Bright", "Dana Dusk").doesNotContain("Carl Crest");
    }

    // S7, REQ-1, TD-5, K12: a sent request with its message is framed under Sent and both cards show a label instead of Send (MAN-08, MAN-41)
    @Test
    void sentRequestShowsUnderSentAndLabelsBothCards() {
        World world = world();
        Browser alex = world.alex();

        sendRequest(alex, world, "Carl Crest", "Want to study together?");
        assertThat(alex.url()).isEqualTo("/match-requests");
        assertThat(alex.text()).contains("Request sent to Carl Crest. Their reply will show under Sent below.");
        String sent = alex.sectionAfter("<h2>Sent</h2>", "</main>");
        assertThat(sent).contains("match-card match-card-new", "Carl Crest", "Waiting for a reply",
                "Want to study together?");

        // A message that is just "no" is still shown
        sendRequest(alex, world, "Dana Dusk", "no");
        assertThat(alex.sectionAfter(">Dana Dusk<", "</li>")).contains("Waiting for a reply");
        assertThat(alex.sectionAfter("<h2>Sent</h2>", "</main>")).contains("<p>no</p>");

        alex.open("/matches?courseId=" + world.courseId());
        assertThat(card(alex, "Carl Crest")).contains("Request sent").doesNotContain("Send request");
        assertThat(card(alex, "Ben Bright")).contains("Send request");

        Browser carl = world.carl();
        carl.open("/matches?courseId=" + world.courseId());
        assertThat(card(carl, "Alex Appendix")).contains("They've sent you a request", "href=\"/match-requests\"")
                .doesNotContain("Send request");
    }

    // S8, REQ-3, REQ-4, TD-5: accepting makes the two study buddies, and only then is each one's contact number shown, on Study buddies only (MAN-09, MAN-42)
    @Test
    void acceptingSharesContactNumbersOnStudyBuddiesOnly() {
        World world = world();
        Browser alex = world.alex();
        Browser ben = world.ben();
        sendRequest(ben, world, "Alex Appendix", "Hi");
        String bensNumber = numberOf(ben);
        String alexsNumber = numberOf(alex);

        alex.open("/match-requests");
        assertThat(alex.html()).doesNotContain(bensNumber);
        long requestId = alex.idAfter(">Ben Bright<", "/match-requests/(\\d+)/accept");
        alex.submit("/match-requests/" + requestId + "/accept");

        assertThat(alex.text())
                .contains("Request accepted. You're now study buddies: their contact number is on the Study buddies page.");
        String received = alex.sectionAfter("<h2>Received</h2>", "<h2>Sent</h2>");
        assertThat(received).contains("Accepted").doesNotContain("/accept", "/reject");
        assertThat(alex.html()).doesNotContain(bensNumber);

        alex.open("/connections");
        assertThat(alex.text()).contains("Ben Bright", bensNumber);
        ben.open("/connections");
        assertThat(ben.text()).contains("Alex Appendix", alexsNumber);

        alex.open("/matches?courseId=" + world.courseId());
        assertThat(card(alex, "Ben Bright")).contains("You're already study buddies", "href=\"/connections\"")
                .doesNotContain("Send request", bensNumber);
    }

    // S8, REQ-2, TD-5: declining shows "Declined" to both students, makes no connection, and the sender may ask again (MAN-10)
    @Test
    void decliningShowsDeclinedAndMakesNoConnection() {
        World world = world();
        Browser alex = world.alex();
        Browser dana = world.dana();
        sendRequest(alex, world, "Dana Dusk", "Study?");

        dana.open("/match-requests");
        long requestId = dana.idAfter(">Alex Appendix<", "/match-requests/(\\d+)/reject");
        dana.submit("/match-requests/" + requestId + "/reject");
        assertThat(dana.text()).contains("Request declined.");
        assertThat(dana.sectionAfter("<h2>Received</h2>", "<h2>Sent</h2>")).contains("Declined")
                .doesNotContain("/accept");

        alex.open("/match-requests");
        assertThat(alex.sectionAfter("<h2>Sent</h2>", "</main>")).contains("Dana Dusk", "Declined");
        alex.open("/connections");
        assertThat(alex.text()).doesNotContain("Dana Dusk");
        alex.open("/matches?courseId=" + world.courseId());
        assertThat(card(alex, "Dana Dusk")).contains("Send request");
    }

    // REQ-2: a student who didn't receive a request can't accept it
    @Test
    void onlyTheRecipientCanAcceptARequest() {
        World world = world();
        sendRequest(world.ben(), world, "Alex Appendix", "Hi");
        Browser alex = world.alex();
        alex.open("/match-requests");
        long requestId = alex.idAfter(">Ben Bright<", "/match-requests/(\\d+)/accept");

        Browser carl = world.carl();
        carl.open("/match-requests");
        carl.submit("/match-requests/" + requestId + "/accept");
        assertThat(carl.text()).contains("Only the student who received this request can accept it.");
        alex.open("/match-requests");
        assertThat(alex.sectionAfter("<h2>Received</h2>", "<h2>Sent</h2>")).contains("Waiting for a reply");
    }

    // S9, REQ-5: ending a connection removes it for both students and hides the contact numbers again (MAN-11)
    @Test
    void endingAConnectionHidesTheContactNumbersAgain() {
        World world = world();
        Browser alex = world.alex();
        Browser ben = world.ben();
        String bensNumber = numberOf(ben);
        sendRequest(alex, world, "Ben Bright", "Hi");
        ben.open("/match-requests");
        ben.submit("/match-requests/" + ben.idAfter(">Alex Appendix<", "/match-requests/(\\d+)/accept") + "/accept");

        alex.open("/connections");
        assertThat(alex.text()).contains("Ben Bright", bensNumber);
        alex.submit("/connections/" + alex.idAfter(">Ben Bright<", "/connections/(\\d+)/end") + "/end");

        assertThat(alex.text()).contains("Connection ended. You can no longer see each other's contact numbers.")
                .doesNotContain(bensNumber);
        ben.open("/connections");
        assertThat(ben.text()).contains("You don't have any study buddies yet.").doesNotContain("Alex Appendix");
    }

    // The student's own contact number, read from their profile form
    private static String numberOf(Browser student) {
        student.open("/student/profile");
        return String.valueOf(student.id("id=\"contactNumber\" name=\"contactNumber\"[^>]*value=\"(\\d{8})\""));
    }
}

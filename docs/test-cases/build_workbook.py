"""
Builds docs/test-cases/study-buddy-test-cases.xlsx from:
  - the test sources in studybuddy/src/test/java (method names, requirement-ID comments, @Disabled reasons)
  - the last surefire run in studybuddy/target/surefire-reports (Pass / Fail / Known bug status)
  - the descriptions below (titles, steps, data, expected results, manual cases, gaps)

Run from the repository root AFTER ./mvnw test:
    python docs/test-cases/build_workbook.py

It stops with an error if a test method in src/test has no description here, or a description
names a test that doesn't exist, so the workbook can't drift from the code.
Needs openpyxl (pip install openpyxl). Nothing here touches pom.xml or src/.
"""
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from pathlib import Path

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter

ROOT = Path(__file__).resolve().parents[2]
TEST_SRC = ROOT / "studybuddy" / "src" / "test" / "java"
REPORTS = ROOT / "studybuddy" / "target" / "surefire-reports"
OUT = Path(__file__).resolve().parent / "study-buddy-test-cases.xlsx"

# Full-suite results before Batch A (recorded by hand from the baseline run on 2026-10-23)
BASELINE = {"run": 99, "passed": 99, "failed": 0, "errors": 0, "skipped": 0}

ID_PATTERN = re.compile(r"\b(?:[SLAD]\d+|ACC-\d+|CRS-\d+|PRF-\d+|PRE-\d+|REQ-\d+|GRP-\d+|MAT-\d+|SEC-\d+|K\d+)\b")
TYPES = ["happy", "negative", "boundary", "permission", "conflict", "side-effect"]

# --------------------------------------------------------------------------------------
# Requirements and business rules (docs/requirements.md)
# --------------------------------------------------------------------------------------
REQUIREMENTS = [
    ("S1", "Student", "Create and update a profile (name, school, programme, year, contact number, courses taken)."),
    ("S2", "Student", "Study preferences (course, study mode, weekly slots, group-size preference, goal)."),
    ("S3", "Student", "Search buddies by course or goal."),
    ("S4", "Student", "Ranked list by matching score."),
    ("S5", "Student", "Filter matches (course, goal, mode, group size, shared time)."),
    ("S6", "Student", "View another student's public profile; contact number hidden until a match is accepted."),
    ("S7", "Student", "Send a buddy request with an optional message."),
    ("S8", "Student", "Accept or decline a request; contact number visible after accept."),
    ("S9", "Student", "View connections and end one."),
    ("L1", "Leader", "Create a study group for a course."),
    ("L2", "Leader", "Group fields: name, description, goals, mode, availability, max size."),
    ("L3", "Leader", "Update or close a group."),
    ("L4", "Leader", "View join requests."),
    ("L5", "Leader", "Accept or reject join requests."),
    ("L6", "Leader", "Remove a member."),
    ("A1", "Admin", "Create, update, delete user accounts (today: admins only create ADMIN accounts)."),
    ("A2", "Admin", "View account status and usage."),
    ("A3", "Admin", "Configure matching criteria (five weights, 0 switches one off)."),
    ("A4", "Admin", "Configure matching strategy and weights."),
    ("D1", "Data/design", "At least 10 courses and 50 students seeded."),
    ("D2", "Data/design", "Interchangeable matching strategies (Balanced, AvailabilityFirst, CourseFirst)."),
    ("D3", "Data/design", "Authorization: admin pages refuse non-admins; student pages refuse admins and anonymous users."),
    ("ACC-1", "Accounts", "Email trimmed, lower-cased, name@domain.tld shape, not blank."),
    ("ACC-2", "Accounts", "Password at least 8 characters, not all spaces, at most 72 UTF-8 bytes; not trimmed."),
    ("ACC-3", "Accounts", "Email must not belong to another account; unique in the database."),
    ("ACC-4", "Accounts", "The last active admin cannot be deactivated or deleted."),
    ("ACC-5", "Accounts", "Deleting a student with requests, groups or connections fails; admin told to deactivate."),
    ("ACC-6", "Accounts", "Deactivated accounts cannot log in. Open sessions are not ended."),
    ("ACC-7", "Accounts", "AccountManagementService is admin-only; passwordHash never exposed."),
    ("ACC-8", "Accounts", "AuthService looks up the email exactly as typed (see K1)."),
    ("CRS-1", "Courses", "Anyone logged in lists courses; create/update/delete admin-only; blank code/name refused by the controller."),
    ("PRF-1", "Profile", "Name, school, programme required; year 1 to 5; contact number exactly 8 digits."),
    ("PRF-2", "Profile", "Can't add a course already taken; can't remove one not taken or with a preference."),
    ("PRF-3", "Profile", "The public profile never includes the contact number."),
    ("PRE-1", "Preferences", "Mode, arrangement, goal required; course must be taken; one preference per course."),
    ("PRE-2", "Preferences", "Only the owner can read, update, delete a preference or its slots."),
    ("PRE-3", "Preferences", "Slot: start before end; no overlap with the preference's other slots (touching allowed)."),
    ("REQ-1", "Requests", "No request to yourself, none if pending either way, none if already connected."),
    ("REQ-2", "Requests", "Only the recipient accepts/rejects, only while pending; only participants view."),
    ("REQ-3", "Requests", "Accepting creates a connection (idempotent); rejecting does not."),
    ("REQ-4", "Requests", "Contact number only in ConnectionSummary, never in request or profile summaries."),
    ("REQ-5", "Requests", "Only a participant ends a connection; ending deletes it; no connection with yourself."),
    ("GRP-1", "Groups", "Create needs a course; name required (max 255), description max 1000, size at least 2."),
    ("GRP-2", "Groups", "Only the leader updates, closes, views requests, accepts, rejects, removes."),
    ("GRP-3", "Groups", "Update needs an active group; size not below member count."),
    ("GRP-4", "Groups", "Join: active, not full, not leader, no duplicate; rejected/removed may ask again."),
    ("GRP-5", "Groups", "Accept needs a pending own membership and space; remove needs accepted non-leader."),
    ("GRP-6", "Groups", "Closing turns pending into GROUP_CLOSED and freezes; removeFromMyGroups only when closed."),
    ("GRP-7", "Groups", "Group slots 1-3, 08:00-23:00 in 15-minute steps, end after start, no overlap."),
    ("MAT-1", "Matching", "Config: strategy required, weights >= 0, total > 0, session minutes > 0."),
    ("MAT-2", "Matching", "Per-criterion scores and weighted overall score; Appendix A scores 92."),
    ("MAT-3", "Matching", "Each student once with best result; inactive excluded; strategy ordering."),
    ("MAT-4", "Matching", "The EITHER score must be between 0 and 1."),
    ("SEC-1", "Security", "URL rules: public, ADMIN and STUDENT paths."),
    ("SEC-2", "Security", "Anonymous -> /login; wrong role -> 403; POST without CSRF -> 403."),
    ("SEC-3", "Security", "H2 console open with CSRF off (development only)."),
]

# Where uncovered items are scheduled
PLANNED = {
    "L1": "Batch B / C", "L2": "Batch B / C", "L3": "Batch B / C", "L4": "Batch B / C", "L5": "Batch B / C",
    "L6": "Batch B / C", "GRP-1": "Batch B", "GRP-2": "Batch B", "GRP-3": "Batch B", "GRP-4": "Batch B",
    "GRP-5": "Batch B", "GRP-6": "Batch B", "GRP-7": "Batch B", "D1": "Batch D", "SEC-3": "Batch C",
    "S6": "Gap K7 (no profile page)", "MAT-4": "Batch D (more bounds)", "S5": "Batch D (mode, group size)",
    "PRF-1": "Batch E (boundaries)", "PRE-1": "Batch E", "PRE-2": "Batch E", "REQ-4": "Batch E (negative)",
    "SEC-1": "Batch C (other URL groups)", "SEC-2": "Batch C (other URL groups)", "D3": "Batch C (other URL groups)",
    "A3": "Batch C / D (controller, service)", "MAT-1": "Batch C / D",
    "ACC-8": "Fix K1, then remove @Disabled from AUK-01",
}

# --------------------------------------------------------------------------------------
# Test classes
# --------------------------------------------------------------------------------------
CLASSES = {
    # Existing (teammates)
    "MatchRequestControllerTest": ("MRC", "Existing", "@WebMvcTest with the real SecurityConfig; MatchRequestService is a Mockito mock. Logged in as student alex@test.com unless the test says otherwise."),
    "MatchingConfigTest": ("MCF", "Existing", "Plain unit test (no Spring). Config starts BALANCED, all weights 20, 120-minute session."),
    "CompatibilityCalculatorTest": ("CCA", "Existing", "Plain unit test (no Spring). EITHER score 0.8, target session 120 minutes."),
    "MatchingStrategyTest": ("MST", "Existing", "Plain unit test. Results: Amy (score 70, same course, 60 shared min), Ben (85, other course, 30 min), Cal (60, same course, 180 min)."),
    "MatchRequestRepositoryTest": ("MRR", "Existing", "@DataJpaTest, empty H2. Sender and recipient students saved; one unsaved PENDING request between them."),
    "MatchRequestServiceTest": ("MRS", "Existing", "@DataJpaTest + MatchRequestService + StudyBuddyConnectionService, empty H2. Students Alex, Ben, Chloe; Alex has sent Ben a PENDING request."),
    "StudyBuddyConnectionServiceTest": ("SBC", "Existing", "@DataJpaTest + StudyBuddyConnectionService, empty H2. Students Alex, Ben, Chloe; Alex and Ben are connected."),
    "MatchingServiceTest": ("MSV", "Existing", "@SpringBootTest @Transactional on the SEEDED data (Alex = student@studybuddy.com, Ben = ben.koh@studybuddy.com); rolled back after each test."),
    "StudentProfileServiceTest": ("SPS", "Existing", "@SpringBootTest @Transactional on the SEEDED data (Alex, Ben: IS442, IS216, CS201); rolled back after each test."),
    "StudyPreferenceServiceTest": ("SPF", "Existing", "@SpringBootTest @Transactional on the SEEDED data; Ben has one IS442 preference (Wed 19-21, Thu 18-20); rolled back."),
    "StudybuddyApplicationTests": ("APP", "Existing", "@SpringBootTest: full application context, seeders run."),
    # New (Batch A)
    "AccountCredentialsTest": ("CRD", "New", "@DataJpaTest + AccountCredentials, empty H2 (no seeders). Each test creates its own accounts and is rolled back."),
    "AccountManagementServiceTest": ("AMS", "New", "@DataJpaTest + AccountManagementService + AccountCredentials, empty H2, method security ON (nested @TestConfiguration @EnableMethodSecurity, BCrypt). Runs as ADMIN unless stated. Own data, rolled back."),
    "AccountManagementControllerTest": ("AMC", "New", "@WebMvcTest with the real SecurityConfig; AccountManagementService is a Mockito mock. Logged in as ADMIN admin@test.com unless stated."),
    "AuthServiceTest": ("AUS", "New", "@DataJpaTest + AuthService, empty H2. Own accounts, rolled back."),
    "AuthServiceKnownBugTest": ("AUK", "New", "@DataJpaTest + AuthService, empty H2. @Disabled known-bug test: describes the desired behaviour."),
    "AuthControllerTest": ("AUC", "New", "@WebMvcTest with the real SecurityConfig and real AuthService (BCrypt); StudentProfileService and UserRepository are Mockito mocks. Not logged in unless stated."),
    "CourseServiceTest": ("CRS", "New", "@DataJpaTest + CourseService, empty H2. Own data, rolled back."),
    "CourseControllerTest": ("CRC", "New", "@WebMvcTest with the real SecurityConfig; CourseService is a Mockito mock. Logged in as ADMIN unless stated."),
    "CourseControllerKnownBugTest": ("CRK", "New", "@WebMvcTest with the real SecurityConfig; CourseService mocked; ADMIN. @Disabled known-bug tests: describe the desired behaviour."),
}


def t(method, kind, title, steps, data, expected, ids=None, notes=""):
    assert kind in TYPES, f"{method}: unknown type {kind}"
    return {"method": method, "type": kind, "title": title, "steps": steps, "data": data,
            "expected": expected, "ids": ids, "notes": notes}


SEEDED = "Depends on seeded data (seeders run in @SpringBootTest)."

CASES = {
    # ================================ EXISTING ================================
    "MatchRequestControllerTest": [
        t("unauthenticatedRequestRedirectsToLogin", "permission", "Anonymous user is sent to login", "GET /match-requests with no user", "-", "302 to /login; service not called", "S7, D3, SEC-2"),
        t("adminCannotAccessMatchRequestsPage", "permission", "Admin refused on the match requests page", "GET /match-requests as ADMIN", "admin@test.com", "403; service not called", "D3, SEC-1"),
        t("postWithoutCsrfTokenIsForbidden", "permission", "POSTs without CSRF token refused", "POST send, accept, reject without csrf()", "requestId 5", "403 each; service not called", "SEC-2"),
        t("pageShowsLoggedInStudentsReceivedAndSentRequests", "happy", "Page lists the logged-in student's requests", "Mock: 0 received, 1 sent; GET /match-requests", "alex@test.com", "200, view student/match-requests, model has received and sent; looked up by the logged-in email", "S7, S8"),
        t("sendRequestRedirectsWithSuccessMessage", "happy", "Send a request with a message", "POST /match-requests recipientId=2, message", "message 'Study?'", "Redirect /match-requests, flash 'Request sent.'; sender is the logged-in student", "S7"),
        t("sendRequestWithoutMessageIsAllowed", "boundary", "Message is optional", "POST /match-requests recipientId=2, no message", "message absent", "Redirect with 'Request sent.'; service gets null message", "S7"),
        t("sendRequestWithoutRecipientIdReturnsBadRequest", "negative", "Recipient is required", "POST /match-requests without recipientId", "-", "400; service not called", "S7"),
        t("duplicateRequestShowsServiceMessage", "conflict", "Duplicate pending request message", "Mock throws DuplicateMatchRequestException; POST", "recipientId 2", "Redirect with the exception message as flash error", "REQ-1"),
        t("requestToSelfShowsServiceMessage", "negative", "Request to yourself shown as error", "Mock throws IllegalArgumentException; POST", "recipientId 1", "Flash error 'Same sender and recipient'", "REQ-1"),
        t("requestToUnknownStudentShowsError", "negative", "Unknown recipient", "Mock throws StudentNotFoundException; POST", "recipientId 999", "Flash error 'That student no longer exists.'", "S7"),
        t("acceptRedirectsWithSuccessMessage", "happy", "Accept a request", "POST /match-requests/5/accept", "requestId 5", "Flash success about the Study buddies page; service called with logged-in email and 5", "S8, REQ-2"),
        t("acceptUnknownRequestShowsError", "negative", "Accept a missing request", "Mock throws MatchRequestNotFoundException; POST accept", "requestId 5", "Flash 'That request no longer exists.'", "S8"),
        t("acceptByNonRecipientShowsError", "permission", "Only the recipient accepts", "Mock throws NotMatchRequestParticipantException; POST accept", "requestId 5", "Flash 'Only the student who received this request can accept it.'", "REQ-2"),
        t("acceptNonPendingRequestShowsEntityMessage", "conflict", "Accept an answered request", "Mock throws IllegalStateException; POST accept", "requestId 5", "Flash 'Only PENDING requests can be accepted'", "REQ-2"),
        t("rejectRedirectsWithSuccessMessage", "happy", "Decline a request", "POST /match-requests/5/reject", "requestId 5", "Flash 'Request declined.'", "S8"),
        t("rejectUnknownRequestShowsError", "negative", "Decline a missing request", "Mock throws MatchRequestNotFoundException; POST reject", "requestId 5", "Flash 'That request no longer exists.'", "S8"),
        t("rejectByNonRecipientShowsError", "permission", "Only the recipient declines", "Mock throws NotMatchRequestParticipantException; POST reject", "requestId 5", "Flash 'Only the student who received this request can decline it.'", "REQ-2"),
        t("rejectNonPendingRequestShowsEntityMessage", "conflict", "Decline an answered request", "Mock throws IllegalStateException; POST reject", "requestId 5", "Flash 'Only PENDING requests can be rejected'", "REQ-2"),
    ],
    "MatchingConfigTest": [
        t("sharesAreEachWeightOverTheTotal", "happy", "Weight shares are relative", "update(weights 1,3,0,0,0)", "weights 1/3/0/0/0", "Total 4; course share 25%, availability 75%", "A3, A4, MAT-1"),
        t("rejectsAllZeroWeightsAndLeavesSettingsUnchanged", "side-effect", "All-zero weights refused, config unchanged", "update(COURSE_FIRST, all 0, 60)", "weights all 0", "InvalidMatchingConfigException; strategy still BALANCED, session still 120", "A3, MAT-1"),
        t("rejectsNegativeWeightsMissingStrategyAndNonPositiveSessionLength", "negative", "Invalid settings refused", "update with -1 weight; null strategy; session 0", "-1, null, 0", "InvalidMatchingConfigException each time", "A3, A4, MAT-1"),
    ],
    "CompatibilityCalculatorTest": [
        t("appendixAExampleScores92WithEqualWeights", "happy", "Brief Appendix A example scores 92", "Score Student A vs Student B (IS442)", "Appendix A preferences and slots", "course 1, shared 120 min, availability 1, mode 0.8, goal 1, group size 0.8; weighted 92", "S4, MAT-2"),
        t("availabilityIsProportionalBelowTheTargetSession", "boundary", "Availability is proportional", "Mon 10-12 vs Mon 11-14", "60 shared minutes", "availability 0.5", "MAT-2"),
        t("availabilityIsCappedAtFullMarks", "boundary", "Availability capped at 1", "Mon 9-17 vs Mon 9-17", "480 shared minutes", "availability 1.0", "MAT-2"),
        t("sharedTimeIsAddedUpAcrossSeveralSlots", "happy", "Shared time summed over slots", "Two 1-hour overlaps on Mon and Tue", "2 slots each", "sharedMinutes 120", "MAT-2"),
        t("slotsThatOnlyTouchOrFallOnDifferentDaysShareNoTime", "boundary", "Touching or other-day slots share nothing", "Mon 10-12 vs Mon 12-14 and Tue 10-12", "-", "sharedMinutes 0, availability 0", "MAT-2, PRE-3"),
        t("differentCoursesDoNotMatch", "negative", "Different courses score 0", "IS442 vs CS201", "-", "course 0", "MAT-2"),
        t("conflictingStudyModesScoreZeroButBothEitherIsAFullMatch", "boundary", "Study mode scoring", "IN_PERSON vs ONLINE; EITHER vs EITHER", "-", "0 and 1.0", "MAT-2"),
        t("conflictingGroupSizesAndGoalsScoreZero", "negative", "Group size and goal conflicts", "ONE_TO_ONE vs SMALL_GROUP, different goals", "-", "groupSize 0, studyGoal 0", "MAT-2"),
        t("aZeroWeightCriterionIsIgnored", "boundary", "Zero weight switches a criterion off", "weights 0,1,0,3,0 on breakdown (0,0.5,0,1,0)", "-", "87.5", "A3, MAT-2"),
        t("rejectsAnEitherScoreOutsideZeroToOne", "negative", "EITHER score outside 0..1 refused", "new CompatibilityCalculator(1.5)", "1.5", "IllegalArgumentException", "MAT-4"),
    ],
    "MatchingStrategyTest": [
        t("balancedRanksByWeightedScore", "happy", "Balanced orders by score", "Sort the three results with Balanced", "-", "Ben, Amy, Cal", "S4, D2, MAT-3"),
        t("availabilityFirstRanksBySharedMinutesThenScore", "happy", "Availability first orders by shared minutes", "Sort with AvailabilityFirst", "-", "Cal, Amy, Ben", "D2, MAT-3"),
        t("courseFirstRanksSameCourseFirstThenScore", "happy", "Course first orders same course first", "Sort with CourseFirst", "-", "Amy, Cal, Ben", "D2, MAT-3"),
        t("equalScoresAreOrderedByName", "boundary", "Ties broken by name", "Zoe and Adam both 50", "-", "Adam, Zoe", "MAT-3"),
    ],
    "MatchRequestRepositoryTest": [
        t("saveMatchRequestAndFindById", "happy", "Saved request can be read back", "saveAndFlush, findById", "-", "Id generated; status PENDING; sender matches", "S7"),
        t("findBySenderReturnsSendersRequests", "happy", "Find by sender", "save, findBySender_userId", "-", "1 request for the sender", "S7"),
        t("findByRecipientReturnsRecipientsRequests", "happy", "Find by recipient", "save, findByRecipient_userId", "-", "1 request for the recipient", "S8"),
        t("deleteMatchRequestById", "happy", "Delete by id", "save, deleteById, findById", "-", "Not found after delete", "S7"),
        t("existsReturnsTrueForPendingRequestInSameDirection", "happy", "Pending request detected", "save, exists(sender, recipient, PENDING)", "-", "true", "REQ-1"),
        t("existsReturnsFalseWhenSenderAndRecipientAreSwapped", "boundary", "Check is one-directional", "exists(recipient, sender, PENDING)", "-", "false", "REQ-1"),
        t("existsReturnsFalseWhenRequestIsNoLongerPending", "boundary", "Accepted request isn't pending", "accept then save; exists(..., PENDING)", "-", "false", "REQ-1"),
    ],
    "MatchRequestServiceTest": [
        t("newRequestIsPendingWithCorrectDetails", "happy", "New request is pending with its message", "getRequestById as sender", "-", "PENDING, message saved, createdAt set", "S7"),
        t("senderSeesRequestInSentList", "happy", "Sender's sent list", "getMatchRequestBySender(Alex)", "-", "Exactly the one request", "S7"),
        t("recipientSeesRequestInReceivedList", "happy", "Recipient's received list", "getMatchRequestByRecipient(Ben)", "-", "Exactly the one request", "S8"),
        t("recipientCanViewRequestById", "happy", "Recipient can view the request", "getRequestById as Ben", "-", "Message returned", "REQ-2"),
        t("unrelatedStudentHasEmptyLists", "side-effect", "Unrelated student sees nothing", "Chloe's sent and received lists", "-", "Both empty", "REQ-2"),
        t("unrelatedStudentCannotViewRequest", "permission", "Non-participant can't view", "getRequestById as Chloe", "-", "NotMatchRequestParticipantException", "REQ-2"),
        t("unrelatedStudentCannotAcceptRequest", "permission", "Non-participant can't accept", "acceptRequest as Chloe", "-", "NotMatchRequestParticipantException", "REQ-2"),
        t("senderCannotAcceptOwnRequest", "permission", "Sender can't accept own request", "acceptRequest as Alex", "-", "NotMatchRequestParticipantException", "REQ-2"),
        t("pendingRequestDoesNotConnect", "side-effect", "Pending request shares nothing", "areConnected, getConnections", "-", "Not connected; no connections", "REQ-3, REQ-4"),
        t("acceptingConnectsBothStudents", "happy", "Accepting connects and shares contact numbers", "acceptRequest as Ben", "contacts 91234567 / 92345678", "ACCEPTED; connected both ways; each sees the other's number", "S8, REQ-3, REQ-4"),
        t("cannotSendRequestToExistingStudyBuddy", "conflict", "No request between buddies", "accept, then createRequest both ways", "-", "DuplicateMatchRequestException both ways", "REQ-1"),
        t("canSendNewRequestAfterConnectionEnds", "happy", "Can reconnect after ending", "accept, endConnection, createRequest", "-", "New PENDING request", "S9, REQ-5"),
        t("cannotAcceptSameRequestTwice", "conflict", "Accept twice refused", "acceptRequest twice", "-", "IllegalStateException", "REQ-2"),
        t("recipientCanRejectRequest", "happy", "Recipient declines", "rejectRequest as Ben; re-read as Alex", "-", "REJECTED, saved", "S8"),
        t("rejectingDoesNotConnect", "side-effect", "Declining doesn't connect", "rejectRequest; areConnected", "-", "false", "REQ-3"),
        t("senderCannotRejectOwnRequest", "permission", "Sender can't decline own request", "rejectRequest as Alex", "-", "NotMatchRequestParticipantException", "REQ-2"),
        t("unrelatedStudentCannotRejectRequest", "permission", "Non-participant can't decline", "rejectRequest as Chloe", "-", "NotMatchRequestParticipantException", "REQ-2"),
        t("cannotRejectSameRequestTwice", "conflict", "Decline twice refused", "rejectRequest twice", "-", "IllegalStateException", "REQ-2"),
        t("cannotRejectAcceptedRequest", "conflict", "Can't decline after accepting", "accept, then reject", "-", "IllegalStateException", "REQ-2"),
        t("cannotAcceptRejectedRequest", "conflict", "Can't accept after declining", "reject, then accept", "-", "IllegalStateException", "REQ-2"),
        t("rejectingUnknownRequestIsRejected", "negative", "Decline missing request", "rejectRequest(999999)", "-", "MatchRequestNotFoundException", "S8"),
        t("senderCanSendNewRequestAfterRejection", "boundary", "New request allowed after a decline", "reject, createRequest again", "-", "New PENDING request with a new id", "REQ-1"),
        t("cannotSendRequestToSelf", "negative", "No request to yourself", "createRequest(Alex -> Alex)", "-", "IllegalArgumentException", "REQ-1"),
        t("cannotSendRequestToUnknownRecipient", "negative", "Unknown recipient", "createRequest to 999999", "-", "StudentNotFoundException", "S7"),
        t("unknownSenderEmailIsRejected", "negative", "Unknown sender email", "createRequest as ghost@test.com", "-", "StudentNotFoundException", "S7"),
        t("viewingUnknownRequestIsRejected", "negative", "View missing request", "getRequestById(999999)", "-", "MatchRequestNotFoundException", "REQ-2"),
        t("acceptingUnknownRequestIsRejected", "negative", "Accept missing request", "acceptRequest(999999)", "-", "MatchRequestNotFoundException", "S8"),
        t("cannotSendDuplicatePendingRequest", "conflict", "No second pending request", "createRequest Alex -> Ben again", "-", "DuplicateMatchRequestException 'already has a pending request'", "REQ-1"),
        t("recipientCannotSendRequestBackWhilePending", "conflict", "No reverse request while pending", "createRequest Ben -> Alex", "-", "DuplicateMatchRequestException 'Accept it instead'", "REQ-1"),
    ],
    "StudyBuddyConnectionServiceTest": [
        t("connectingTheSamePairTwiceKeepsOneConnection", "boundary", "connect is idempotent", "connect(Alex,Ben) and connect(Ben,Alex) again", "-", "Still 1 connection", "REQ-3"),
        t("eachStudentSeesTheOtherAsTheirBuddy", "happy", "Each sees the other and their number", "getConnections for Alex, Ben, Chloe", "-", "Alex sees Ben 92345678, Ben sees Alex 91234567, Chloe none", "S9, REQ-4"),
        t("eitherStudentCanEndTheConnection", "happy", "Either side ends the connection", "endConnection as Ben", "-", "Not connected; Alex has none", "S9, REQ-5"),
        t("anotherStudentCannotEndTheConnection", "permission", "Outsider can't end it", "endConnection as Chloe", "-", "ConnectionNotFoundException; still connected", "REQ-5"),
        t("endingAnUnknownConnectionFails", "negative", "End a missing connection", "endConnection(999)", "-", "ConnectionNotFoundException", "REQ-5"),
        t("studentCannotConnectWithThemselves", "negative", "No connection with yourself", "connect(Alex, Alex)", "-", "IllegalArgumentException", "REQ-5"),
    ],
    "MatchingServiceTest": [
        t("appendixAPairComesTopForIs442WithDefaultSettings", "happy", "Ben Koh tops Alex's IS442 search with 92", "findMatches(Alex, course IS442)", "seeded", "First is Ben Koh, score 92; all IS442", "S3, S4, MAT-2", SEEDED),
        t("neverListsTheSearcherOrTheSameStudentTwice", "side-effect", "No self and no duplicates", "findMatches(Alex, no filters)", "seeded", "No 'Alex Tan'; no duplicate names", "MAT-3", SEEDED),
        t("preferencesForACourseTheSearcherAlsoWantsAreComparedOnThatCourse", "happy", "Same-course preferences compared together", "findMatches(Alex, no filters)", "seeded", "Results in Alex's courses all score course 1.0", "S3, MAT-3", SEEDED),
        t("filtersNarrowTheResults", "happy", "Goal and shared-time filters", "findMatches(goal EXAM_PREPARATION, sharedTimeOnly)", "seeded", "All results have that goal and shared minutes > 0", "S3, S5", SEEDED),
        t("theAdminsStrategyDecidesTheOrder", "happy", "Admin's strategy decides the order", "Set AVAILABILITY_FIRST, search IS442", "seeded", "Sorted by shared minutes, descending", "A4, D2, MAT-3", SEEDED),
        t("deactivatedStudentsAreNotMatched", "side-effect", "Inactive students excluded", "Deactivate Ben, search IS442", "seeded", "Ben Koh not listed", "MAT-3", SEEDED),
    ],
    "StudentProfileServiceTest": [
        t("updatedProfileIsSaved", "happy", "Profile update is saved", "updateProfile(Alex, ...)", "Computer Science, year 3, 87654321", "Saved values read back", "S1, PRF-1", SEEDED),
        t("signUpCreatesAStudentWhoCanLogIn", "happy", "Sign-up stores normalised email and a hash", "createProfile('  New.Student@Example.com ', ...)", "password123", "Email lower-cased and trimmed; active; hash matches password", "S1, ACC-1, ACC-2", SEEDED),
        t("signUpRejectsTakenEmailAndBadDetails", "negative", "Sign-up refuses taken email, short password, bad contact", "createProfile three times", "Alex's email; 'short'; '123'", "DuplicateEmailException; InvalidAccountDetailsException; IllegalArgumentException", "S1, ACC-2, ACC-3, PRF-1", SEEDED),
        t("invalidProfileIsRejected", "negative", "Invalid profile values refused", "updateProfile with blank name, year 0, contact 1234", "-", "IllegalArgumentException each time", "PRF-1", SEEDED),
        t("publicProfileHidesContactDetails", "side-effect", "Public profile has no contact or email", "getPublicProfile(Ben)", "-", "Name present; no 92345678 or email", "S6, PRF-3", SEEDED),
        t("addAndRemoveCourse", "happy", "Add and remove a course", "addCourse IS111, removeCourse CS201 (Ben)", "-", "Courses IS442, IS216, IS111", "S1, PRF-2", SEEDED + " Note: removes CS201 while Ben leads the seeded CS201 group (K8)."),
        t("courseRulesAreEnforced", "negative", "Course rules", "add IS442 again; remove IS111; remove IS442", "-", "IllegalStateException each time", "PRF-2", SEEDED),
    ],
    "StudyPreferenceServiceTest": [
        t("createPreferenceWithASlot", "happy", "Create a preference and a slot", "createPreference(Ben, CS201), addAvailabilitySlot Mon 10-12", "-", "Saved with that slot", "S2, PRE-1, PRE-3", SEEDED),
        t("createRulesAreEnforced", "negative", "Create rules", "IS111 (not taken), IS442 (exists), null goal", "-", "IllegalStateException, IllegalStateException, IllegalArgumentException", "PRE-1", SEEDED),
        t("updatePreference", "happy", "Update a preference, slots kept", "updatePreference(Ben IS442)", "-", "Goal changed; 2 slots kept", "S2", SEEDED),
        t("deletingAPreferenceAlsoDeletesItsSlots", "side-effect", "Delete removes slots", "deletePreference(Ben IS442)", "-", "Preference and slots gone", "S2", SEEDED),
        t("anotherStudentCannotTouchAPreference", "permission", "Only the owner", "get, delete, addSlot as Alex on Ben's preference", "-", "StudyPreferenceNotFoundException each time", "PRE-2", SEEDED),
        t("badSlotsAreRejected", "boundary", "Slot order and overlap", "Mon 12-10; Wed 20-22; Wed 21-22", "-", "Two refused; touching slot accepted (3 slots)", "PRE-3", SEEDED),
        t("removeSlot", "happy", "Remove a slot", "removeAvailabilitySlot", "-", "Slot gone; 1 left", "S2, PRE-3", SEEDED),
        t("newPreferenceShowsUpInMatching", "happy", "New preference changes matching", "Ben adds CS201 Fri 13-15; Alex searches CS201", "-", "Ben Koh first", "S2, S3", SEEDED + " K19: relies on random seeded students."),
    ],
    "StudybuddyApplicationTests": [
        t("contextLoads", "happy", "Application starts", "Start the full Spring context", "-", "No error (includes MatchingService's 'every strategy has an implementation' check and all seeders)", "D2", "Indirect: no assertion on seeded counts (D1)."),
    ],

    # ================================ NEW (Batch A) ================================
    "AccountCredentialsTest": [
        t("normaliseEmailTrimsAndLowerCases", "happy", "Email trimmed and lower-cased", "normaliseEmail", "'  Alex.Tan@Example.COM '", "'alex.tan@example.com'"),
        t("normaliseEmailAcceptsValidShapes", "happy", "Valid email shapes accepted", "normaliseEmail", "a@b.co; first.last+tag@mail.smu.edu.sg", "Returned unchanged"),
        t("normaliseEmailRejectsBlank", "negative", "Blank email refused", "normaliseEmail", "null, '', '   '", "InvalidAccountDetailsException 'Enter an email address.'"),
        t("normaliseEmailRejectsMalformedShapes", "negative", "Malformed emails refused", "normaliseEmail for each", "alex; alex@; @example.com; alex@example; 'alex tan@example.com'; alex@@example.com; 'alex@exa mple.com'", "InvalidAccountDetailsException 'Enter a valid email address, like name@example.com.'"),
        t("passwordMinimumIsEightCharacters", "boundary", "Password minimum 7/8", "checkPassword", "'1234567', '12345678'", "7 refused ('at least 8 characters'); 8 accepted"),
        t("passwordMaximumIs72Bytes", "boundary", "Password maximum 72/73 bytes (ASCII)", "checkPassword", "'a' x 72, 'a' x 73", "72 accepted; 73 refused"),
        t("multibytePasswordIsLimitedByBytes", "boundary", "Multibyte password limited by bytes", "checkPassword", "'€' x 24 (72 bytes), '€' x 25 (75 bytes)", "24 accepted; 25 refused although only 25 characters", notes="N1: the message says 'at most 72 characters' but the rule is bytes (Gaps)."),
        t("blankOrNullPasswordIsRejected", "negative", "Blank or null password refused", "checkPassword", "null, '', 8 spaces", "Refused; 8 spaces gives 'Passwords can't be only spaces.'"),
        t("spacesCountTowardsPasswordLength", "boundary", "Spaces count (not trimmed)", "checkPassword", "'      ab'", "Accepted"),
        t("emailAvailableWhenFreeOrKeptBySameAccount", "happy", "Free email, or own email, is available", "requireEmailAvailable", "free@test.com; alex@test.com with Alex's id", "No exception"),
        t("emailTakenByAnotherAccountIsRefused", "conflict", "Email of another account refused", "requireEmailAvailable", "student's and admin's emails", "DuplicateEmailException 'Another account already uses alex@test.com.'"),
    ],
    "AccountManagementServiceTest": [
        t("studentIsRefusedByEveryMethod", "permission", "Student refused by all 9 methods; admin allowed", "As STUDENT call all 9 methods; switch to ADMIN and repeat 5 of them", "student@test.com / ADMIN", "AccessDeniedException for every call as STUDENT; the same calls succeed as ADMIN (proves @PreAuthorize is active)"),
        t("anonymousIsRefused", "permission", "Anonymous refused", "As anonymous call getAllUsers, deleteUser", "-", "AccessDeniedException"),
        t("refusedCallChangesNothing", "side-effect", "Refused calls change nothing", "As STUDENT try deactivate, updateEmail, resetPassword, createAdmin, delete", "alex@test.com", "All refused; account still active, same email and hash; still 1 account"),
        t("getAllUsersListsEveryAccountWithStatus", "happy", "List all accounts with status", "Save admin, Alex, Ben (inactive); getAllUsers", "-", "3 rows with email, display name, role, active flag; createdAt set"),
        t("studentUsageCountsTheirActivity", "happy", "Student usage figures", "Alex: 2 courses, 1 preference, leads 1 group, sent 1, received 2; getAccountStatus", "-", "StudentUsage(2, 1, 1, 1, 2)"),
        t("adminHasNoUsage", "happy", "Admins have no usage", "getAccountStatus(admin)", "-", "usage null"),
        t("accountStatusHasNoPasswordHashOrContactNumber", "side-effect", "AccountStatus never carries hash or contact", "getAccountStatus(Alex); inspect record fields and toString", "contact 91234567", "No passwordHash/password/contactNumber field; toString has neither the hash nor 91234567"),
        t("unknownAccountIsNotFound", "negative", "Unknown id refused by every method", "6 methods with id 999999", "999999", "UserNotFoundException each time"),
        t("createAdminAccountStoresNormalisedEmailAndHash", "happy", "Create admin: normalised email, hash only", "createAdminAccount", "'  New.Admin@Example.COM ', password123", "Email new.admin@example.com; ADMIN; active; no usage; stored as Admin; hash != raw and matches"),
        t("createAdminAccountKeepsSpacesInPassword", "boundary", "Password spaces kept", "createAdminAccount", "' secret pass '", "Hash matches ' secret pass ' and not 'secret pass'"),
        t("createAdminAccountRejectsBadDetailsAndSavesNothing", "negative", "Bad details refused, nothing saved", "createAdminAccount three times", "'not-an-email'; 7-char password; 73-byte password", "InvalidAccountDetailsException each; 0 accounts"),
        t("createAdminAccountRejectsEmailInUseInAnyCase", "conflict", "Email in use refused in any case", "Student alex@test.com exists; createAdminAccount", "' ALEX@Test.com'", "DuplicateEmailException; no admin created"),
        t("updateEmailChangesTheLoginEmail", "happy", "Change login email", "updateEmail", "' Alex.Tan@NewMail.com '", "alex.tan@newmail.com found with the same id; old email not found"),
        t("updateEmailToOwnEmailIsAllowed", "boundary", "Keeping own email allowed", "updateEmail", "'ALEX@test.com'", "Returns alex@test.com"),
        t("updateEmailToAnotherAccountsEmailChangesNothing", "conflict", "Another account's email refused", "updateEmail Alex -> ben@test.com", "-", "DuplicateEmailException; Alex's email unchanged"),
        t("updateEmailRejectsInvalidEmail", "negative", "Invalid new email refused", "updateEmail", "'alex@', '  '", "InvalidAccountDetailsException; email unchanged"),
        t("oldEmailNoLongerFindsStudentAfterUpdate", "side-effect", "Old email no longer finds the student", "updateEmail; studentRepository.findByEmail(old)", "-", "Empty: the old session email no longer matches a student"),
        t("resetPasswordReplacesTheHash", "happy", "Reset password", "resetPassword", "'brand-new-pass'", "New password matches; old doesn't"),
        t("resetPasswordRejectsInvalidPasswordAndKeepsHash", "negative", "Invalid new password refused, hash kept", "resetPassword", "'short', 8 spaces", "InvalidAccountDetailsException; hash unchanged"),
        t("deactivateAndReactivateStudent", "happy", "Deactivate then reactivate", "deactivateUser, reactivateUser", "-", "inactive, then active again (saved)"),
        t("lastActiveAdminCannotBeDeactivated", "conflict", "Last active admin can't be deactivated", "One admin (+ a student); deactivateUser(admin)", "-", "LastAdminException; admin still active"),
        t("adminCanBeDeactivatedWhileAnotherIsActive", "happy", "Deactivate one of two admins", "Two admins; deactivateUser", "-", "inactive"),
        t("inactiveAdminDoesNotCountTowardsLastAdminRule", "boundary", "Inactive admin doesn't count", "Active admin + inactive admin; deactivate the active one", "-", "LastAdminException"),
        t("lastActiveAdminCannotBeDeleted", "conflict", "Last active admin can't be deleted", "deleteUser(only admin)", "-", "LastAdminException; still exists"),
        t("adminCanBeDeletedWhileAnotherIsActive", "happy", "Delete one of two admins", "deleteUser", "-", "Account gone"),
        t("deletingStudentWithoutActivityRemovesTheirPreferences", "happy", "Delete student without activity", "Student with a course and a preference with a slot; deleteUser", "-", "Student, preference and slot gone; course kept"),
        t("deletingStudentWithMatchRequestFails", "conflict", "Delete blocked by a match request", "Alex sent Ben a request; deleteUser(Alex); flush", "-", "DataIntegrityViolationException", notes="In the app the refusal happens at commit; flush forces it in the test."),
        t("deletingGroupLeaderFails", "conflict", "Delete blocked by a group led", "Alex leads a group; deleteUser(Alex); flush", "-", "DataIntegrityViolationException", notes="Refusal at commit in the app."),
        t("deletingStudentWithBuddyFails", "conflict", "Delete blocked by a connection", "Alex and Ben connected; deleteUser(Alex); flush", "-", "DataIntegrityViolationException", notes="Refusal at commit in the app."),
    ],
    "AccountManagementControllerTest": [
        t("anonymousIsRedirectedToLogin", "permission", "Anonymous sent to login", "GET /admin/accounts; POST /admin/accounts/7/delete with CSRF", "-", "302 to /login; service not called"),
        t("studentIsForbiddenFromEveryRoute", "permission", "Student refused on every route", "As STUDENT: GET + 6 POSTs with CSRF", "-", "403 each; service not called"),
        t("postWithoutCsrfTokenIsForbidden", "permission", "POSTs without CSRF refused", "As ADMIN: 6 POSTs without CSRF", "-", "403 each; service not called"),
        t("pageShowsAccountsAndMarksLoggedInAdmin", "happy", "Accounts page", "Mock 2 accounts; GET /admin/accounts", "admin@test.com + a student", "200, view admin/accounts; model holds AccountStatus records and currentEmail; page shows '(you)' and 'Requests sent 1 · received 2'"),
        t("createAdminRedirectsWithSuccess", "happy", "Create admin", "POST /admin/accounts", "new.admin@test.com / password123", "Redirect; flash 'Admin account new.admin@test.com has been created. It can log in straight away.'"),
        t("createAdminWithInvalidDetailsRefillsEmailButNotPassword", "side-effect", "Invalid details: email refilled, password not", "Mock throws InvalidAccountDetailsException; POST", "password 'short'", "Flash error with the service message and newAdminEmail only (2 flash attributes, no password)"),
        t("createAdminWithDuplicateEmailShowsMessage", "conflict", "Duplicate email", "Mock throws DuplicateEmailException; POST", "alex@test.com", "Flash 'Another account already uses alex@test.com.'; email refilled"),
        t("createAdminDatabaseRefusalShowsDuplicateEmailMessage", "conflict", "Database refusal shown as duplicate email", "Mock throws DataIntegrityViolationException; POST", "-", "Flash 'Another account already uses that email.'"),
        t("createAdminWithMissingFieldsSendsBlanksToService", "boundary", "Missing fields sent as blanks", "POST with no fields", "-", "Service called with ('', ''); flash 'Enter an email address.'"),
        t("updateEmailRedirectsWithSuccess", "happy", "Change email", "POST /admin/accounts/7/email", "alex.new@test.com", "Flash 'The account now logs in with alex.new@test.com.'"),
        t("updateEmailOfMissingAccountShowsError", "negative", "Change email of missing account", "Mock throws UserNotFoundException", "-", "Flash 'That account no longer exists.'"),
        t("updateEmailRefusedByServiceShowsItsMessage", "negative", "Invalid or taken new email", "Mock throws Invalid... and Duplicate...", "'alex@', ben@test.com", "Flash with each service message"),
        t("updateEmailDatabaseRefusalShowsDuplicateEmailMessage", "conflict", "Database refusal on email change", "Mock throws DataIntegrityViolationException", "-", "Flash 'Another account already uses that email.'"),
        t("resetPasswordRedirectsWithSuccess", "happy", "Reset password", "POST /admin/accounts/7/password", "brand-new-pass", "Flash 'The password for alex@test.com has been changed.'"),
        t("resetPasswordOfMissingAccountShowsError", "negative", "Reset password of missing account", "Mock throws UserNotFoundException", "-", "Flash 'That account no longer exists.'"),
        t("resetPasswordWithInvalidPasswordShowsMessage", "negative", "Invalid new password", "Mock throws InvalidAccountDetailsException", "short", "Flash with the service message"),
        t("deactivateRedirectsWithSuccess", "happy", "Deactivate", "POST /admin/accounts/7/deactivate", "-", "Flash 'alex@test.com has been deactivated and can no longer log in.'"),
        t("deactivateMissingAccountShowsError", "negative", "Deactivate missing account", "Mock throws UserNotFoundException", "-", "Flash 'That account no longer exists.'"),
        t("deactivateLastAdminShowsMessage", "conflict", "Deactivate last admin", "Mock throws LastAdminException", "-", "Flash 'This is the last active admin account, so it can't be deactivated.'"),
        t("reactivateRedirectsWithSuccess", "happy", "Reactivate", "POST /admin/accounts/7/reactivate", "-", "Flash 'alex@test.com has been reactivated.'"),
        t("reactivateMissingAccountShowsError", "negative", "Reactivate missing account", "Mock throws UserNotFoundException", "-", "Flash 'That account no longer exists.'"),
        t("deleteRedirectsWithSuccess", "happy", "Delete", "POST /admin/accounts/7/delete", "-", "Flash 'The account has been deleted.'"),
        t("deleteMissingAccountShowsError", "negative", "Delete missing account", "Mock throws UserNotFoundException", "-", "Flash 'That account no longer exists.'"),
        t("deleteLastAdminShowsMessage", "conflict", "Delete last admin", "Mock throws LastAdminException", "-", "Flash 'This is the last active admin account, so it can't be deleted.'"),
        t("deleteStudentWithActivityShowsDeactivateInstead", "conflict", "Delete blocked by activity", "Mock throws DataIntegrityViolationException", "-", "Flash 'This student still has match requests, study groups or study buddies, so the account can't be deleted. Deactivate it instead.'"),
    ],
    "AuthServiceTest": [
        t("loadsStudentWithRoleAndHash", "happy", "Student loaded for login", "loadUserByUsername", "alex@test.com", "Username, stored hash, ROLE_STUDENT, enabled"),
        t("loadsAdminWithAdminRole", "happy", "Admin loaded with ADMIN role", "loadUserByUsername", "admin@test.com", "ROLE_ADMIN, enabled"),
        t("deactivatedAccountIsDisabled", "negative", "Deactivated account disabled", "Deactivate; loadUserByUsername", "-", "isEnabled false"),
        t("unknownEmailIsNotFound", "negative", "Unknown email", "loadUserByUsername", "ghost@test.com", "UsernameNotFoundException"),
    ],
    "AuthServiceKnownBugTest": [
        t("emailInAnotherLetterCaseFindsTheAccount", "negative", "Login email in another letter case should find the account", "Save alex@test.com; loadUserByUsername", "'Alex@Test.COM'", "DESIRED: account alex@test.com found. TODAY: UsernameNotFoundException (confirmed when enabled)"),
    ],
    "AuthControllerTest": [
        t("loginAndRegisterPagesArePublic", "permission", "Login and sign-up pages are public", "Anonymous GET /login, /register", "-", "200 with views auth/login and auth/register"),
        t("registerWithoutCsrfIsForbidden", "permission", "Sign-up without CSRF refused", "POST /register without CSRF", "-", "403; service not called"),
        t("registerPassesEveryFieldAndRedirectsToLogin", "happy", "Sign-up", "POST /register with all fields", "new@test.com, password123, New Student, SCIS, Information Systems, 1, 98765432", "Redirect /login?registered; service gets every field exactly"),
        t("registerErrorRefillsEverythingButPassword", "side-effect", "Error refills the form except password", "Service throws InvalidAccountDetailsException", "as above", "Form again with the message; all fields refilled; no password in the model"),
        t("registerDuplicateEmailShowsMessage", "conflict", "Sign-up with taken email", "Service throws DuplicateEmailException", "new@test.com", "'Another account already uses new@test.com.'"),
        t("registerInvalidProfileShowsMessage", "negative", "Invalid profile value", "Service throws IllegalArgumentException", "-", "'Contact number must be 8 digits'"),
        t("registerDatabaseRefusalShowsDuplicateEmailMessage", "conflict", "Database refusal shown as duplicate email", "Service throws DataIntegrityViolationException", "-", "'Another account already uses that email.'"),
        t("blankYearOfStudyIsSentAsZeroAndNotRefilled", "boundary", "Blank year sent as 0, not refilled", "POST with yearOfStudy ''", "''", "Service gets 0; model yearOfStudy null"),
        t("nonNumericYearOfStudyIsBadRequest", "negative", "Non-numeric year", "POST with yearOfStudy 'two'", "'two'", "400; service not called"),
        t("contactNumberIsPassedOnAsTyped", "boundary", "Contact number not stripped", "POST with contactNumber ' 98765432 '", "' 98765432 '", "Service gets ' 98765432 ' unchanged"),
        t("activeStudentCanLogIn", "happy", "Student logs in", "Form login (email field)", "alex@test.com / password123", "Redirect /home; authenticated with ROLE_STUDENT"),
        t("adminLogsInWithAdminRole", "happy", "Admin logs in", "Form login", "admin@test.com / password123", "Redirect /home; authenticated with ROLE_ADMIN"),
        t("wrongPasswordIsRefused", "negative", "Wrong password", "Form login", "alex@test.com / wrongpass1", "Redirect /login?error; not authenticated"),
        t("deactivatedAccountCannotLogIn", "negative", "Deactivated account can't log in", "Form login with the right password", "inactive alex@test.com", "Redirect /login?error; not authenticated"),
        t("logoutRedirectsToLoginPage", "happy", "Log out", "POST /logout with CSRF as a student", "-", "Redirect /login?logout; not authenticated"),
    ],
    "CourseServiceTest": [
        t("createCourseSavesCodeAndName", "happy", "Create a course", "createCourse; read back", "IS442 / Object Oriented Programming", "Saved code and name"),
        t("getAllCoursesReturnsEveryCourse", "happy", "List courses", "Create 2; getAllCourses", "IS442, CS201", "Both returned"),
        t("unknownCourseIsNotFound", "negative", "Unknown course", "get/update/delete 999999", "999999", "CourseNotFoundException each; nothing created"),
        t("updateCourseChangesCodeAndName", "happy", "Rename a course", "updateCourse", "IS443 / Object Oriented Programming II", "Saved new values"),
        t("deleteCourseRemovesIt", "happy", "Delete a course", "deleteCourse", "-", "Course gone"),
        t("deletingCourseInUseFails", "conflict", "Delete blocked while a student takes it", "Student takes IS442; deleteCourse; flush", "-", "DataIntegrityViolationException", notes="In the app the refusal happens at commit."),
        t("serviceSavesBlankValues", "boundary", "Service itself doesn't check blanks", "createCourse('', ' ')", "'' / ' '", "Saved (the controller is the only check)"),
        t("courseCodeOver255CharactersFailsInDatabase", "boundary", "Code length 255 / 256", "createCourse", "'C' x 255, 'C' x 256", "255 saved; 256 DataIntegrityViolationException"),
    ],
    "CourseControllerTest": [
        t("anonymousIsRedirectedToLogin", "permission", "Anonymous sent to login", "GET /courses; POST /courses with CSRF", "-", "302 to /login; service not called"),
        t("studentSeesListWithoutAdminTools", "permission", "Student sees a read-only list", "As STUDENT GET /courses?edit=1", "IS442 (id 1)", "200; isAdmin false; editingId null; no edit form, no add form"),
        t("adminSeesEditFormForChosenCourse", "happy", "Admin sees edit and add forms", "As ADMIN GET /courses?edit=1", "IS442 (id 1)", "isAdmin true; editingId 1; edit form for course 1 and add form shown"),
        t("studentCannotAddUpdateOrDeleteCourses", "permission", "Student can't change courses", "As STUDENT POST add, update, delete with CSRF", "-", "403 each (@PreAuthorize); service not called"),
        t("postWithoutCsrfTokenIsForbidden", "permission", "POSTs without CSRF refused", "As ADMIN POST add, update, delete without CSRF", "-", "403 each; service not called"),
        t("addCourseStripsAndRedirectsWithSuccess", "happy", "Add a course (values stripped)", "POST /courses", "'  IS999 ', ' Test Course '", "Service gets IS999 / Test Course; flash 'IS999 has been added.'"),
        t("addCourseWithBlankFieldSavesNothing", "negative", "Blank code or missing name", "POST /courses twice", "'   '; name missing", "Flash 'Enter both a course code and a course name.'; service not called"),
        t("updateCourseRedirectsWithSuccess", "happy", "Rename a course", "POST /courses/5/update", "' IS443 ', 'New Name '", "Service gets stripped values; flash 'IS443 has been updated.'"),
        t("updateCourseWithBlankFieldReturnsToEditForm", "negative", "Blank name on edit", "POST /courses/5/update", "name ' '", "Redirect /courses?edit=5 with the error; service not called"),
        t("updateUnknownCourseShowsError", "negative", "Rename a missing course", "Service throws CourseNotFoundException", "-", "Flash 'That course no longer exists.'"),
        t("deleteCourseRedirectsWithSuccess", "happy", "Delete a course", "POST /courses/5/delete", "-", "Flash 'The course has been deleted.'"),
        t("deleteUnknownCourseShowsError", "negative", "Delete a missing course", "Service throws CourseNotFoundException", "-", "Flash 'That course no longer exists.'"),
        t("deleteCourseInUseShowsMessage", "conflict", "Delete a course in use", "Service throws DataIntegrityViolationException", "-", "Flash 'Students, study preferences or study groups still use this course, so it can't be deleted.'"),
    ],
    "CourseControllerKnownBugTest": [
        t("addCourseTheDatabaseRefusesShowsAnError", "negative", "Adding an over-long course should show an error", "Service throws DataIntegrityViolationException; POST /courses", "code 'C' x 256", "DESIRED: redirect /courses with a flash error. TODAY: uncaught exception, 500 (confirmed when enabled)"),
        t("updateCourseTheDatabaseRefusesShowsAnError", "negative", "Renaming to an over-long value should show an error", "Service throws DataIntegrityViolationException; POST /courses/5/update", "code 'C' x 256", "DESIRED: flash error. TODAY: uncaught exception, 500 (confirmed when enabled)"),
    ],
}

# --------------------------------------------------------------------------------------
# Manual (browser) test cases
# --------------------------------------------------------------------------------------
APP_PRE = ("App running: in the studybuddy folder run ./mvnw spring-boot:run, wait for 'Started StudybuddyApplication', "
           "then open http://localhost:8080/login. Seeded password for every seeded account: password123 "
           "(studybuddy.seed.password in application.properties). The database is in memory: restart the app to get the "
           "seeded data back before this test.")
LOGIN = "Open http://localhost:8080/login, type {who} in Email and password123 in Password, click 'Log in'."


def login(who):
    return LOGIN.format(who=who)


def m(mid, ids, kind, title, pre, steps, data, expected, notes=""):
    return {"id": mid, "ids": ids, "type": kind, "title": title, "pre": pre, "steps": steps,
            "data": data, "expected": expected, "notes": notes}


def numbered(*lines):
    return "\n".join(f"{i}. {line}" for i, line in enumerate(lines, 1))


MANUAL = [
    m("MAN-01", "S1, ACC-1", "happy", "Student signs up and logs in", APP_PRE + " Logged out.",
      numbered("Open http://localhost:8080/register.",
               "Email: manual.tester@example.com; Password: password123; Name: Manual Tester; School: SCIS; Programme: Information Systems; Year of study: 2; Contact number: 98765432.",
               "Click 'Create account'.",
               "On the login page type manual.tester@example.com and password123, click 'Log in'."),
      "As in step 2",
      "After step 3: the login page shows 'Your account is ready. Log in to add your courses.' After step 4: the Home page shows 'Welcome back' and 'Logged in as manual.tester@example.com'."),
    m("MAN-02", "S1, ACC-3, PRF-1", "conflict", "Sign-up with a taken email keeps the typed details", APP_PRE + " Logged out.",
      numbered("Open http://localhost:8080/register.",
               "Email: student@studybuddy.com; Password: password123; Name: Copy Cat; School: SCIS; Programme: IS; Year of study: 1; Contact number: 98765432.",
               "Click 'Create account'.",
               "Change Contact number to 1234567 and click 'Create account' again."),
      "student@studybuddy.com (seeded)",
      "After step 3: the form shows 'Another account already uses student@studybuddy.com.'; Email, Name, School, Programme, Year and Contact number are still filled in; Password is empty. After step 4: the browser itself refuses to submit and highlights Contact number (8 digits pattern)."),
    m("MAN-03", "S1, PRF-1, PRF-2", "happy", "Student updates profile and courses", APP_PRE,
      numbered(login("student@studybuddy.com"),
               "Open http://localhost:8080/student/profile.",
               "Change Programme to 'Computer Science' and click 'Save details'. Press F5.",
               "Under 'Your courses' pick ECON101 in the Course list and click 'Add course'.",
               "Click 'Remove' next to ECON101.",
               "Click 'Remove' next to IS442."),
      "Programme 'Computer Science'; course ECON101",
      "Step 3: 'Your profile has been updated.' and Programme still 'Computer Science' after F5. Step 4: 'The course has been added.' and ECON101 listed. Step 5: 'The course has been removed.' Step 6: error 'Delete your IS442 study preference before removing the course.' and IS442 still listed."),
    m("MAN-04", "S2, PRE-1, PRE-3", "happy", "Student manages a study preference and slots", APP_PRE,
      numbered(login("student@studybuddy.com"),
               "Open http://localhost:8080/student/profile, pick ECON101 in Course and click 'Add course'.",
               "Open http://localhost:8080/student/preferences. Under the add form choose Course ECON101, Study mode Online, Group size One-to-one, Study goal Concept review; click 'Add preference'.",
               "In the ECON101 edit form choose Day Monday, From 10:00, To 12:00; click '+ Add slot'.",
               "Choose Day Monday, From 11:00, To 13:00; click '+ Add slot'.",
               "Click 'Done', then 'Delete' on the ECON101 row and confirm in the box."),
      "ECON101; Mon 10:00-12:00; Mon 11:00-13:00",
      "Step 3: 'Your ECON101 preference has been added. Add the times you're free below.' Step 4: 'The time slot has been added.' Step 5: error 'This slot overlaps one you already have on MONDAY.' Step 6: 'The study preference has been deleted.' and ECON101 gone from the list."),
    m("MAN-05", "S3, S4, MAT-2", "happy", "Search by course gives the ranked list", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/matches.",
               "Choose IS442 in Course and click 'Search'."),
      "Course IS442",
      "Summary says the results are ranked by Balanced matching. The first card is Ben Koh with 92 / 100. Every card shows Course IS442. Scores go down (or stay equal) from top to bottom."),
    m("MAN-06", "S5, S3", "happy", "Filters narrow the matches", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/matches.",
               "Course 'All my courses', Study goal 'Exam preparation', tick 'Only students free when I am'; click 'Search'.",
               "Also set Study mode 'Online'; click 'Search'."),
      "Goal Exam preparation; shared time only; mode Online",
      "Step 3: every card shows Goal 'Exam preparation' and its 'Shared free time' bar is not 'None'. Step 4: every card's Study mode is 'Online' or 'In person or online'."),
    m("MAN-07", "S6, PRF-3, REQ-4", "side-effect", "Match cards show public details only", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/matches, choose IS442, click 'Search'.",
               "Read Ben Koh's card. Press Ctrl+F and search for 92345678."),
      "Ben Koh's number 92345678",
      "The card shows name, programme, year and school only. 92345678 is not found on the page.",
      "K7: there is no separate profile page; this is the only place another student's details appear."),
    m("MAN-08", "S7, REQ-1", "happy", "Send a buddy request with a message", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/matches, choose IS442, click 'Search'.",
               "On the first card that is NOT Ben Koh, Chloe Lim or Daniel Ng, type 'Want to study IS442 together?' in 'Optional message' and click 'Send request'.",
               "Go back to http://localhost:8080/matches, choose IS442, click 'Search', and click 'Send request' on Ben Koh's card."),
      "Message 'Want to study IS442 together?'",
      "Step 3: the Match requests page shows 'Request sent.' and the student under 'Sent' with the message and PENDING. Step 4: error 'You're already study buddies. Find them under Study buddies.'"),
    m("MAN-09", "S8, REQ-3, REQ-4", "happy", "Accept a request; contact number appears", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/match-requests.",
               "Under 'Received', on Chloe Lim's request click 'Accept'.",
               "Open http://localhost:8080/connections."),
      "Chloe Lim's number 81000000",
      "Step 3: 'Request accepted. You're now study buddies: their contact number is on the Study buddies page.' and the request shows ACCEPTED without buttons; 81000000 is not on this page. Step 4: Chloe Lim is listed with 81000000."),
    m("MAN-10", "S8, REQ-2", "happy", "Decline a request", APP_PRE,
      numbered(login("daniel.ng@studybuddy.com"), "Open http://localhost:8080/match-requests.",
               "Under 'Received', on Alex Tan's request click 'Decline'.",
               "Log out, log in as student@studybuddy.com and open http://localhost:8080/match-requests."),
      "daniel.ng@studybuddy.com",
      "Step 3: 'Request declined.'; the request shows REJECTED and no buttons. Step 4: under 'Sent', the request to Daniel Ng shows REJECTED. /connections does not list Daniel Ng."),
    m("MAN-11", "S9, REQ-5", "happy", "End a connection", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/connections.",
               "On Ben Koh's row click 'End'; in the box click 'End connection'."),
      "Ben Koh (92345678)",
      "Before step 3 Ben Koh is listed with 92345678. After: 'Connection ended. You can no longer see each other's contact numbers.' and Ben Koh is no longer listed."),
    m("MAN-12", "L1, L2, GRP-1, GRP-7", "happy", "Create a study group", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/groups and click '+ Create a group'.",
               "Group name 'Manual Test Group'; Course IS442; Description 'Practice questions'; Study goal Exam preparation; Study mode Online; Maximum group size 4.",
               "Slot 1: Day Monday, From 10:00, To 12:00. Click '+ Add slot'; Slot 2: Day Wednesday, From 14:00, To 16:00.",
               "Click 'Create group'."),
      "As in steps 3-4",
      "The group page shows 'Group created. Other students can now find it on Study groups and ask to join.', the name, IS442, the description, goal, mode, 1 of 4 members (Alex Tan) and the sessions Mon 10:00-12:00 and Wed 14:00-16:00."),
    m("MAN-13", "L3, GRP-3, GRP-6", "happy", "Edit and close a group", APP_PRE + " MAN-12 done in the same app run.",
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/groups/mine and open 'Manual Test Group'.",
               "Click 'Edit group', change Description to 'Edited', click 'Save changes'.",
               "Open http://localhost:8080/groups/mine, open 'IS442 OOP Exam Prep', click 'Edit group', set Maximum group size to 2 and click 'Save changes'.",
               "Open 'Manual Test Group' again, click 'Close group', then 'Close group' in the box."),
      "Description 'Edited'; size 2",
      "Step 3: 'Changes saved.' and the new description. Step 4: the browser refuses to submit (the field's minimum is the member count, 3). Step 5: 'Group closed.' and the group shows as closed with no Edit or join options."),
    m("MAN-14", "L4, L5, GRP-2, GRP-5", "happy", "View, accept and reject join requests", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/groups/mine and open 'IS442 OOP Exam Prep'. Click 'Manage members'.",
               "Under 'Join requests', click 'Accept' for Farhan Ismail.",
               "Click 'Reject' for Jasmine Teo, then 'Reject request' in the box."),
      "Seeded IS442 group (Alex leads; Ben, Chloe members; Farhan, Jasmine pending)",
      "Step 2: Join requests lists Farhan Ismail and Jasmine Teo; Members lists Alex Tan, Ben Koh, Chloe Lim. Step 3: 'Farhan Ismail is now a member of the group.' Step 4: 'Jasmine Teo's request was rejected.' and no join requests are left."),
    m("MAN-15", "L6, GRP-5", "happy", "Remove a member", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/groups/mine, open 'IS442 OOP Exam Prep', click 'Manage members'.",
               "Under 'Members', click 'Remove' for Chloe Lim, then 'Remove member' in the box."),
      "Chloe Lim",
      "'Chloe Lim was removed from the group.' Chloe Lim is no longer under Members. Alex Tan (the leader) has no Remove button."),
    m("MAN-16", "A1, ACC-1, ACC-2", "happy", "Admin creates an admin account", APP_PRE,
      numbered(login("admin@studybuddy.com"), "Open http://localhost:8080/admin/accounts.",
               "In the add-admin form type Email ' Second.Admin@StudyBuddy.com ' and Password password123; click 'Add admin account'.",
               "Log out and log in as second.admin@studybuddy.com with password123."),
      "' Second.Admin@StudyBuddy.com ' / password123",
      "Step 3: 'Admin account second.admin@studybuddy.com has been created. It can log in straight away.' and a new Admin row. Step 4: Home shows the admin links (User accounts, Courses, Matching settings)."),
    m("MAN-17", "A1, ACC-3", "happy", "Admin changes an email and resets a password", APP_PRE,
      numbered(login("admin@studybuddy.com"), "Open http://localhost:8080/admin/accounts.",
               "On ben.koh@studybuddy.com click 'Edit'; in the box set New email to ben.k@studybuddy.com and click 'Save email'.",
               "On the same row click 'Edit'; set New password to newpassword1 and click 'Set password'.",
               "On chloe.lim@studybuddy.com click 'Edit', set New email to student@studybuddy.com, click 'Save email'.",
               "Log out; log in as ben.k@studybuddy.com with newpassword1."),
      "ben.k@studybuddy.com / newpassword1",
      "Step 3: 'The account now logs in with ben.k@studybuddy.com.' Step 4: 'The password for ben.k@studybuddy.com has been changed.' Step 5: 'Another account already uses student@studybuddy.com.' Step 6: logged in as Ben Koh."),
    m("MAN-18", "A1, ACC-6", "happy", "Deactivated account can't log in; reactivation restores it", APP_PRE,
      numbered(login("admin@studybuddy.com"), "Open http://localhost:8080/admin/accounts and click 'Deactivate' on chloe.lim@studybuddy.com.",
               "Log out; try to log in as chloe.lim@studybuddy.com / password123.",
               "Log in as admin again, click 'Reactivate' on chloe.lim@studybuddy.com, log out and log in as Chloe."),
      "chloe.lim@studybuddy.com",
      "Step 2: 'chloe.lim@studybuddy.com has been deactivated and can no longer log in.' and status Inactive. Step 3: login page shows 'That email and password don't match an active account. Check them and try again.' Step 4: 'has been reactivated.', status Active, and Chloe reaches Home."),
    m("MAN-19", "A1, ACC-4", "conflict", "Last active admin is protected", APP_PRE + " Only the seeded admin exists.",
      numbered(login("admin@studybuddy.com"), "Open http://localhost:8080/admin/accounts.",
               "On your own row (marked '(you)') click 'Deactivate'.",
               "On the same row click 'Delete', then 'Delete account' in the box."),
      "admin@studybuddy.com",
      "Step 3: 'This is the last active admin account, so it can't be deactivated.' Step 4: 'This is the last active admin account, so it can't be deleted.' The row stays Active."),
    m("MAN-20", "A1, ACC-5", "conflict", "Student with activity can't be deleted; a new admin can", APP_PRE,
      numbered(login("admin@studybuddy.com"), "Open http://localhost:8080/admin/accounts.",
               "On student@studybuddy.com (Alex Tan) click 'Delete', read the box, click 'Delete account'.",
               "Create an admin third.admin@studybuddy.com / password123 with the add-admin form, then click 'Delete' on it and 'Delete account'."),
      "student@studybuddy.com; third.admin@studybuddy.com",
      "Step 3: the box warns that a student with requests, groups or buddies can't be deleted; after confirming: 'This student still has match requests, study groups or study buddies, so the account can't be deleted. Deactivate it instead.' and Alex is still listed. Step 4: 'The account has been deleted.' and third.admin is gone."),
    m("MAN-21", "A2", "happy", "Account status and usage figures", APP_PRE,
      numbered(login("admin@studybuddy.com"), "Open http://localhost:8080/admin/accounts and find Alex Tan (student@studybuddy.com)."),
      "Seeded data",
      "Alex Tan: Role Student, Active, a created date, 'Courses 4 · Preferences 4 · Groups led 1' and 'Requests sent 1 · received 2'. The admin row shows '—' for usage and '(you)'."),
    m("MAN-22", "A3, A4, MAT-1", "happy", "Admin changes the matching settings", APP_PRE,
      numbered(login("admin@studybuddy.com"), "Open http://localhost:8080/admin/matching.",
               "Choose 'Availability first'; set Same course 0, Shared free time 1, Study mode 0, Study goal 0, Group size 0; click 'Save settings'.",
               "Set all five weights to 0; click 'Save settings'.",
               "Type -1 in Same course; click 'Save settings'."),
      "Weights 0/1/0/0/0, then all 0, then -1",
      "Step 3: 'Settings saved. New searches use them straight away.' and Shared free time shows 100%. Step 4: 'At least one criterion needs a weight above 0.' Step 5: the browser refuses to submit (minimum 0)."),
    m("MAN-23", "D2, A4, MAT-3", "happy", "The admin's strategy changes the order", APP_PRE,
      numbered(login("admin@studybuddy.com") + " Open http://localhost:8080/admin/matching, choose 'Availability first', click 'Save settings', log out.",
               login("student@studybuddy.com") + " Open http://localhost:8080/matches, Course 'All my courses', click 'Search'."),
      "-",
      "The summary says 'ranked by Availability first matching'. Going down the list, the 'Shared free time' value never increases."),
    m("MAN-24", "CRS-1", "happy", "Admin adds, edits and deletes a course", APP_PRE,
      numbered(login("admin@studybuddy.com"), "Open http://localhost:8080/courses.",
               "Course code 'IS999', Course name 'Manual Testing'; click 'Add course'.",
               "On IS999 click 'Edit', change the name to 'Manual Testing II', click 'Save'.",
               "On IS999 click 'Delete', then 'Delete course' in the box.",
               "On IS442 click 'Delete', then 'Delete course'."),
      "IS999 / Manual Testing",
      "Step 3: 'IS999 has been added.' Step 4: 'IS999 has been updated.' Step 5: 'The course has been deleted.' Step 6: 'Students, study preferences or study groups still use this course, so it can't be deleted.' and IS442 stays."),
    m("MAN-25", "CRS-1, D3", "permission", "Students see a read-only course list", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/courses.",
               "Open http://localhost:8080/courses?edit=1."),
      "-",
      "Both times: the 10 seeded courses are listed with no Actions column, no Edit/Delete buttons and no add form."),
    m("MAN-26", "D3, SEC-1, SEC-2", "permission", "Each role is kept to its own pages", APP_PRE,
      numbered(login("student@studybuddy.com") + " Open http://localhost:8080/admin/accounts.",
               "Log out. " + login("admin@studybuddy.com") + " Open http://localhost:8080/matches, then http://localhost:8080/groups.",
               "Log out. Open http://localhost:8080/groups."),
      "-",
      "Step 1 and step 2 (both pages): an error page with status 403 (Forbidden). Step 3: the login page.",
      "K16: the 403 page is Spring's default error page, not a designed one."),
    m("MAN-27", "D1", "happy", "Seeded data counts", APP_PRE,
      numbered(login("admin@studybuddy.com"), "Open http://localhost:8080/courses and count the rows.",
               "Open http://localhost:8080/admin/accounts and count the rows."),
      "-",
      "10 courses. 51 accounts: 50 students and 1 admin."),
    m("MAN-28", "ACC-8, K1", "negative", "Login email in another letter case (known bug)", APP_PRE,
      numbered("Open http://localhost:8080/login, type STUDENT@StudyBuddy.com and password123, click 'Log in'."),
      "STUDENT@StudyBuddy.com",
      "DESIRED: logged in as Alex Tan. TODAY: 'That email and password don't match an active account...' (K1).",
      "Known bug K1; matches AuthServiceKnownBugTest."),
    m("MAN-29", "A1, K2, ACC-6", "side-effect", "Changing a logged-in student's email", APP_PRE + " Two browsers (or a normal and a private window).",
      numbered("Browser 1: " + login("chloe.lim@studybuddy.com") + " Open http://localhost:8080/student/profile.",
               "Browser 2: " + login("admin@studybuddy.com") + " On chloe.lim@studybuddy.com click 'Edit', set New email chloe.new@studybuddy.com, click 'Save email'.",
               "Browser 1: reload http://localhost:8080/student/profile."),
      "chloe.new@studybuddy.com",
      "TODAY: step 3 shows an error page (404, 'No student account for chloe.lim@studybuddy.com') until Chloe logs out and back in with the new email.",
      "Pins current behaviour K2 (suspected bug)."),
    m("MAN-30", "CRS-1, K4", "negative", "Over-long course code (known bug)", APP_PRE,
      numbered(login("admin@studybuddy.com"), "Open http://localhost:8080/courses.",
               "Paste 256 characters (e.g. 256 letter C) into Course code, type 'Too long' in Course name, click 'Add course'."),
      "256-character code",
      "DESIRED: back on /courses with an error message. TODAY: an error page with status 500 (K4).",
      "Known bug K4; matches CourseControllerKnownBugTest."),
    m("MAN-31", "L4, GRP-4", "happy", "Student asks to join a group; full groups can't be joined", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/groups and open 'IS112 SQL Practice'.",
               "Click 'Request to join'.",
               "Open http://localhost:8080/groups and open 'IS216 Web App Project Team'.",
               "Open http://localhost:8080/groups/mine."),
      "Seeded IS112 group (has space) and IS216 group (full, 3 of 3)",
      "Step 3: 'Request sent. The group's leader will review it.' and the page now says the request was sent. Step 4: the 'Request to join' button is greyed out (group full). Step 5: 'IS112 SQL Practice' is under 'Pending group join requests'."),
]

# --------------------------------------------------------------------------------------
# Gaps and bugs
# --------------------------------------------------------------------------------------
GAPS = [
    ("K1", "Candidate bug (confirmed)", "Login is case-sensitive, but sign-up and the admin page lower-case emails.", "AuthService.java:27", "(b) Known-bug test, @Disabled", "AUK-01, MAN-28", "Confirmed failing when enabled (2026-10-24)."),
    ("K2", "Candidate bug", "Changing a logged-in student's email breaks their session (404s); deactivation and password reset don't end open sessions.", "AccountManagementService.java:120", "(a) Pin + manual case", "AMS (oldEmailNoLongerFindsStudentAfterUpdate), MAN-29", "Pinned."),
    ("K3", "Candidate bug", "Any DataIntegrityViolationException shows 'Another account already uses that email', including over-long fields.", "AuthController.java:61; AccountManagementController.java:64,85", "(a) Pin current message", "AMC (createAdminDatabaseRefusal..., updateEmailDatabaseRefusal...), AUC (registerDatabaseRefusal...)", "Pinned."),
    ("K4", "Candidate bug (confirmed)", "Inputs over 255 characters reach the database: course code and name give a 500. Also match message and profile fields (not yet tested).", "CourseController.java:53,66; MatchRequestController.java:63; StudentProfileController.java:52", "(b) controller known-bug tests; (a) service pin", "CRK-01, CRK-02, CRS (courseCodeOver255...), MAN-30", "Controller tests confirmed failing when enabled. Match message and profile parts: not yet scheduled (open question)."),
    ("K5", "Candidate bug", "Duplicate course codes allowed; CourseService has no @Transactional.", "CourseService.java:20; Course.java:21", "(c) List only", "-", "Listed."),
    ("K6", "Candidate bug", "A match request can be sent to a deactivated student by direct POST.", "MatchRequestService.java:79", "(b) Known-bug test in Batch E", "-", "Batch E."),
    ("K7", "Not implemented", "getPublicProfile and getRequestById have no route; no profile page (S6 partial).", "StudentProfileService.java:66; MatchRequestService.java:115", "Not decided (outside Batch A)", "MAN-07", "Open."),
    ("K8", "Candidate bug", "A student can drop a course while leading or in a group for it; creating or joining a group doesn't require taking the course.", "StudentProfileService.java:113; StudyGroupService.java:68", "Not decided (Batch B)", "SPS (addAndRemoveCourse shows it)", "Open."),
    ("K9", "Candidate bug", "Preference slots are looser than group slots (any times, no maximum).", "StudyPreferenceService.java:93", "(c) List only (Batch E)", "-", "Listed."),
    ("K10", "Candidate bug", "/register doesn't strip spaces from the contact number; profile update does.", "AuthController.java:57", "(a) Pin", "AUC (contactNumberIsPassedOnAsTyped)", "Pinned."),
    ("K11", "Candidate bug", "A non-numeric year of study gives a raw 400.", "AuthController.java:53; StudentProfileController.java:54", "(a) Pin", "AUC (nonNumericYearOfStudyIsBadRequest)", "Pinned."),
    ("K12", "Candidate bug", "An empty message is stored as ''; th:if hides messages 'no', 'false', 'off'.", "match-requests.html:26", "(c) List only (Batch E)", "-", "Listed."),
    ("K13", "Candidate bug", "The duplicate-request error shows internal student ids.", "DuplicateMatchRequestException.java:24", "(c) List only (Batch E)", "-", "Listed."),
    ("K14", "Candidate bug", "No database constraint for one pending request or one connection per pair (race).", "MatchRequestService.java:90-104; StudyBuddyConnectionService.java:46", "(c) List only (Batch E)", "-", "Listed."),
    ("K15", "Candidate bug", "H2 console open to everyone with CSRF off.", "SecurityConfig.java:28; application.properties:15", "Batch C", "-", "Open."),
    ("K16", "Candidate bug", "No @ControllerAdvice or custom error pages.", "-", "(c) List only", "MAN-26", "Listed."),
    ("K17", "Candidate bug", "/matches still lists existing buddies.", "MatchingService.java:91", "Batch D", "-", "Open."),
    ("K18", "Test risk", "Open-session-in-view is relied on; non-transactional tests can hit LazyInitializationException.", "-", "Handled: every new service test is transactional", "-", "Mitigated."),
    ("K19", "Test risk", "StudyPreferenceServiceTest.newPreferenceShowsUpInMatching depends on seeded random data.", "StudyPreferenceServiceTest.java:160", "(c) List only (teammate file is read-only)", "SPF (newPreferenceShowsUpInMatching)", "Listed."),
    ("N1", "New finding", "Password limit is 72 BYTES, but the message says 'Passwords can be at most 72 characters.' 25 x '€' (25 characters) is refused.", "AccountCredentials.java:58-59", "(a) Byte behaviour tested; message mismatch listed", "CRD (multibytePasswordIsLimitedByBytes)", "Listed."),
    ("N2", "New finding", "The 8-character minimum counts UTF-16 units: 4 emoji pass.", "AccountCredentials.java:51", "(c) List only", "-", "Listed."),
    ("N3", "New finding", "The email check accepts loose shapes such as a@.b.c, a@b.c. and a@b..c.", "AccountCredentials.java:28", "(c) List only", "-", "Listed."),
    ("N5", "New finding", "Deactivating an already inactive account or reactivating an active one succeeds and shows the success message.", "AccountManagementService.java:91-102", "(c) List only", "-", "Listed."),
    ("GAP-ACC2", "Decision", "ACC-2: the password limit is 72 bytes (agreed behaviour), not 72 characters.", "AccountCredentials.java:26", "Recorded", "CRD (passwordMaximumIs72Bytes, multibytePasswordIsLimitedByBytes)", "Agreed."),
    ("GAP-A1", "Ambiguous requirement", "A1 says admins create user accounts; today they can only create ADMIN accounts (students sign up themselves) and update only email and password.", "AccountManagementService.java:107", "Pin current behaviour; list 'admin creates a student' as a gap", "AMS (createAdmin... tests)", "Open."),
    ("GAP-NR1", "Behaviour with no requirement", "An admin can deactivate or delete their own account while another active admin exists, and keeps their session.", "AccountManagementService.java:91,145", "List only", "-", "Listed."),
    ("GAP-NR2", "Behaviour with no requirement", "Email change and password reset are allowed on deactivated accounts.", "AccountManagementService.java:120,131", "List only", "-", "Listed."),
    ("GAP-NR3", "Behaviour with no requirement", "/register works while logged in.", "SecurityConfig.java:50", "List only", "-", "Listed."),
    ("GAP-NR4", "Behaviour with no requirement", "'Groups led' in the usage figures includes closed groups.", "AccountStatus.java:49", "List only", "-", "Listed."),
    ("GAP-OPEN1", "Open question", "The match-message and profile-field parts of K4 aren't assigned to a batch yet.", "-", "To decide", "-", "Open."),
]

# --------------------------------------------------------------------------------------
# Read the code and the last run
# --------------------------------------------------------------------------------------


def parse_sources():
    """{ClassName: [{method, ids, comment, disabled}]} for every @Test in src/test."""
    found = {}
    for path in sorted(TEST_SRC.rglob("*.java")):
        lines = path.read_text(encoding="utf-8").splitlines()
        tests = []
        for i, line in enumerate(lines):
            if line.strip() != "@Test":
                continue
            # The method name: first "void name(" after @Test
            j = i + 1
            while "void " not in lines[j]:
                j += 1
            method = re.search(r"void\s+(\w+)\s*\(", lines[j]).group(1)
            between = "\n".join(lines[i + 1:j])
            disabled = None
            if "@Disabled" in between:
                disabled = "".join(re.findall(r'"([^"]*)"', between[between.index("@Disabled"):]))
            # The comment block above @Test, skipping annotations such as @WithMockUser
            k = i - 1
            while k >= 0 and lines[k].strip().startswith("@"):
                k -= 1
            comment = []
            while k >= 0 and lines[k].strip().startswith("//"):
                comment.insert(0, lines[k].strip()[2:].strip())
                k -= 1
            first = comment[0] if comment else ""
            head = first.split(":")[0]
            ids = ", ".join(dict.fromkeys(ID_PATTERN.findall(head)))
            tests.append({"method": method, "ids": ids, "comment": " ".join(comment), "disabled": disabled})
        if tests:
            found[path.stem] = tests
    return found


def parse_reports(sources):
    """{(ClassName, method): status} from the surefire XML reports. Reports left behind by
    test classes that no longer exist are ignored (surefire never deletes old reports)."""
    results = {}
    for xml in REPORTS.glob("TEST-*.xml"):
        for case in ET.parse(xml).getroot().iter("testcase"):
            cls = case.get("classname").split(".")[-1]
            if cls not in sources:
                continue
            if case.find("failure") is not None or case.find("error") is not None:
                status = "Fail"
            elif case.find("skipped") is not None:
                status = "Skipped"
            else:
                status = "Pass"
            results[(cls, case.get("name"))] = status
    return results


def build_rows(sources, reports):
    problems = []
    for cls in sources:
        if cls not in CLASSES:
            problems.append(f"Test class {cls} in src/test has no entry in CLASSES")
    rows = []
    for cls, (prefix, written_by, pre) in CLASSES.items():
        if cls not in sources:
            problems.append(f"{cls} is described here but has no @Test methods in src/test")
            continue
        code = {test["method"]: test for test in sources[cls]}
        described = {case["method"]: case for case in CASES.get(cls, [])}
        for name in code.keys() - described.keys():
            problems.append(f"{cls}.{name} exists in src/test but has no description")
        for name in described.keys() - code.keys():
            problems.append(f"{cls}.{name} is described but doesn't exist in src/test")
        for n, test in enumerate(sources[cls], 1):
            case = described.get(test["method"])
            if case is None:
                continue
            ids = test["ids"] or case["ids"]
            if not ids:
                problems.append(f"{cls}.{test['method']} has no requirement IDs")
            notes = [case["notes"]] if case["notes"] else []
            if "pins current behaviour" in test["comment"]:
                ks = ", ".join(k for k in ID_PATTERN.findall(ids) if k.startswith("K"))
                notes.insert(0, f"Pins current behaviour (suspected bug {ks}): update if fixed.")
            status = reports.get((cls, test["method"]), "Not run")
            if test["disabled"]:
                status = "Known bug (disabled)" if status in ("Skipped", "Not run") else status
                notes.insert(0, f"@Disabled: {test['disabled']} Confirmed failing when enabled (2026-10-24).")
            rows.append({
                "id": f"{prefix}-{n:02d}", "ids": ids, "title": case["title"], "type": case["type"],
                "pre": pre, "steps": case["steps"], "data": case["data"], "expected": case["expected"],
                "mode": "Automated", "where": f"{cls}#{test['method']}", "status": status,
                "by": written_by, "notes": " ".join(notes), "cls": cls,
            })
    for case in MANUAL:
        rows.append({
            "id": case["id"], "ids": case["ids"], "title": case["title"], "type": case["type"], "pre": case["pre"],
            "steps": case["steps"], "data": case["data"], "expected": case["expected"], "mode": "Manual",
            "where": "", "status": "Not run", "by": "New", "notes": case["notes"], "cls": "",
        })
    if problems:
        print("Workbook does not match the code:", *problems, sep="\n  ")
        sys.exit(1)
    return rows


# --------------------------------------------------------------------------------------
# Write the workbook
# --------------------------------------------------------------------------------------
HEADER_FILL = PatternFill("solid", fgColor="1F3864")
HEADER_FONT = Font(bold=True, color="FFFFFF")
FILLS = {
    "Pass": PatternFill("solid", fgColor="C6EFCE"),
    "Fail": PatternFill("solid", fgColor="FFC7CE"),
    "Known bug (disabled)": PatternFill("solid", fgColor="FFEB9C"),
    "Not run": PatternFill("solid", fgColor="EDEDED"),
    "No test": PatternFill("solid", fgColor="FFC7CE"),
    "amber": PatternFill("solid", fgColor="FFEB9C"),
}
WRAP = Alignment(wrap_text=True, vertical="top")


def sheet(wb, title, headers, rows, widths):
    ws = wb.create_sheet(title)
    ws.append(headers)
    for cell in ws[1]:
        cell.fill, cell.font, cell.alignment = HEADER_FILL, HEADER_FONT, WRAP
    for row in rows:
        ws.append(row)
    for row in ws.iter_rows(min_row=2):
        for cell in row:
            cell.alignment = WRAP
    for i, width in enumerate(widths, 1):
        ws.column_dimensions[get_column_letter(i)].width = width
    ws.freeze_panes = "B2"
    ws.auto_filter.ref = ws.dimensions
    return ws


def ids_of(text):
    return ID_PATTERN.findall(text or "")


def write(rows, reports):
    wb = Workbook()
    wb.remove(wb.active)

    # --- Test Cases ---
    headers = ["Test ID", "Requirement/Rule IDs", "Title", "Type", "Preconditions", "Steps", "Test data",
               "Expected result", "Automated or Manual", "Test class and method", "Status", "Written by", "Notes"]
    ws = sheet(wb, "Test Cases", headers,
               [[r["id"], r["ids"], r["title"], r["type"], r["pre"], r["steps"], r["data"], r["expected"],
                 r["mode"], r["where"], r["status"], r["by"], r["notes"]] for r in rows],
               [11, 18, 34, 12, 40, 48, 26, 48, 12, 44, 14, 10, 44])
    for row in ws.iter_rows(min_row=2):
        fill = FILLS.get(row[10].value)
        if fill:
            row[10].fill = fill

    # --- Traceability ---
    auto, known, manual = defaultdict(list), defaultdict(list), defaultdict(list)
    for r in rows:
        for rid in ids_of(r["ids"]):
            if r["mode"] == "Manual":
                manual[rid].append(r["id"])
            elif r["status"] == "Known bug (disabled)":
                known[rid].append(r["id"])
            else:
                auto[rid].append(r["id"])
    trace = []
    for rid, group, desc in REQUIREMENTS:
        if auto[rid]:
            coverage = "Automated" + (" + manual" if manual[rid] else "")
        elif known[rid]:
            coverage = "Known bug only"
        elif manual[rid]:
            coverage = "Manual only"
        else:
            coverage = "No test"
        trace.append([rid, group, desc, len(auto[rid]), ", ".join(auto[rid]), ", ".join(known[rid]),
                      ", ".join(manual[rid]), coverage, PLANNED.get(rid, "")])
    ws = sheet(wb, "Traceability", ["ID", "Group", "Requirement / rule", "Automated tests (count)",
                                     "Automated test IDs", "Known-bug test IDs (disabled)", "Manual test IDs",
                                     "Coverage", "Planned next"],
               trace, [8, 12, 50, 12, 60, 20, 26, 18, 26])
    for row in ws.iter_rows(min_row=2):
        coverage = row[7].value
        if coverage == "No test":
            for cell in row:
                cell.fill = FILLS["No test"]
        elif coverage in ("Manual only", "Known bug only"):
            row[7].fill = FILLS["amber"]

    # --- Gaps ---
    sheet(wb, "Gaps", ["Gap ID", "Kind", "Description", "Where", "Treatment chosen", "Related tests", "Status"],
          [list(g) for g in GAPS], [11, 22, 60, 34, 32, 40, 30])

    # --- Summary ---
    ws = wb.create_sheet("Summary")
    bold = Font(bold=True)

    def block(title, header, data):
        ws.append([])
        ws.append([title])
        ws.cell(ws.max_row, 1).font = Font(bold=True, size=12)
        ws.append(header)
        for cell in ws[ws.max_row]:
            cell.fill, cell.font = HEADER_FILL, HEADER_FONT
        for line in data:
            ws.append(line)

    ws.append(["Study Buddy Matcher: test case summary"])
    ws.cell(1, 1).font = Font(bold=True, size=14)
    statuses = Counter(reports.values())
    after = [sum(statuses.values()), statuses["Pass"], statuses["Fail"], statuses["Skipped"]]
    block("./mvnw test results", ["Run", "Tests run", "Passed", "Failed or errors", "Skipped (known bugs)"],
          [["Baseline (before Batch A)", BASELINE["run"], BASELINE["passed"], BASELINE["failed"] + BASELINE["errors"],
            BASELINE["skipped"]],
           ["Last run (this workbook)"] + after])

    automated = [r for r in rows if r["mode"] == "Automated"]
    manual_rows = [r for r in rows if r["mode"] == "Manual"]
    block("Test cases", ["", "Automated", "Manual", "Total"],
          [["Existing (teammates)", sum(r["by"] == "Existing" for r in automated), 0,
            sum(r["by"] == "Existing" for r in rows)],
           ["New", sum(r["by"] == "New" for r in automated), len(manual_rows), sum(r["by"] == "New" for r in rows)],
           ["Total", len(automated), len(manual_rows), len(rows)]])

    status_counts = Counter(r["status"] for r in rows)
    block("By status", ["Status", "Test cases"],
          [[s, status_counts[s]] for s in ["Pass", "Fail", "Known bug (disabled)", "Not run"]])

    type_counts = Counter((r["type"], r["mode"]) for r in rows)
    block("By type", ["Type", "Automated", "Manual"],
          [[kind, type_counts[(kind, "Automated")], type_counts[(kind, "Manual")]] for kind in TYPES])

    groups = ["S", "L", "A", "D", "ACC", "CRS", "PRF", "PRE", "REQ", "GRP", "MAT", "SEC", "K"]
    group_rows = []
    for g in groups:
        def in_group(r):
            return any((rid.split("-")[0] if "-" in rid else rid[0]) == g for rid in ids_of(r["ids"]))
        a = [r for r in rows if r["mode"] == "Automated" and in_group(r)]
        group_rows.append([g, sum(r["status"] == "Pass" for r in a), sum(r["status"] == "Fail" for r in a),
                           sum(r["status"] == "Known bug (disabled)" for r in a),
                           sum(r["mode"] == "Manual" and in_group(r) for r in rows)])
    block("By requirement group (a test counts once per group it covers)",
          ["Group", "Automated pass", "Automated fail", "Known bug (disabled)", "Manual"], group_rows)

    coverage = Counter(line[7] for line in trace)
    block("Requirement and rule coverage", ["Coverage", "IDs"], [[c, n] for c, n in sorted(coverage.items())])
    ws.column_dimensions["A"].width = 44
    for col in "BCDE":
        ws.column_dimensions[col].width = 18
    for row in ws.iter_rows():
        if row[0].value and str(row[0].value).startswith("Total"):
            for cell in row:
                cell.font = bold

    wb.move_sheet("Summary", offset=-3)
    wb.save(OUT)
    return after, status_counts, coverage


def main():
    if not REPORTS.exists():
        sys.exit("No surefire reports: run ./mvnw test in studybuddy/ first")
    sources = parse_sources()
    reports = parse_reports(sources)
    rows = build_rows(sources, reports)
    after, status_counts, coverage = write(rows, reports)
    print(f"Wrote {OUT}")
    print(f"Last run: {after[0]} run, {after[1]} passed, {after[2]} failed, {after[3]} skipped")
    print(f"Test cases: {len(rows)} ({dict(status_counts)})")
    print(f"Coverage: {dict(coverage)}")


if __name__ == "__main__":
    main()

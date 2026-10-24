"""
Builds docs/test-cases/study-buddy-test-cases.xlsx from:
  - the test sources in studybuddy/src/test/java (method names, requirement-ID comments, @Disabled reasons)
  - the last surefire run in studybuddy/target/surefire-reports (Pass / Fail / Known bug status)
  - the descriptions below (titles, steps, data, expected results, manual cases, gaps)

Run from the repository root AFTER ./mvnw test:
    python docs/test-cases/build_workbook.py

It stops with an error if a test method in src/test has no description here, or a description
names a test that doesn't exist, so the workbook can't drift from the code.

Manual results (Status, Tester, Date, Actual result) typed into the existing workbook are read
first and carried over by Test ID, so rebuilding never loses them. A manual ID that is in the old
workbook but no longer described here is reported with a WARNING (its results are dropped).
Needs openpyxl (pip install openpyxl). Nothing here touches pom.xml or src/.
"""
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from pathlib import Path

from openpyxl import Workbook, load_workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter
from openpyxl.worksheet.datavalidation import DataValidation

ROOT = Path(__file__).resolve().parents[2]
TEST_SRC = ROOT / "studybuddy" / "src" / "test" / "java"
REPORTS = ROOT / "studybuddy" / "target" / "surefire-reports"
OUT = Path(__file__).resolve().parent / "study-buddy-test-cases.xlsx"

# Full-suite results before each batch (recorded by hand from the baseline runs)
BASELINES = [
    ("Baseline before Batch A (2026-10-23)", {"run": 99, "passed": 99, "failed": 0, "errors": 0, "skipped": 0}),
    ("Baseline before Batch B (2026-10-24)", {"run": 207, "passed": 204, "failed": 0, "errors": 0, "skipped": 3}),
    ("Baseline before Batch C (2026-10-24)", {"run": 294, "passed": 291, "failed": 0, "errors": 0, "skipped": 3}),
    ("Baseline before Batch D (2026-10-24)", {"run": 593, "passed": 588, "failed": 0, "errors": 0, "skipped": 5}),
]
BATCH_B = "Batch B"
BATCH_C = "Batch C"
BATCH_D = "Batch D"

# Not after a letter, digit or "-", so finding ids such as N-D1 aren't read as requirement D1
ID_PATTERN = re.compile(r"(?<![\w-])(?:[SLAD]\d+|ACC-\d+|CRS-\d+|PRF-\d+|PRE-\d+|REQ-\d+|GRP-\d+|MAT-\d+|SEC-\d+|K\d+)\b")
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
    "S6": "Gap K7 (no profile page)",
    "PRF-1": "Batch E (boundaries)", "PRE-1": "Batch E", "PRE-2": "Batch E", "REQ-4": "Batch E (negative)",
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
    # New (Batch B)
    "StudyGroupTest": ("SGE", BATCH_B, "Plain unit test (no Spring, no database). Group 'IS442 Revision' (IS442, Exam preparation, Online, maximum size 4) led by Alex; Ben, Chloe and Daniel are other students. Nothing is saved, so ids are null."),
    "StudyGroupFormValidatorTest": ("SGV", BATCH_B, "Plain unit test. Settings built by hand with the application.properties values: 3 slots, 08:00 to 23:00, 15-minute steps. Errors collected in a BeanPropertyBindingResult."),
    "StudyGroupFormValidatorPropertiesTest": ("SGP", BATCH_B, "@SpringBootTest(classes = StudyGroupFormValidator): only the validator and its settings bound from the real application.properties. No auto-configuration, so no database, no seeders, no web server."),
    "StudyGroupFormTest": ("SGF", BATCH_B, "Plain unit test with a jakarta Validator (no Spring). Form starts valid: 'IS442 Revision', course 1, Exam preparation, Online, size 4."),
    "StudyGroupSettingsTest": ("SGT", BATCH_B, "Plain unit test of the StudyGroupSettings record."),
    "GroupSortTest": ("GSO", BATCH_B, "Plain unit test. Unsaved groups whose sort values all differ."),
    "StudyGroupServiceTest": ("SGS", BATCH_B, "@DataJpaTest + StudyGroupService (+ StudentProfileService, AccountCredentials, BCrypt for the K8 test), empty H2. Courses IS442, CS201; Alex (IS442), Ben and Chloe (IS442, CS201), Daniel (no courses). Own data, rolled back."),
    # New (Batch C)
    "SecurityConfigTest": ("SCM", BATCH_C, "@WebMvcTest(HomeController) + SecurityConfig, NO mocks. Only HomeController is loaded, so a request that passes security ends as 404 (no handler) and one that doesn't is 302 /login or 403. Route table ROUTES lists every controller route with who may call it (RouteInventoryTest keeps it complete). Users: alex@test.com STUDENT, admin@test.com ADMIN."),
    "RouteInventoryTest": ("RTI", BATCH_C, "@WebMvcTest of every controller + SecurityConfig + StudyGroupFormValidator; every service is a Mockito mock that is only declared (no behaviour). Compares the real request mappings with SecurityConfigTest.ROUTES."),
    "HomeControllerTest": ("HMC", BATCH_C, "@WebMvcTest with the real SecurityConfig and real templates (Thymeleaf renders the page). No service."),
    "StudentProfileControllerTest": ("SPC", BATCH_C, "@WebMvcTest with the real SecurityConfig and real templates (Thymeleaf renders the page); StudentProfileService and CourseService mocked. Logged in as STUDENT alex@test.com (takes IS442; courses IS442, CS201)."),
    "StudentProfileControllerKnownBugTest": ("SPK", BATCH_C, "@WebMvcTest with the real SecurityConfig and real templates (Thymeleaf renders the page); services mocked; STUDENT alex@test.com. @Disabled known-bug test: describes the desired behaviour."),
    "StudyPreferenceControllerTest": ("PFC", BATCH_C, "@WebMvcTest with the real SecurityConfig and real templates (Thymeleaf renders the page); StudyPreferenceService and StudentProfileService mocked; real study group settings (time list). STUDENT alex@test.com; preference 4 (IS442) with slot 9 Mon 10:00-12:00."),
    "MatchingControllerTest": ("MTC", BATCH_C, "@WebMvcTest with the real SecurityConfig and real templates (Thymeleaf renders the page); MatchingService and MatchingConfigService mocked. STUDENT alex@test.com; one result Ben Koh scoring 92; strategy Balanced."),
    "StudyBuddyConnectionControllerTest": ("CNC", BATCH_C, "@WebMvcTest with the real SecurityConfig and real templates (Thymeleaf renders the page); StudyBuddyConnectionService mocked. STUDENT alex@test.com connected to Ben Koh (connection 3, 92345678)."),
    "AdminMatchingConfigControllerTest": ("MCC", BATCH_C, "@WebMvcTest with the real SecurityConfig and real templates (Thymeleaf renders the page); MatchingConfigService mocked. ADMIN admin@test.com; settings in use Balanced, all weights 20, 120 minutes."),
    "MatchRequestControllerPrincipalTest": ("MRP", BATCH_C, "@WebMvcTest with the real SecurityConfig and real templates (Thymeleaf renders the page); MatchRequestService mocked; STUDENT alex@test.com. Adds to the teammate's MatchRequestControllerTest (unchanged)."),
    "MatchRequestControllerKnownBugTest": ("MRK", BATCH_C, "@WebMvcTest with the real SecurityConfig and real templates (Thymeleaf renders the page); MatchRequestService mocked; STUDENT alex@test.com. @Disabled known-bug tests: describe the desired behaviour. Batch E adds its K4 test here."),
    "ContactNumberVisibilityTest": ("CNV", BATCH_C, "@WebMvcTest with the real SecurityConfig and real templates (Thymeleaf renders the page) for /matches, /match-requests and /connections; services mocked but returning summaries built from real Student entities through the real from() methods. Contacts: Alex 91110001 (logged in), Ben 92220002 (buddy), Chloe 93330003."),
    "HtmlEscapingTest": ("ESC", BATCH_C, "@WebMvcTest with the real SecurityConfig and real templates (Thymeleaf renders the page) for /match-requests, /groups/{id} and /student/profile; services mocked. Typed text contains <script>alert(1)</script>."),
    "StudyGroupControllerTest": ("SGC", BATCH_C, "@WebMvcTest with the real SecurityConfig and real templates (Thymeleaf renders the page); StudyGroupService, GroupMembershipService, CourseService mocked; real StudyGroupFormValidator and settings (3 slots, 08:00-23:00, NEWEST). STUDENT alex@test.com leads group 1 'IS442 Revision' (size 4; Alex, Ben, Chloe)."),
    "GroupMembershipControllerTest": ("GMC", BATCH_C, "@WebMvcTest with the real SecurityConfig and real templates (Thymeleaf renders the page); GroupMembershipService and StudyGroupService mocked. STUDENT alex@test.com leads group 1 (size 4): Chloe Lim member (membership 12), Farhan Ismail waiting (13)."),
    # New (Batch D)
    "MatchSearchTest": ("MSE", BATCH_D, "Plain unit test. Theirs: IS442, In person, One-to-one, Exam preparation; the breakdown only matters for shared time."),
    "ScoreBreakdownTest": ("SBD", BATCH_D, "Plain unit test. Breakdown: course 1, availability 0.5, mode 0.8, goal 0, group size 1."),
    "CompatibilityCalculatorBoundaryTest": ("CCB", BATCH_D, "Plain unit test; adds boundaries to the teammate's CompatibilityCalculatorTest. IS442, Exam preparation, One-to-one unless stated; target 120 minutes."),
    "MatchingStrategyComponentsTest": ("MSC", BATCH_D, "Plain unit test, no database and no Spring context: strategy classes found by scanning com.studybuddy; MatchingService built by hand with mocked repositories."),
    "MatchingConfigServiceTest": ("MCS", BATCH_D, "@DataJpaTest, empty H2 (no seeders), own data, rolled back. Saved settings: Balanced, every weight 20, 120 minutes (no row unless the test saves one)."),
    "MatchingServiceFixtureTest": ("MSF", BATCH_D, "@DataJpaTest, empty H2 (no seeders), own data, rolled back (unlike the teammate's seeded MatchingServiceTest). Settings Balanced, weights 20, 120 min. Alex (IS442, In person, Either, Exam, Mon 10-14) vs Amy (IS442, Mon 10-11: 90), Ben (CS201, Mon 10-14: 80), Cal (IS442, Online, Problem solving, Mon 10-13: 60)."),
    "MatchingServiceKnownBugTest": ("MSK", BATCH_D, "@DataJpaTest, empty H2 (no seeders), own data, rolled back. Alex searches; Ben, Chloe and Dan all have an IS442 preference like Alex's. @Disabled known-bug tests: describe the desired behaviour."),
    "DataSeedersTest": ("DSD", BATCH_D, "@SpringBootTest @Transactional on the SEEDED data: every seeder runs once at startup; each test is rolled back. Only rules that hold whatever the seed details are, plus one labelled pin of today's sizes."),
    "GroupMembershipServiceTest": ("GMS", BATCH_B, "@DataJpaTest + GroupMembershipService + StudyGroupService, empty H2. Course IS442 taken by Alex, Ben, Chloe, Emily (Daniel takes nothing). Alex leads 'IS442 Revision', maximum size 4. Own data, rolled back."),
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

    # ================================ NEW (Batch B) ================================
    "StudyGroupTest": [
        t("newGroupIsActiveWithLeaderAsOnlyMember", "happy", "New group is active with the leader as its only member", "new StudyGroup(...)", "IS442 Revision, Past papers, size 4", "ACTIVE; all fields kept; createdAt set; 1 member (Alex, ACCEPTED); no pending; Alex isLeader and groupsLed has the group"),
        t("blankNameIsRefused", "negative", "Blank group name refused", "new StudyGroup with each name", "null, '', '   '", "IllegalArgumentException 'Group name is required'; Ben not made a leader"),
        t("sizeBelowTwoIsRefusedAndTwoIsAllowed", "boundary", "Size 0/1 refused, 2 allowed", "new StudyGroup with each size", "1, 0, 2", "1 and 0: IllegalArgumentException 'Max size must be at least 2'; 2 created"),
        t("missingCourseOrLeaderIsRefused", "negative", "Course and leader are required", "new StudyGroup with null course, then null leader", "null", "NullPointerException 'course is required' / 'leader is required' (the entity's own Objects.requireNonNull check)"),
        t("requestToJoinAddsPendingRequest", "happy", "Join request is pending", "requestToJoin(Ben)", "-", "PENDING, requestedAt set, respondedAt null, in pending list; member count still 1"),
        t("leaderCannotRequestToJoinOwnGroup", "negative", "Leader can't join own group", "requestToJoin(Alex)", "-", "IllegalArgumentException 'The leader is already in the group'; still 1 row"),
        t("duplicatePendingOrAcceptedRequestIsRefused", "conflict", "Duplicate pending or accepted request refused", "Ben pending, Chloe accepted; both ask again", "-", "IllegalStateException 'Student has already requested or joined this group'; still 3 rows, statuses unchanged"),
        t("rejectedOrRemovedStudentCanAskAgain", "conflict", "Rejected or removed student can ask again", "Reject Ben, remove Chloe; both requestToJoin", "-", "Same row objects come back PENDING with respondedAt null; still 3 rows"),
        t("fullGroupRefusesRequestsAndAccepts", "boundary", "Group exactly full (size 2)", "Size 2; Chloe pending; accept Ben (fills it); Daniel asks; accept Chloe", "maxSize 2", "Not full before Ben; then full (2 of 2): request 'Group is full', accept 'Group is full. Remove a member to accept new members.'; Chloe stays PENDING"),
        t("acceptAndRejectNeedPendingRequest", "conflict", "Answered requests can't be answered again", "Accept Ben, reject Chloe; accept/reject again", "-", "IllegalStateException 'This request has already been answered' x3; statuses unchanged"),
        t("membershipOfAnotherGroupIsRefused", "conflict", "Membership from another group refused", "Daniel's group has Ben pending, Chloe member; Alex's group accept/reject/remove them", "-", "IllegalArgumentException 'Membership does not belong to this group' x3; nothing changed"),
        t("removeMemberEndsMembershipButNotTheLeaders", "happy", "Remove a member; the leader can't be removed", "removeMember(Ben); removeMember(leader's row)", "-", "Ben REMOVED (accept time kept), count 1; leader: IllegalArgumentException 'The leader can't be removed from their own group'"),
        t("removeNeedsAcceptedMember", "negative", "Only accepted members can be removed", "removeMember on a pending and an already removed row", "-", "IllegalStateException 'This student isn't a member of the group any more'"),
        t("updateDetailsChangesEverythingButCourse", "happy", "Update changes the details, not the course", "updateDetails('OOP Finals', 'Mock exams', PROBLEM_SOLVING, IN_PERSON, 6)", "-", "All five values saved; course still IS442"),
        t("updateSizeCannotGoBelowMemberCount", "boundary", "Size = member count allowed, below refused", "3 members; size 3, then 2; blank name; size 1", "3, 2, '   ', 1", "3 accepted (group full); 2: 'Max size can't be below the current member count (3)'; blank: 'Group name is required'; 1: 'Max size must be at least 2'; nothing changed by the refused ones"),
        t("replaceAvailabilityReplacesEverySlot", "happy", "Replace the slots", "Mon + Tue slots; replace with Fri; replace with own list", "-", "Only Friday left; passing the group's own list keeps it"),
        t("closeEndsPendingRequestsAsGroupClosed", "side-effect", "Closing ends pending requests as GROUP_CLOSED", "Ben pending, Chloe member, Daniel rejected; close()", "-", "CLOSED; Ben GROUP_CLOSED with respondedAt; Chloe ACCEPTED, Daniel REJECTED unchanged; 2 members"),
        t("closedGroupIsFrozen", "side-effect", "Closed group is frozen", "Close, then join, accept, remove, update, replace slots, add slot, close, reject", "-", "IllegalStateException 'Group is closed' for 7 actions; reject: 'This request has already been answered' (N-B3); nothing changed"),
        t("removeFromMyGroupsOnlyForClosedGroupAndLinkedStudent", "conflict", "Remove from My groups: closed and linked only", "Open group; close; unlinked Daniel; Ben", "-", "Open: 'Only a closed group can be removed from My groups'; Daniel: 'You aren't linked to this group'; Ben hidden, Alex not, Ben still ACCEPTED"),
    ],
    "StudyGroupFormValidatorTest": [
        t("validSlotsPass", "happy", "A valid slot passes", "validate", "Mon 10:00-12:00", "No errors"),
        t("supportsOnlyTheGroupForm", "happy", "Validator only applies to the group form", "supports()", "StudyGroupForm, SlotForm", "true, false"),
        t("noSlotsIsRefused", "boundary", "0 slots refused", "validate", "no slots", "'Please add at least one time slot'"),
        t("oneToThreeSlotsAreAllowed", "boundary", "1 and 3 slots allowed, 4 refused", "validate", "1, 3, 4 slots", "1 and 3: no errors; 4: only 'A group can have at most 3 time slots'"),
        t("incompleteRowSaysWhatIsMissing", "negative", "Incomplete rows name what is missing", "validate", "empty row; no day; no end", "'Slot 1: please choose a day, a start time and an end time', 'Slot 2: please choose a day', 'Slot 3: please choose an end time'"),
        t("earliestAndLatestTimesAreAccepted", "boundary", "08:00 and 23:00 accepted", "validate", "Mon 08:00-23:00", "No errors"),
        t("quarterHourSlotIsAccepted", "boundary", "One 15-minute step is a valid slot", "validate", "Mon 08:15-08:30", "No errors"),
        t("timesNotOnTheListAreRefused", "boundary", "Times off the list refused", "validate each", "07:45, 23:15, 10:10, 11:59", "'Slot 1: please choose times from the list (08:00 to 23:00)'"),
        t("endMustBeAfterStart", "boundary", "End must be after start", "validate each", "12:00-10:00, 10:00-10:00, 23:00-23:00", "'Slot 1: the end time must be after the start time'"),
        t("overlappingSlotsAreRefused", "conflict", "Overlapping slots refused", "validate", "Mon 10-12 + 11:45-13; Fri 10-14 + 11-12 + 13-15", "'Slots 1 and 2 overlap'; then 'Slots 1 and 2 overlap', 'Slots 1 and 3 overlap'"),
        t("touchingOrOtherDaySlotsAreAllowed", "boundary", "Touching or other-day slots allowed", "validate", "Mon 14-16, Mon 16-18, Tue 14-16", "No errors"),
        t("badRowIsNotComparedForOverlaps", "negative", "A bad row isn't also reported as an overlap", "validate", "Mon 10-12; 11:30-11:00; 11:00-11:10", "Only 'Slot 2: the end time must be after the start time' and 'Slot 3: please choose times from the list (08:00 to 23:00)'"),
        t("messagesAreFiledUnderSlots", "side-effect", "Every message is on the 'slots' field", "validate a reversed and an empty row", "-", "2 errors, both on field 'slots', codes slot.endBeforeStart and slot.incomplete"),
    ],
    "StudyGroupFormValidatorPropertiesTest": [
        t("settingsComeFromApplicationProperties", "happy", "Settings bound from application.properties", "Read StudyGroupSettings from the light context", "application.properties", "3 slots, 08:00, 23:00, 15 minutes, NEWEST; context has no DataSource and no CommandLineRunner (no database, no seeders)"),
        t("configuredValidatorUsesTheseSettings", "boundary", "Configured validator uses the real settings", "validate", "Mon 08:00-23:00; 07:45-10:00; 4 slots", "No errors; 'Slot 1: please choose times from the list (08:00 to 23:00)'; 'A group can have at most 3 time slots'"),
    ],
    "StudyGroupFormTest": [
        t("validFormHasNoErrors", "happy", "Valid form passes", "Validator.validate", "valid form", "No violations"),
        t("nameIsRequiredAndAtMost255Characters", "boundary", "Name required, 255 / 256", "validate each", "null, '   ', 'N' x 255, 'N' x 256", "Blank: 'Please enter a group name'; 255 OK; 256: 'Please keep the group name under 255 characters'", notes="N-B4: the message says 'under 255' but 255 is allowed."),
        t("descriptionIsOptionalAndAtMost1000Characters", "boundary", "Description optional, 1000 / 1001", "validate each", "null, 'D' x 1000, 'D' x 1001", "null and 1000 OK; 1001: 'Please keep the description under 1000 characters'"),
        t("sizeIsRequiredAndAtLeastTwo", "boundary", "Size required, 1 / 2", "validate each", "null, 1, 2", "null: 'Please enter a maximum group size'; 1: 'A group needs room for at least 2 members, including you'; 2 OK"),
        t("courseGoalAndModeAreRequired", "negative", "Course, goal and mode required", "validate", "all three null", "'Please choose a course', 'Please choose a study goal', 'Please choose a study mode'"),
        t("addSlotStopsAtTheMaximumAndRemoveKeepsOneRow", "boundary", "Add slot stops at 3; Remove keeps one row", "blank(); addSlot(3) x3; removeSlot(5), (-1); removeSlot(0) x3", "-", "1 row, then 3 (not 4); bad indexes ignored; 1 row left"),
        t("editFormIsFilledFromTheGroup", "happy", "Edit form pre-filled from the group", "StudyGroupForm.from(group)", "Fri 14-16, Mon 18-20, Mon 9-10", "Name, description, goal, mode, size copied; slots Mon 9, Mon 18, Fri 14"),
    ],
    "StudyGroupSettingsTest": [
        t("timeOptionsRunFromEarliestToLatestInSteps", "boundary", "Time list 08:00 to 23:00 in 15-minute steps", "timeOptions()", "3, 08:00, 23:00, 15", "61 times from 08:00, 08:15 ... 23:00; no 07:45, 08:10, 23:15"),
        t("timeOptionsStopBeforeMidnight", "boundary", "Time list doesn't wrap at midnight", "timeOptions()", "23:00 to 23:59, 30 minutes", "23:00, 23:30 only"),
        t("unusableSettingsAreRefused", "negative", "Unusable settings stop the app", "new StudyGroupSettings with each bad value", "0 slots; earliest = latest; null earliest; step 0; null sort", "IllegalArgumentException with the property name each time; smallest usable values accepted"),
    ],
    "GroupSortTest": [
        t("fromNameFallsBackForUnknownNames", "negative", "Unknown sort name falls back", "fromName", "NAME, MOST_PLACES, null, bogus, name", "NAME, MOST_PLACES, then the fallback for the other three"),
        t("eachOptionSortsByItsOwnValue", "happy", "Each sort option orders correctly", "Sort three groups", "beta (IS442, 3 left), Alpha (IS442, 5 left), Gamma (CS201, 2 left)", "NAME Alpha, beta, Gamma; MOST_PLACES Alpha, beta, Gamma; COURSE Gamma, Alpha, beta", notes="GAP-NR5: no requirement fixes the browse order."),
    ],
    "StudyGroupServiceTest": [
        t("createGroupSavesDetailsSlotsAndLeader", "happy", "Create a group", "createGroup as Alex; flushAndClear; getGroup", "IS442 Revision, Past papers, size 4, Mon 10-12 + Wed 14-16", "All fields saved, ACTIVE, leader Alex the only member, 2 slots; Alex isLeader"),
        t("createGroupWithUnknownCourseOrStudentSavesNothing", "negative", "Unknown course or student", "createGroup with course 999999; as ghost@test.com", "-", "CourseNotFoundException; StudentNotFoundException; 0 groups"),
        t("createGroupRejectsBadDetailsAndSavesNothing", "negative", "Blank name or size 1 refused", "createGroup twice", "'   '; size 1", "'Group name is required'; 'Max size must be at least 2'; 0 groups"),
        t("nameOver255CharactersFailsInDatabase", "boundary", "Name 255 / 256 at the database", "createGroup", "'N' x 255, 'N' x 256", "255 saved; 256 DataIntegrityViolationException (only the form's @Size stops it in the app)"),
        t("descriptionOver1000CharactersFailsInDatabase", "boundary", "Description 1000 / 1001 at the database", "createGroup", "'D' x 1000, 'D' x 1001", "1000 saved; 1001 DataIntegrityViolationException"),
        t("createGroupDoesNotRequireTakingTheCourse", "boundary", "Leader needn't take the course", "Daniel (no courses) creates a CS201 group", "-", "Group saved with Daniel as leader"),
        t("leaderCanDropTheCourseAndStillLeadTheGroup", "side-effect", "Leader can drop the group's course", "Ben creates a CS201 group; StudentProfileService.removeCourse(Ben, CS201)", "-", "Ben takes only IS442; the CS201 group is still active and led by Ben"),
        t("updateGroupChangesDetailsAndSlotsButNotCourse", "happy", "Update details and slots; course ignored", "updateGroup with course CS201 in the form", "OOP Finals, Mock exams, PROBLEM_SOLVING, IN_PERSON, 6, Fri + Sat slots", "All saved; course still IS442; slots Fri and Sat; old slot deleted (2 slot rows in total)"),
        t("updateSizeCannotGoBelowMemberCount", "boundary", "Size = member count allowed, below refused", "3 members; update size 3, then size 2 with new name and slot", "3, 2", "3 saved (full); 2: 'Max size can't be below the current member count (3)'; name and slots unchanged"),
        t("onlyTheLeaderCanUpdateOrClose", "permission", "Only the leader updates or closes", "As member Ben, pending Chloe, outsider Daniel: update, close, getGroupLedBy", "-", "NotGroupLeaderException each time; group unchanged and active; Alex still gets it"),
        t("closeGroupEndsPendingRequestsAndReturnsHowMany", "side-effect", "Close returns the number of requests ended", "Ben, Chloe pending; Daniel member; closeGroup", "-", "Returns 2; CLOSED; Ben and Chloe GROUP_CLOSED; Daniel ACCEPTED; 2 members"),
        t("closeGroupWithNoRequestsReturnsZero", "boundary", "Close with nothing waiting", "closeGroup", "-", "Returns 0"),
        t("closedGroupCannotBeUpdatedOrClosedAgain", "conflict", "Closed group can't be updated or closed again", "closeGroup; updateGroup; closeGroup", "-", "IllegalStateException 'Group is closed' twice; name, slot and CLOSED unchanged"),
        t("unknownGroupOrStudentIsNotFound", "negative", "Unknown group or student", "get, getGroupLedBy, update, close with 999999; close and findGroupsLedBy as ghost@test.com", "999999", "StudyGroupNotFoundException x4; StudentNotFoundException x2"),
        t("browseListsOpenGroupsWithFullOnesLast", "happy", "Browse: open groups only, full ones last, every sort", "Full 'Alpha full', 'Charlie', closed 'Closed', newer 'bravo' (CS201); findActiveGroups with each sort", "-", "Closed left out; NEWEST bravo, Charlie, full; NAME bravo, Charlie, full; MOST_PLACES Charlie, bravo, full; COURSE bravo, Charlie, full"),
        t("browseByCourseListsThatCourseOnly", "happy", "Browse one course", "IS442 open, CS201 open, IS442 closed; findActiveGroupsByCourse(IS442); unknown course", "-", "Only the open IS442 group; CourseNotFoundException for 999999"),
        t("groupsLedByListsOpenFirstAndHidesRemovedClosedGroups", "side-effect", "My groups (led): open first, removed closed ones hidden", "Alex leads 4 groups (1 open, 3 closed, 1 removed from My groups); Ben leads 1", "-", "Open, Closed new, Closed old (hidden and Ben's left out)"),
    ],
    "GroupMembershipServiceTest": [
        t("requestToJoinCreatesPendingRequestTheLeaderSees", "happy", "Ask to join; the leader sees it", "requestToJoin(Ben); flushAndClear", "-", "Saved PENDING, requestedAt set, respondedAt null; in Alex's pending list and Ben's pending list; 1 member"),
        t("pendingRequestsAreOldestFirst", "happy", "Join requests oldest first", "Chloe, Ben, Emily ask in that order", "-", "getPendingRequests: Chloe, Ben, Emily"),
        t("leaderCannotJoinOwnGroup", "negative", "Leader can't join own group", "requestToJoin as Alex", "-", "IllegalArgumentException 'The leader is already in the group'; still 1 row"),
        t("duplicatePendingOrAcceptedRequestIsRefused", "conflict", "Duplicate request refused", "Ben pending, Chloe accepted; both ask again", "-", "IllegalStateException 'Student has already requested or joined this group'; still 3 rows"),
        t("rejectedOrRemovedStudentCanAskAgain", "conflict", "Rejected or removed student can ask again", "Reject Ben; accept then remove Chloe; both ask again", "-", "Same membership ids back as PENDING; respondedAt null; still 3 rows; both in the pending list"),
        t("fullOrClosedGroupCannotBeJoined", "conflict", "Full or closed group can't be joined", "Chloe's size-2 group with Ben accepted; Alex's group closed; Emily asks both", "-", "'Group is full'; 'Group is closed'; Emily has no requests"),
        t("joiningDoesNotRequireTakingTheCourse", "boundary", "Member needn't take the course", "Daniel (no courses) asks; Alex accepts", "-", "Daniel is a member"),
        t("leaderAcceptsRequest", "happy", "Leader accepts a request", "acceptRequest(Ben's request)", "-", "Returns Ben's row; ACCEPTED with respondedAt; 2 members; members Alex, Ben; Ben's joined groups has it; nothing pending"),
        t("leaderRejectsRequest", "happy", "Leader rejects a request", "rejectRequest(Ben's request)", "-", "REJECTED with respondedAt; 1 member; Ben not joined; in Ben's past requests"),
        t("acceptingIntoFullGroupIsRefused", "boundary", "Accepting into an exactly full group", "Size 4; Ben, Chloe, Emily accepted (the last one fills it); accept Daniel", "-", "Third accept works; Daniel: 'Group is full. Remove a member to accept new members.'; Daniel PENDING; 4 members; rejecting Daniel still works"),
        t("answeringTwiceIsRefused", "conflict", "Answer twice refused", "Accept Ben, reject Chloe; accept/reject again", "-", "IllegalStateException 'This request has already been answered' x3; statuses unchanged"),
        t("membershipOfAnotherGroupIsRefused", "permission", "Membership id from another group refused", "Ben's group: Chloe pending, Emily member; Alex sends them with his group id, then with Ben's group id", "-", "IllegalArgumentException 'Membership does not belong to this group' x3; NotGroupLeaderException with Ben's group id; nothing changed"),
        t("onlyTheLeaderManagesMembers", "permission", "Only the leader views and manages members", "As member Ben, pending Chloe, outsider Daniel: getPendingRequests, getMembers, accept, reject, remove", "-", "NotGroupLeaderException for all 15 calls; Ben still ACCEPTED, Chloe still PENDING"),
        t("unknownGroupMembershipOrStudentIsNotFound", "negative", "Unknown group, membership or student", "Calls with group 999999, membership 999999, ghost@test.com", "999999", "StudyGroupNotFoundException x4; MembershipNotFoundException x3; StudentNotFoundException x2"),
        t("leaderRemovesMember", "happy", "Leader removes a member", "removeMember(Ben)", "-", "Returns Ben's row; REMOVED; 1 member; members Alex only; Ben not joined; in Ben's past requests"),
        t("leaderCannotBeRemovedAndPendingRequestCannotBeRemoved", "negative", "Leader and pending request can't be removed", "removeMember(leader's row); removeMember(Chloe pending)", "-", "'The leader can't be removed from their own group'; 'This student isn't a member of the group any more'; nothing changed"),
        t("closedGroupIsFrozenForMembership", "side-effect", "Closed group is frozen for membership", "Ben member, Chloe pending; close; join, accept, remove, reject", "-", "'Group is closed' x3; reject: 'This request has already been answered' (N-B3); Ben ACCEPTED, Chloe GROUP_CLOSED; leader still sees 2 members, no requests"),
        t("closingMovesPendingRequestsToGroupClosed", "side-effect", "Closing shows the student 'group closed', not a decline", "Ben pending; close", "-", "Ben's pending list has the row as GROUP_CLOSED; past list empty; viewer role GROUP_CLOSED (can't ask again)"),
        t("removeFromMyGroupsOnlyWorksForClosedGroups", "conflict", "Remove from My groups only for closed groups", "Open group (Ben, Alex); close; Daniel (no link)", "-", "Open: 'Only a closed group can be removed from My groups' x2; Daniel: 'You aren't linked to this group'; Ben still sees it"),
        t("removeFromMyGroupsHidesTheGroupForThatStudentOnly", "side-effect", "Remove from My groups hides it for that student only", "Ben, Chloe members, Emily pending; close; Alex, Ben, Emily remove it", "-", "Gone from Alex's led list, Ben's joined list, Emily's pending list; Chloe still sees it; 3 members unchanged"),
        t("membersAreLeaderFirstThenInJoinOrder", "happy", "Members: leader first, then join order", "Chloe asks, Ben asks; accept Ben, (clock ticks), accept Chloe", "-", "Alex, Ben, Chloe", notes="Accept times on this machine tick once a millisecond; equal times fall back to the request order, so the test waits for the clock to tick (no fixed sleep)."),
        t("joinedGroupsListOpenFirstAndLeaveOutLedGroups", "happy", "Joined groups: open first; led groups left out", "Ben joins Chloe's group (then closed) and Alex's group; Ben leads his own", "-", "Alex's group, then Chloe's closed group; Ben's own left out; Alex has none"),
        t("pastRequestsListDeclinesAndRemovalsForOpenGroupsOnly", "happy", "Declined or removed: open groups only, newest first", "Ben removed from Chloe's, rejected by Alex, rejected by Emily (then closed)", "-", "Alex's rejection, then Chloe's removal; Emily's closed group left out"),
        t("viewerRoleForEveryKindOfStudent", "happy", "What the group page offers each student", "Ben member, Chloe pending, Daniel rejected, Emily removed, Frank no link; getViewerRole, getViewerRoles", "-", "LEADER, MEMBER, PENDING, REJECTED, REMOVED, NONE; map for Ben: MEMBER / LEADER; only NONE, REJECTED, REMOVED can ask"),
    ],
    # ================================ NEW (Batch C) ================================
    "SecurityConfigTest": [
        t("anonymousIsSentToLogin", "permission", "Anonymous user sent to login on every protected route", "Each protected route in ROUTES, with a CSRF token, not logged in", "45 routes", "302 to /login"),
        t("anonymousPostWithoutCsrfIsForbidden", "permission", "Anonymous POST without CSRF refused", "Each protected POST, no token, not logged in", "30 POST routes", "403 (the CSRF check runs before the login check, N-C3)"),
        t("studentIsForbiddenFromAdminRoutes", "permission", "Student refused on every admin route", "Each /admin route as STUDENT with CSRF", "9 routes", "403"),
        t("adminIsForbiddenFromStudentRoutes", "permission", "Admin refused on every student route", "Each student route as ADMIN with CSRF", "31 routes", "403"),
        t("rightRoleGetsPastSecurity", "happy", "The right role gets past security on every route", "Each protected route as its role (ADMIN for /admin, otherwise STUDENT) with CSRF", "45 routes", "Neither 403 nor a redirect to /login (404 here: no controller loaded)"),
        t("anyLoggedInUserGetsPastSecurity", "happy", "/home and /courses open to both roles", "Each any-user route as STUDENT and as ADMIN", "5 routes", "Neither 403 nor /login", notes="The /courses POSTs are admin-only through @PreAuthorize, not the URL rules; CourseControllerTest checks the refusal."),
        t("loggedInPostWithoutCsrfIsForbidden", "permission", "Every POST needs a CSRF token, even for the right role", "Each protected POST as its role, no token", "30 POST routes", "403"),
        t("publicRoutesNeedNoLogin", "happy", "Login and sign-up need no login; sign-up POST still needs CSRF", "GET /login, GET /register, POST /register anonymous", "-", "Not refused or redirected; POST /register without token 403"),
        t("staticFilesAndErrorPageNeedNoLogin", "happy", "Static files and the error page are public", "GET /css/style.css, /js/app.js, /images/logo.png, /error anonymous", "-", "style.css 200; none refused or redirected to /login"),
        t("logoutNeedsCsrfToken", "permission", "Logout needs a CSRF token", "POST /logout as a student without and with a token", "-", "403; with token redirect /login?logout"),
        t("h2ConsoleIsOpenWithoutLoginOrCsrf", "permission", "H2 console open to everyone, no CSRF check", "GET /h2-console/ and POST /h2-console/login.do (no token) anonymous; POST as a student", "-", "Never 302 or 403 (404 only because the console servlet isn't in this slice)"),
        t("h2ConsoleRelaxesFrameOptionsOnlyForItself", "side-effect", "Only the console may be framed", "GET /h2-console/, /login, /home", "-", "Console: no X-Frame-Options; /login and /home: X-Frame-Options DENY"),
    ],
    "RouteInventoryTest": [
        t("securityMatrixListsEveryControllerRoute", "boundary", "Security matrix lists every controller route", "Read every request mapping of com.studybuddy.controller; compare with SecurityConfigTest.ROUTES", "48 mappings", "Same set both ways; otherwise 'Route table out of date: add <method path> to the SecurityConfigTest matrix and say which role may call it' (or remove ...)"),
        t("rootAddressIsNotMapped", "negative", "Bare address / is not mapped", "GET / anonymous, as STUDENT, as ADMIN", "-", "Anonymous: /login; logged in: 404"),
    ],
    "HomeControllerTest": [
        t("studentSeesStudentLinks", "happy", "Student home page links", "GET /home as STUDENT", "alex@test.com", "View home; username, isAdmin false; links to profile, matches, match requests, connections, groups, courses; no admin links"),
        t("adminSeesAdminLinks", "happy", "Admin home page links", "GET /home as ADMIN", "admin@test.com", "isAdmin true; links to accounts, courses, matching settings; no student links"),
        t("anonymousIsRedirectedToLogin", "permission", "Anonymous sent to login", "GET /home not logged in", "-", "302 to /login"),
    ],
    "StudentProfileControllerTest": [
        t("pageShowsOwnProfileAndOnlyOtherCoursesToAdd", "happy", "Profile page", "Mock: Alex takes IS442; all courses IS442, CS201; GET /student/profile", "-", "View student/profile; student, coursesTaken [IS442], otherCourses [CS201]"),
        t("updateProfileStripsValuesAndRedirectsWithSuccess", "happy", "Update profile (values stripped)", "POST /student/profile", "'  Alex Tan ', ' SCIS ', ' Computer Science ', 3, ' 87654321 '", "Service gets stripped values for alex@test.com; flash 'Your profile has been updated.'"),
        t("invalidProfileShowsServiceMessage", "negative", "Invalid profile value", "Mock throws IllegalArgumentException('Contact number must be 8 digits')", "contact 1234", "Flash 'Contact number must be 8 digits.'"),
        t("missingFieldsAreSentAsBlanks", "boundary", "Missing fields reach the service as blanks", "POST with no fields", "-", "Service gets ('', '', '', 0, '')"),
        t("nonNumericYearOfStudyIsBadRequest", "negative", "Non-numeric year", "POST yearOfStudy 'two'", "'two'", "400; service not called"),
        t("addCourseRedirectsWithSuccess", "happy", "Add a course", "POST /student/profile/courses courseId 2", "-", "Flash 'The course has been added.'; addCourse(alex, 2)"),
        t("addCourseWithoutChoosingOneSavesNothing", "negative", "Add without choosing a course", "POST /student/profile/courses, no courseId", "-", "Flash 'Choose a course to add.'; service not called"),
        t("addUnknownCourseShowsError", "negative", "Add a missing course", "Mock throws CourseNotFoundException", "99", "Flash 'That course no longer exists.'"),
        t("addCourseAlreadyTakenShowsServiceMessage", "conflict", "Add a course already taken", "Mock throws IllegalStateException", "IS442", "Flash 'You are already taking IS442.'"),
        t("removeCourseRedirectsWithSuccess", "happy", "Remove a course", "POST /student/profile/courses/1/remove", "-", "Flash 'The course has been removed.'"),
        t("removeCourseWithPreferenceShowsServiceMessage", "conflict", "Remove a course that has a preference", "Mock throws IllegalStateException", "IS442", "Flash 'Delete your IS442 study preference before removing the course.'"),
        t("removeUnknownCourseShowsError", "negative", "Remove a missing course", "Mock throws CourseNotFoundException", "99", "Flash 'That course no longer exists.'"),
        t("emailParameterIsIgnored", "permission", "An 'email' field can't act for another student", "Three POSTs with email=ben@test.com", "-", "Service always gets alex@test.com"),
        t("missingStudentAccountIsNotFound", "negative", "Logged-in email with no student account", "Mock throws StudentNotFoundException; GET /student/profile", "-", "404 (bare error page)"),
        t("refusedRequestsNeverReachTheService", "permission", "Refused requests never reach the service", "4 routes anonymous and as ADMIN; 3 POSTs without CSRF", "-", "/login, 403, 403; no service call"),
    ],
    "StudentProfileControllerKnownBugTest": [
        t("profileValueTheDatabaseRefusesShowsAnError", "negative", "Over-long profile value should show an error", "Mock throws DataIntegrityViolationException; POST /student/profile", "name 'A' x 256", "DESIRED: redirect /student/profile with a flash error. TODAY: uncaught exception, 500 (confirmed when enabled)"),
    ],
    "StudyPreferenceControllerTest": [
        t("pageShowsPreferencesAndOnlyCoursesWithoutOne", "happy", "Preferences page", "Mock: preference 4 (IS442); Alex takes IS442, CS201; GET ?edit=4", "-", "View student/preferences; preferences, coursesWithoutPreference [CS201], editingId 4, 61 time options"),
        t("createPreferenceRedirectsToItsEditForm", "happy", "Add a preference", "POST /student/preferences", "IS442, ONLINE, ONE_TO_ONE, CONCEPT_REVIEW", "Redirect ?edit=4; flash 'Your IS442 preference has been added. Add the times you're free below.'"),
        t("createWithoutCourseSavesNothing", "negative", "Add without a course", "POST without courseId", "-", "Flash 'Choose a course for the preference.'; service not called"),
        t("createForUnknownCourseShowsError", "negative", "Add for a missing course", "Mock throws CourseNotFoundException", "99", "Flash 'That course no longer exists.'"),
        t("refusedCreateShowsServiceMessage", "conflict", "Refused add shows the service message", "Mock throws IllegalArgumentException, then IllegalStateException", "-", "'Choose a study mode, group size and study goal.'; 'You already have a preference for IS442.'"),
        t("updatePreferenceRedirectsWithSuccess", "happy", "Update a preference", "POST /student/preferences/4/update", "IN_PERSON, SMALL_GROUP, PROBLEM_SOLVING", "Flash 'Your IS442 preference has been updated.'"),
        t("updateWithMissingChoiceReturnsToEditForm", "negative", "Update with a missing choice", "Mock throws IllegalArgumentException", "-", "Redirect ?edit=4; flash 'Choose a study mode, group size and study goal.'"),
        t("unknownPreferenceShowsErrorOnEveryAction", "permission", "Missing or someone else's preference", "Mock throws StudyPreferenceNotFoundException for update, delete, add slot, remove slot", "preference 8", "Redirect /student/preferences; flash 'That study preference no longer exists.' each time"),
        t("deletePreferenceRedirectsWithSuccess", "happy", "Delete a preference", "POST /student/preferences/4/delete", "-", "Flash 'The study preference has been deleted.'"),
        t("addSlotReadsTimesAndReturnsToEditForm", "happy", "Add a slot", "POST /student/preferences/4/slots", "MONDAY 10:00-12:00", "Service gets MONDAY, 10:00, 12:00; redirect ?edit=4; flash 'The time slot has been added.'"),
        t("refusedSlotShowsServiceMessageOnEditForm", "conflict", "Overlapping slot", "Mock throws IllegalArgumentException", "MONDAY 11:00-13:00", "Redirect ?edit=4; flash 'This slot overlaps one you already have on MONDAY.'"),
        t("timeNotInHourMinuteFormatIsBadRequest", "negative", "Time not in HH:mm", "POST startTime 25:00", "25:00, 26:00", "400; service not called"),
        t("removeSlotReturnsToEditForm", "happy", "Remove a slot", "POST /student/preferences/4/slots/9/remove", "-", "Redirect ?edit=4; flash 'The time slot has been removed.'"),
        t("removeUnknownSlotShowsError", "negative", "Remove a missing slot", "Mock throws IllegalArgumentException", "slot 77", "Flash 'That time slot no longer exists.'"),
        t("emailParameterIsIgnored", "permission", "An 'email' field can't act for another student", "POSTs and GET with email=ben@test.com", "-", "Service always gets alex@test.com"),
        t("refusedRequestsNeverReachTheService", "permission", "Refused requests never reach the service", "6 routes anonymous and as ADMIN; 5 POSTs without CSRF", "-", "/login, 403, 403; no service call"),
    ],
    "MatchingControllerTest": [
        t("pageShowsRankedResultsForLoggedInStudent", "happy", "Matches page", "GET /matches", "-", "View student/matches; myPreferences, results, strategy BALANCED; filter choices without EITHER; page shows Ben Koh and 92"),
        t("filtersAreBoundToTheSearch", "happy", "Every filter reaches the service", "GET /matches with all five filters", "courseId 1, EXAM_PREPARATION, ONLINE, SMALL_GROUP, sharedTimeOnly true", "findMatches(alex, MatchSearch(1, EXAM_PREPARATION, ONLINE, SMALL_GROUP, true))"),
        t("noFiltersSearchEverything", "boundary", "No filters", "GET /matches", "-", "MatchSearch(null, null, null, null, false)"),
        t("emailParameterIsIgnored", "permission", "An 'email' parameter can't search as another student", "GET /matches?email=ben@test.com", "-", "findMatches with alex@test.com only"),
        t("unknownFilterValueIsBadRequest", "negative", "Hand-edited filter value", "GET ?studyGoal=BOGUS; ?courseId=abc", "-", "400 each; service not called"),
        t("missingStudentAccountIsNotFound", "negative", "Logged-in email with no student account", "Mock throws StudentNotFoundException", "-", "404"),
        t("refusedRequestsNeverReachTheService", "permission", "Refused requests never reach the service", "GET anonymous and as ADMIN", "-", "/login, 403; no service call"),
    ],
    "StudyBuddyConnectionControllerTest": [
        t("pageListsLoggedInStudentsBuddies", "happy", "Study buddies page", "GET /connections", "Ben Koh, 92345678, 13 Oct 2026", "View student/connections; page shows name, number and date"),
        t("endConnectionRedirectsWithSuccess", "happy", "End a connection", "POST /connections/3/end", "-", "Flash 'Connection ended. You can no longer see each other's contact numbers.'; endConnection(alex, 3)"),
        t("endUnknownConnectionShowsError", "negative", "End a missing or someone else's connection", "Mock throws ConnectionNotFoundException", "8", "Flash 'That connection no longer exists.'"),
        t("emailParameterIsIgnored", "permission", "An 'email' field can't act for another student", "POST end and GET with email=ben@test.com", "-", "Service gets alex@test.com"),
        t("refusedRequestsNeverReachTheService", "permission", "Refused requests never reach the service", "GET and POST anonymous, as ADMIN, POST without CSRF", "-", "/login, 403, 403; no service call"),
    ],
    "AdminMatchingConfigControllerTest": [
        t("pageShowsCurrentSettings", "happy", "Matching settings page", "GET /admin/matching", "-", "View admin/matching-config; form from the current settings, strategies, current"),
        t("saveRedirectsWithSavedMessage", "happy", "Save settings", "POST AVAILABILITY_FIRST, weights 0/1/0/0/0, 60 minutes", "-", "update(form with those values); redirect /admin/matching; flash saved = true"),
        t("invalidSettingsShowServiceMessage", "negative", "Refused settings", "Mock throws InvalidMatchingConfigException", "all weights 0", "Form again with error 'At least one criterion needs a weight above 0.'; current settings shown"),
        t("blankOrNonNumericFieldShowsMessage", "boundary", "Blank or non-numeric field", "POST with course weight '', 'abc', '1.5'", "-", "Form again with 'Every weight and the session length must be a whole number.'; update never called"),
        t("missingStrategyIsLeftToTheService", "negative", "No strategy chosen", "POST without strategy; mock throws", "-", "Service gets strategy null; its message shown"),
        t("refusedRequestsNeverReachTheService", "permission", "Refused requests never reach the service", "GET and POST anonymous, as STUDENT, POST without CSRF", "-", "/login, 403, 403; no service call"),
    ],
    "MatchRequestControllerPrincipalTest": [
        t("emailParameterIsIgnoredOnEveryAction", "permission", "An 'email' field can't act for another student", "POST send, accept, reject and GET with email / senderEmail = ben@test.com", "-", "Service always gets alex@test.com"),
        t("refusedPostsNeverReachTheService", "permission", "Refused POSTs never reach the service", "3 POSTs anonymous and as ADMIN (with CSRF)", "-", "/login and 403; no service call"),
    ],
    "MatchRequestControllerKnownBugTest": [
        t("shortMessagesThatReadAsFalseAreShown", "negative", "Messages 'no', 'false', 'off' should be shown", "Mock: received 'no' and 'Study IS442 together?', sent 'false' and 'off'; GET /match-requests", "-", "DESIRED: <p>no</p>, <p>false</p>, <p>off</p> on the page. TODAY: only the ordinary message is shown (th:if reads the three words as false; confirmed when enabled)"),
    ],
    "ContactNumberVisibilityTest": [
        t("matchesPageNeverShowsContactNumbers", "side-effect", "Matches page never shows a contact number", "Mock: results for Ben and Chloe built with from(); GET /matches", "91110001, 92220002, 93330003", "Names shown; none of the three numbers in the HTML"),
        t("matchRequestsPageNeverShowsContactNumbers", "side-effect", "Match requests never show a contact number, even accepted", "Mock: accepted request from Ben, pending to Chloe (from()); GET /match-requests", "same numbers", "Names shown; no number in the HTML"),
        t("connectionsPageShowsOnlyTheBuddysNumber", "happy", "Study buddies shows only the buddy's number", "Mock: Alex-Ben connection via ConnectionSummary.from(); GET /connections", "same numbers", "Ben's 92220002 shown; Alex's own and Chloe's not"),
    ],
    "HtmlEscapingTest": [
        t("matchRequestMessageIsEscaped", "negative", "Match request message is escaped", "Mock: received message 'Hi <script>alert(1)</script>'; GET /match-requests", "<script>alert(1)</script>", "HTML has '&lt;script&gt;alert(1)&lt;/script&gt;' and never the raw tag"),
        t("groupNameAndDescriptionAreEscaped", "negative", "Group name and description are escaped", "Mock group 1 with the script in name and description; GET /groups/1", "<script>alert(1)</script>", "Both escaped; raw tag absent"),
        t("profileNameIsEscaped", "negative", "Profile name is escaped (inside an input value)", "Mock profile name 'Alex <script>alert(1)</script>'; GET /student/profile", "<script>alert(1)</script>", "Escaped; raw tag absent"),
    ],
    "StudyGroupControllerTest": [
        t("browseListsOpenGroupsWithViewerRoles", "happy", "Browse groups", "Mock: Alex's group (leader) and a full group; GET /groups", "-", "View studygroup/browse; both summaries; spaceCount 1, fullCount 1; selectedSort NEWEST; joinableOnly false"),
        t("browseUsesCourseFilterAndSort", "happy", "Course filter and sort", "GET ?courseId=1&sort=NAME; GET ?sort=bogus", "-", "findActiveGroupsByCourse(1, NAME); bogus falls back to NEWEST"),
        t("browseUnknownCourseShowsAllGroupsInstead", "negative", "Browse a missing course", "Mock throws CourseNotFoundException", "99", "Redirect /groups; flash 'That course doesn't exist. Showing all groups instead.'"),
        t("joinableOnlyKeepsGroupsTheStudentCanJoin", "boundary", "Only groups I can join", "Roles NONE, MEMBER, REJECTED, NONE-but-full; GET ?joinableOnly=true", "-", "Only the NONE and REJECTED groups with room"),
        t("myGroupsShowsTheStudentsFourLists", "happy", "My groups", "GET /groups/mine", "-", "View studygroup/my-groups; groupsLed, groupsJoined, pendingRequests, pastRequests for alex@test.com"),
        t("groupPageShowsGroupAndViewerRole", "happy", "Group page", "GET /groups/1?from=mine", "-", "View studygroup/details; group summary, viewerRole LEADER, fromMyGroups true"),
        t("unknownGroupPageGoesBackToBrowse", "negative", "Missing group page", "Mock throws StudyGroupNotFoundException", "99", "Redirect /groups; flash 'That group no longer exists.'"),
        t("createFormStartsBlank", "happy", "Create form", "GET /groups/new", "-", "View studygroup/form; 1 slot row; minSize 2; maxSlots 3; 61 time options"),
        t("createGroupRedirectsToItsPage", "happy", "Create a group", "POST /groups (valid form)", "New Group, IS442, size 3, Mon 10:00-12:00", "Redirect /groups/7?from=mine; flash 'Group created. Other students can now find it on Study groups and ask to join.'; service gets the form for alex@test.com"),
        t("invalidCreateShowsEveryErrorOnTheForm", "negative", "Invalid create shows every error", "POST blank name, size 1, overlapping slots", "-", "Form again: 'Please enter a group name', 'A group needs room for at least 2 members, including you', 'Slots 1 and 2 overlap'; service not called"),
        t("createForUnknownCourseShowsErrorUnderCourse", "negative", "Course deleted meanwhile", "Mock throws CourseNotFoundException", "-", "Form again; under Course 'That course no longer exists. Please choose another.'"),
        t("createRefusedByTheGroupShowsItsMessage", "negative", "Entity refuses the group", "Mock throws IllegalArgumentException", "-", "Form again with error 'Max size must be at least 2'"),
        t("addAndRemoveSlotRowsSaveNothing", "boundary", "Add slot / Remove rows", "POST with addSlot (1 row, then 3 rows), with removeSlot=0 (2 rows)", "-", "2 rows; still 3 (no fourth); only the Tuesday row left; nothing saved"),
        t("editFormIsFilledForTheLeader", "happy", "Edit form", "GET /groups/1/edit?from=mine", "-", "Form filled ('IS442 Revision'); editing summary; minSize 3 (member count)"),
        t("editFormRefusedWithReason", "permission", "Edit refused for non-leader, closed or missing group", "GET edit for groups 2, 3 (closed), 99", "-", "'Only the group's leader can do that.'; 'This group is closed, so it can't be edited.' (from=mine kept); 'That group no longer exists.'"),
        t("updateGroupRedirectsWithChangesSaved", "happy", "Save changes", "POST /groups/1/edit size 5, from=mine", "-", "Redirect /groups/1?from=mine; flash 'Changes saved.'"),
        t("updateBelowMemberCountShowsErrorUnderSize", "boundary", "Size below the member count", "POST /groups/1/edit size 2 (3 members)", "2", "Form again; under size 'The group has 3 members, so the maximum size can't be below 3'; service not called"),
        t("updateRefusedByServiceShowsItsMessage", "negative", "Service refuses the update", "Mock updateGroup throws IllegalArgumentException", "-", "Form again with the message; group read again"),
        t("closeGroupSaysHowManyRequestsEnded", "side-effect", "Close messages", "closeGroup returns 0, 1, 2", "-", "'Group closed.'; '... 1 pending request ended without an answer.'; '... 2 pending requests ended without an answer.'"),
        t("closeRefusedWithReason", "permission", "Close refused", "Mock throws NotGroupLeader, IllegalState, StudyGroupNotFound", "-", "'Only the group's leader can do that.'; 'This group is already closed.'; 'That group no longer exists.' (to /groups)"),
        t("emailParameterIsIgnored", "permission", "An 'email' field can't act for another student", "Create, edit, close with email=ben@test.com", "-", "Service always gets alex@test.com"),
        t("refusedRequestsNeverReachTheService", "permission", "Refused requests never reach the service", "8 routes anonymous and as ADMIN; 3 POSTs without CSRF", "-", "/login, 403, 403; no service call"),
    ],
    "GroupMembershipControllerTest": [
        t("requestToJoinRedirectsWithSuccess", "happy", "Ask to join", "POST /groups/1/join (and with from=mine)", "-", "Redirect /groups/1 (/groups/1?from=mine); flash 'Request sent. The group's leader will review it.'"),
        t("refusedJoinShowsTheGroupsMessage", "conflict", "Refused join", "Mock throws IllegalState, IllegalArgument, StudyGroupNotFound", "-", "'Group is full'; 'The leader is already in the group'; 'That group no longer exists.' (to /groups)"),
        t("removeFromMyGroupsRedirectsWithSuccess", "happy", "Remove a closed group from My groups", "POST /groups/1/remove-from-my-groups", "-", "Redirect /groups/mine; flash 'Removed from My groups.'"),
        t("removeFromMyGroupsRefusedWithReason", "conflict", "Remove from My groups refused", "Mock throws IllegalState; StudyGroupNotFound", "-", "'Only a closed group can be removed from My groups'; 'That group no longer exists.'"),
        t("membersPageShowsRequestsAndMembers", "happy", "Manage members page", "GET /groups/1/members", "-", "View studygroup/members; group summary, requests [Farhan], members [Alex, Chloe]"),
        t("membersPageRefusedForNonLeaderOrMissingGroup", "permission", "Manage members refused", "Mock throws NotGroupLeader (2), StudyGroupNotFound (99)", "-", "Redirect /groups/2 'Only the group's leader can do that.'; /groups 'That group no longer exists.'"),
        t("acceptAndRejectNameTheStudent", "happy", "Accept and reject", "POST /members/13/accept, /reject", "-", "'Farhan Ismail is now a member of the group.'; 'Farhan Ismail's request was rejected.'"),
        t("removeMemberNamesTheStudent", "happy", "Remove a member", "POST /members/12/remove", "-", "'Chloe Lim was removed from the group.'"),
        t("manageActionsRefusedWithReason", "conflict", "Manage actions refused", "Not leader; missing group; missing membership; full group; leader removal", "-", "Each message and destination: /groups/2, /groups, 'That request or member no longer exists.', 'Group is full. Remove a member to accept new members.', 'The leader can't be removed from their own group'"),
        t("emailParameterIsIgnored", "permission", "An 'email' field can't act for another student", "Join, remove from My groups, accept, remove with email=ben@test.com", "-", "Service always gets alex@test.com"),
        t("refusedRequestsNeverReachTheService", "permission", "Refused requests never reach the service", "GET members and 5 POSTs anonymous, as ADMIN, without CSRF", "-", "/login, 403, 403; no service call"),
    ],
    # ================================ NEW (Batch D) ================================
    "MatchSearchTest": [
        t("emptySearchAcceptsEveryone", "happy", "Empty search keeps everyone", "MatchSearch(null x5)", "-", "sharedTimeOnly false; accepts with 0 shared minutes; includesMine any course"),
        t("courseFilterKeepsThatCourseOnly", "happy", "Course filter", "accepts with courseId 1 / 2", "theirs IS442 (id 1)", "1 accepted; 2 refused"),
        t("courseFilterPicksTheSearchersPreferenceForIt", "happy", "Course filter picks the searcher's own preference", "includesMine with courseId 1", "IS442, CS201", "IS442 included; CS201 not"),
        t("goalFilterKeepsThatGoalOnly", "happy", "Goal filter", "accepts with EXAM_PREPARATION / CONCEPT_REVIEW", "-", "Same goal accepted; other refused"),
        t("studyModeFilterKeepsThatModeAndEither", "boundary", "Study mode filter and EITHER", "IN_PERSON / ONLINE filters on IN_PERSON and EITHER students", "-", "Same mode accepted; other refused; an EITHER student passes both"),
        t("groupSizeFilterKeepsThatSizeAndEither", "boundary", "Group size filter and EITHER", "ONE_TO_ONE / SMALL_GROUP filters", "-", "Same size accepted; other refused; an EITHER student passes"),
        t("eitherFilterKeepsEveryone", "boundary", "An EITHER filter keeps everyone", "studyMode EITHER, groupSize EITHER", "-", "Everyone accepted"),
        t("sharedTimeFilterNeedsAtLeastOneMinute", "boundary", "Shared time 0 / 1 minute", "sharedTimeOnly true and false", "0, 1 shared minutes", "true: 0 refused, 1 accepted; false: 0 accepted"),
        t("combinedFiltersMustAllPass", "negative", "All filters must pass", "All five filters set", "-", "Matching student accepted; no shared time, other goal, other course each refused"),
    ],
    "ScoreBreakdownTest": [
        t("equalWeightsGiveTheAverage", "happy", "Equal weights give the average", "weightedScore with all weights 20", "1, .5, .8, 0, 1", "66"),
        t("onlyOneWeightOnMeansOnlyThatCriterionCounts", "boundary", "One weight above 0", "weights 0/1/0/0/0, 0/0/0/7/0, 5/0/0/0/0", "-", "50, 0, 100"),
        t("weightsAreRelative", "happy", "Weights are relative", "weights 3/1/0/0/0 and 30/10/0/0/0", "-", "Both 87.5"),
        t("sharedTimeLabelReadsAsHoursAndMinutes", "boundary", "Shared time label", "sharedTimeLabel()", "0, 45, 60, 90, 240 minutes", "None, 45m, 1h, 1h 30m, 4h"),
    ],
    "CompatibilityCalculatorBoundaryTest": [
        t("eitherScoreBelowZeroIsRefused", "negative", "Either score -0.1 refused", "new CompatibilityCalculator(-0.1)", "-0.1", "IllegalArgumentException 'studybuddy.matching.either-preference-score must be between 0 and 1, was -0.1'"),
        t("eitherScoreOfZeroOrOneIsAllowedAndUsed", "boundary", "Either score 0 and 1", "Score IN_PERSON/ONE_TO_ONE vs EITHER/EITHER with 0 and with 1", "0, 1", "Mode and group size 0, then 1.0"),
        t("availabilityAroundTheTargetSession", "boundary", "Shared time 119 / 120 / 121 minutes", "Mon 10:00-11:59 / 12:00 / 12:01 vs Mon 10:00-14:00", "target 120", "119/120; 1.0; 1.0 (capped)"),
        t("oneMinuteTargetGivesFullMarksForAnyOverlap", "boundary", "One-minute target", "1 shared minute, target 1", "-", "sharedMinutes 1, availability 1.0"),
        t("noSlotsShareNoTime", "boundary", "No slots", "No slots on one side, the other, both", "-", "sharedMinutes 0, availability 0; course and goal still 1.0"),
        t("eitherOnBothSidesIsAFullMatchEvenWithZeroEitherScore", "boundary", "EITHER vs EITHER is a full match", "Either score 0; EITHER vs EITHER", "-", "Mode and group size 1.0"),
    ],
    "MatchingStrategyComponentsTest": [
        t("everyStrategyTypeHasExactlyOneComponent", "happy", "Each strategy type has exactly one component", "Scan com.studybuddy for MatchingStrategy @Components", "-", "BALANCED, AVAILABILITY_FIRST, COURSE_FIRST each map to exactly one class"),
        t("matchingServiceStartsWithAllStrategies", "happy", "MatchingService starts with all strategies", "new MatchingService(..., the scanned strategies)", "-", "No error"),
        t("matchingServiceRefusesToStartWithAMissingStrategy", "negative", "A missing strategy stops startup", "new MatchingService without CourseFirst", "-", "IllegalStateException 'No MatchingStrategy implementation for COURSE_FIRST'"),
    ],
    "MatchingConfigServiceTest": [
        t("getCurrentReturnsTheSavedSettings", "happy", "Current settings", "Save defaults; getCurrent", "-", "The saved row: Balanced, total weight 100, 120 minutes"),
        t("getCurrentWithoutSettingsFails", "negative", "No settings row", "getCurrent on an empty table", "-", "IllegalStateException 'No matching config found; MatchingConfigDataSeeder should create it at startup'"),
        t("severalRowsUseTheFirstOne", "boundary", "Two rows: the first is used", "Save defaults, then a Course first row", "-", "getCurrent returns the first (Balanced)"),
        t("updateSavesTheNewSettings", "happy", "Update the settings", "update(AVAILABILITY_FIRST, 0/3/1/0/2, 90); flushAndClear", "-", "Every value saved; still one row"),
        t("refusedUpdatesChangeNothing", "negative", "Each refusal leaves the settings unchanged", "update with null strategy, weight -1, all 0, session 0 and -30", "-", "InvalidMatchingConfigException: 'Choose a matching strategy.', 'Weights can't be negative.', 'At least one criterion needs a weight above 0.', 'The study session length must be at least 1 minute.' x2; saved row still Balanced / 20s / 120"),
        t("smallestValidSettingsAreAccepted", "boundary", "Smallest valid settings", "update with one weight 1 and 1 minute", "0/0/0/1/0, 1", "Saved: total weight 1, 1 minute"),
        t("updateWithoutSettingsFails", "negative", "Update with no settings row", "update on an empty table", "-", "IllegalStateException; still no row"),
    ],
    "MatchingServiceFixtureTest": [
        t("balancedRanksByScore", "happy", "Balanced ranks by score", "findMatches(Alex, no filters)", "-", "Amy 90, Ben 80, Cal 60; Ben course 0, 240 shared minutes"),
        t("eachStrategyOrdersTheSameResultsDifferently", "happy", "The admin's strategy decides the order on the next search", "Set Availability first, search; set Course first, search", "-", "Ben, Cal, Amy; then Amy, Cal, Ben; scores unchanged"),
        t("weightsChangeTheScoresOnTheNextSearch", "happy", "Weights change the scores on the next search", "Only availability weight; only course weight", "0/1/0/0/0, 1/0/0/0/0", "Ben 100, Cal 100, Amy 50; then Ben 0, Amy 100"),
        t("courseSearchLeavesOutOtherCourses", "happy", "Course search leaves out other courses", "Search IS442", "-", "Amy, Cal; all IS442"),
        t("courseWithoutOwnPreferenceFindsNobody", "boundary", "Course the searcher has no preference for", "Search CS201 (Alex has none)", "-", "Empty, although Ben wants CS201"),
        t("filtersNarrowTheResults", "happy", "Filters through the service", "Dan (Mon 18-20) added; goal, shared time, mode filters", "-", "Exam goal: Amy, Ben, Dan; shared time: Amy, Ben, Cal; Online: Cal"),
        t("studentWithSeveralPreferencesIsListedOnceThroughTheBest", "side-effect", "Several preferences: listed once, through the best", "Dana: weak IS442 (40) and strong CS201 (80)", "-", "Dana once, through CS201, 80"),
        t("bestResultFollowsTheActiveStrategy", "boundary", "'Best' follows the active strategy", "Erin: IS442 (90, 60 min) and CS201 (40, 240 min); Balanced, then Availability first", "-", "Balanced: IS442; Availability first: CS201 (40)"),
        t("searcherAndInactiveStudentsAreLeftOut", "side-effect", "Searcher and inactive students left out", "Deactivate Amy; search", "-", "Ben, Cal; never Alex"),
        t("studentWithNoPreferencesGetsNoMatches", "boundary", "Student with no preferences", "Zoe (no preferences) searches", "-", "No matches; no own preferences"),
        t("unknownEmailOrAdminIsNotFound", "negative", "Unknown email or an admin", "findMatches / getPreferences as ghost@test.com; findMatches as an admin", "-", "StudentNotFoundException each time"),
        t("ownPreferencesAreListedByCourseCode", "happy", "Own preferences by course code", "Alex adds a CS201 preference; getPreferences", "-", "CS201, IS442"),
        t("newPreferenceIsMatchedOnTheNextSearch", "happy", "A new preference is matched on the next search", "Fay (no preference) not listed; she adds an IS442 preference and slot like Alex's", "-", "Fay first with 100", notes="Own-data replacement for the teammate's seeded StudyPreferenceServiceTest.newPreferenceShowsUpInMatching (K19)."),
    ],
    "MatchingServiceKnownBugTest": [
        t("connectedBuddyIsNotListed", "negative", "A connected buddy should not be listed", "Alex and Ben connected; Alex searches", "-", "DESIRED: Chloe and Dan, not Ben. TODAY: Ben, Chloe and Dan (confirmed when enabled)"),
        t("studentWithPendingRequestEitherWayIsNotListed", "negative", "A student with a pending request either way should not be listed", "Alex -> Ben and Chloe -> Alex pending; Alex searches", "-", "DESIRED: Dan only. TODAY: Ben, Chloe and Dan (confirmed when enabled)", notes="Planned in docs/decisions.md ('Not done yet')."),
    ],
    "DataSeedersTest": [
        t("atLeastTenCoursesAndFiftyStudentsAreSeeded", "happy", "At least 10 courses and 50 students", "Count after startup", "seeded", ">= 10 courses, >= 50 students", notes="Uses the seeded data (DataSeedersTest checks the seeders)."),
        t("exactSeedSizes", "boundary", "Today's seed sizes (pin)", "Count after startup", "seeded", "Exactly 10 courses, 50 students, 1 admin", notes="Uses the seeded data (DataSeedersTest checks the seeders). A pin of today's seed data, not a rule: update the numbers if the seed data changes."),
        t("emailsAndCourseCodesAreUnique", "happy", "Emails and course codes unique", "All users and courses", "seeded", "No duplicates", notes="Uses the seeded data (DataSeedersTest checks the seeders)."),
        t("seededPasswordsAreBcryptHashesOfTheSeedPassword", "side-effect", "Seeded passwords are BCrypt hashes", "Every user's passwordHash", "studybuddy.seed.password", "Never the password itself; BCrypt format; each distinct hash matches the seed password", notes="Uses the seeded data (DataSeedersTest checks the seeders)."),
        t("oneActiveAdminIsSeeded", "happy", "One active admin", "All admins", "seeded", "Exactly admin@studybuddy.com, active", notes="Uses the seeded data (DataSeedersTest checks the seeders)."),
        t("matchingConfigRowEqualsTheDefaults", "happy", "Matching settings equal the defaults", "All MatchingConfig rows vs the MatchingDefaults bean", "application.properties", "One row; strategy, five weights and minutes equal the defaults", notes="Uses the seeded data (DataSeedersTest checks the seeders)."),
        t("preferencesAreOnlyForCoursesTheStudentTakes", "happy", "Preferences only for taken courses", "Every student's preferences", "seeded", "Each preference's course is in the student's courses", notes="Uses the seeded data (DataSeedersTest checks the seeders)."),
        t("contactNumbersAreEightDigitsAndUnique", "happy", "Contact numbers valid and unique", "Every student's contact number", "seeded", "8 digits each; no duplicates", notes="Uses the seeded data (DataSeedersTest checks the seeders)."),
        t("runningEverySeederAgainChangesNothing", "side-effect", "Running every seeder again adds nothing", "Run all 7 CommandLineRunner seeders again", "seeded", "Users, courses, preferences, slots, configs, groups, memberships, requests, connections unchanged", notes="Uses the seeded data (DataSeedersTest checks the seeders)."),
    ],
}

# --------------------------------------------------------------------------------------
# Manual (browser) test cases
# --------------------------------------------------------------------------------------
MANUAL_BANNER = ("MANUAL CASES: restart the app before each full manual run. The database is in memory, so restarting "
                 "resets it to the seeded data; a case marked 'Do not restart' must follow the case it names in the same app run.")
APP_RUN = ("App running: in the studybuddy folder run ./mvnw spring-boot:run, wait for 'Started StudybuddyApplication', "
           "then open http://localhost:8080/login. Seeded password for every seeded account: password123 "
           "(studybuddy.seed.password in application.properties). The database is in memory: restarting the app resets "
           "it to the seeded data.")
APP_PRE = APP_RUN + " Restart the app right before this case."
LOGIN = "Open http://localhost:8080/login, type {who} in Email and password123 in Password, click 'Log in'."
LOGOUT = "Click 'Log out' at the top of the page."


def login(who):
    return LOGIN.format(who=who)


def after(*earlier):
    """Preconditions for a case that builds on earlier ones in the same app run."""
    return (APP_RUN + f" Do not restart: run straight after {' and '.join(earlier)} in the same app run "
            "(a restart undoes them).")


def m(mid, ids, kind, title, pre, steps, data, expected, notes="", batch="New"):
    return {"id": mid, "ids": ids, "type": kind, "title": title, "pre": pre, "steps": steps,
            "data": data, "expected": expected, "notes": notes, "batch": batch}


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
      "The group page shows 'Group created. Other students can now find it on Study groups and ask to join.', the name, 'IS442 Object Oriented Programming', the description, 'Exam preparation', 'Online', Members '1/4 members' with a green dot then 'Alex Tan' (screen readers also hear ', places left'), and Time slots 'Mon 10:00-12:00' and 'Wed 14:00-16:00'."),
    m("MAN-13", "L3, GRP-3, GRP-6", "happy", "Edit and close a group", after("MAN-12"),
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/groups/mine and open 'Manual Test Group'.",
               "Click 'Edit group', change Description to 'Edited', click 'Save changes'.",
               "Open http://localhost:8080/groups/mine, open 'IS442 OOP Exam Prep', click 'Edit group', set Maximum group size to 2 and click 'Save changes'.",
               "Open 'Manual Test Group' again, click 'Close group', then 'Close group' in the box."),
      "Description 'Edited'; size 2",
      "Step 3: 'Changes saved.' and the new description. Step 4: the form comes back (the form has novalidate, so the server checks it) with 'Some details need fixing. See the messages below.' and, under Maximum group size, 'The group has 3 members, so the maximum size can't be below 3'; nothing is saved. Step 5: the box says 'Close this group? It will stop taking new members, and you can't reopen it.'; then 'Group closed.' and 'This group is closed, so it isn't taking new members.', with no 'Edit group', 'Manage members' or 'Close group' buttons."),
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
      "Step 3: 'Request sent. The group's leader will review it.' and the page now says 'Request sent. Waiting for the group's leader to respond.' Step 4: the 'Request to join' button is greyed out and 'This group is full.' is shown under it. Step 5: 'IS112 SQL Practice' is under 'Pending group join requests'."),

    # ================================ NEW (Batch B) ================================
    # Seeded groups (checked against the seeders on 2026-10-24; ids follow the seeding order):
    # 1 IS442 OOP Exam Prep (Alex leads; Ben, Chloe members; Farhan, Jasmine pending; 3/4)
    # 2 CS201 DSA Problem Solvers (Ben leads; Alex, Emily Wong members; 3/5)
    # 3 IS111 Python Basics (Farhan leads), 4 IS112 SQL Practice (Daniel Ng leads; 2/4),
    # 5 IS216 Web App Project Team (Grace Lee leads; full 3/3), 6 ECON101 Midterm Revision (Emily Wong leads; CLOSED)
    m("MAN-32", "L2, GRP-1, GRP-7", "negative", "Create form shows every problem, then accepts touching slots", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/groups and click '+ Create a group'.",
               "Leave Group name empty. Course: 'IS442 Object Oriented Programming'; Study goal: 'Exam preparation'; Study mode: 'Online'; Maximum group size: 1.",
               "Slot 1: Day Monday, From 10:00, To 12:00. Click '+ Add slot'. Slot 2: Day Monday, From 11:00, To 13:00. Click '+ Add slot'. Slot 3: Day Tuesday, From 12:00, To 10:00.",
               "Check the time slots section, then click 'Create group'.",
               "Type 'Form Check Group' in Group name, set Maximum group size to 3, change Slot 2 to From 12:00 To 13:00 and Slot 3 to From 10:00 To 12:00. Click 'Create group'."),
      "Empty name, size 1, Mon 10-12 / Mon 11-13 / Tue 12-10; then the fixed values",
      "Step 4: '+ Add slot' is gone and 'Max time slots reached (3)' is shown. Step 5: 'Some details need fixing. See the messages below.' at the top; under Group name 'Please enter a group name'; under Maximum group size 'A group needs room for at least 2 members, including you'; under Weekly time slots 'Slot 3: the end time must be after the start time' and 'Slots 1 and 2 overlap'; everything typed is still there. Step 6: 'Group created. Other students can now find it on Study groups and ask to join.' and Time slots 'Mon 10:00-12:00', 'Mon 12:00-13:00', 'Tue 10:00-12:00'.",
      "The form has novalidate, so the browser doesn't stop the submit; the server lists every problem.", BATCH_B),
    m("MAN-33", "L3, L4, GRP-2", "permission", "A member can't manage or edit someone else's group", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/groups/mine. Under 'Joined groups', on 'CS201 DSA Problem Solvers' click 'View'. Note the number after /groups/ in the address bar (2 on a fresh start).",
               "Type http://localhost:8080/groups/2/members in the address bar and press Enter.",
               "Type http://localhost:8080/groups/2/edit and press Enter."),
      "Alex Tan, a member of Ben Koh's CS201 group (group 2)",
      "Step 2: 'You're a member of this group.'; no 'Edit group', 'Manage members' or 'Close group' buttons. Step 3 and step 4: back on the CS201 DSA Problem Solvers page with the error 'Only the group's leader can do that.'", batch=BATCH_B),
    m("MAN-34", "L3, GRP-6", "side-effect", "Closing a group ends its pending requests", APP_PRE + " Needs the seeded pending requests of Farhan Ismail and Jasmine Teo.",
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/groups/mine. Under 'Groups I lead', the 'IS442 OOP Exam Prep' card shows '2 requests waiting'. Click 'View'.",
               "Click 'Close group' and read the box, then click 'Close group' in the box.",
               "Open http://localhost:8080/groups/mine.",
               LOGOUT + " " + login("farhan.ismail@studybuddy.com") + " Open http://localhost:8080/groups/mine.",
               "Under 'Pending group join requests', on 'IS442 OOP Exam Prep' click 'Remove'.",
               LOGOUT + " " + login("jasmine.teo@studybuddy.com") + " Open http://localhost:8080/groups/mine."),
      "Seeded IS442 OOP Exam Prep (Farhan, Jasmine pending)",
      "Step 3: the box says 'Close this group? Its 2 pending requests will end without an answer, and you can't reopen it.'; then 'Group closed. 2 pending requests ended without an answer.' and 'This group is closed, so it isn't taking new members.' with no Edit group, Manage members or Close group buttons. Step 4: the card shows 'Closed', 'No requests waiting' and a 'Remove' button. Step 5: under 'Pending group join requests' the card shows 'Closed' and 'The group was closed before the leader answered your request.' Step 6: 'Removed from My groups.' and the card is gone. Step 7: Jasmine still sees the closed card with the same note.", batch=BATCH_B),
    m("MAN-35", "L6, GRP-4", "conflict", "A removed student asks to join again", after("MAN-15"),
      numbered(LOGOUT + " " + login("chloe.lim@studybuddy.com"), "Open http://localhost:8080/groups/mine. Under 'Declined or removed', on 'IS442 OOP Exam Prep' click 'View'.",
               "Click 'Request to join again' and read the box, then click 'Request again'.",
               LOGOUT + " " + login("student@studybuddy.com") + " Open http://localhost:8080/groups/mine, on 'IS442 OOP Exam Prep' click 'View', then 'Manage members'."),
      "Chloe Lim, removed in MAN-15",
      "Step 2: the card says 'Removed from the group'. The group page says 'You were removed from this group before.' Step 3: the box says 'You were removed from this group before. Do you want to request to join again?'; then 'Request sent. The group's leader will review it.' and 'Request sent. Waiting for the group's leader to respond.' Step 4: Chloe Lim is listed under 'Join requests' (with Farhan Ismail and Jasmine Teo unless MAN-14 ran first).", batch=BATCH_B),
    m("MAN-36", "L5, GRP-4, GRP-5", "boundary", "A full group keeps requests waiting until a place opens", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/groups/mine, on 'IS442 OOP Exam Prep' click 'View', then 'Manage members'.",
               "Under 'Join requests' click 'Accept' for Farhan Ismail.",
               LOGOUT + " " + login("jasmine.teo@studybuddy.com") + " Open http://localhost:8080/groups/mine.",
               LOGOUT + " " + login("student@studybuddy.com") + " Open the IS442 OOP Exam Prep 'Manage members' page again (as in step 2). Under 'Members' click 'Remove' for Ben Koh, then 'Remove member' in the box.",
               "Click 'Accept' for Jasmine Teo."),
      "Seeded IS442 OOP Exam Prep, 3/4 with Farhan and Jasmine pending",
      "Step 3: 'Farhan Ismail is now a member of the group.', the count shows '4/4 members', the warning 'Group is full. Remove a member to accept new members.' appears and Jasmine Teo's 'Accept' is greyed out (can't be clicked). Step 4: under 'Pending group join requests' the IS442 OOP Exam Prep card shows 'Full' and 'The group is full right now. The leader can still accept you if a place opens up.' Step 5: 'Ben Koh was removed from the group.', '3/4 members', Jasmine's 'Accept' works again. Step 6: 'Jasmine Teo is now a member of the group.' and '4/4 members'.", batch=BATCH_B),
    m("MAN-37", "L3, GRP-6", "conflict", "A closed group can't be edited or changed, only removed from My groups", APP_PRE,
      numbered(login("emily.wong@studybuddy.com"), "Open http://localhost:8080/groups/mine. Under 'Groups I lead', on 'ECON101 Midterm Revision' click 'View'. Note the number after /groups/ in the address bar (6 on a fresh start).",
               "Type http://localhost:8080/groups/6/edit and press Enter.",
               "Type http://localhost:8080/groups/6/members and press Enter.",
               "Open http://localhost:8080/groups/mine and click 'Remove' on 'ECON101 Midterm Revision'."),
      "Emily Wong, leader of the seeded closed ECON101 group (group 6)",
      "Step 2: 'This group is closed, so it isn't taking new members.' and 'You lead this group.'; no Edit group, Manage members or Close group buttons. Step 3: back on the group page with the error 'This group is closed, so it can't be edited.' Step 4: Manage members shows 'This group is closed, so its members can't be changed.', no 'Join requests' section, and Emily Wong, Grace Lee, Isaac Chua with no Remove buttons. Step 5: 'Removed from My groups.' and ECON101 Midterm Revision is gone from 'Groups I lead'; 'CS201 DSA Problem Solvers' is still under 'Joined groups'.", batch=BATCH_B),
    m("MAN-38", "L1, GRP-4, K8", "side-effect", "Groups don't need the course (known gap K8)", APP_PRE,
      numbered(login("ben.koh@studybuddy.com"), "Open http://localhost:8080/student/profile. Under 'Your courses' click 'Remove' next to CS201.",
               "Open http://localhost:8080/groups/mine.",
               "Open http://localhost:8080/groups, open 'IS112 SQL Practice' and click 'Request to join'.",
               "Open http://localhost:8080/groups and click '+ Create a group'. Group name 'K8 Check'; Course 'ECON101 Principles of Economics'; Study goal 'Exam preparation'; Study mode 'Online'; Maximum group size 3; Slot 1: Day Monday, From 10:00, To 12:00. Click 'Create group'."),
      "Ben Koh takes IS442, IS216, CS201 (no CS201 preference) and leads the CS201 group",
      "TODAY: Step 2: 'The course has been removed.' Step 3: 'CS201 DSA Problem Solvers' is still under 'Groups I lead'. Step 4: 'Request sent. The group's leader will review it.' although Ben doesn't take IS112. Step 5: 'Group created. Other students can now find it on Study groups and ask to join.' although Ben doesn't take ECON101.",
      "Pins current behaviour K8 (team decision, treatment (a)); matches SGS and GMS K8 tests.", BATCH_B),

    # ================================ NEW (Batch C) ================================
    m("MAN-39", "SEC-3, K15", "permission", "H2 console opens without logging in (dev only)", APP_PRE + " Logged out.",
      numbered("Open http://localhost:8080/h2-console in the browser.",
               login("student@studybuddy.com") + " Then open http://localhost:8080/h2-console again."),
      "-",
      "TODAY: both times the H2 Console's own login form opens (with a 'Connect' button); the browser is NOT sent to the Study Buddy login page and no 403 is shown. Don't click 'Connect': the case only checks that the page is open to anyone.",
      "Pins current behaviour SEC-3 / K15 (development setting). Turn the console off before any real deployment; matches SCM (h2Console...).", BATCH_C),
    m("MAN-40", "S1, PRF-1, K4", "negative", "Over-long profile name (known bug)", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/student/profile.",
               "Replace Name with 256 characters (e.g. paste 256 letter A) and click 'Save details'."),
      "256-character name",
      "DESIRED: back on the profile page with an error message, name unchanged. TODAY: an error page with status 500 (K4).",
      "Known bug K4; matches StudentProfileControllerKnownBugTest.", BATCH_C),
    m("MAN-41", "S7, S8, K12", "negative", "A message that is just 'no' disappears (known bug)", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/matches, choose IS442, click 'Search'.",
               "On the first card that is NOT Ben Koh, Chloe Lim or Daniel Ng, type no in 'Optional message' and click 'Send request'."),
      "Message 'no'",
      "DESIRED: under 'Sent' the new request shows the message 'no'. TODAY: 'Request sent.' and the request is listed as PENDING with no message line at all (K12). The same happens for 'false' and 'off'.",
      "Known bug K12; matches MatchRequestControllerKnownBugTest. Compare MAN-08, where a normal message is shown.", BATCH_C),

    # ================================ NEW (Batch D) ================================
    m("MAN-42", "S3, S7, K17", "negative", "Matches still list buddies and pending requests (known bug)",
      APP_PRE + " Seeded: Ben Koh is already Alex's study buddy; Chloe Lim -> Alex and Alex -> Daniel Ng are pending requests.",
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/matches, choose IS442 in Course and click 'Search'.",
               "On Ben Koh's card click 'Send request'.",
               "Open http://localhost:8080/matches, choose 'All my courses' in Course and click 'Search'. Press Ctrl+F and search for Chloe Lim, then Daniel Ng."),
      "Seeded data",
      "DESIRED: Ben Koh, Chloe Lim and Daniel Ng are not listed. TODAY: step 2 lists Ben Koh first (92 / 100); step 3 shows the error 'You're already study buddies. Find them under Study buddies.'; step 4 finds cards for Chloe Lim and Daniel Ng (K17).",
      "Known bug K17; matches MatchingServiceKnownBugTest (connected buddy; pending request either way, planned in docs/decisions.md).", BATCH_D),
    m("MAN-43", "A3, A4, D2, MAT-2", "happy", "Course first with only the course weight", APP_PRE,
      numbered(login("admin@studybuddy.com"), "Open http://localhost:8080/admin/matching.",
               "Choose 'Course first'. Set Same course 1, Shared free time 0, Study mode (in person / online) 0, Study goal 0, Group size (one-to-one / small group) 0. Click 'Save settings'.",
               LOGOUT + " " + login("student@studybuddy.com") + " Open http://localhost:8080/matches, choose 'All my courses' in Course and click 'Search'."),
      "Weights 1/0/0/0/0, Course first",
      "Step 3: 'Settings saved. New searches use them straight away.' and the Same course row shows 100% under Current share. Step 4: the summary says 'ranked by Course first matching.'; every card for one of Alex's courses (IS111, IS112, IS442, CS201) shows 100 / 100 and comes before every card showing 0 / 100; Chloe Lim and Daniel Ng (no preference for Alex's courses) show 0 / 100.",
      "Restart afterwards (or set the settings back) before other matching cases: the settings stay until the app stops.", BATCH_D),
]

# --------------------------------------------------------------------------------------
# Gaps and bugs
# --------------------------------------------------------------------------------------
GAPS = [
    ("K1", "Candidate bug (confirmed)", "Login is case-sensitive, but sign-up and the admin page lower-case emails.", "AuthService.java:27", "(b) Known-bug test, @Disabled", "AUK-01, MAN-28", "Confirmed failing when enabled (2026-10-24)."),
    ("K2", "Candidate bug", "Changing a logged-in student's email breaks their session (404s); deactivation and password reset don't end open sessions.", "AccountManagementService.java:120", "(a) Pin + manual case", "AMS (oldEmailNoLongerFindsStudentAfterUpdate), MAN-29", "Pinned."),
    ("K3", "Candidate bug", "Any DataIntegrityViolationException shows 'Another account already uses that email', including over-long fields.", "AuthController.java:61; AccountManagementController.java:64,85", "(a) Pin current message", "AMC (createAdminDatabaseRefusal..., updateEmailDatabaseRefusal...), AUC (registerDatabaseRefusal...)", "Pinned."),
    ("K4", "Candidate bug (confirmed)", "Inputs over 255 characters reach the database: course code and name give a 500. Also match message and profile fields (planned, see PLAN-K4a/b). Study groups are NOT affected in the app: StudyGroupForm's @Size refuses a name over 255 or a description over 1000 with a message; only the service and entity accept them, and the database refuses them.", "CourseController.java:53,66; MatchRequestController.java:63; StudentProfileController.java:52; StudyGroupForm.java:31,34", "(b) controller known-bug tests; (a) service pin (courses and groups)", "CRK-01, CRK-02, CRS (courseCodeOver255...), SGS (nameOver255..., descriptionOver1000...), SGF (name/description limits), MAN-30", "Course controller tests confirmed failing when enabled. Group service limits pinned (Batch B). Profile and match-message parts planned for Batch C and E."),
    ("K5", "Candidate bug", "Duplicate course codes allowed; CourseService has no @Transactional.", "CourseService.java:20; Course.java:21", "(c) List only", "-", "Listed."),
    ("K6", "Candidate bug", "A match request can be sent to a deactivated student by direct POST.", "MatchRequestService.java:79", "(b) Known-bug test in Batch E", "-", "Batch E."),
    ("K7", "Not implemented", "getPublicProfile and getRequestById have no route; no profile page (S6 partial).", "StudentProfileService.java:66; MatchRequestService.java:115", "Not decided (outside Batch A)", "MAN-07", "Open."),
    ("K8", "Candidate bug (team decision)", "A student can drop a course while leading or in a group for it; creating or joining a group doesn't require taking the course. requirements.md L1 records 'Leader need not take the course' as current behaviour.", "StudentProfileService.java:113; StudyGroupService.java:68; StudyGroup.java:446", "(a) Pin all three (agreed 2026-10-24)", "SGS (createGroupDoesNotRequireTakingTheCourse, leaderCanDropTheCourseAndStillLeadTheGroup), GMS (joiningDoesNotRequireTakingTheCourse), SPS (addAndRemoveCourse), MAN-38", "Pinned. If the team decides it is a bug, add a StudyGroupServiceKnownBugTest."),
    ("K9", "Candidate bug", "Preference slots are looser than group slots (any times, no maximum).", "StudyPreferenceService.java:93", "(c) List only (Batch E)", "-", "Listed."),
    ("K10", "Candidate bug", "/register doesn't strip spaces from the contact number; profile update does.", "AuthController.java:57", "(a) Pin", "AUC (contactNumberIsPassedOnAsTyped)", "Pinned."),
    ("K11", "Candidate bug", "A non-numeric year of study gives a raw 400.", "AuthController.java:53; StudentProfileController.java:54", "(a) Pin", "AUC (nonNumericYearOfStudyIsBadRequest), SPC (nonNumericYearOfStudyIsBadRequest)", "Pinned (sign-up and profile)."),
    ("K12", "Candidate bug (confirmed)", "th:if=\"${req.message}\" hides a message that is just 'no', 'false' or 'off' (Thymeleaf reads them as false). Separately, an empty message is stored as '' and shows as an empty paragraph.", "match-requests.html:26,52", "(b) Known-bug test for the hidden words; (c) list only for the empty message", "MRK-01, MAN-41", "Hidden words confirmed failing when enabled (2026-10-24): an ordinary message renders, the three words don't. Empty message listed."),
    ("K13", "Candidate bug", "The duplicate-request error shows internal student ids.", "DuplicateMatchRequestException.java:24", "(c) List only (Batch E)", "-", "Listed."),
    ("K14", "Candidate bug", "No database constraint for one pending request or one connection per pair (race).", "MatchRequestService.java:90-104; StudyBuddyConnectionService.java:46", "(c) List only (Batch E)", "-", "Listed."),
    ("K15", "Candidate bug", "H2 console open to everyone with CSRF off and frame options off (its own filter chain, permitAll).", "SecurityConfig.java:26-35; application.properties:15", "(a) Pin (SEC-3) + deployment note GAP-DEP1", "SCM (h2ConsoleIsOpenWithoutLoginOrCsrf, h2ConsoleRelaxesFrameOptionsOnlyForItself), MAN-39", "Pinned."),
    ("K16", "Candidate bug", "No @ControllerAdvice or custom error pages: an exception a controller doesn't catch becomes a bare 404 (StudentNotFoundException has @ResponseStatus) or 500.", "-", "(a) Pin representative 404s; (c) list 'no custom pages'", "SPC, MTC (missingStudentAccountIsNotFound), MAN-26", "Pinned / listed."),
    ("K17", "Candidate bug (confirmed)", "/matches lists students who are already study buddies (Send request then only gives 'You're already study buddies...'), and students with a pending request either way. Belongs to MatchingService.findMatches, which builds the list (docs/decisions.md 'Not done yet' plans the check there).", "MatchingService.java:81-107", "(b) Two known-bug tests", "MSK-01, MSK-02, MAN-42", "Both confirmed failing when enabled (2026-10-24): Ben (buddy) and Ben/Chloe (pending) are listed."),
    ("K18", "Test risk", "Open-session-in-view is relied on; non-transactional tests can hit LazyInitializationException.", "-", "Handled: every new service test is transactional (@DataJpaTest); entity, form and validator tests use no session", "SGS, GMS", "Mitigated (Batch A and B)."),
    ("K19", "Test risk", "StudyPreferenceServiceTest.newPreferenceShowsUpInMatching depends on seeded random data (Ben must come first among random CS201 students).", "StudyPreferenceServiceTest.java:150-161", "(c) List only (teammate file is read-only) + own-data replacement test", "SPF (newPreferenceShowsUpInMatching), MSF (newPreferenceIsMatchedOnTheNextSearch)", "Listed; behaviour now also covered without seeded data."),
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
    ("GAP-OPEN1", "Open question", "The match-message and profile-field parts of K4 aren't assigned to a batch yet.", "-", "Decided: PLAN-K4a (Batch C), PLAN-K4b (Batch E)", "-", "Closed (2026-10-24)."),
    ("PLAN-K4a", "Built (Batch C)", "K4 on the profile fields: StudentProfileControllerKnownBugTest, @Disabled('KNOWN BUG K4: ...'). Profile values over 255 characters should show a message on the profile page, not a 500.", "StudentProfileController.java:52-64", "(b) Known-bug test, @Disabled", "SPK-01, MAN-40", "Built; confirmed failing when enabled (2026-10-24)."),
    ("PLAN-K4b", "Planned test (Batch E)", "K4 on the match-request message: new MatchRequestControllerKnownBugTest, @Disabled('KNOWN BUG K4: ...'). A message over 255 characters should show an error, not a 500.", "MatchRequestController.java:63", "(b) Known-bug test, @Disabled", "-", "Planned, not built."),
    # ---- Batch B (study groups) ----
    ("N-B1", "New finding", "The service and entity accept a null study goal or study mode (saved as null); a null maximum size gives a NullPointerException and a null course id a Spring data exception. Only StudyGroupForm's @NotNull stops them (the service expects an already validated form).", "StudyGroupService.java:68-76; StudyGroup.java:413", "(c) List only (agreed: don't pin NPE or Spring data exceptions)", "SGF (courseGoalAndModeAreRequired, sizeIsRequiredAndAtLeastTwo) cover the form side", "Listed."),
    ("N-B2", "New finding", "Check order: the leader asking to join their own FULL group gets 'Group is full' (not 'The leader is already in the group'); accepting an already accepted member of a full group says 'Group is full...' (not 'already answered').", "StudyGroup.java:447-453, 477-483", "(c) List only", "-", "Listed."),
    ("N-B3", "New finding", "rejectRequest doesn't check that the group is open. On a closed group every pending request is already GROUP_CLOSED, so it fails with 'This request has already been answered' rather than 'Group is closed'. The group stays frozen; only the message is misleading.", "StudyGroup.java:492", "(a) Pin inside the 'closed group is frozen' tests", "SGE (closedGroupIsFrozen), GMS (closedGroupIsFrozenForMembership)", "Pinned."),
    ("N-B4", "New finding", "Form messages say 'under 255 characters' / 'under 1000 characters', but exactly 255 / 1000 is allowed.", "StudyGroupForm.java:31,34", "(c) List only", "SGF (nameIsRequired..., descriptionIsOptional...)", "Listed."),
    ("N-B5", "New finding", "The service doesn't re-check slots: createGroup / updateGroup save 0 or overlapping slots if called without StudyGroupFormValidator (documented design: the controller runs the validator).", "StudyGroupService.java:74,92", "(c) List only", "-", "Listed."),
    ("N-B6", "New finding", "Two leader tabs accepting at the same moment could go over maxSize: no lock or version column (like K14).", "StudyGroup.java:476", "(c) List only", "-", "Listed."),
    ("N-B7", "New finding", "A deactivated student can still ask to join or be accepted, and groups they lead stay on Browse (similar to K6). No requirement covers it.", "GroupMembershipService.java:271", "(c) List only", "-", "Listed."),
    ("N-B8", "New finding", "Stale Javadoc: GroupMembership.reject() says it is also reached through StudyGroup#close, but close() uses endBecauseGroupClosed().", "GroupMembership.java:853-859", "(c) List only", "-", "Listed."),
    ("N-B9", "Test note", "LocalDateTime.now() ticks about once a millisecond on the test machine, so two quick actions can get the same time; the code then falls back to the id order (documented). Tests that depend on join order wait for the clock to tick.", "GroupMembershipService.java:244-249", "Handled in the test", "GMS (membersAreLeaderFirstThenInJoinOrder)", "Handled."),
    ("N-B10", "Manual case fix", "MAN-13 step 4 said the browser refuses a size below the member count; the group form has novalidate, so the server shows the error instead. MAN-12's member count text and MAN-31's request-sent text were also made exact.", "studygroup/form.html:31", "Fixed in the workbook", "MAN-12, MAN-13, MAN-31", "Fixed (2026-10-24)."),
    ("GAP-L2", "Ambiguous requirement", "L2 lists 'goals' (plural); a group has exactly one study goal.", "StudyGroup.java:376", "Ask the team", "-", "Open."),
    ("GAP-GRP1", "Ambiguous requirement", "GRP-1 'name max 255, description max 1000': only StudyGroupForm (@Size) and the database columns enforce it, not the entity or service.", "StudyGroupForm.java:31,34; StudyGroup.java:365,367", "Recorded (see K4 row)", "SGF, SGS", "Open."),
    ("GAP-GRP4", "Ambiguous requirement", "GRP-4 says join needs a 'non-full' group, but requests sent before the group filled up stay pending as a waiting list and can be accepted once a place opens.", "StudyGroup.java:446-483", "Recorded (tested as built)", "SGE (fullGroupRefusesRequestsAndAccepts), MAN-36", "Open."),
    ("GAP-GRP6", "Ambiguous requirement", "No rule says closing is permanent; the code and the confirm box say it can't be reopened.", "StudyGroup.java:565; studygroup/details.html:41", "Recorded", "SGS (closedGroupCannotBeUpdatedOrClosedAgain), MAN-34", "Open."),
    ("GAP-NR5", "Behaviour with no requirement", "Browse sort options, 'only groups I can join', full groups listed last, viewer roles, GROUP_CLOSED status and 'Remove from My groups' have no requirement in the brief.", "GroupSort.java; StudyGroupService.java:145-183; ViewerRole.java", "Tested as built", "GSO, SGS (browse...), GMS (viewerRole..., removeFromMyGroups...)", "Listed."),
    # ---- Batch C (security and controllers) ----
    ("N-C1", "New finding", "Hand-edited values that can't be converted give a raw 400 instead of a message: /matches?studyGoal=BOGUS or courseId=abc, a slot time like 25:00 (and K11's year of study).", "MatchingController.java:81; StudyPreferenceController.java:230", "(a) Pin", "MTC (unknownFilterValueIsBadRequest), PFC (timeNotInHourMinuteFormatIsBadRequest)", "Pinned."),
    ("N-C2", "New finding", "The bare address / isn't mapped: a logged-in user who types http://localhost:8080/ gets a 404 page (after login the app always goes to /home).", "HomeController.java:20,31", "(a) Pin", "RTI (rootAddressIsNotMapped)", "Pinned."),
    ("N-C3", "New finding", "An anonymous POST without a CSRF token gets 403, not a redirect to /login: the CSRF filter runs before the login check. Consistent with SEC-2.", "SecurityConfig.java:46", "(a) Pin", "SCM (anonymousPostWithoutCsrfIsForbidden)", "Pinned."),
    ("N-C4", "New finding", "SecurityConfig's Javadoc says only /matches and /groups are student-only; the rules also cover /match-requests, /connections and /student.", "SecurityConfig.java:37-43", "(c) List only", "-", "Listed."),
    ("N-C5", "New finding", "/admin/matching has no @PreAuthorize: it relies on the URL rule only (as requirements.md MAT-1 says), unlike AccountManagementService and the course POSTs.", "AdminMatchingConfigController.java; MatchingConfigService.java", "(c) List only", "MCC (refusedRequestsNeverReachTheService), SCM", "Listed."),
    ("N-C6", "Checked, no finding", "No template uses th:utext or an unescaped inline expression [(...)]; typed text (match messages, group name and description, profile name) is shown escaped.", "src/main/resources/templates", "Tested", "ESC-01..03", "OK (2026-10-24)."),
    ("GAP-DEP1", "Deployment note", "SEC-3: the H2 console (spring.h2.console.enabled=true, permitAll, CSRF and frame options off) must be switched off before the app is deployed anywhere real.", "application.properties:15; SecurityConfig.java:26", "List only", "SCM, MAN-39", "Listed."),
    ("GAP-TST1", "Test note", "ContactNumberVisibilityTest and HtmlEscapingTest check one concern across several controllers, so they aren't named after one class; MatchRequestControllerPrincipalTest adds to a teammate's test class without changing it.", "src/test/java/com/studybuddy/controller", "Recorded", "CNV, ESC, MRP", "Recorded."),
    # ---- Batch D (matching and seeders) ----
    ("N-D1", "New finding", "A student matched through several preferences is listed through the one the ACTIVE STRATEGY ranks best, not the one with the best score: under Availability first the card shows the preference with the most shared time even if it scores lower.", "MatchingService.java:101-102", "(a) Pin", "MSF (bestResultFollowsTheActiveStrategy)", "Pinned."),
    ("GAP-MAT3", "Ambiguous requirement", "MAT-3 says each student appears once 'with their best result' without saying best by what: score, or the active strategy's order (today: the strategy's order, see N-D1).", "docs/requirements.md MAT-3", "Ask the team", "MSF (studentWithSeveralPreferencesIsListedOnceThroughTheBest, bestResultFollowsTheActiveStrategy)", "Open."),
    ("N-D2", "New finding", "Two strategy components with the same type would silently replace each other (last one wins); the startup check only catches a missing type.", "MatchingService.java:56-62", "(c) List only", "MSC (everyStrategyTypeHasExactlyOneComponent fails on a duplicate)", "Listed."),
    ("N-D3", "New finding", "A study mode or group size filter of EITHER (not offered on the page, only by editing the address) keeps everyone.", "MatchSearch.java:49-52", "(a) Pin", "MSE (eitherFilterKeepsEveryone)", "Pinned."),
    ("N-D4", "New finding", "Searching a course the student has no preference for returns no matches and no hint why (the course list only offers courses with a preference, so only by editing the address).", "MatchingService.java:83-85,111-116", "(a) Pin", "MSF (courseWithoutOwnPreferenceFindsNobody)", "Pinned."),
    ("N-D5", "New finding", "Every search loads every study preference in the database (findAll) and scores it.", "MatchingService.java:91", "(c) List only", "-", "Listed."),
    ("N-D6", "New finding", "Every seeded account shares one password, kept in plain text in application.properties (studybuddy.seed.password); development only.", "application.properties:24; AdminDataSeeder.java; StudentDataSeeder.java", "(c) List only", "DSD (seededPasswordsAreBcryptHashesOfTheSeedPassword)", "Listed."),
    ("GAP-NI1", "Not implemented (no requirement)", "A member can't leave a group, a student can't withdraw a pending join request, and a group can't be deleted.", "GroupMembershipService.java", "List only", "-", "Listed."),
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
            if line.strip() != "@Test" and not line.strip().startswith("@ParameterizedTest"):
                continue
            parameterized = line.strip().startswith("@ParameterizedTest")
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
            tests.append({"method": method, "ids": ids, "comment": " ".join(comment), "disabled": disabled,
                          "parameterized": parameterized})
        if tests:
            found[path.stem] = tests
    return found


class Reports(dict):
    """{(ClassName, method): status} from the surefire XML reports, one entry per test method.
    A parameterized method runs once per value and surefire reports each run ("method(Route)[3]"),
    so its runs are merged: Fail if any run failed, Skipped if all were skipped, else Pass.
    runs counts every surefire run (what ./mvnw test prints); invocations holds how many runs each
    parameterized method had. Reports left behind by test classes that no longer exist are
    ignored (surefire never deletes old reports)."""

    def __init__(self):
        super().__init__()
        self.runs = Counter()
        self.invocations = Counter()


def parse_reports(sources):
    reports = Reports()
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
            reports.runs[status] += 1
            name = case.get("name")
            key = (cls, name.split("(")[0])
            if "(" in name:
                reports.invocations[key] += 1
            earlier = reports.get(key)
            # A failed run wins; a skipped one only stands if nothing else ran
            if earlier is None or earlier == "Skipped" or status == "Fail":
                reports[key] = status
    return reports


MANUAL_STATUSES = ["Not run", "Pass", "Fail"]
WRITTEN_BY = {"Existing": "Existing (teammates)", "New": "New (Batch A)", BATCH_B: "New (Batch B)",
              BATCH_C: "New (Batch C)", BATCH_D: "New (Batch D)"}
RESULT_COLUMNS = {"status": "Status", "tester": "Tester", "date": "Date", "actual": "Actual result"}


def read_previous_manual_results():
    """{Test ID: {status, tester, date, actual}} for every manual row of the existing workbook, so
    rebuilding never wipes results typed in by hand. Columns are found by their header text, so an
    older workbook without Tester / Date / Actual result still gives its Status."""
    if not OUT.exists():
        return {}
    ws = load_workbook(OUT)["Test Cases"]
    header = [cell.value for cell in ws[1]]
    col = {name: header.index(name) for name in ["Test ID", "Automated or Manual", *RESULT_COLUMNS.values()]
           if name in header}
    results = {}
    for row in ws.iter_rows(min_row=2, values_only=True):
        test_id = row[col["Test ID"]] if "Test ID" in col else None
        if not test_id or row[col["Automated or Manual"]] != "Manual":
            continue
        kept = {key: row[col[name]] for key, name in RESULT_COLUMNS.items() if name in col and row[col[name]]}
        if kept.get("status") not in MANUAL_STATUSES:
            kept.pop("status", None)
        results[test_id] = kept
    return results


def build_rows(sources, reports, previous):
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
            ids = test["ids"] or case["ids"] or ""
            if not ids:
                problems.append(f"{cls}.{test['method']} has no requirement IDs")
            notes = [case["notes"]] if case["notes"] else []
            if "pins current behaviour" in test["comment"]:
                # K ids come from the requirement IDs; new findings (N-B3 ...) are only named in the comment
                ks = [k for k in ID_PATTERN.findall(ids) if k.startswith("K")]
                ks += [f for f in re.findall(r"\bN-[A-Z]?\d+\b", test["comment"]) if f not in ks]
                notes.insert(0, f"Pins current behaviour (suspected bug {', '.join(ks)}): update if fixed.")
            status = reports.get((cls, test["method"]), "Not run")
            runs = reports.invocations.get((cls, test["method"]), 0)
            if test["parameterized"]:
                notes.insert(0, f"Parameterized: {runs} runs in the last ./mvnw test (one per route or value); "
                                f"Pass only if every run passed.")
            if test["disabled"]:
                status = "Known bug (disabled)" if status in ("Skipped", "Not run") else status
                notes.insert(0, f"@Disabled: {test['disabled']} Confirmed failing when enabled (2026-10-24).")
            rows.append({
                "id": f"{prefix}-{n:02d}", "ids": ids, "title": case["title"], "type": case["type"],
                "pre": pre, "steps": case["steps"], "data": case["data"], "expected": case["expected"],
                "mode": "Automated", "where": f"{cls}#{test['method']}", "status": status,
                "by": written_by, "notes": " ".join(notes), "cls": cls,
                "tester": "", "date": "", "actual": "", "runs": runs,
            })
    manual_ids = Counter(case["id"] for case in MANUAL)
    problems += [f"Manual ID {mid} is used {n} times" for mid, n in manual_ids.items() if n > 1]
    for case in MANUAL:
        # Results typed into the last workbook are kept, matched by Test ID
        kept = previous.get(case["id"], {})
        rows.append({
            "id": case["id"], "ids": case["ids"], "title": case["title"], "type": case["type"], "pre": case["pre"],
            "steps": case["steps"], "data": case["data"], "expected": case["expected"], "mode": "Manual",
            "where": "", "status": kept.get("status", "Not run"), "by": case["batch"], "notes": case["notes"],
            "cls": "", "tester": kept.get("tester", ""), "date": kept.get("date", ""),
            "actual": kept.get("actual", ""),
        })
    for mid in sorted(previous.keys() - manual_ids.keys()):
        print(f"WARNING: manual case {mid} is in the old workbook but no longer exists; its results were dropped "
              f"({previous[mid]})")
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
               "Expected result", "Automated or Manual", "Test class and method", "Status", "Tester", "Date",
               "Actual result", "Written by", "Notes"]
    lines = []
    for r in rows:
        # One banner line above the first manual case
        if r["mode"] == "Manual" and (not lines or lines[-1][8] != "Manual"):
            lines.append([None, None, MANUAL_BANNER] + [None] * (len(headers) - 3))
        lines.append([r["id"], r["ids"], r["title"], r["type"], r["pre"], r["steps"], r["data"], r["expected"],
                      r["mode"], r["where"], r["status"], r["tester"], r["date"], r["actual"],
                      WRITTEN_BY[r["by"]], r["notes"]])
    ws = sheet(wb, "Test Cases", headers, lines, [11, 18, 34, 12, 40, 48, 26, 48, 12, 44, 14, 14, 12, 40, 14, 44])
    # Manual Status is typed in by the tester: offer only Not run / Pass / Fail
    status_choice = DataValidation(type="list", formula1='"' + ",".join(MANUAL_STATUSES) + '"', allow_blank=False)
    ws.add_data_validation(status_choice)
    for row in ws.iter_rows(min_row=2):
        if row[0].value is None:
            for cell in row:
                cell.fill, cell.font = FILLS["amber"], Font(bold=True)
            continue
        fill = FILLS.get(row[10].value)
        if fill:
            row[10].fill = fill
        if row[8].value == "Manual":
            status_choice.add(row[10])
            row[12].number_format = "yyyy-mm-dd"

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
    statuses = reports.runs
    after = [sum(statuses.values()), statuses["Pass"], statuses["Fail"], statuses["Skipped"]]
    block("./mvnw test results", ["Run", "Tests run", "Passed", "Failed or errors", "Skipped (known bugs)"],
          [[label, b["run"], b["passed"], b["failed"] + b["errors"], b["skipped"]] for label, b in BASELINES]
          + [["Last run (this workbook)"] + after])
    parameterized = [r for r in rows if r.get("runs")]
    ws.append([f"Of the last run, {sum(r['runs'] for r in parameterized)} runs come from {len(parameterized)} "
               f"parameterized test methods (one workbook row each)."])

    automated = [r for r in rows if r["mode"] == "Automated"]
    manual_rows = [r for r in rows if r["mode"] == "Manual"]
    block("Test cases", ["Written by", "Automated", "Manual", "Total"],
          [[WRITTEN_BY[by], sum(r["by"] == by for r in automated), sum(r["by"] == by for r in manual_rows),
            sum(r["by"] == by for r in rows)] for by in WRITTEN_BY]
          + [["Total", len(automated), len(manual_rows), len(rows)]])

    status_counts = Counter(r["status"] for r in rows)
    mode_status = Counter((r["mode"], r["status"]) for r in rows)
    block("By status", ["Status", "Automated", "Manual", "Total"],
          [[s, mode_status[("Automated", s)], mode_status[("Manual", s)], status_counts[s]]
           for s in ["Pass", "Fail", "Known bug (disabled)", "Not run"]])

    type_counts = Counter((r["type"], r["mode"]) for r in rows)
    block("By type", ["Type", "Automated", "Manual"],
          [[kind, type_counts[(kind, "Automated")], type_counts[(kind, "Manual")]] for kind in TYPES])

    groups = ["S", "L", "A", "D", "ACC", "CRS", "PRF", "PRE", "REQ", "GRP", "MAT", "SEC", "K"]
    group_rows = []
    for g in groups:
        def in_group(r):
            return any((rid.split("-")[0] if "-" in rid else rid[0]) == g for rid in ids_of(r["ids"]))
        a = [r for r in rows if r["mode"] == "Automated" and in_group(r)]
        man = [r for r in rows if r["mode"] == "Manual" and in_group(r)]
        group_rows.append([g, sum(r["status"] == "Pass" for r in a), sum(r["status"] == "Fail" for r in a),
                           sum(r["status"] == "Known bug (disabled)" for r in a), len(man),
                           sum(r["status"] == "Pass" for r in man), sum(r["status"] == "Fail" for r in man)])
    block("By requirement group (a test counts once per group it covers)",
          ["Group", "Automated pass", "Automated fail", "Known bug (disabled)", "Manual", "Manual pass",
           "Manual fail"], group_rows)

    coverage = Counter(line[7] for line in trace)
    block("Requirement and rule coverage", ["Coverage", "IDs"], [[c, n] for c, n in sorted(coverage.items())])
    ws.column_dimensions["A"].width = 44
    for col in "BCDEFG":
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
    # Read before writing: the workbook is rebuilt from scratch
    previous = read_previous_manual_results()
    rows = build_rows(sources, reports, previous)
    after, status_counts, coverage = write(rows, reports)
    kept = sum(1 for r in rows if r["mode"] == "Manual"
               and (r["status"] != "Not run" or r["tester"] or r["date"] or r["actual"]))
    print(f"Manual results carried over: {kept} of {sum(r['mode'] == 'Manual' for r in rows)} manual cases have a result")
    print(f"Wrote {OUT}")
    print(f"Last run: {after[0]} run, {after[1]} passed, {after[2]} failed, {after[3]} skipped")
    print(f"Parameterized: {sum(reports.invocations.values())} runs from {len(reports.invocations)} methods")
    print(f"Test cases: {len(rows)} ({dict(status_counts)})")
    print(f"Coverage: {dict(coverage)}")


if __name__ == "__main__":
    main()

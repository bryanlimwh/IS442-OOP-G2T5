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
import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

from openpyxl import Workbook, load_workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter
from openpyxl.worksheet.datavalidation import DataValidation

ROOT = Path(__file__).resolve().parents[2]
TEST_SRC = ROOT / "studybuddy" / "src" / "test" / "java"
REPORTS = ROOT / "studybuddy" / "target" / "surefire-reports"
OUT = Path(__file__).resolve().parent / "study-buddy-test-cases.xlsx"
# Class#method -> Test ID, kept in git so IDs never change when tests are inserted, reordered or removed
TEST_IDS = Path(__file__).resolve().parent / "test-ids.json"
# The Playwright suite (e2e/) and its JSON report, and the JaCoCo report from the command-line coverage run
E2E_DIR = ROOT / "e2e"
E2E_TESTS = E2E_DIR / "tests"
E2E_REPORT = E2E_DIR / "test-results" / "results.json"
JACOCO_CSV = ROOT / "studybuddy" / "target" / "site" / "jacoco" / "jacoco.csv"
E2E_LEVEL = "End-to-end (browser)"
LEVELS = {}  # test class or spec -> test level, filled by parse_sources()

# Full-suite results before each batch (recorded by hand from the baseline runs)
BASELINES = [
    ("Baseline before Batch A (2026-10-23)", {"run": 99, "passed": 99, "failed": 0, "errors": 0, "skipped": 0}),
    ("Baseline before Batch B (2026-10-24)", {"run": 207, "passed": 204, "failed": 0, "errors": 0, "skipped": 3}),
    ("Baseline before Batch C (2026-10-24)", {"run": 294, "passed": 291, "failed": 0, "errors": 0, "skipped": 3}),
    ("Baseline before Batch D (2026-10-24)", {"run": 593, "passed": 588, "failed": 0, "errors": 0, "skipped": 5}),
    ("Baseline before Batch E (2026-10-24)", {"run": 646, "passed": 639, "failed": 0, "errors": 0, "skipped": 7}),
    ("Before the 2026-10-28 sync (after the teammate's public profile and full-group commits)",
     {"run": 687, "passed": 678, "failed": 0, "errors": 0, "skipped": 9}),
    ("Before the 2026-10-28 fixes of K1, K4, K6, K12", {"run": 699, "passed": 690, "failed": 0, "errors": 0, "skipped": 9}),
    ("Before the 2026-10-28 GAP-K3REG fix", {"run": 699, "passed": 697, "failed": 0, "errors": 0, "skipped": 2}),
    ("Before TD-5 and TD-6 (2026-10-30)", {"run": 703, "passed": 701, "failed": 0, "errors": 0, "skipped": 2}),
    ("Before the automation push (2026-11-07, after the teammate's cancel, leave and layout commits)",
     {"run": 740, "passed": 740, "failed": 0, "errors": 0, "skipped": 0}),
    ("After the full-stack flows, before the coverage-gap tests (2026-11-07)",
     {"run": 781, "passed": 781, "failed": 0, "errors": 0, "skipped": 0}),
    ("Before the coverage cleanup (2026-11-09: GAP-DEAD1 methods removed, last real coverage gaps tested)",
     {"run": 784, "passed": 784, "failed": 0, "errors": 0, "skipped": 0}),
]
BATCH_B = "Batch B"
BATCH_C = "Batch C"
BATCH_D = "Batch D"
BATCH_E = "Batch E"
SYNC = "Sync 2026-10-28"
TD56 = "TD-5/TD-6 2026-10-30"
CANCEL = "Cancel join request 2026-11-06"
LEAVE = "Leave group 2026-11-06"
FLOW = "Full-stack flows 2026-11-07"
E2E = "End-to-end 2026-11-07"
E2E_PRE = ("End to end: Playwright drives Chromium against the real app, which playwright.config.js starts on port 8089 with a "
           "fresh in-memory database for each run. Every test signs up its own students and makes its own courses and "
           "groups (random suffix); admins are made by the seeded admin only to add a fresh one, never changed. Google "
           "Fonts are blocked. Projects: desktop (1280x800), phone (375x667, touch), settings (after the others).")
RESIDUAL = "Residual manual checklist 2026-11-07"
COVERAGE = "Coverage gaps 2026-11-09"
FLOW_PRE = ("Full stack: @SpringBootTest + MockMvc with the real services, H2 and SecurityConfig. Browser keeps a real "
            "session, logs in through /login and posts each form with the page's own CSRF token; the persistence context "
            "is flushed and cleared after every request. Every test makes its own admin, students and courses (unique "
            "suffix) and is rolled back.")

# Not after a letter, digit or "-", so finding ids such as N-D1 aren't read as requirement D1
ID_PATTERN = re.compile(r"(?<![\w-])(?:[SLAD]\d+|ACC-\d+|CRS-\d+|PRF-\d+|PRE-\d+|REQ-\d+|GRP-\d+|MAT-\d+|SEC-\d+|TD-\d+|UI-\d+|K\d+)\b")
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
    ("ACC-8", "Accounts", "Login looks up the email trimmed and lower-cased, like sign-up stores it (K1 fixed 2026-10-28)."),
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
    ("GRP-4", "Groups", "Join: active, not full, not leader, no duplicate; rejected/removed/cancelled/left may ask again."),
    ("GRP-5", "Groups", "Accept needs a pending own membership and space; remove needs accepted non-leader."),
    ("GRP-6", "Groups", "Closing turns pending into GROUP_CLOSED and freezes; removeFromMyGroups only when closed."),
    ("GRP-7", "Groups", "Group slots 1-3, 08:00-23:00 in 15-minute steps, end after start, no overlap."),
    ("GRP-8", "Groups", "A student cancels their own pending request (CANCELLED); leader can't answer it; may ask again."),
    ("GRP-9", "Groups", "A member (not the leader) leaves an open group with an optional reason (max 500); only the leader sees it."),
    ("MAT-1", "Matching", "Config: strategy required, weights >= 0, total > 0, session minutes > 0."),
    ("MAT-2", "Matching", "Per-criterion scores and weighted overall score; Appendix A scores 92."),
    ("MAT-3", "Matching", "Each student once with best result; inactive excluded; strategy ordering."),
    ("MAT-4", "Matching", "The EITHER score must be between 0 and 1."),
    ("SEC-1", "Security", "URL rules: public, ADMIN and STUDENT paths."),
    ("SEC-2", "Security", "Anonymous -> /login; wrong role -> 403; POST without CSRF -> 403."),
    ("SEC-3", "Security", "H2 console open with CSRF off (development only)."),
    ("TD-1", "Team decisions", "A leader or member need not take the group's course (was K8; decided 2026-10-28)."),
    ("TD-2", "Team decisions", "A group has exactly one study goal from the shared list; extra goals go in the description (L2)."),
    ("TD-3", "Team decisions", "A full group keeps pending requests as a waiting list; a place reopens when a member leaves, the leader removes a member or raises the maximum size (GRP-4)."),
    ("TD-4", "Team decisions", "Closing a group is permanent (GRP-6)."),
    ("TD-5", "Team decisions", "Find study buddies keeps listing buddies and students with a pending request either way; their card shows a label instead of 'Send request' (was K17; decided 2026-10-30)."),
    ("UI-1", "Pages", "Confirmation boxes (popover): quiet button or Esc closes without changes; only the box's own button submits."),
    ("UI-2", "Pages", "Success banners 'message success' role status; errors 'message error' role alert; shown once."),
    ("UI-3", "Pages", "Up to 800px wide: form before illustration, one-column cards, stacked labelled tables; Courses/Profile tables scroll in their box."),
    ("UI-4", "Pages", "One time-slot format everywhere: 'Wed 19:00-21:00'."),
    ("UI-5", "Pages", "Test standard (e2e/): axe WCAG 2.0/2.1 A and AA on every main page, desktop and phone; known violations pinned in a11y-baseline.json."),
    ("TD-6", "Team decisions", "A deactivated student's public profile is treated like an unknown id: redirect to /matches with 'That student's profile no longer exists.' (was N-S1; decided 2026-10-30)."),
]

# Where uncovered items are scheduled
PLANNED = {
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
    # New (Batch E)
    "StudentProfileServiceGapTest": ("SPG", BATCH_E, "@DataJpaTest, empty H2 (no seeders), own data, rolled back; adds to the teammate's seeded StudentProfileServiceTest. Alex (alex@test.com) takes IS442: Alex Tan, SCIS, Information Systems, year 2, 91234567. BCrypt encoder."),
    "StudyPreferenceServiceGapTest": ("PFG", BATCH_E, "@DataJpaTest, empty H2 (no seeders), own data, rolled back; adds to the teammate's seeded StudyPreferenceServiceTest. Alex and Ben take IS442 and CS201; Alex's IS442 preference (Online, One-to-one, Concept review) has one slot Mon 10:00-12:00."),
    "MatchRequestServiceGapTest": ("MRG", BATCH_E, "@DataJpaTest, empty H2 (no seeders), own data, rolled back; adds to the teammate's MatchRequestServiceTest. Alex 91234567, Ben 92345678, Chloe 93456789; no requests at the start."),
    "MatchRequestServiceKnownBugTest": ("MRQ", BATCH_E, "@DataJpaTest, empty H2 (no seeders), own data, rolled back. @Disabled known-bug test: describes the desired behaviour."),
    "StudyBuddyConnectionServiceGapTest": ("SBG", BATCH_E, "@DataJpaTest, empty H2 (no seeders), own data, rolled back; adds to the teammate's StudyBuddyConnectionServiceTest. Alex 91234567, Ben 92345678, Chloe 93456789; no connections at the start."),
    # Teammate (2026-10-25) and sync (2026-10-28)
    "PublicProfileControllerTest": ("PPC", "Existing", "@WebMvcTest with the real SecurityConfig and real templates (Thymeleaf renders the page); StudentProfileService mocked. Logged in as STUDENT alex@test.com, viewing Ben Koh (student 2)."),
    "PublicProfileControllerGapTest": ("PPG", SYNC, "@WebMvcTest with the real SecurityConfig and real templates (Thymeleaf renders the page); StudentProfileService mocked; STUDENT alex@test.com. Adds to the teammate's PublicProfileControllerTest (unchanged)."),
    "PublicProfileControllerServiceTest": ("PPS", TD56, "@WebMvcTest(PublicProfileController) with the REAL StudentProfileService and AccountCredentials; only the repositories are mocked (no database). STUDENT alex@test.com; Ben Koh is student 2."),
    "PublicProfileLinksTest": ("PPL", SYNC, "@WebMvcTest with the real SecurityConfig and real templates (Thymeleaf renders the page) for /matches, /match-requests, /connections and /groups/{id}/members; services mocked. STUDENT alex@test.com; the other student is Ben Koh, student 2."),
    # Full-stack flows (2026-11-07)
    "SignUpAndLoginFlowTest": ("FLA", FLOW, FLOW_PRE),
    "ProfileAndPreferencesFlowTest": ("FLP", FLOW, FLOW_PRE),
    "BuddyMatchingFlowTest": ("FLM", FLOW, FLOW_PRE + " World: a new course; Alex (In person, One-to-one, Exam preparation, Wed 19-21) searches; Ben (either, either, Exam, Wed 19-21: 92), Carl (Problem solving, Wed 19-20: 70), Dana (Online, Small group, Concept review, Mon 10-12: 20)."),
    "StudyGroupFlowTest": ("FLG", FLOW, FLOW_PRE + " Groups are created through the form with one Monday 10:00-12:00 slot unless stated."),
    "AdminAccountsFlowTest": ("FLD", FLOW, FLOW_PRE + " A student who is still used can't be deleted only when the database commits, so that refusal is tested in e2e/, not here."),
    "MatchingSettingsFlowTest": ("FLS", FLOW, FLOW_PRE + " Alex (Mon 10-14, In person, One-to-one, Exam preparation) searches; Pia matches everything but 30 minutes (85), Quinn shares 4 hours only (40), Rex is in another course."),
    "CourseAdminFlowTest": ("FLC", FLOW, FLOW_PRE + " Deleting a course that is still used is refused only when the database commits, so that is tested in e2e/."),
    # End-to-end in a real browser (2026-11-07): e2e/tests/<name>.js, keyed by the file name without .js
    "auth.spec": ("EAU", E2E, E2E_PRE + " Runs in desktop and phone."),
    "confirmations.spec": ("ECF", E2E, E2E_PRE + " Runs in desktop and phone."),
    "flash.spec": ("EFL", E2E, E2E_PRE + " Runs in desktop."),
    "forms.spec": ("EFO", E2E, E2E_PRE + " Runs in desktop."),
    "matches.spec": ("EMA", E2E, E2E_PRE + " Runs in desktop."),
    "roles.spec": ("ERO", E2E, E2E_PRE + " Runs in desktop."),
    "settings.spec": ("ESE", E2E, E2E_PRE + " Runs in the settings project only, after the others, one test at a time; the matching settings are read first and put back after every test, even a failed one."),
    "layout.spec": ("ELY", E2E, E2E_PRE + " Runs in phone only. Each page's full-page screenshot is attached to the Playwright report."),
    "a11y.spec": ("EAX", E2E, E2E_PRE + " Runs in desktop and phone. axe-core 4.14, WCAG 2.0/2.1 A and AA plus the experimental label-content-name-mismatch rule; e2e/a11y-baseline.json pins known violations."),
    # Coverage gaps (2026-11-09)
    "CourseTest": ("CRE", COVERAGE, "Plain unit test (no Spring, no database). Course ids set by hand (ReflectionTestUtils), as the database would."),
    "GroupMemberSummaryTest": ("GMY", COVERAGE, "Plain unit test, nothing saved. Alex leads 'IS442 Revision' (IS442, size 4); Ben takes IS442, Daniel takes no courses."),
    "GroupRequestSummaryTest": ("GRQ", COVERAGE, "Plain unit test, nothing saved. Alex leads 'IS442 Revision' (IS442) with room for 2: Alex and one more; Ben and Chloe ask to join."),
    "GroupMembershipServiceTest": ("GMS", BATCH_B,"@DataJpaTest + GroupMembershipService + StudyGroupService, empty H2. Course IS442 taken by Alex, Ben, Chloe, Emily (Daniel takes nothing). Alex leads 'IS442 Revision', maximum size 4. Own data, rolled back."),
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
        t("pageShowsStatusLabelsAndFramesTheRequestJustSent", "happy", "Statuses as coloured labels; the request just sent is framed", "Mock: sent PENDING (id 5) and REJECTED, received ACCEPTED; GET with flash success and newRequestId 5; then GET without", "-", "Green 'message success' banner; labels 'Waiting for a reply' (pending), 'Declined' (rejected), 'Accepted' (accepted); no raw PENDING; card 5 has match-card-new; without the flash nothing is framed", "S7, S8"),
        t("sendRequestRedirectsWithSuccessMessage", "happy", "Send a request with a message", "POST /match-requests recipientId=2, message", "message 'Study?'", "Redirect /match-requests, flash 'Request sent to Ben Koh. Their reply will show under Sent below.' and newRequestId 5; sender is the logged-in student", "S7"),
        t("sendRequestWithoutMessageIsAllowed", "boundary", "Message is optional", "POST /match-requests recipientId=2, no message", "message absent", "Redirect with 'Request sent to Ben Koh. ...' and newRequestId 5; service gets null message", "S7"),
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
        t("publicPreferencesListTheStudentsPreferences", "happy", "Public preferences list the student's preferences", "getPublicPreferences(Ben)", "seeded", "Includes IS442", "S6", SEEDED + " Written by a teammate (commit 971c1a0); its requirement IDs come from this workbook."),
        t("addAndRemoveCourse", "happy", "Add and remove a course", "addCourse IS111, removeCourse CS201 (Ben)", "-", "Courses IS442, IS216, IS111", "S1, PRF-2", SEEDED + " Note: removes CS201 while Ben leads the seeded CS201 group (K8)."),
        t("courseRulesAreEnforced", "negative", "Course rules", "add IS442 again; remove IS111; remove IS442", "-", "IllegalStateException each time", "PRF-2", SEEDED),
    ],
    "StudyPreferenceServiceTest": [
        t("createPreferenceWithASlot", "happy", "Create a preference and a slot", "createPreference(Ben, CS201), addAvailabilitySlot Mon 10-12", "-", "Saved with that slot", "S2, PRE-1, PRE-3", SEEDED),
        t("createRulesAreEnforced", "negative", "Create rules", "IS111 (not taken), IS442 (exists), null goal", "-", "IllegalStateException, IllegalStateException, IllegalArgumentException", "PRE-1", SEEDED),
        t("updatePreference", "happy", "Update a preference, slots kept", "updatePreference(Ben IS442)", "-", "Goal changed; 2 slots kept", "S2", SEEDED),
        t("deletingAPreferenceAlsoDeletesItsSlots", "side-effect", "Delete removes slots", "deletePreference(Ben IS442)", "-", "Preference and slots gone", "S2", SEEDED),
        t("anotherStudentCannotTouchAPreference", "permission", "Only the owner", "delete, addSlot as Alex on Ben's preference", "-", "StudyPreferenceNotFoundException each time", "PRE-2", SEEDED + " The get step was dropped on 2026-11-09 with the unused getPreference (GAP-DEAD1)."),
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
        t("inactiveAdminCanBeDeactivatedAndDeletedWithOneActiveAdminLeft", "boundary", "An already-inactive admin can be deactivated and deleted", "Active admin + inactive admin; deactivateUser(inactive), then deleteUser(inactive)", "-", "No LastAdminException: still inactive, then gone, although only one admin is active"),
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
        t("createAdminDatabaseRefusalShowsDuplicateEmailMessage", "conflict", "Database refusal shown as duplicate email", "Mock throws DataIntegrityViolationException (same email saved twice at once); POST", "new.admin@test.com", "Flash 'Another account already uses that email.'; email refilled", notes="A real duplicate race; since 2026-10-28 an over-long value is stopped before the service (GAP-K3REG fixed)."),
        t("createAdminWithMissingFieldsSendsBlanksToService", "boundary", "Missing fields sent as blanks", "POST with no fields", "-", "Service called with ('', ''); flash 'Enter an email address.'"),
        t("updateEmailRedirectsWithSuccess", "happy", "Change email", "POST /admin/accounts/7/email", "alex.new@test.com", "Flash 'The account now logs in with alex.new@test.com.'"),
        t("updateEmailOfMissingAccountShowsError", "negative", "Change email of missing account", "Mock throws UserNotFoundException", "-", "Flash 'That account no longer exists.'"),
        t("updateEmailRefusedByServiceShowsItsMessage", "negative", "Invalid or taken new email", "Mock throws Invalid... and Duplicate...", "'alex@', ben@test.com", "Flash with each service message"),
        t("createAdminWithOverLongEmailShowsItsOwnMessage", "boundary", "Over-long admin email gets its own message", "POST /admin/accounts with a 256-character email", "'a' x 247 + '@test.com'", "Redirect; flash 'The email can be at most 255 characters.'; email refilled; service not called", notes="GAP-K3REG fixed 2026-10-28."),
        t("updateEmailOverLongShowsItsOwnMessage", "boundary", "Over-long new email gets its own message", "POST /admin/accounts/7/email with a 256-character email", "'a' x 247 + '@test.com'", "Redirect; flash 'The email can be at most 255 characters.'; service not called", notes="GAP-K3REG fixed 2026-10-28."),
        t("updateEmailDatabaseRefusalShowsDuplicateEmailMessage", "conflict", "Database refusal on email change", "Mock throws DataIntegrityViolationException (same email saved twice at once)", "ben@test.com", "Flash 'Another account already uses that email.'", notes="A real duplicate race; since 2026-10-28 an over-long value is stopped before the service (GAP-K3REG fixed)."),
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
        t("emailInAnotherLetterCaseFindsTheAccount", "boundary", "Login email in another letter case or with spaces finds the account", "Save alex@test.com; loadUserByUsername", "'Alex@Test.COM', '  ALEX@test.com '", "Account alex@test.com found both times", notes="Fixed 2026-10-28; was a @Disabled known-bug test, now a regression test (same Test ID)."),
    ],
    "AuthControllerTest": [
        t("loginAndRegisterPagesArePublic", "permission", "Login and sign-up pages are public", "Anonymous GET /login, /register", "-", "200 with views auth/login and auth/register"),
        t("registerWithoutCsrfIsForbidden", "permission", "Sign-up without CSRF refused", "POST /register without CSRF", "-", "403; service not called"),
        t("registerPassesEveryFieldAndRedirectsToLogin", "happy", "Sign-up", "POST /register with all fields", "new@test.com, password123, New Student, SCIS, Information Systems, 1, 98765432", "Redirect /login?registered; service gets every field exactly"),
        t("registerErrorRefillsEverythingButPassword", "side-effect", "Error refills the form except password", "Service throws InvalidAccountDetailsException", "as above", "Form again with the message; all fields refilled; no password in the model"),
        t("registerDuplicateEmailShowsMessage", "conflict", "Sign-up with taken email", "Service throws DuplicateEmailException", "new@test.com", "'Another account already uses new@test.com.'"),
        t("registerInvalidProfileShowsMessage", "negative", "Invalid profile value", "Service throws IllegalArgumentException", "-", "'Contact number must be 8 digits'"),
        t("overLongFieldShowsItsOwnMessageAndKeepsTheForm", "boundary", "Over-long sign-up field gets its own message", "POST /register with 256 characters in email, then name, school, programme", "'x' x 256", "Form again with 'Name, school, programme and email can be at most 255 characters.'; every field refilled, no password; service not called", notes="GAP-K3REG fixed 2026-10-28."),
        t("fieldOf255CharactersReachesTheService", "boundary", "255-character sign-up field still accepted", "POST /register with school 'x' x 255", "'x' x 255", "Redirect /login?registered; the service gets the 255 characters"),
        t("registerDatabaseRefusalShowsDuplicateEmailMessage", "conflict", "Database refusal shown as duplicate email", "Service throws DataIntegrityViolationException('unique constraint'): two sign-ups with the same email at once", "-", "'Another account already uses that email.'", notes="A real duplicate race; since 2026-10-28 an over-long value is stopped before the service (GAP-K3REG fixed)."),
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
        t("courseCodeOver255CharactersFailsInDatabase", "boundary", "Code length 255 / 256", "createCourse", "'C' x 255, 'C' x 256", "255 saved; 256 DataIntegrityViolationException", notes="The database is the service's limit; the controller checks the length first (K4 fixed 2026-10-28)."),
    ],
    "CourseControllerTest": [
        t("anonymousIsRedirectedToLogin", "permission", "Anonymous sent to login", "GET /courses; POST /courses with CSRF", "-", "302 to /login; service not called"),
        t("studentSeesListWithoutAdminTools", "permission", "Student sees a read-only list", "As STUDENT GET /courses?edit=1", "IS442 (id 1)", "200; isAdmin false; editingId null; no edit form, no add form"),
        t("adminSeesEditFormForChosenCourse", "happy", "Admin sees edit and add forms", "As ADMIN GET /courses?edit=1", "IS442 (id 1)", "isAdmin true; editingId 1; edit form for course 1 and add form shown"),
        t("studentCannotAddUpdateOrDeleteCourses", "permission", "Student can't change courses", "As STUDENT POST add, update, delete with CSRF", "-", "403 each (@PreAuthorize); service not called"),
        t("postWithoutCsrfTokenIsForbidden", "permission", "POSTs without CSRF refused", "As ADMIN POST add, update, delete without CSRF", "-", "403 each; service not called"),
        t("addCourseStripsAndRedirectsWithSuccess", "happy", "Add a course (values stripped)", "POST /courses", "'  IS999 ', ' Test Course '", "Service gets IS999 / Test Course; flash 'IS999 has been added.'"),
        t("addCourseWithBlankFieldSavesNothing", "negative", "Blank code or missing name", "POST /courses twice", "'   '; name missing", "Flash 'Enter both a course code and a course name.'; service not called"),
        t("overLongCourseNameIsRefused", "boundary", "Course name over 255 characters refused", "POST /courses; POST /courses/5/update", "name 'N' x 256", "Flash 'Course code and course name can be at most 255 characters.'; add back to /courses, update back to /courses?edit=5; service not called"),
        t("updateCourseRedirectsWithSuccess", "happy", "Rename a course", "POST /courses/5/update", "' IS443 ', 'New Name '", "Service gets stripped values; flash 'IS443 has been updated.'"),
        t("updateCourseWithBlankFieldReturnsToEditForm", "negative", "Blank name on edit", "POST /courses/5/update", "name ' '", "Redirect /courses?edit=5 with the error; service not called"),
        t("updateUnknownCourseShowsError", "negative", "Rename a missing course", "Service throws CourseNotFoundException", "-", "Flash 'That course no longer exists.'"),
        t("deleteCourseRedirectsWithSuccess", "happy", "Delete a course", "POST /courses/5/delete", "-", "Flash 'The course has been deleted.'"),
        t("deleteUnknownCourseShowsError", "negative", "Delete a missing course", "Service throws CourseNotFoundException", "-", "Flash 'That course no longer exists.'"),
        t("deleteCourseInUseShowsMessage", "conflict", "Delete a course in use", "Service throws DataIntegrityViolationException", "-", "Flash 'Students, study preferences or study groups still use this course, so it can't be deleted.'"),
    ],
    "CourseControllerKnownBugTest": [
        t("addCourseTheDatabaseRefusesShowsAnError", "boundary", "Adding an over-long course shows a message", "POST /courses (the mocked service would throw DataIntegrityViolationException)", "code 'C' x 256", "Redirect /courses; flash 'Course code and course name can be at most 255 characters.'; the service isn't reached", notes="Fixed 2026-10-28; was a @Disabled known-bug test, now a regression test (same Test ID)."),
        t("updateCourseTheDatabaseRefusesShowsAnError", "boundary", "Renaming to an over-long value shows a message", "POST /courses/5/update (the mocked service would throw)", "code 'C' x 256", "Redirect /courses?edit=5; flash 'Course code and course name can be at most 255 characters.'", notes="Fixed 2026-10-28; was a @Disabled known-bug test, now a regression test (same Test ID)."),
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
        t("cancelRequestWithdrawsOnlyAPendingRequest", "conflict", "Only a waiting request can be cancelled", "Ben pending, Chloe member; cancel Ben, Ben again, Chloe, never-asked Daniel", "-", "Ben's row CANCELLED, respondedAt null, not in pending list; then 'Only a request that is still waiting can be cancelled' x2; Daniel: 'You haven't asked to join this group'; Chloe still ACCEPTED"),
        t("leaderCannotAnswerACancelledRequest", "conflict", "A cancelled request can't be accepted or rejected", "Ben requests then cancels; leader accepts, rejects", "-", "IllegalStateException 'The student has cancelled this request' x2; still CANCELLED; 1 member"),
        t("cancelledStudentCanAskAgain", "conflict", "A student who cancelled can ask again", "Ben requests, cancels, requests again", "-", "Same row back as PENDING; still 2 rows; Ben in the pending list"),
        t("leaveEndsMembershipWithAnOptionalReason", "happy", "Leave a group with an optional reason", "Ben and Chloe members; Ben leaves with '  My timetable changed  ', Chloe with '   '", "-", "Both LEFT; Ben's reason trimmed, leftAt set, accept time kept; Chloe's reason null; 1 member; both in the left list"),
        t("leaveRefusedForLeaderNonMemberClosedGroupAndLongReason", "conflict", "Leader, non-members, a closed group and a long reason are refused", "Leader; never-joined Daniel; pending Chloe; 501-char reason; then close and leave", "501 characters", "'The leader can't leave their own group'; 'You aren't a member of this group' x2; 'The reason can be at most 500 characters'; 'Group is closed'; Ben still ACCEPTED"),
        t("leaveReasonOfExactly500CharactersIsKept", "boundary", "A 500-character reason is kept", "Ben leaves with 500 characters", "500 characters", "Reason stored, 500 long"),
        t("leftStudentCanAskAgain", "conflict", "A student who left can ask again", "Ben leaves with a reason, then requests", "-", "Same row back as PENDING; leftAt and reason cleared; left list empty"),
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
        t("missingLatestSlotTimeIsRefused", "negative", "Missing latest slot time stops the app", "new StudyGroupSettings", "3, 08:00, latest null, 15, NEWEST", "IllegalArgumentException 'studybuddy.groups.earliest-slot-time must be before studybuddy.groups.latest-slot-time'"),
    ],
    # Coverage gaps (2026-11-09)
    "CourseTest": [
        t("separateObjectsWithTheSameIdAreEqual", "happy", "Same course loaded twice is equal", "Two Course objects with id 7; one with id 8", "IS442", "Not the same object, but equal with the same hash code; id 8 not equal"),
    ],
    "GroupMemberSummaryTest": [
        t("memberWithNoCoursesHasAnEmptyCoursesLine", "boundary", "Member with no courses: empty courses line", "Daniel (no courses) and Ben (IS442) join; GroupMemberSummary.from(...).coursesLine()", "-", "Daniel ''; Ben 'Takes IS442'"),
    ],
    "GroupRequestSummaryTest": [
        t("requestToAGroupThatHasSinceFilledShowsFull", "boundary", "Waiting request to a group that has since filled shows full", "Group size 2; Ben asks; Chloe asks and is accepted; GroupRequestSummary.from(Ben's request)", "-", "Before: full() false. After: PENDING, 2 members, full() true"),
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
        t("cancelledRequestIsSavedAndLeavesEveryList", "side-effect", "A cancelled request leaves every list", "Ben requests; cancelRequest; read back; leader accepts; Ben asks again", "-", "Saved as CANCELLED; leader's and Ben's pending lists and Ben's declined list empty; viewer role NONE; accept refused 'The student has cancelled this request'; asking again reuses the same id and is pending"),
        t("cancelRequestOnlyTouchesTheCallersRequest", "permission", "Only the caller's own request is cancelled", "Ben pending; cancel as Chloe, on an unknown group, as an unknown email", "-", "'You haven't asked to join this group'; StudyGroupNotFoundException; StudentNotFoundException; Ben still PENDING"),
        t("leavingIsSavedAndTheLeaderSeesWhoLeftAndWhy", "side-effect", "Leaving frees the place; the leader sees who left and why", "Ben and Chloe accepted; Ben leaves with a reason, then Chloe with none; read back", "-", "Leader's left list: Chloe (no reason), Ben ('My timetable changed'); 1 member; Ben's joined and declined lists empty; viewer role NONE; Ben asking for the left list: NotGroupLeaderException"),
        t("leaderCannotLeaveAndUnknownGroupOrStudentIsRefused", "permission", "The leader can't leave; unknown group or student refused", "Leader leaves; unknown group; unknown email", "-", "'The leader can't leave their own group'; StudyGroupNotFoundException; StudentNotFoundException; still 1 member"),
        t("removeFromMyGroupsOnlyWorksForClosedGroups", "conflict", "Remove from My groups only for closed groups", "Open group (Ben, Alex); close; Daniel (no link)", "-", "Open: 'Only a closed group can be removed from My groups' x2; Daniel: 'You aren't linked to this group'; Ben still sees it"),
        t("removeFromMyGroupsHidesTheGroupForThatStudentOnly", "side-effect", "Remove from My groups hides it for that student only", "Ben, Chloe members, Emily pending; close; Alex, Ben, Emily remove it", "-", "Gone from Alex's led list, Ben's joined list, Emily's pending list; Chloe still sees it; 3 members unchanged"),
        t("membersAreLeaderFirstThenInJoinOrder", "happy", "Members: leader first, then join order", "Chloe asks, Ben asks; accept Ben, (clock ticks), accept Chloe", "-", "Alex, Ben, Chloe", notes="Accept times on this machine tick once a millisecond; equal times fall back to the request order, so the test waits for the clock to tick (no fixed sleep)."),
        t("joinedGroupsListOpenFirstAndLeaveOutLedGroups", "happy", "Joined groups: open first; led groups left out", "Ben joins Chloe's group (then closed) and Alex's group; Ben leads his own", "-", "Alex's group, then Chloe's closed group; Ben's own left out; Alex has none"),
        t("pastRequestsListDeclinesAndRemovalsForOpenGroupsOnly", "happy", "Declined or removed: open groups only, newest first", "Ben removed from Chloe's, rejected by Alex, rejected by Emily (then closed)", "-", "Alex's rejection, then Chloe's removal; Emily's closed group left out"),
        t("viewerRoleForEveryKindOfStudent", "happy", "What the group page offers each student", "Ben member, Chloe pending, Daniel rejected, Emily removed, Frank no link; getViewerRole, getViewerRoles", "-", "LEADER, MEMBER, PENDING, REJECTED, REMOVED, NONE; map for Ben: MEMBER / LEADER; only NONE, REJECTED, REMOVED can ask"),
    ],
    # ================================ NEW (Batch C) ================================
    "SecurityConfigTest": [
        t("anonymousIsSentToLogin", "permission", "Anonymous user sent to login on every protected route", "Each protected route in ROUTES, with a CSRF token, not logged in", "46 routes", "302 to /login"),
        t("anonymousPostWithoutCsrfIsForbidden", "permission", "Anonymous POST without CSRF refused", "Each protected POST, no token, not logged in", "30 POST routes", "403 (the CSRF check runs before the login check, N-C3)"),
        t("studentIsForbiddenFromAdminRoutes", "permission", "Student refused on every admin route", "Each /admin route as STUDENT with CSRF", "9 routes", "403"),
        t("adminIsForbiddenFromStudentRoutes", "permission", "Admin refused on every student route", "Each student route as ADMIN with CSRF", "32 routes", "403"),
        t("rightRoleGetsPastSecurity", "happy", "The right role gets past security on every route", "Each protected route as its role (ADMIN for /admin, otherwise STUDENT) with CSRF", "46 routes", "Neither 403 nor a redirect to /login (404 here: no controller loaded)"),
        t("anyLoggedInUserGetsPastSecurity", "happy", "/home and /courses open to both roles", "Each any-user route as STUDENT and as ADMIN", "5 routes", "Neither 403 nor /login", notes="The /courses POSTs are admin-only through @PreAuthorize, not the URL rules; CourseControllerTest checks the refusal."),
        t("loggedInPostWithoutCsrfIsForbidden", "permission", "Every POST needs a CSRF token, even for the right role", "Each protected POST as its role, no token", "30 POST routes", "403"),
        t("publicRoutesNeedNoLogin", "happy", "Login and sign-up need no login; sign-up POST still needs CSRF", "GET /login, GET /register, POST /register anonymous", "-", "Not refused or redirected; POST /register without token 403"),
        t("staticFilesAndErrorPageNeedNoLogin", "happy", "Static files and the error page are public", "GET /css/style.css, /js/app.js, /images/logo.png, /error anonymous", "-", "style.css 200; none refused or redirected to /login"),
        t("logoutNeedsCsrfToken", "permission", "Logout needs a CSRF token", "POST /logout as a student without and with a token", "-", "403; with token redirect /login?logout"),
        t("h2ConsoleIsOpenWithoutLoginOrCsrf", "permission", "H2 console open to everyone, no CSRF check", "GET /h2-console/ and POST /h2-console/login.do (no token) anonymous; POST as a student", "-", "Never 302 or 403 (404 only because the console servlet isn't in this slice)"),
        t("h2ConsoleRelaxesFrameOptionsOnlyForItself", "side-effect", "Only the console may be framed", "GET /h2-console/, /login, /home", "-", "Console: no X-Frame-Options; /login and /home: X-Frame-Options DENY"),
    ],
    "RouteInventoryTest": [
        t("securityMatrixListsEveryControllerRoute", "boundary", "Security matrix lists every controller route", "Read every request mapping of com.studybuddy.controller; compare with SecurityConfigTest.ROUTES", "51 mappings", "Same set both ways; otherwise 'Route table out of date: add <method path> to the SecurityConfigTest matrix and say which role may call it' (or remove ...)"),
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
        t("profileValueTheDatabaseRefusesShowsAnError", "boundary", "Over-long profile value shows a message", "POST /student/profile (the mocked service would throw DataIntegrityViolationException)", "name 'A' x 256", "Redirect /student/profile; flash 'Name, school and programme can be at most 255 characters.'; the service isn't reached", notes="Fixed 2026-10-28; was a @Disabled known-bug test, now a regression test (same Test ID)."),
    ],
    "StudyPreferenceControllerTest": [
        t("pageShowsPreferencesAndOnlyCoursesWithoutOne", "happy", "Preferences page", "Mock: preference 4 (IS442); Alex takes IS442, CS201; GET ?edit=4", "-", "View student/preferences; preferences, coursesWithoutPreference [CS201], editingId 4, 61 time options"),
        t("slotsShowAsChipsAndEditButtonsHaveTheirOwnRow", "happy", "Slots as chips; Save and Done in their own row", "Mock: IS442 preference with Mon 10:00-12:00; GET /student/preferences, then ?edit=4", "-", "Table: chip 'Mon 10:00-12:00' (no 'Monday 10:00'), table marked data-table-stack with data-label cells for phones; edit form: page-sized cell, the slot as a chip, Save and Done in a button-row"),
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
        t("buddyStatusesComeFromConnectionsAndPendingRequests", "happy", "Link to each listed student worked out for the page", "Mock: buddy Ben (2), sent pending to Dan (4), received pending from Chloe (3), rejected both ways with Eve (5) and Finn (6); GET /matches", "-", "Model buddyStatuses = {2: CONNECTED, 4: REQUEST_SENT, 3: REQUEST_RECEIVED}; rejected ones absent; services asked with alex@test.com"),
        t("relatedStudentsShowALabelInsteadOfTheSendButton", "happy", "Label instead of 'Send request' for buddies and pending requests", "Same links; results Ben, Chloe, Dan, Eve; GET /matches", "-", "All four listed; 'You're already study buddies' links to /connections, 'Request sent' and 'They've sent you a request' to /match-requests; only Eve (rejected) has a Send form"),
        t("requestMessageIsALabelledMultiLineBox", "happy", "The request message is a labelled multi-line box", "Mock: one unrelated result; GET /matches", "-", "Label 'Message (optional)' tied to a 3-row textarea with maxlength 255; no one-line text field for the message"),
        t("oneTimeErrorIsShownOnThePage", "happy", "One-time error shown on the matches page", "GET /matches with flash error", "'That student's profile no longer exists.'", "The message is on the page"),
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
        t("messageTheDatabaseRefusesShowsAnError", "boundary", "Over-long match message shows a message", "POST /match-requests (the mocked service would throw DataIntegrityViolationException)", "message 'M' x 256", "Redirect /match-requests; flash 'Messages can be at most 255 characters.'; the service isn't reached", notes="Fixed 2026-10-28; was a @Disabled known-bug test, now a regression test (same Test ID). Only a hand-made POST can send it: the page's message box has maxlength 255."),
        t("shortMessagesThatReadAsFalseAreShown", "boundary", "Messages 'no', 'false', 'off' are shown", "Mock: received 'no' and 'Study IS442 together?', sent 'false' and 'off'; GET /match-requests", "-", "<p>no</p>, <p>false</p>, <p>off</p> and the ordinary message all on the page", notes="Fixed 2026-10-28; was a @Disabled known-bug test, now a regression test (same Test ID)."),
    ],
    "ContactNumberVisibilityTest": [
        t("matchesPageNeverShowsContactNumbers", "side-effect", "Matches page never shows a contact number", "Mock: results for Ben and Chloe built with from(); GET /matches", "91110001, 92220002, 93330003", "Names shown; none of the three numbers in the HTML"),
        t("matchRequestsPageNeverShowsContactNumbers", "side-effect", "Match requests never show a contact number, even accepted", "Mock: accepted request from Ben, pending to Chloe (from()); GET /match-requests", "same numbers", "Names shown; no number in the HTML"),
        t("publicProfilePageNeverShowsContactNumbers", "side-effect", "Public profile page never shows a contact number", "Mock Ben's profile and preference built with from(); GET /students/2", "92220002, 91110001", "Name and times shown; no number and no email in the HTML"),
        t("connectionsPageShowsOnlyTheBuddysNumber", "happy", "Study buddies shows only the buddy's number", "Mock: Alex-Ben connection via ConnectionSummary.from(); GET /connections", "same numbers", "Ben's 92220002 shown; Alex's own and Chloe's not"),
    ],
    "HtmlEscapingTest": [
        t("matchRequestMessageIsEscaped", "negative", "Match request message is escaped", "Mock: received message 'Hi <script>alert(1)</script>'; GET /match-requests", "<script>alert(1)</script>", "HTML has '&lt;script&gt;alert(1)&lt;/script&gt;' and never the raw tag"),
        t("groupNameAndDescriptionAreEscaped", "negative", "Group name and description are escaped", "Mock group 1 with the script in name and description; GET /groups/1", "<script>alert(1)</script>", "Both escaped; raw tag absent"),
        t("publicProfileNameAndProgrammeAreEscaped", "negative", "Public profile name and programme are escaped", "Mock profile with the script in name and programme; GET /students/2", "<script>alert(1)</script>", "Escaped; raw tag absent"),
        t("profileNameIsEscaped", "negative", "Profile name is escaped (inside an input value)", "Mock profile name 'Alex <script>alert(1)</script>'; GET /student/profile", "<script>alert(1)</script>", "Escaped; raw tag absent"),
    ],
    "StudyGroupControllerTest": [
        t("browseListsOpenGroupsWithViewerRoles", "happy", "Browse groups", "Mock: Alex's group (leader) and a full group; GET /groups", "-", "View studygroup/browse; both summaries; spaceCount 1, fullCount 1; selectedSort NEWEST; joinableOnly false"),
        t("browseUsesCourseFilterAndSort", "happy", "Course filter and sort", "GET ?courseId=1&sort=NAME; GET ?sort=bogus", "-", "findActiveGroupsByCourse(1, NAME); bogus falls back to NEWEST"),
        t("browseUnknownCourseShowsAllGroupsInstead", "negative", "Browse a missing course", "Mock throws CourseNotFoundException", "99", "Redirect /groups; flash 'That course doesn't exist. Showing all groups instead.'"),
        t("joinableOnlyKeepsGroupsTheStudentCanJoin", "boundary", "Only groups I can join", "Roles NONE, MEMBER, REJECTED, NONE-but-full; GET ?joinableOnly=true", "-", "Only the NONE and REJECTED groups with room"),
        t("myGroupsShowsTheStudentsFourLists", "happy", "My groups", "GET /groups/mine", "-", "View studygroup/my-groups; groupsLed, groupsJoined, pendingRequests, pastRequests for alex@test.com"),
        t("groupPageShowsGroupAndViewerRole", "happy", "Group page", "GET /groups/1?from=mine", "-", "View studygroup/details; group summary, viewerRole LEADER, fromMyGroups true"),
        t("pendingStudentOnFullGroupIsToldTheyAreWaiting", "boundary", "Waiting student on a full group is told why", "Mock: full group (2 of 2), viewer PENDING; GET /groups/5", "-", "Page shows 'The group is full right now. The leader can still accept you if a place opens up.'", "GRP-4, TD-3, L4", notes="Written by a teammate (commit 0385418); its requirement IDs come from this workbook."),
        t("pendingStudentOnOpenGroupSeesNoFullNote", "boundary", "No full-group note while there's room", "Mock: open group (2 of 4), viewer PENDING; GET /groups/6", "-", "'Waiting for the group's leader to respond.' shown; the full-group note isn't", "GRP-4, TD-3", notes="Written by a teammate (commit 0385418); its requirement IDs come from this workbook."),
        t("pendingStudentCanCancelFromTheGroupPage", "happy", "Waiting student sees Cancel request on the group's page", "Mock: viewer PENDING; GET /groups/6?from=mine; then viewer MEMBER", "-", "Form posting to /groups/6/cancel-request with from=mine and no backToMyGroups; a member sees no cancel form"),
        t("myGroupsOffersCancelOnlyOnWaitingRequests", "boundary", "My groups offers Cancel only on waiting requests", "Mock: one waiting request (group 7), one whose group closed (group 8); GET /groups/mine", "-", "Cancel form for group 7 with backToMyGroups=true; none for group 8"),
        t("onlyAMemberOfAnOpenGroupCanLeave", "permission", "Only a member of an open group sees Leave group", "Mock: viewer MEMBER of open group 6; then LEADER; then group closed, MEMBER", "-", "Member: form posting to /groups/6/leave with a 'reason' box (maxlength 500); leader and closed group: no leave form"),
        t("unknownGroupPageGoesBackToBrowse", "negative", "Missing group page", "Mock throws StudyGroupNotFoundException", "99", "Redirect /groups; flash 'That group no longer exists.'"),
        t("createFormStartsBlank", "happy", "Create form", "GET /groups/new", "-", "View studygroup/form; 1 slot row; minSize 2; maxSlots 3; 61 time options"),
        t("createGroupRedirectsToItsPage", "happy", "Create a group", "POST /groups (valid form)", "New Group, IS442, size 3, Mon 10:00-12:00", "Redirect /groups/7?from=mine; flash 'Group created. Other students can now find it on Study groups and ask to join.'; service gets the form for alex@test.com"),
        t("invalidCreateShowsEveryErrorOnTheForm", "negative", "Invalid create shows every error", "POST blank name, size 1, overlapping slots", "-", "Form again: 'Please enter a group name', 'A group needs room for at least 2 members, including you', 'Slots 1 and 2 overlap'; service not called"),
        t("createForUnknownCourseShowsErrorUnderCourse", "negative", "Course deleted meanwhile", "Mock throws CourseNotFoundException", "-", "Form again; under Course 'That course no longer exists. Please choose another.'"),
        t("createRefusedByTheGroupShowsItsMessage", "negative", "Entity refuses the group", "Mock throws IllegalArgumentException", "-", "Form again with error 'Max size must be at least 2'"),
        t("addAndRemoveSlotRowsSaveNothing", "boundary", "Add slot / Remove rows", "POST with addSlot (1 row, then 3 rows), with removeSlot=0 (2 rows)", "-", "2 rows; still 3 (no fourth); only the Tuesday row left; nothing saved"),
        t("editFormIsFilledForTheLeader", "happy", "Edit form", "GET /groups/1/edit?from=mine", "-", "Form filled ('IS442 Revision'); editing summary; minSize 3 (member count)"),
        t("editFormRefusedWithReason", "permission", "Edit refused for non-leader, closed or missing group", "GET edit for groups 2, 3 (closed), 99", "-", "'Only the group's leader can do that.'; 'This group is closed, so it can't be edited.' (from=mine kept); 'That group no longer exists.'"),
        t("saveEditRefusedWithReason", "permission", "Hand-made Save refused for non-leader, closed or missing group", "POST a valid edit form to groups 2, 3 (closed, from=mine), 99", "-", "Same redirects and messages as opening the edit form; updateGroup not called"),
        t("addOrRemoveSlotOnEditRefusedWithReason", "permission", "Add slot / Remove refused for non-leader, closed or missing group", "POST the edit form with addSlot, then with removeSlot=0, to groups 2, 3 (closed, from=mine), 99", "-", "Same redirects and messages as opening the edit form, for both buttons"),
        t("updateWithBlankSizeShowsErrorUnderSize", "negative", "Blank size on edit", "POST /groups/1/edit with maxSize ''", "-", "Form shown again; the only maxSize error is 'Please enter a maximum group size'; updateGroup not called"),
        t("updateGroupRedirectsWithChangesSaved", "happy", "Save changes","POST /groups/1/edit size 5, from=mine", "-", "Redirect /groups/1?from=mine; flash 'Changes saved.'"),
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
        t("cancelRequestRedirectsWithSuccess", "happy", "Cancel a join request", "POST /groups/1/cancel-request; with from=mine; with backToMyGroups=true", "-", "Redirect /groups/1, /groups/1?from=mine, /groups/mine; flash 'Request cancelled. You can ask to join again later.'"),
        t("cancelRequestRefusedWithReason", "conflict", "A refused cancel shows the reason", "Mock: group 1 refuses (already answered); group 99 unknown", "-", "Group 1: redirect /groups/1 with the entity's message; group 99: redirect /groups with 'That group no longer exists.'"),
        t("leaveGroupRedirectsWithSuccess", "happy", "Leave a group", "POST /groups/1/leave with reason; with from=mine and no reason", "-", "Redirect /groups/1 and /groups/1?from=mine; flash 'You left the group. You can ask to join again later.'; service gets the reason, then null"),
        t("leaveGroupRefusedWithReason", "conflict", "A refused leave shows the reason", "Mock: group 1 refuses (leader); group 99 unknown", "-", "Group 1: redirect /groups/1 with 'The leader can't leave their own group'; group 99: redirect /groups with 'That group no longer exists.'"),
        t("removeFromMyGroupsRefusedWithReason", "conflict", "Remove from My groups refused", "Mock throws IllegalState; StudyGroupNotFound", "-", "'Only a closed group can be removed from My groups'; 'That group no longer exists.'"),
        t("membersPageShowsRequestsAndMembers", "happy", "Manage members page", "GET /groups/1/members", "-", "View studygroup/members; group summary, requests [Farhan], members [Alex, Chloe]"),
        t("membersPageShowsWhoLeftAndWhy", "happy", "Manage members shows who left and why", "Chloe left with a reason, Ben with none; GET /groups/1/members", "-", "leftMembers = Ben, Chloe; page shows 'Members who left', 'My timetable changed' and 'No reason given.'"),
        t("membersPageRefusedForNonLeaderOrMissingGroup", "permission", "Manage members refused", "Mock throws NotGroupLeader (2), StudyGroupNotFound (99)", "-", "Redirect /groups/2 'Only the group's leader can do that.'; /groups 'That group no longer exists.'"),
        t("acceptAndRejectNameTheStudent", "happy", "Accept and reject", "POST /members/13/accept, /reject", "-", "'Farhan Ismail is now a member of the group.'; 'Farhan Ismail's request was rejected.'"),
        t("removeMemberNamesTheStudent", "happy", "Remove a member", "POST /members/12/remove", "-", "'Chloe Lim was removed from the group.'"),
        t("manageActionsRefusedWithReason", "conflict", "Manage actions refused", "Not leader; missing group; missing membership; full group; leader removal", "-", "Each message and destination: /groups/2, /groups, 'That request or member no longer exists.', 'Group is full. Remove a member to accept new members.', 'The leader can't be removed from their own group'"),
        t("emailParameterIsIgnored", "permission", "An 'email' field can't act for another student", "Join, remove from My groups, cancel request, leave, accept, remove with email=ben@test.com", "-", "Service always gets alex@test.com"),
        t("refusedRequestsNeverReachTheService", "permission", "Refused requests never reach the service", "GET members and 7 POSTs anonymous, as ADMIN, without CSRF", "-", "/login, 403, 403; no service call"),
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
        t("connectedBuddyIsStillListed", "happy", "A buddy is still listed", "Alex and Ben connected; Alex searches", "-", "Ben, Chloe and Dan all listed (the page shows Ben a label instead of the button)", notes="Was a @Disabled K17 known-bug test asserting 'not listed'; rewritten 2026-10-30 for TD-5 (renamed, same Test ID)."),
        t("studentWithPendingRequestEitherWayIsStillListed", "happy", "Students with a pending request either way are still listed", "Alex -> Ben and Chloe -> Alex pending; Alex searches", "-", "Ben, Chloe and Dan all listed (the page shows labels instead of the button)", notes="Was a @Disabled K17 known-bug test asserting 'not listed'; rewritten 2026-10-30 for TD-5 (renamed, same Test ID)."),
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
    # ================================ NEW (Batch E) ================================
    "StudentProfileServiceGapTest": [
        t("yearOfStudyBoundariesOnSignUp", "boundary", "Year of study 0/1/5/6 on sign-up", "createProfile with each year", "1, 5; 0, 6, -1", "1 and 5 saved; others 'Year of study must be between 1 and 5'; nothing saved"),
        t("yearOfStudyBoundariesOnUpdate", "boundary", "Year of study boundaries on update", "updateProfile with 5, 1, then 0, 6", "-", "5 and 1 saved; 0 and 6 refused; nothing else changed"),
        t("contactNumberMustBeEightDigitsOnSignUp", "boundary", "Contact number on sign-up", "createProfile with each number", "81234567; 1234567, 123456789, 1234567a, 1234-567, '', null", "8 digits saved; every other refused 'Contact number must be 8 digits'"),
        t("contactNumberMustBeEightDigitsOnUpdate", "boundary", "Contact number on update", "updateProfile with each number", "7 and 9 digits, letters, null", "Refused; profile unchanged"),
        t("contactNumberWithSpacesIsRefusedByTheService", "negative", "Contact number with spaces (service)", "updateProfile ' 91234567 '; createProfile '9123 4567'", "-", "Refused: the service doesn't strip (only the profile controller does)"),
        t("blankDetailsAreRefusedOnSignUp", "negative", "Blank name, school or programme on sign-up", "createProfile with null, '' and spaces in each field", "-", "'Name, school and programme are required'; nothing saved"),
        t("blankDetailsAreRefusedOnUpdate", "negative", "Blank name, school or programme on update", "updateProfile with null, '' and spaces in each field", "-", "Refused; profile unchanged"),
        t("detailsAreStripped", "happy", "Details saved without surrounding spaces", "createProfile and updateProfile with padded values", "'  New Student ', ' SCIS ', ...", "Stored stripped; contact number saved as given"),
        t("publicProfileHasNoContactNumberOrEmail", "side-effect", "Public profile has no contact number or email", "getPublicProfile(Alex)", "91234567, alex@test.com", "Fields: studentId, name, school, programme, yearOfStudy, courseCodes; text has neither number nor email"),
        t("unknownStudentOrCourseIsNotFound", "negative", "Unknown student or course", "getPublicProfile(999999), getProfile / updateProfile as ghost, add / remove course 999999", "-", "StudentNotFoundException x3; CourseNotFoundException x2; courses unchanged"),
        t("publicPreferencesListOnlyThatStudentsPreferences", "side-effect", "Public preferences: only that student's, no contact", "Alex IS442 and Ben CS201 preferences; getPublicPreferences(Alex)", "91234567, alex@test.com", "Only IS442 (Wed 19:00-21:00); PreferenceSummary fields have no contact or email; neither appears"),
        t("studentWithoutPreferencesHasNoPublicPreferences", "boundary", "No preferences gives an empty list", "getPublicPreferences(Alex) with none", "-", "Empty list"),
        t("deactivatedStudentHasNoPublicProfile", "negative", "Deactivated student has no public profile", "Alex deactivated; getPublicProfile / getPublicPreferences", "-", "StudentNotFoundException from both, as for an unknown id"),
        t("unknownOrAdminIdHasNoPublicProfile", "negative", "Unknown or admin id has no public profile", "getPublicProfile / getPublicPreferences for 999999 and an admin's id", "-", "StudentNotFoundException x4"),
        t("profileValueOver255CharactersFailsInDatabase", "boundary", "Profile name 255 / 256 at the database", "updateProfile name 'N' x 255, then x 256; flush", "-", "255 saved; 256 DataIntegrityViolationException", notes="The database is the service's limit; the controller checks the length first (K4 fixed 2026-10-28)."),
    ],
    "StudyPreferenceServiceGapTest": [
        t("createRefusesEachMissingChoice", "negative", "Missing choice on create", "createPreference with null mode, arrangement, goal", "CS201", "'Choose a study mode, group size and study goal' x3; still only the IS442 preference"),
        t("updateRefusesEachMissingChoiceAndKeepsTheOldOnes", "negative", "Missing choice on update", "updatePreference with null mode, arrangement, goal", "-", "Refused x3; Online, One-to-one, Concept review kept"),
        t("createForUnknownCourseIsNotFound", "negative", "Preference for a missing course", "createPreference course 999999", "-", "CourseNotFoundException"),
        t("anotherStudentCannotUpdateOrRemoveSlots", "permission", "Another student can't update or remove slots", "As Ben: updatePreference, removeAvailabilitySlot on Alex's preference", "-", "StudyPreferenceNotFoundException x2; nothing changed; Alex still can"),
        t("touchingSlotsAndSameTimesOnAnotherDayAreAllowed", "boundary", "Touching slots and another day", "Add Mon 12-14, Mon 8-10, Tue 10-12 next to Mon 10-12", "-", "All accepted (4 slots)"),
        t("oneMinuteOverlapIsRefused", "boundary", "One-minute overlap", "Add Mon 11:59-13:00 and Mon 09:00-10:01", "-", "'This slot overlaps one you already have on MONDAY'; still 1 slot"),
        t("incompleteOrEmptySlotIsRefused", "negative", "Slot without day, start or end, or start = end", "addAvailabilitySlot with each", "-", "'A slot needs a day and must end after it starts' x4; still 1 slot"),
        t("removingAnUnknownSlotIsRefused", "negative", "Remove a slot the preference doesn't have", "removeAvailabilitySlot(999999)", "-", "'This preference has no slot 999999'; still 1 slot"),
        t("preferencesAreListedByCourseCode", "happy", "Own preferences listed by course code", "Alex adds a CS201 preference after IS442; getPreferences", "-", "CS201, IS442"),
    ],
    "MatchRequestServiceGapTest": [
        t("recipientCanSendBackAfterRejecting", "boundary", "Recipient can send back after rejecting", "Alex -> Ben; Ben rejects; Ben -> Alex", "-", "New PENDING request from Ben"),
        t("newRequestAfterRejectionCanBeAccepted", "happy", "Request after a rejection can be accepted", "Alex -> Ben rejected; Alex -> Ben again; Ben accepts", "-", "Connected; Alex's sent list REJECTED and ACCEPTED"),
        t("deactivatedSenderCanStillSend", "negative", "Deactivated sender (open session) can still send", "Alex deactivated; createRequest Alex -> Chloe", "-", "PENDING request saved"),
        t("requestWithoutMessageIsSaved", "boundary", "Message is optional", "createRequest with null message", "-", "Saved; message null"),
        t("messageOver255CharactersFailsInDatabase", "boundary", "Message 255 / 256 at the database", "createRequest with 'M' x 255, then x 256", "-", "255 saved; 256 DataIntegrityViolationException", notes="The database is the service's limit; the controller checks the length first (K4 fixed 2026-10-28)."),
        t("requestSummariesNeverCarryAContactNumber", "side-effect", "Request summaries never carry a contact number", "Create, accept, list both ways, view by id", "91234567, 92345678", "Fields: requestId, sender, recipient, message, status, createdAt; no number in any summary"),
        t("acceptedRequestStaysAfterTheConnectionEnds", "side-effect", "Accepted request stays after the connection ends", "Accept, then Ben ends the connection", "-", "Not connected; both lists still show ACCEPTED"),
    ],
    "MatchRequestServiceKnownBugTest": [
        t("requestToDeactivatedStudentIsRefused", "negative", "Request to a deactivated student is refused", "Ben deactivated; createRequest Alex -> Ben", "-", "StudentNotFoundException (the page shows 'That student no longer exists.'); no request saved", notes="Fixed 2026-10-28; was a @Disabled known-bug test, now a regression test (same Test ID)."),
    ],
    "StudyBuddyConnectionServiceGapTest": [
        t("connectionsAreListedNewestFirst", "happy", "Buddies newest first", "Connect Alex-Ben, then (clock ticks) Chloe-Alex", "-", "Chloe, then Ben", notes="Creation times tick once a millisecond on the test machine; the test waits for a tick (no fixed sleep)."),
        t("summaryCarriesOnlyTheBuddysNumber", "side-effect", "Summary carries only the buddy's number", "Alex and Ben connected; each one's summary", "91234567, 92345678", "Alex sees 92345678 and never his own; Ben sees 91234567"),
        t("endingHidesTheConnectionFromBoth", "side-effect", "Ending hides the connection from both", "Alex ends the Ben connection (Chloe stays)", "-", "Ben has none; Alex has only Chloe; not connected either way"),
        t("endingTwiceFails", "negative", "Ending twice fails", "End, then end again as Alex and as Ben", "-", "ConnectionNotFoundException x2"),
    ],
    # ================================ Teammate (2026-10-25) and sync (2026-10-28) ================================
    "PublicProfileControllerTest": [
        t("showsProfileAndPreferencesButNoContactDetails", "happy", "Public profile page", "Mock Ben's public profile and IS442 preference; GET /students/2", "-", "View student/public-profile; name, 'Information Systems, Year 3', courses, preference and times shown; 92345678 absent", "S6, PRF-3"),
        t("unknownStudentRedirectsToMatchesWithMessage", "negative", "Unknown student", "Mock throws StudentNotFoundException; GET /students/99", "-", "Redirect /matches; flash 'That student's profile no longer exists.'", "S6"),
        t("onlyStudentsCanViewProfiles", "permission", "Only students view profiles", "GET /students/2 as ADMIN and anonymous", "-", "403; redirect", "D3, SEC-1, S6"),
    ],
    "PublicProfileControllerGapTest": [
        t("refusedRequestsNeverReachTheService", "permission", "Refused requests never reach the service", "GET /students/2 anonymous and as ADMIN", "-", "/login, 403; no service call"),
        t("nonNumericIdIsBadRequest", "negative", "Non-numeric id", "GET /students/abc", "-", "400; service not called"),
    ],
    "PublicProfileControllerServiceTest": [
        t("deactivatedStudentsProfileRedirectsLikeAnUnknownId", "negative", "Deactivated student's profile redirects", "Mocked repository returns Ben, deactivated; GET /students/2", "-", "Redirect /matches; flash 'That student's profile no longer exists.'"),
        t("activeStudentsProfileIsShown", "happy", "Active student's profile is shown", "Mocked repository returns Ben, active; GET /students/2", "-", "200, view student/public-profile"),
    ],
    "PublicProfileLinksTest": [
        t("matchCardNameLinksToTheProfile", "happy", "Match card name links to the profile", "Mock one result (Ben); GET /matches", "-", "<a href=\"/students/2\">Ben Koh</a>"),
        t("requestNamesLinkToTheProfile", "happy", "Request names link to the profile", "Mock received from Ben, sent to Chloe; GET /match-requests", "-", "Links to /students/2 (Ben) and /students/3 (Chloe)"),
        t("buddyNameLinksToTheProfile", "happy", "Buddy name links to the profile", "Mock connection with Ben; GET /connections", "-", "<a href=\"/students/2\">Ben Koh</a>"),
        t("memberAndRequestNamesLinkToTheProfile", "happy", "Member and request names link to the profile", "Mock group with Ben a member and Chloe waiting; GET /groups/1/members", "-", "Links to /students/2 and /students/3"),
    ],
    # ================================ Full-stack flows (2026-11-07) ================================
    "SignUpAndLoginFlowTest": [
        t("newStudentSignsUpLogsInAndLogsOut", "happy", "Sign up, log in, log out", "GET /register, POST it; log in through /login; Log out; open /home", "New student, password123", "/login?registered with 'Your account is ready...'; /home with 'Welcome back' and 'Logged in as <email>'; /login?logout with 'You've logged out.'; /home then goes to /login"),
        t("signUpWithATakenEmailKeepsTheTypedDetails", "conflict", "Taken email keeps the form", "Sign up a student; a visitor signs up with the same email", "Email of the first student", "Form again with 'Another account already uses <email>.'; email, name, school, programme, year, contact number refilled; password empty"),
        t("loginIgnoresEmailCaseAndSpacesButNotTheWrongPassword", "boundary", "Login email case and spaces; wrong password", "Log in with the email upper-cased and padded; log in with a wrong password", "'  EMAIL  '", "/home, 'Logged in as <lower-case email>'; wrong password: /login?error with 'That email and password don't match an active account...'"),
        t("overLongSchoolKeepsTheFormAndCreatesNoAccount", "boundary", "Over-long school on sign-up", "Sign up with a 256-character school; then try to log in", "256 x 'S'", "Form again with 'Name, school, programme and email can be at most 255 characters.', values kept; login fails (no account)"),
        t("eachRoleIsKeptToItsOwnPages", "permission", "Each role kept to its own pages", "Student opens /admin/accounts, /admin/matching; admin opens /matches, /groups, /home; visitor opens /groups", "-", "403, 403; 403, 403, admin links only; /login"),
    ],
    "ProfileAndPreferencesFlowTest": [
        t("studentUpdatesDetailsAndCourses", "happy", "Update details and courses", "Change programme, year, contact; reload; add and remove a course; remove a course with a preference", "Programme 'Computer Science'", "'Your profile has been updated.' once; values kept after reload; 'The course has been removed.'; 'Delete your <code> study preference before removing the course.' and the course stays"),
        t("studentManagesAPreferenceAndItsSlots", "happy", "Preference and slots", "Add a preference; slots Mon 10-12, Mon 11-13 (overlap), Mon 12-13 (touching); delete the preference", "Online, One-to-one, Concept review", "'...preference has been added...'; overlap refused 'This slot overlaps one you already have on MONDAY.'; list shows 'Mon 10:00-12:00', 'Mon 12:00-13:00'; 'The study preference has been deleted.'"),
        t("overLongNameIsRefusedAndTheNameIsKept", "boundary", "Over-long profile name", "Save the profile with a 256-character name", "256 x 'A'", "'Name, school and programme can be at most 255 characters.'; old name still in the form"),
        t("overLongSchoolOrProgrammeIsRefused", "boundary", "Over-long school or programme on the profile", "Save the profile with a 256-character school, then a 256-character programme", "256 x 'S', 256 x 'P'", "Each time 'Name, school and programme can be at most 255 characters.'; the saved school and programme stay"),
        t("deletedStudentStillLoggedInGetsNotFound", "side-effect", "Deleted while logged in (K16)", "Admin deletes a student who is still logged in; the student opens their profile, preferences and Study buddies", "-", "TODAY: each page is a bare 404 (no friendly page)"),
        t("publicProfileShowsStudyDetailsOnly", "side-effect", "Public profile shows study details only", "Viewer finds Pat on /matches and opens /students/{id}; opens /students/999999999; admin opens the profile; admin deactivates Pat; viewer opens it again", "Pat's contact number and email", "Match card and profile show name, programme, year, school, course, preference, 'Wed 19:00-21:00'; never the number or email; unknown id and deactivated: /matches with 'That student's profile no longer exists.'; admin 403"),
    ],
    "BuddyMatchingFlowTest": [
        t("courseSearchRanksStudentsByScore", "happy", "Course search ranked by score", "Alex searches the new course", "Ben (Appendix A), Carl, Dana", "'3 students, ranked by Balanced matching.'; Ben 92, Carl 70, Dana 20, in that order; Alex not listed"),
        t("filtersNarrowTheList", "happy", "Filters narrow the list", "Search with goal Exam preparation; with shared time only; with mode Online", "-", "Only Ben; Ben and Carl; Ben (either) and Dana"),
        t("sentRequestShowsUnderSentAndLabelsBothCards", "happy", "Sent request under Sent; labels on both cards", "Alex sends Carl a message and Dana 'no'; Alex and Carl open /matches", "'Want to study together?', 'no'", "'Request sent to Carl Crest. Their reply will show under Sent below.'; Sent card framed (match-card-new), 'Waiting for a reply' and the message; 'no' shown; Alex sees 'Request sent', Carl sees 'They've sent you a request', no Send button"),
        t("acceptingSharesContactNumbersOnStudyBuddiesOnly", "happy", "Accepting shares contact numbers on Study buddies only", "Ben sends Alex a request; Alex accepts; both open /connections; Alex opens /matches", "Both contact numbers", "'Request accepted. You're now study buddies...'; label 'Accepted', no buttons; numbers only on /connections, for both; Ben's card 'You're already study buddies' linking to /connections"),
        t("decliningShowsDeclinedAndMakesNoConnection", "happy", "Declining", "Alex sends Dana a request; Dana declines", "-", "'Request declined.'; 'Declined' for both; no connection; Alex's card for Dana has 'Send request' again"),
        t("onlyTheRecipientCanAcceptARequest", "permission", "Only the recipient accepts", "Ben sends Alex a request; Carl posts its accept", "-", "'Only the student who received this request can accept it.'; still 'Waiting for a reply'"),
        t("endingAConnectionHidesTheContactNumbersAgain", "happy", "Ending a connection", "Alex and Ben connect; Alex ends it", "-", "'Connection ended. You can no longer see each other's contact numbers.'; gone for both"),
    ],
    "StudyGroupFlowTest": [
        t("leaderCreatesAGroupThatOthersCanFind", "happy", "Create a group", "Fill the create form with two slots; another student opens /groups", "Size 4, Mon 10-12, Wed 14-16", "/groups/{id}?from=mine: 'Group created...', details, '1/4 members', ', places left', 'Mon 10:00-12:00', 'Wed 14:00-16:00', leader buttons; listed on Browse"),
        t("createFormListsEveryProblemThenAcceptsTouchingSlots", "negative", "Create form lists every problem", "Add slots to 3; submit with no name, size 1, overlapping and reversed slots; fix and submit", "-", "'Max time slots reached (3)', no '+ Add slot'; every message at once; then 'Group created.' with 'Mon 10:00-12:00', 'Mon 12:00-13:00', 'Tue 10:00-12:00'"),
        t("leaderEditsAGroupButCannotShrinkItBelowItsMembers", "boundary", "Edit; size not below members", "Group with 3 members; save description 'Edited'; save size 2", "-", "'Changes saved.'; size 2 refused 'The group has 3 members, so the maximum size can't be below 3'; still '3/4 members'"),
        t("leaderAddsAndRemovesSlotsOnTheEditForm", "happy", "Add and remove slots on the edit form", "Edit form: '+ Add slot', then Remove slot 1 (Fri 09:00-10:00 stays on the form); open the group without saving", "-", "Slot 2 appears; after Remove only one slot (Friday) is on the form; the saved group still shows only 'Mon 10:00-12:00'"),
        t("closingEndsPendingRequestsAndFreezesTheGroup", "side-effect", "Closing ends pending requests", "Two pending requests; leader closes; tries edit and members; a waiting student removes it from My groups", "-", "Box text with '2 pending requests'; 'Group closed. 2 pending requests ended without an answer.'; edit and members refused; waiting students see 'Closed' and 'The group was closed before...'; 'Removed from My groups.' only for that student; leader can remove it too"),
        t("leaderAcceptsAndRejectsJoinRequests", "happy", "Accept and reject join requests", "Farah and Jas ask to join; leader accepts Farah, rejects Jas", "-", "'Request sent...', pending on My groups; '2 requests waiting'; 'Farah Joiner is now a member of the group.'; 'Jas Joiner's request was rejected.'; Farah under Joined groups; Jas under 'Declined or removed' and told she was declined"),
        t("myGroupsCountsWaitingRequests", "boundary", "Waiting requests counted on My groups", "Leader opens My groups with no request, then with one", "-", "'No requests waiting'; then '1 request waiting' (singular)"),
        t("leaderRemovesAMemberWhoMayAskAgain", "conflict", "Remove a member who asks again", "Leader removes Chloe; Chloe asks to join again", "-", "'Chloe Removed was removed from the group.'; 'Removed from the group' and 'You were removed from this group before.'; new request listed for the leader"),
        t("fullGroupKeepsARequestWaitingUntilAPlaceOpens", "boundary", "Full group waiting list", "Size 2; two requests; accept one; a late student opens it; the member leaves; accept the waiting one", "-", "'2/2 members', full warning, Accept disabled; waiting student told the group is full; late student 'This group is full.' and no join form; after leaving, accept works"),
        t("memberCannotManageOrEditTheGroup", "permission", "Member can't manage", "A member opens the group, /members, /edit and posts /close", "-", "'You're a member of this group.', no leader buttons; each refused with 'Only the group's leader can do that.'"),
        t("groupsDoNotNeedTheCourse", "side-effect", "Groups don't need the course (TD-1)", "A student with no courses creates a group; another with no courses asks to join", "-", "Both succeed"),
        t("studentCancelsAWaitingRequestAndMayAskAgain", "happy", "Cancel a waiting request", "Farah cancels from My groups; opens the group; leader accepts the old request; Farah asks again", "-", "Box text; stays on /groups/mine with 'Request cancelled...'; plain 'Request to join'; leader told 'The student has cancelled this request'; new request pending with 'Cancel request'"),
        t("memberLeavesWithAReasonOnlyTheLeaderSees", "happy", "Leave with a reason", "Ben leaves with a 501-character reason, then with a padded reason; leader opens Manage members; another member opens the group; leader tries to leave", "'  Exams clash with my job  '", "Too long: 'The reason can be at most 500 characters'; then 'You left the group...'; gone from My groups; leader sees '2/4 members' and under 'Members who left' 'Left on' and the trimmed reason; the other member never sees it; leader has no Leave button and is refused"),
    ],
    "AdminAccountsFlowTest": [
        t("adminCreatesAnAdminWhoCanLogInStraightAway", "happy", "Create an admin", "Add an admin with a padded mixed-case email; log in as it", "'  Second.Admin.<n>@StudyBuddy.com '", "'Admin account <lower-case> has been created. It can log in straight away.'; Admin, Active; new admin reaches the admin Home"),
        t("adminChangesAnEmailAndResetsAPassword", "happy", "Change email and password", "Change Ben's email and password; give another student the same email; log in as Ben", "newpassword1", "'The account now logs in with <email>.'; 'The password for <email> has been changed.'; 'Another account already uses <email>.'; old password refused, new one works"),
        t("deactivatedStudentCannotLogInUntilReactivated", "happy", "Deactivate and reactivate", "Deactivate Chloe; she logs in; reactivate; she logs in", "-", "'<email> has been deactivated and can no longer log in.', Inactive; login refused with the login error; '<email> has been reactivated.', Active; login works"),
        t("lastActiveAdminCannotBeDeactivatedOrDeleted", "conflict", "Last active admin protected", "Inside the rolled-back test, every other admin is deactivated; the admin deactivates and deletes their own account", "-", "'This is the last active admin account, so it can't be deactivated.' / '...deleted.'; row stays Active, '(you)'"),
        t("unusedAdminCanBeDeletedAndTheStudentDeleteBoxWarns", "happy", "Delete an unused admin; student delete warning", "Add an admin and delete it; read a student's delete box", "-", "'The account has been deleted.' and gone; the student's box warns that a used student can't be deleted"),
        t("accountListShowsStatusAndUsage", "happy", "Status and usage figures", "Alex takes 2 courses, 1 preference, leads 1 group, sends 1 and receives 1 request; admin opens the list", "-", "'Courses 2 · Preferences 1 · Groups led 1', 'Requests sent 1 · received 1'; admin row '(you)' and '—'"),
        t("changingALoggedInStudentsEmailBreaksTheirSession", "side-effect", "Email change during a session (K2)", "Admin changes Chloe's email while she is logged in; deactivates Dave while he is logged in", "-", "TODAY: Chloe's profile gives 404; Dave's open session still shows his profile"),
    ],
    "MatchingSettingsFlowTest": [
        t("adminSavesSettingsAndBadOnesAreRefused", "happy", "Save settings; bad ones refused", "Save Availability first 0/1/0/0/0; all zero; course -1", "-", "'Settings saved...', Shared free time 100%; 'At least one criterion needs a weight above 0.'; 'Weights can't be negative.'; saved strategy still checked"),
        t("strategyChangesTheOrderStudentsSee", "happy", "Strategy changes the order", "Balanced, then Availability first; Alex searches each time", "Pia (85, 30 min), Quinn (40, 4 h)", "'ranked by Balanced matching.' Pia then Quinn; 'ranked by Availability first matching.' Quinn then Pia; same scores"),
        t("courseFirstWithOnlyTheCourseWeight", "happy", "Course first, course weight only", "Course first 1/0/0/0/0; Alex searches all courses", "-", "'Same course' 100%; 'ranked by Course first matching.'; scores never go up; course mates 100 before Rex (other course, 0)"),
    ],
    "CourseAdminFlowTest": [
        t("adminAddsEditsAndDeletesAnUnusedCourse", "happy", "Add, edit, delete a course", "Add; rename; save a blank code; delete", "'Manual Testing II'", "'<code> has been updated.'; blank refused 'Enter both a course code and a course name.' on the edit form; 'The course has been deleted.' and gone"),
        t("studentsSeeAReadOnlyCourseList", "permission", "Read-only course list for students", "Student opens /courses and /courses?edit={id}; posts a delete", "-", "List without Actions, add form or Delete; delete 403; course still there"),
        t("overLongCourseCodeIsRefused", "boundary", "Over-long course code", "Add a course with a 256-character code", "256 x 'C'", "'Course code and course name can be at most 255 characters.'; nothing added"),
    ],
    # ================================ End to end (2026-11-07) ================================
    "auth.spec": [
        t("student signs up, logs in and logs out", "happy", "Sign up, log in, log out (browser)", "Fill /register, log in, click Log out, open /home", "New student", "'Welcome back', 'Logged in as <email>'; 'You've logged out.'; /home goes to /login"),
        t("the browser blocks a short password and a 7-digit contact number on sign-up", "negative", "Browser blocks bad sign-up values", "Type a 5-character password and 1234567; click Create account; fix the password; fix the number", "'short', '1234567'", "Stays on /register with the fields invalid (tooShort, patternMismatch) and nothing sent; once both are fixed the account is created"),
        t("a deactivated student cannot log in", "happy", "Deactivated student can't log in (browser)", "Fresh admin deactivates a student; the student logs in", "-", "'<email> has been deactivated...'; /login?error with the login error"),
        t("each role only reaches its own pages", "permission", "Each role on its own pages (browser)", "Student opens /admin/accounts; admin opens /matches and /groups; visitor opens /groups", "-", "403, 403, 403; /login"),
        t("the H2 console opens without logging in", "permission", "H2 console open (K15)", "Visitor opens /h2-console; a student does too", "-", "TODAY: the H2 Console's own login form (Connect button) both times, never the app's login page"),
    ],
    "confirmations.spec": [
        t("ending a connection asks first", "happy", "End connection box", "Two students connect; End, Cancel; End, Esc; End, End connection", "-", "Box text; Cancel and Esc keep the buddy; then 'Connection ended...' and the buddy is gone"),
        t("a course that is still used cannot be deleted", "conflict", "Used course can't be deleted (commit)", "A student takes course A; admin deletes A (Cancel first, then Delete course); admin deletes unused course B", "-", "Box text; A refused 'Students, study preferences or study groups still use this course, so it can't be deleted.' and stays; B 'The course has been deleted.' and gone"),
        t("a student who is still used cannot be deleted, an unused admin can", "conflict", "Used student can't be deleted (commit)", "A student leads a group; admin deletes them; admin deletes an extra admin", "-", "Student's box warns; refused 'This student still has match requests, study groups or study buddies, so the account can't be deleted. Deactivate it instead.' and listed; the admin's box has no warning and 'The account has been deleted.'"),
        t("the admin's Edit box changes an email", "happy", "Edit box", "Edit, Close; Edit, type a new email, Save email", "-", "Close changes nothing; 'The account now logs in with <email>.'"),
        t("deleting a study preference asks first", "happy", "Delete preference box", "Delete, Cancel; Delete, Delete preference", "-", "Box text; Cancel keeps it; 'The study preference has been deleted.'"),
        t("closing a group asks first and says how many requests end", "side-effect", "Close group box", "Group with 1 pending request: Close group, Cancel; Close group, Close group", "-", "Box 'Its 1 pending request will end without an answer...'; Cancel keeps Edit group; 'Group closed. 1 pending request ended without an answer.'"),
        t("rejecting, removing and asking again each ask first", "happy", "Reject, remove and ask-again boxes", "Reject Rita; accept and remove Mo (Esc first); Mo asks again", "-", "Each box's text; 'Rita Rejected's request was rejected.'; 'Mo Removed was removed from the group.'; 'You were removed from this group before. Do you want to request to join again?' then 'Request sent...'"),
        t("cancelling a join request asks first", "happy", "Cancel request box", "On My groups: Cancel request, Keep request; Cancel request, Cancel request", "-", "Box text; Keep request changes nothing; stays on /groups/mine with 'Request cancelled...' and the card gone"),
        t("leaving a group asks first and sends the reason", "happy", "Leave group box", "Member: Leave group, Stay in group; Leave group, type a reason, Leave group; leader opens Manage members", "'Clashes with my internship'", "Box text; Stay keeps membership; 'You left the group...'; leader's 'Members who left' shows the name and 'Reason: Clashes with my internship'"),
    ],
    "flash.spec": [
        t("a success banner shows once", "happy", "Success banner once", "Save the profile; reload", "-", "role status, class 'message success', 'Your profile has been updated.'; gone after reload, value kept"),
        t("an error banner is an alert", "negative", "Error banner is an alert", "Add an overlapping slot; remove a course that has a preference", "-", "role alert, class 'message error', the service's message each time"),
    ],
    "forms.spec": [
        t("the group form stops at three slots", "boundary", "Three slots at most (browser)", "Click '+ Add slot' twice; Remove slot 2", "-", "Slot 3 shown, 'Max time slots reached (3)', no '+ Add slot'; after Remove two slots and '+ Add slot' back"),
        t("the browser refuses a negative matching weight", "negative", "Browser refuses -1 weight", "Type -1 in Same course; Save settings", "-1", "Nothing saved; the field is rangeUnderflow"),
        t("the message and reason boxes stop at their limits", "boundary", "Text box limits", "Type 300 characters into a match message, 600 into a leave reason", "-", "The boxes keep 255 and 500 characters"),
        t("a full group's join and accept buttons are switched off", "boundary", "Full group buttons off", "Size 2, two requests, accept one; a late student opens the group", "-", "Full warning; the waiting student's Accept is disabled; the late student's 'Request to join' is disabled with 'This group is full.'"),
    ],
    "matches.spec": [
        t("the request just sent is framed and labelled", "happy", "New request framed", "Send Ben a message; send Cat 'no'", "'Want to study together?', 'no'", "Banner; exactly one .match-card-new with the name, message and 'Waiting for a reply'; 'no' shown"),
        t("the labels on match cards open the right page", "happy", "Labels open the right page", "Ben is a buddy, Cat has sent a request; click each label", "-", "No Send button; 'You're already study buddies' opens /connections; 'They've sent you a request' opens /match-requests; Cat sees 'Request sent'"),
        t("accepted and declined requests show their labels", "happy", "Accepted and Declined labels", "Accept Ben's request, decline Cat's", "-", "Banners; 'Accepted' (status-label-accepted), 'Declined' (status-label-rejected); no Accept buttons left; Cat sees 'Declined'"),
    ],
    "roles.spec": [
        t("a student's Home links to every student page", "happy", "Student Home links", "Click each Home link, then the top bar's Study Buddy Matcher", "-", "Each opens its page (h1) and the top bar leads back to /home; no admin links"),
        t("an admin's Home links to every admin page", "happy", "Admin Home links", "Click each Home link, then the top bar", "-", "User accounts, Courses, Matching settings open; no student links"),
    ],
    "settings.spec": [
        t("the strategy changes the order students see", "happy", "Strategy changes the order (browser)", "Balanced, Alex searches; Availability first, Alex searches", "Pia (30 min, all else matches), Quinn (4 h only)", "'ranked by Balanced matching.' Pia, Quinn; 'ranked by Availability first matching.' Quinn, Pia; settings put back afterwards"),
        t("course first with only the course weight scores every course mate 100", "happy", "Course first, course weight only (browser)", "Course first 1/0/0/0/0; Alex searches the course", "-", "Same course 100%; 'ranked by Course first matching.'; both cards 100; settings put back afterwards"),
    ],
    "layout.spec": [
        t("login and sign-up fit a phone screen", "happy", "Login and sign-up on a phone", "Open /login and /register at 375x667", "-", "No sideways scroll, every button on screen, form above the illustration; screenshots attached"),
        t("student pages fit a phone screen", "happy", "Student pages on a phone", "Open every student page with data; open the End connection box", "-", "No sideways scroll; buttons on screen; Home link and Log out in view; stacked tables labelled; the box fits; screenshots attached"),
        t("study group pages fit a phone screen", "happy", "Group pages on a phone", "Open Browse, My groups, create, group page, Manage members, edit", "-", "As above"),
        t("admin pages fit a phone screen", "happy", "Admin pages on a phone", "Open Home, User accounts, Matching settings, Courses", "-", "As above; the accounts table is labelled cards"),
        t("empty student pages fit a phone screen", "happy", "Empty pages on a phone", "A new student opens their pages before adding anything", "-", "As above"),
    ],
    "a11y.spec": [
        t("login and sign-up pages pass axe", "happy", "Login and sign-up accessible", "axe on /login and /register", "-", "No violation outside a11y-baseline.json; results attached"),
        t("student pages pass axe", "happy", "Student pages accessible", "axe on every student page with data", "-", "As above"),
        t("study group pages pass axe", "happy", "Group pages accessible", "axe on every group page with data", "-", "As above"),
        t("admin pages pass axe", "happy", "Admin pages accessible", "axe on every admin page", "-", "As above"),
        t("a waiting join request passes axe", "happy", "Waiting request accessible", "A student with a pending request: axe on My groups and the group page", "-", "As above; no violation at all (A11Y-1, the Cancel request label, fixed 2026-11-07)"),
    ],
}

# Manual cases that an automated test replaces without naming them in its comment
EXTRA_REPLACED = {"MAN-27": ["DataSeedersTest#exactSeedSizes"]}

# --------------------------------------------------------------------------------------
# Manual (browser) test cases
# --------------------------------------------------------------------------------------
MANUAL_BANNER = ("MANUAL CASES (MAN-nn). Since 2026-11-07, rows marked Automated are replaced by the automated tests named in "
                 "'Test class and method' (their status is those tests' last run; the steps stay as documentation, and a "
                 "result recorded by hand is kept under 'Manual result (history)'). Rows still marked Manual need a person. "
                 "Restart the app before each full manual run. The database is in memory, so restarting resets it to the "
                 "seeded data; a case marked 'Do not restart' must follow the case it names in the same app run.")
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


def m(mid, ids, kind, title, pre, steps, data, expected, notes="", batch="New", retired=""):
    """A manual case. retired: the reason it can no longer be run; it then keeps its ID (never reused) and
    its row, with Status 'Retired', but is left out of Traceability and every Summary count."""
    return {"id": mid, "ids": ids, "type": kind, "title": title, "pre": pre, "steps": steps,
            "data": data, "expected": expected, "notes": notes, "batch": batch, "retired": retired}


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
      "Ben Koh's name is a link to his public profile page (see MAN-45), which doesn't show the number either."),
    m("MAN-08", "S7, REQ-1, TD-5", "happy", "Send a buddy request with a message", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/matches, choose IS442, click 'Search'.",
               "On the first card that is NOT Ben Koh, Chloe Lim or Daniel Ng, type 'Want to study IS442 together?' in the 'Message (optional)' box and click 'Send request'.",
               "Go back to http://localhost:8080/matches, choose IS442, click 'Search', and look at Ben Koh's card (still first, 92 / 100). Click its 'You're already study buddies' label."),
      "Message 'Want to study IS442 together?'",
      "Step 3: the Match requests page shows a green banner 'Request sent to <name>. Their reply will show under Sent below.' and the student under 'Sent', framed in green, with the message and the label 'Waiting for a reply'. Step 4: Ben Koh's card has no 'Send request' button and no message box; it shows 'You're already study buddies', which opens the Study buddies page (Ben Koh listed).",
      "Step 4 changed on 2026-10-30 (TD-5): before, the card had a Send button that gave 'You're already study buddies. Find them under Study buddies.'"),
    m("MAN-09", "S8, REQ-3, REQ-4", "happy", "Accept a request; contact number appears", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/match-requests.",
               "Under 'Received', on Chloe Lim's request click 'Accept'.",
               "Open http://localhost:8080/connections."),
      "Chloe Lim's number 81000000",
      "Step 3: 'Request accepted. You're now study buddies: their contact number is on the Study buddies page.' and the request shows the label 'Accepted' without buttons; 81000000 is not on this page. Step 4: Chloe Lim is listed with 81000000.",
      "Updated 2026-11-07: the status is now shown as a label ('Accepted'), teammate commit 5cd8fa1."),
    m("MAN-10", "S8, REQ-2", "happy", "Decline a request", APP_PRE,
      numbered(login("daniel.ng@studybuddy.com"), "Open http://localhost:8080/match-requests.",
               "Under 'Received', on Alex Tan's request click 'Decline'.",
               "Log out, log in as student@studybuddy.com and open http://localhost:8080/match-requests."),
      "daniel.ng@studybuddy.com",
      "Step 3: 'Request declined.'; the request shows the label 'Declined' and no buttons. Step 4: under 'Sent', the request to Daniel Ng shows 'Declined'. /connections does not list Daniel Ng.",
      "Updated 2026-11-07: the status is now shown as a label ('Declined' for REJECTED), teammate commit 5cd8fa1."),
    m("MAN-11", "S9, REQ-5", "happy", "End a connection", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/connections.",
               "On Ben Koh's row click 'End'; in the box click 'End connection'."),
      "Ben Koh (92345678)",
      "Before step 3 Ben Koh is listed with 92345678. After: 'Connection ended. You can no longer see each other's contact numbers.' and Ben Koh is no longer listed."),
    m("MAN-12", "L1, L2, GRP-1, GRP-7, TD-2", "happy", "Create a study group", APP_PRE,
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
    m("MAN-28", "ACC-8, K1", "boundary", "Login email in another letter case", APP_PRE,
      numbered("Open http://localhost:8080/login, type STUDENT@StudyBuddy.com and password123, click 'Log in'."),
      "STUDENT@StudyBuddy.com",
      "The Home page shows 'Welcome back' and 'Logged in as student@studybuddy.com' (the stored, lower-case email).",
      "K1 fixed 2026-10-28; matches AuthServiceKnownBugTest (now a regression test)."),
    m("MAN-29", "A1, K2, ACC-6", "side-effect", "Changing a logged-in student's email", APP_PRE + " Two browsers (or a normal and a private window).",
      numbered("Browser 1: " + login("chloe.lim@studybuddy.com") + " Open http://localhost:8080/student/profile.",
               "Browser 2: " + login("admin@studybuddy.com") + " On chloe.lim@studybuddy.com click 'Edit', set New email chloe.new@studybuddy.com, click 'Save email'.",
               "Browser 1: reload http://localhost:8080/student/profile."),
      "chloe.new@studybuddy.com",
      "TODAY: step 3 shows an error page (404, 'No student account for chloe.lim@studybuddy.com') until Chloe logs out and back in with the new email.",
      "Pins current behaviour K2 (suspected bug)."),
    m("MAN-30", "CRS-1, K4", "boundary", "Over-long course code", APP_PRE,
      numbered(login("admin@studybuddy.com"), "Open http://localhost:8080/courses.",
               "Paste 256 characters (e.g. 256 letter C) into Course code, type 'Too long' in Course name, click 'Add course'."),
      "256-character code",
      "Back on /courses with the error 'Course code and course name can be at most 255 characters.'; no new course is listed.",
      "K4 fixed 2026-10-28; matches CourseControllerKnownBugTest (now regression tests)."),
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
    m("MAN-34", "L3, GRP-6, TD-4", "side-effect", "Closing a group ends its pending requests", APP_PRE + " Needs the seeded pending requests of Farhan Ismail and Jasmine Teo.",
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
    m("MAN-36", "L5, GRP-4, GRP-5, TD-3", "boundary", "A full group keeps requests waiting until a place opens", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/groups/mine, on 'IS442 OOP Exam Prep' click 'View', then 'Manage members'.",
               "Under 'Join requests' click 'Accept' for Farhan Ismail.",
               LOGOUT + " " + login("jasmine.teo@studybuddy.com") + " Open http://localhost:8080/groups/mine.",
               "Under 'Pending group join requests', on 'IS442 OOP Exam Prep' click 'View'.",
               LOGOUT + " " + login("student@studybuddy.com") + " Open the IS442 OOP Exam Prep 'Manage members' page again (as in step 2). Under 'Members' click 'Remove' for Ben Koh, then 'Remove member' in the box.",
               "Click 'Accept' for Jasmine Teo."),
      "Seeded IS442 OOP Exam Prep, 3/4 with Farhan and Jasmine pending",
      "Step 3: 'Farhan Ismail is now a member of the group.', the count shows '4/4 members', the warning 'Group is full. Remove a member to accept new members.' appears and Jasmine Teo's 'Accept' is greyed out (can't be clicked). Step 4: under 'Pending group join requests' the IS442 OOP Exam Prep card shows 'Full' and 'The group is full right now. The leader can still accept you if a place opens up.' Step 5: the group page says 'Request sent. Waiting for the group's leader to respond.' and, under it, 'The group is full right now. The leader can still accept you if a place opens up.' Step 6: 'Ben Koh was removed from the group.', '3/4 members', Jasmine's 'Accept' works again. Step 7: 'Jasmine Teo is now a member of the group.' and '4/4 members'.", "Step 5 added on 2026-10-28 (teammate commit 0385418); TD-3.", BATCH_B),
    m("MAN-37", "L3, GRP-6, TD-4", "conflict", "A closed group can't be edited or changed, only removed from My groups", APP_PRE,
      numbered(login("emily.wong@studybuddy.com"), "Open http://localhost:8080/groups/mine. Under 'Groups I lead', on 'ECON101 Midterm Revision' click 'View'. Note the number after /groups/ in the address bar (6 on a fresh start).",
               "Type http://localhost:8080/groups/6/edit and press Enter.",
               "Type http://localhost:8080/groups/6/members and press Enter.",
               "Open http://localhost:8080/groups/mine and click 'Remove' on 'ECON101 Midterm Revision'."),
      "Emily Wong, leader of the seeded closed ECON101 group (group 6)",
      "Step 2: 'This group is closed, so it isn't taking new members.' and 'You lead this group.'; no Edit group, Manage members or Close group buttons. Step 3: back on the group page with the error 'This group is closed, so it can't be edited.' Step 4: Manage members shows 'This group is closed, so its members can't be changed.', no 'Join requests' section, and Emily Wong, Grace Lee, Isaac Chua with no Remove buttons. Step 5: 'Removed from My groups.' and ECON101 Midterm Revision is gone from 'Groups I lead'; 'CS201 DSA Problem Solvers' is still under 'Joined groups'.", batch=BATCH_B),
    m("MAN-38", "L1, GRP-4, TD-1", "side-effect", "Groups don't need the course (team decision TD-1)", APP_PRE,
      numbered(login("ben.koh@studybuddy.com"), "Open http://localhost:8080/student/profile. Under 'Your courses' click 'Remove' next to CS201.",
               "Open http://localhost:8080/groups/mine.",
               "Open http://localhost:8080/groups, open 'IS112 SQL Practice' and click 'Request to join'.",
               "Open http://localhost:8080/groups and click '+ Create a group'. Group name 'K8 Check'; Course 'ECON101 Principles of Economics'; Study goal 'Exam preparation'; Study mode 'Online'; Maximum group size 3; Slot 1: Day Monday, From 10:00, To 12:00. Click 'Create group'."),
      "Ben Koh takes IS442, IS216, CS201 (no CS201 preference) and leads the CS201 group",
      "Step 2: 'The course has been removed.' Step 3: 'CS201 DSA Problem Solvers' is still under 'Groups I lead'. Step 4: 'Request sent. The group's leader will review it.' although Ben doesn't take IS112. Step 5: 'Group created. Other students can now find it on Study groups and ask to join.' although Ben doesn't take ECON101.",
      "Intended (team decision TD-1, was K8): a leader or member need not take the course. Matches the SGS and GMS TD-1 tests.", BATCH_B),

    # ================================ NEW (Batch C) ================================
    m("MAN-39", "SEC-3, K15", "permission", "H2 console opens without logging in (dev only)", APP_PRE + " Logged out.",
      numbered("Open http://localhost:8080/h2-console in the browser.",
               login("student@studybuddy.com") + " Then open http://localhost:8080/h2-console again."),
      "-",
      "TODAY: both times the H2 Console's own login form opens (with a 'Connect' button); the browser is NOT sent to the Study Buddy login page and no 403 is shown. Don't click 'Connect': the case only checks that the page is open to anyone.",
      "Pins current behaviour SEC-3 / K15 (development setting). Turn the console off before any real deployment; matches SCM (h2Console...).", BATCH_C),
    m("MAN-40", "S1, PRF-1, K4", "boundary", "Over-long profile name", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/student/profile.",
               "Replace Name with 256 characters (e.g. paste 256 letter A) and click 'Save details'."),
      "256-character name",
      "Back on the profile page with the error 'Name, school and programme can be at most 255 characters.'; the name is unchanged.",
      "K4 fixed 2026-10-28; matches StudentProfileControllerKnownBugTest (now a regression test).", BATCH_C),
    m("MAN-41", "S7, S8, K12", "boundary", "A message that is just 'no' is shown", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/matches, choose IS442, click 'Search'.",
               "On the first card that is NOT Ben Koh, Chloe Lim or Daniel Ng, type no in the 'Message (optional)' box and click 'Send request'."),
      "Message 'no'",
      "'Request sent to <name>. Their reply will show under Sent below.' and, under 'Sent', the new request shows 'Waiting for a reply' and the message 'no'. The same holds for 'false' and 'off'.",
      "K12 fixed 2026-10-28; matches MatchRequestControllerKnownBugTest (now a regression test). Compare MAN-08.", BATCH_C),

    # ================================ NEW (Batch D) ================================
    m("MAN-42", "S3, S7, K17, TD-5", "happy", "Matches keep buddies and pending requests, with a label instead of Send",
      APP_PRE + " Seeded: Ben Koh is already Alex's study buddy; Chloe Lim -> Alex and Alex -> Daniel Ng are pending requests.",
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/matches, choose IS442 in Course and click 'Search'.",
               "On Ben Koh's card click 'You're already study buddies'.",
               "Open http://localhost:8080/matches, choose 'All my courses' in Course and click 'Search'. Press Ctrl+F and find Chloe Lim's card, then Daniel Ng's card.",
               "On Chloe Lim's card click 'They've sent you a request'."),
      "Seeded data",
      "Step 2: Ben Koh is still listed first (92 / 100); his card shows 'You're already study buddies' and has no 'Send request' button. Step 3: the Study buddies page opens. Step 4: Chloe Lim's card shows 'They've sent you a request' and Daniel Ng's card 'Request sent', neither with a 'Send request' button; the other cards still have one. Step 5: the Match requests page opens, with Chloe Lim's request under 'Received'.",
      "TD-5 (team decision 2026-10-30, was K17). Matches MatchingControllerTest (buddyStatusesComeFromConnectionsAndPendingRequests, relatedStudentsShowALabelInsteadOfTheSendButton) and MatchingServiceKnownBugTest (still listed).", BATCH_D),
    m("MAN-43", "A3, A4, D2, MAT-2", "happy", "Course first with only the course weight", APP_PRE,
      numbered(login("admin@studybuddy.com"), "Open http://localhost:8080/admin/matching.",
               "Choose 'Course first'. Set Same course 1, Shared free time 0, Study mode (in person / online) 0, Study goal 0, Group size (one-to-one / small group) 0. Click 'Save settings'.",
               LOGOUT + " " + login("student@studybuddy.com") + " Open http://localhost:8080/matches, choose 'All my courses' in Course and click 'Search'."),
      "Weights 1/0/0/0/0, Course first",
      "Step 3: 'Settings saved. New searches use them straight away.' and the Same course row shows 100% under Current share. Step 4: the summary says 'ranked by Course first matching.'; every card for one of Alex's courses (IS111, IS112, IS442, CS201) shows 100 / 100 and comes before every card showing 0 / 100; Chloe Lim and Daniel Ng (no preference for Alex's courses) show 0 / 100.",
      "Restart afterwards (or set the settings back) before other matching cases: the settings stay until the app stops.", BATCH_D),

    # ================================ NEW (Batch E) ================================
    m("MAN-44", "S7, REQ-1, K13", "conflict", "Duplicate-request messages show internal ids",
      APP_PRE + " Seeded: Alex -> Daniel Ng and Chloe Lim -> Alex are pending requests.",
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/matches, choose 'All my courses' in Course and click 'Search'.",
               "On Daniel Ng's card click 'Send request'.",
               "Go back to http://localhost:8080/matches, choose 'All my courses', click 'Search', and on Chloe Lim's card click 'Send request'."),
      "Seeded data",
      "Step 3: the Match requests page shows the error 'Student <n> already has a pending request to student <m>', with two internal id numbers (on a fresh start Alex is 2 and Daniel 5). Step 4: 'Student <m> has already sent you a request. Accept it instead of sending a new one' (Chloe's id, 4 on a fresh start). No new request is listed under 'Sent'.",
      "K13 (c): the ids mean nothing to the student. The ids depend on seeding order; compare the wording, not the numbers.", BATCH_E,
      retired="TD-5: the Send button is replaced by a label for pending requests, so K13's id-filled message is no longer reachable from the page, only by a hand-made request."),

    # ================================ Sync (2026-10-28) ================================
    m("MAN-45", "S6, PRF-3, REQ-4, D3, TD-6", "happy", "Another student's public profile page", APP_PRE,
      numbered(login("student@studybuddy.com"), "Open http://localhost:8080/matches, choose IS442 in Course and click 'Search'.",
               "Click the name 'Ben Koh' on the first card. Press Ctrl+F and search for 92345678, then for ben.koh@studybuddy.com. Note the number at the end of the address (3 on a fresh start).",
               "Type http://localhost:8080/students/99999 in the address bar and press Enter.",
               LOGOUT + " " + login("admin@studybuddy.com") + " Open http://localhost:8080/students/3 (the number from step 3).",
               "Open http://localhost:8080/admin/accounts and click 'Deactivate' on ben.koh@studybuddy.com.",
               LOGOUT + " " + login("student@studybuddy.com") + " Open http://localhost:8080/students/3 (the number from step 3)."),
      "Ben Koh (92345678)",
      "Step 3: the page shows 'Ben Koh', 'Information Systems, Year 3 · School of Computing and Information Systems', the note 'Contact numbers are only shared between study buddies, once a match request is accepted. See yours under Study buddies.', Courses IS442, IS216 and CS201, and one study preference: IS442 Object Oriented Programming, Exam preparation, In person or online, One-to-one, Free 'Wed 19:00-21:00, Thu 18:00-20:00'. Neither 92345678 nor the email is found. Step 4: back on Find study buddies with 'That student's profile no longer exists.' Step 5: an error page with status 403 (Forbidden). Step 6: 'ben.koh@studybuddy.com has been deactivated and can no longer log in.' Step 7: back on Find study buddies with 'That student's profile no longer exists.' (a deactivated student is treated like an unknown id).",
      "Added for teammate commit 971c1a0. Steps 6-7 added on 2026-10-30 for TD-6 (was N-S1).", SYNC),
    m("MAN-46", "S1, PRF-1, K3, K4", "boundary", "Sign-up with an over-long school keeps the form", APP_PRE + " Logged out.",
      numbered("Open http://localhost:8080/register.",
               "Email: long.school@example.com; Password: password123; Name: Long School; School: paste 256 characters (e.g. 256 letter S); Programme: Information Systems; Year of study: 2; Contact number: 98765432.",
               "Click 'Create account'.",
               "Open http://localhost:8080/login and try to log in as long.school@example.com with password123."),
      "256-character school",
      "Step 3: the sign-up form comes back with 'Name, school, programme and email can be at most 255 characters.'; Email, Name, School (all 256 characters), Programme, Year of study and Contact number are still filled in; Password is empty. Step 4: 'That email and password don't match an active account. Check them and try again.' (no account was created).",
      "GAP-K3REG fixed 2026-10-28; matches AuthControllerTest (overLongFieldShowsItsOwnMessageAndKeepsTheForm). Before the fix this showed 'Another account already uses that email.'", SYNC),
    m("MAN-47", "L4, GRP-4, GRP-8", "happy", "A student cancels a join request and asks again", APP_PRE + " Needs the seeded pending requests of Farhan Ismail and Jasmine Teo.",
      numbered(login("farhan.ismail@studybuddy.com") + " Open http://localhost:8080/groups/mine.",
               "Under 'Pending group join requests', on 'IS442 OOP Exam Prep' click 'Cancel request' and read the box, then click 'Keep request'.",
               "Click 'Cancel request' again, then 'Cancel request' in the box.",
               "Open http://localhost:8080/groups, find 'IS442 OOP Exam Prep' and click 'View'.",
               LOGOUT + " " + login("student@studybuddy.com") + " Open http://localhost:8080/groups/mine, on 'IS442 OOP Exam Prep' click 'View', then 'Manage members'.",
               LOGOUT + " " + login("farhan.ismail@studybuddy.com") + " Open the IS442 OOP Exam Prep page (as in step 4) and click 'Request to join'."),
      "Seeded IS442 OOP Exam Prep (Farhan, Jasmine pending)",
      "Step 2: the box says 'Cancel your request to join IS442 OOP Exam Prep? If you ask again later, it will count as a new request.'; 'Keep request' closes it and nothing changes. Step 3: 'Request cancelled. You can ask to join again later.'; the card is gone from 'Pending group join requests' and does not appear under 'Declined or removed'. Step 4: a plain 'Request to join' button, with no 'declined' or 'removed' message. Step 5: only Jasmine Teo is under 'Join requests'. Step 6: 'Request sent. The group's leader will review it.' and 'Request sent. Waiting for the group's leader to respond.' with a 'Cancel request' button.", batch=CANCEL),
    m("MAN-48", "L4, GRP-2, GRP-4, GRP-9", "happy", "A member leaves with a reason and the leader sees it", APP_PRE,
      numbered(login("ben.koh@studybuddy.com") + " Open http://localhost:8080/groups/mine. Under 'Joined groups', on 'IS442 OOP Exam Prep' click 'View'.",
               "Click 'Leave group' and read the box. Type 'My timetable changed' in Reason, then click 'Leave group' in the box.",
               "Open http://localhost:8080/groups/mine.",
               LOGOUT + " " + login("student@studybuddy.com") + " Open http://localhost:8080/groups/mine, on 'IS442 OOP Exam Prep' click 'View', then 'Manage members'.",
               "Click 'View' on the group's page and look for a 'Leave group' button."),
      "Ben Koh, a member of the seeded IS442 OOP Exam Prep (3/4)",
      "Step 2: the box says 'Leave IS442 OOP Exam Prep? You can ask to join again later.' with a Reason box marked '(optional, only the leader sees it)'; then 'You left the group. You can ask to join again later.' and a plain 'Request to join' button. Step 3: IS442 OOP Exam Prep is no longer under 'Joined groups' or 'Declined or removed'. Step 4: '2/4 members'; Ben Koh is not under 'Members'; under 'Members who left' Ben Koh shows 'Left on <today>' and 'Reason: My timetable changed'. Step 5: the leader has no 'Leave group' button.", batch=LEAVE),

    # ================================ Residual manual checklist (2026-11-07) ================================
    # Everything a test can check is automated; these need a person's judgement.
    m("MAN-49", "UI-3", "happy", "The phone layout on real phones", APP_PRE + " A real iPhone (Safari) and a real Android phone (Chrome) on the same network; open http://<this computer's address>:8080.",
      numbered(login("student@studybuddy.com") + " on each phone.", "Open every page from Home: My profile, Study preferences, Find study buddies, Match requests, Study buddies, Study groups, My groups, a group page, Manage members, Create a group.",
               "Type into a form (the on-screen keyboard opens) and scroll while it is open."),
      "-", "Every page reads comfortably: no squeezed or cut-off text, tables shown as cards are easy to scan, buttons are easy to tap without hitting the wrong one, and the on-screen keyboard never hides the field being typed in.",
      "Playwright checks the 375x667 layout in Chromium (ELY); real phones, Safari and touch need a person.", RESIDUAL),
    m("MAN-50", "UI-1", "happy", "Confirmation boxes on real phones", "As MAN-49.",
      numbered("On a phone, open Study buddies (with a buddy), tap End, then tap outside the box.", "Tap End again and use the phone's Back gesture.", "Tap End again and tap End connection."),
      "-", "The box opens centred and readable; tapping outside or Back closes it without ending anything; End connection ends it.",
      "ECF checks the boxes in Chromium; Safari's popover support and the Back gesture need a person.", RESIDUAL),
    m("MAN-51", "UI-1, UI-2", "happy", "Wording and tone of messages", APP_RUN,
      numbered("Go through the main flows (sign up, add a preference, send and answer a request, create and join a group, close a group, admin edits an account) and read every banner, error and confirmation box."),
      "-", "Each message says what happened or what to do next, in plain words, without internal ids or jargon, and in the same voice across pages.",
      "The automated tests check the exact text; whether it reads well needs a person.", RESIDUAL),
    m("MAN-52", "UI-2, S8", "happy", "Colours and emphasis", APP_RUN,
      numbered("Look at a green success banner, a red error banner, the status labels on Match requests (waiting, accepted, declined), the green frame on a just-sent request and a full group's red member count."),
      "-", "Each stands out enough to be noticed and the colours mean the same thing everywhere; nothing relies on colour alone (the words say it too).",
      "axe checks contrast (EAX); whether emphasis works needs a person.", RESIDUAL),
    m("MAN-53", "D3", "happy", "First-time user walk-through", APP_PRE + " Someone who has not used the app before.",
      numbered("Ask them to sign up, set a preference, find a buddy and send a request, then join a study group, without help."),
      "-", "They finish without getting lost. There is no menu bar: note every time they need Home to move on.",
      "Usability can't be automated.", RESIDUAL),
    m("MAN-54", "UI-5", "happy", "Screen reader spot check", APP_RUN + " NVDA (Windows) or VoiceOver (Mac / iPhone).",
      numbered(login("student@studybuddy.com") + " with the screen reader on.", "Open Find study buddies and a study group page; listen to a match card, the member count and the Request to join / Cancel request buttons."),
      "-", "Cards read in a sensible order; the member count says ', places left' or ', full'; each button says what it does (Cancel request is read as 'Cancel request to join <group>', A11Y-1 fixed).",
      "axe finds rule breaks (EAX); whether it makes sense when heard needs a person.", RESIDUAL),
    m("MAN-55", "UI-1, UI-5", "happy", "Keyboard only", APP_RUN,
      numbered("Without a mouse, log in, open Study buddies and End a connection (Tab, Enter, Esc), then create a study group with two slots."),
      "-", "The focus outline is always visible; focus moves into a confirmation box when it opens and back to its button when it closes; every action can be done from the keyboard.",
      "Focus visibility and order need a person.", RESIDUAL),
    m("MAN-56", "UI-3", "happy", "Review the e2e screenshots", "After npx playwright test in e2e/.",
      numbered("Run npx playwright show-report in e2e/.", "Open the phone layout tests (layout.spec.js) and look at every attached page screenshot."),
      "-", "Nothing looks broken, overlapping or oddly spaced on any page.",
      "ELY checks measurable layout rules; how it looks needs a person.", RESIDUAL),
    m("MAN-57", "D1", "happy", "Demo data makes sense for the presentation", APP_PRE,
      numbered(login("admin@studybuddy.com") + " Read User accounts and Courses; log in as a few seeded students and read their matches and groups."),
      "-", "Names, schools, programmes, timetables and groups look realistic, and the demo shows good and poor matches.",
      "DSD checks the counts; plausibility needs a person.", RESIDUAL),
]

# --------------------------------------------------------------------------------------
# Gaps and bugs
# --------------------------------------------------------------------------------------
GAPS = [
    ("K1", "Fixed (2026-10-28)", "Login was case-sensitive although sign-up and the admin page store emails lower-cased. AuthService now looks up the trimmed, lower-cased email (the same rule as AccountCredentials.normaliseEmail, without its format check).", "AuthService.java:28-31", "Fixed; known-bug test kept as a regression test", "AUK-01, MAN-28", "Fixed (2026-10-28)."),
    ("K2", "Candidate bug", "Changing a logged-in student's email breaks their session (404s); deactivation and password reset don't end open sessions.", "AccountManagementService.java:120", "(a) Pin + manual case", "AMS (oldEmailNoLongerFindsStudentAfterUpdate), MAN-29", "Pinned."),
    ("K3", "Candidate bug (narrowed 2026-10-28)", "A DataIntegrityViolationException at save still shows 'Another account already uses that email' (sign-up, create admin, change email). Since 2026-10-28 an over-long value is checked first and gets its own message (GAP-K3REG), so this message now comes only from a real database refusal such as two saves of the same email at once.", "AuthController.java; AccountManagementController.java", "(a) Pin the duplicate-race message", "AMC (createAdminDatabaseRefusal..., updateEmailDatabaseRefusal...), AUC (registerDatabaseRefusal...)", "Pinned (duplicate race)."),
    ("K4", "Fixed (2026-10-28)", "Inputs over 255 characters reached the database and gave a 500 (course code and name, profile name / school / programme, match message). The three controllers now check the length first and show 'Course code and course name can be at most 255 characters.', 'Name, school and programme can be at most 255 characters.' and 'Messages can be at most 255 characters.' No database error is caught. The services still rely on the database limit. Study groups were never affected (StudyGroupForm's @Size). Sign-up and the admin account forms were fixed the same day (GAP-K3REG).", "CourseController.java; StudentProfileController.java; MatchRequestController.java", "Fixed in the controllers; known-bug tests kept as regression tests; service tests note the database limit", "CRK-01, CRK-02, SPK-01, MRK-02, CRS (courseCodeOver255...), SPG (profileValueOver255...), MRG (messageOver255...), SGS (nameOver255..., descriptionOver1000...), MAN-30, MAN-40", "Fixed (2026-10-28)."),
    ("K5", "Candidate bug", "Duplicate course codes allowed; CourseService has no @Transactional.", "CourseService.java:20; Course.java:21", "(c) List only", "-", "Listed."),
    ("K6", "Fixed (2026-10-28)", "A match request could be sent to a deactivated student by direct POST. MatchRequestService now treats a deactivated recipient as not found, so nothing is saved and the page shows 'That student no longer exists.'", "MatchRequestService.java:79-82", "Fixed; known-bug test kept as a regression test", "MRQ-01", "Fixed (2026-10-28)."),
    ("K7", "Partly resolved (2026-10-25)", "getPublicProfile now has a page: GET /students/{id} (S6). getRequestById still has no route.", "PublicProfileController.java; MatchRequestService.java:115", "Recorded", "PPC, PPG, PPL, CNV (publicProfilePageNeverShowsContactNumbers), MAN-45", "S6 done; getRequestById open."),
    ("K8", "Resolved: team decision (2026-10-28)", "Now TD-1: a leader or member need not take the group's course (a senior who already took it may lead a group to help juniors). Creating or joining a group without the course, and dropping the course while leading, are intended.", "StudentProfileService.java:113; StudyGroupService.java:68; StudyGroup.java:446", "Intended; the tests now say so (TD-1)", "SGS (createGroupDoesNotRequireTakingTheCourse, leaderCanDropTheCourseAndStillLeadTheGroup), GMS (joiningDoesNotRequireTakingTheCourse), MAN-38", "Resolved: team decision (2026-10-28)."),
    ("K9", "Candidate bug", "Preference slots are looser than group slots: any times (not just 08:00-23:00 in 15-minute steps; the page's dropdowns hide this) and any number of slots (groups: 1-3).", "StudyPreferenceService.java:93-104", "(c) List only (agreed 2026-10-24)", "-", "Listed."),
    ("K10", "Candidate bug", "/register doesn't strip spaces from the contact number; profile update does (in the controller). The service itself never strips it, so ' 91234567 ' is refused there.", "AuthController.java:57; StudentProfileService.java:80,93", "(a) Pin", "AUC (contactNumberIsPassedOnAsTyped), SPG (contactNumberWithSpacesIsRefusedByTheService)", "Pinned (controller and service)."),
    ("K11", "Candidate bug", "A non-numeric year of study gives a raw 400.", "AuthController.java:53; StudentProfileController.java:54", "(a) Pin", "AUC (nonNumericYearOfStudyIsBadRequest), SPC (nonNumericYearOfStudyIsBadRequest)", "Pinned (sign-up and profile)."),
    ("K12", "Fixed (2026-10-28), display", "th:if=\"${req.message}\" hid a message that was just 'no', 'false' or 'off'. The template now shows any non-blank message (th:if checks not null and not blank), so an empty or spaces-only message no longer shows as an empty line. Storage is unchanged: an empty message is still stored as '' (list only).", "match-requests.html:27,53", "Fixed (display); known-bug test kept as a regression test; empty-message storage (c)", "MRK-01, MAN-41", "Fixed (2026-10-28); empty-message storage listed."),
    ("K13", "Candidate bug (not reachable from the page since 2026-10-30)", "The duplicate-request errors show internal student ids: 'Student 2 already has a pending request to student 5', 'Student 4 has already sent you a request. Accept it instead of sending a new one'. Since TD-5 (2026-10-30) the Send button is replaced by a label for pending requests, so this message is no longer reachable from the page, only by a hand-made request; MAN-44 retired.", "DuplicateMatchRequestException.java:24-34", "(c) List only", "MRC (duplicateRequestShowsServiceMessage), MAN-44 (retired)", "Listed; not reachable from the page."),
    ("K14", "Candidate bug", "No database constraint for one pending request or one connection per pair (two requests at the same moment could both pass the checks).", "MatchRequestService.java:90-104; StudyBuddyConnectionService.java:46-50; StudyBuddyConnection.java:15", "(c) List only (agreed 2026-10-24)", "-", "Listed."),
    ("K15", "Candidate bug", "H2 console open to everyone with CSRF off and frame options off (its own filter chain, permitAll).", "SecurityConfig.java:26-35; application.properties:15", "(a) Pin (SEC-3) + deployment note GAP-DEP1", "SCM (h2ConsoleIsOpenWithoutLoginOrCsrf, h2ConsoleRelaxesFrameOptionsOnlyForItself), MAN-39", "Pinned."),
    ("K16", "Candidate bug", "No @ControllerAdvice or custom error pages: an exception a controller doesn't catch becomes a bare 404 (StudentNotFoundException has @ResponseStatus) or 500.", "-", "(a) Pin representative 404s; (c) list 'no custom pages'", "SPC, MTC (missingStudentAccountIsNotFound), MAN-26", "Pinned / listed."),
    ("K17", "Fixed (2026-10-30): team decision TD-5", "Find study buddies listed buddies and students with a pending request, and 'Send request' then only gave an error. Team decision TD-5: they stay listed (findMatches is unchanged), but their card shows a label instead of the button: 'You're already study buddies' (links to Study buddies), 'Request sent' or 'They've sent you a request' (link to Match requests). A rejected request keeps the button.", "MatchingController.java; matches.html; BuddyStatus.java", "Fixed by TD-5; the two known-bug tests rewritten as regression tests (same IDs)", "MSK-01, MSK-02, MTC (buddyStatusesComeFromConnectionsAndPendingRequests, relatedStudentsShowALabelInsteadOfTheSendButton), MAN-08, MAN-42", "Fixed (2026-10-30)."),
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
    ("PLAN-K4b", "Built (Batch E)", "K4 on the match-request message: MatchRequestControllerKnownBugTest.messageTheDatabaseRefusesShowsAnError, @Disabled('KNOWN BUG K4: ...'). A message over 255 characters should show an error, not a 500.", "MatchRequestController.java:63-75", "(b) Known-bug test, @Disabled; (a) service pin", "MRK-02, MRG (messageOver255CharactersFailsInDatabase)", "Built; confirmed failing when enabled (2026-10-24)."),
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
    ("GAP-L2", "Resolved: team decision (2026-10-28)", "TD-2: a group has exactly one study goal, from the fixed list shared with study preferences, so groups stay comparable and filterable; the description covers any extra goals. requirements.md L2 now says 'goal'.", "StudyGroup.java:376", "Recorded as TD-2", "-", "Resolved: team decision (2026-10-28)."),
    ("GAP-GRP1", "Ambiguous requirement", "GRP-1 'name max 255, description max 1000': only StudyGroupForm (@Size) and the database columns enforce it, not the entity or service.", "StudyGroupForm.java:31,34; StudyGroup.java:365,367", "Recorded (see K4 row)", "SGF, SGS", "Open."),
    ("GAP-GRP4", "Resolved: team decision (2026-10-28)", "TD-3: a full group keeps pending requests as a waiting list on purpose; a place reopens when the leader removes a member or raises the maximum size. While pending, the student sees 'The group is full right now. The leader can still accept you if a place opens up.' (on My groups and, since 2026-10-25, on the group's page).", "StudyGroup.java:446-483; details.html:62-65", "Recorded as TD-3", "SGE (fullGroupRefusesRequestsAndAccepts), SGC (pendingStudentOnFullGroupIsToldTheyAreWaiting, pendingStudentOnOpenGroupSeesNoFullNote), MAN-36", "Resolved: team decision (2026-10-28)."),
    ("GAP-GRP6", "Resolved: team decision (2026-10-28)", "TD-4: closing a group is permanent (the brief only says 'close the group when it is no longer active').", "StudyGroup.java:565; studygroup/details.html:41", "Recorded as TD-4", "SGS (closedGroupCannotBeUpdatedOrClosedAgain), MAN-34, MAN-37", "Resolved: team decision (2026-10-28)."),
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
    # ---- Batch E (profile, preferences, requests, connections) ----
    ("N-E1", "New finding", "A deactivated student who is still logged in (open sessions aren't ended, ACC-6 / K2) can still send match requests, and accept them.", "MatchRequestService.java:77-110,162", "(a) Pin the send case", "MRG (deactivatedSenderCanStillSend)", "Pinned."),
    ("N-E2", "New finding", "Match messages aren't trimmed; a message of only spaces is stored and shows as an empty line.", "MatchRequestService.java:106; match-requests.html:26", "(c) List only", "-", "Listed."),
    ("N-E3", "New finding", "Accepting a request from a sender who was deactivated in the meantime still connects the two students.", "MatchRequestService.java:162-186", "(c) List only", "-", "Listed."),
    ("N-E4", "New finding", "The matches page limits the match message to 255 characters (maxlength), so K4 there needs a hand-made POST; the profile fields have no limit, so K4 on the profile happens in a normal browser (MAN-40).", "matches.html:116; profile.html:28-32", "(c) List only", "MRK-02, SPK-01", "Listed."),
    # ---- Sync (2026-10-28) ----
    ("GAP-TD3", "Resolved (2026-11-06)", "The team's decision TD-3 says places reopen when 'members leave or are removed'. Members can now leave a group (GRP-9), which frees the place; requirements.md TD-3 updated to match.", "GroupMembershipService.java (leaveGroup); StudyGroup.java (leave)", "Implemented as GRP-9", "SGE (leaveEndsMembershipWithAnOptionalReason), GMS (leavingIsSavedAndTheLeaderSeesWhoLeftAndWhy), MAN-48", "Resolved (2026-11-06)."),
    ("N-S1", "Fixed (2026-10-30): team decision TD-6", "A deactivated student's public profile was still shown at /students/{id}. Team decision TD-6: StudentProfileService treats a deactivated student like an unknown id, so the page redirects to /matches with 'That student's profile no longer exists.'", "StudentProfileService.java (getPublicProfile, getPublicPreferences)", "Fixed by TD-6", "SPG (deactivatedStudentHasNoPublicProfile), PPS (deactivatedStudentsProfileRedirectsLikeAnUnknownId, activeStudentsProfileIsShown), MAN-45", "Fixed (2026-10-30)."),
    ("N-S2", "New finding", "The public profile lists study preferences in no fixed order (getPublicPreferences doesn't sort; the matches page sorts its own by course code).", "StudentProfileService.java:73-81", "(c) List only", "-", "Listed."),
    ("N-S3", "New finding", "The public profile's back link always says '← Find study buddies', even when it was opened from Match requests, Study buddies or Manage members.", "student/public-profile.html:8", "(c) List only", "-", "Listed."),
    ("GAP-IDS", "Test note", "Automated Test IDs are now stable: docs/test-cases/test-ids.json maps Class#method to its ID (seeded 2026-10-28 from the Batch E workbook). Inserting or reordering tests no longer renumbers; a new test gets the next free number; a removed test's number is retired, never reused.", "docs/test-cases/test-ids.json", "Recorded", "-", "In place."),
    ("GAP-K3REG", "Fixed (2026-10-28)", "Sign-up, create-admin and change-email reported an over-long value (over 255 characters, email included) as 'Another account already uses that email.' AuthController and AccountManagementController now check the length first: 'Name, school, programme and email can be at most 255 characters.' (form re-shown with everything typed except the password) and 'The email can be at most 255 characters.' (email refilled on create). Real duplicate-email refusals are reported as before; no other database error is caught.", "AuthController.java; AccountManagementController.java", "Fixed in the controllers", "AUC (overLongFieldShowsItsOwnMessageAndKeepsTheForm, fieldOf255CharactersReachesTheService), AMC (createAdminWithOverLongEmailShowsItsOwnMessage, updateEmailOverLongShowsItsOwnMessage), MAN-46", "Fixed (2026-10-28)."),
    ("GAP-NI1", "Partly implemented (2026-11-06)", "A student can now cancel a pending join request (GRP-8) and a member can leave a group with an optional reason (GRP-9). A group still can't be deleted (closing is permanent instead, TD-4).", "GroupMembershipService.java (cancelRequest, leaveGroup)", "List only (group deletion)", "MAN-47, MAN-48", "Listed (group deletion only)."),
    # ---- Automation push (2026-11-07) ----
    ("A11Y-1", "Fixed (2026-11-07), accessibility", "The 'Cancel request' button's accessible name was 'Cancel your request to join <group>' (aria-label), which didn't contain its visible words 'Cancel request' (WCAG 2.5.3 Label in Name, axe label-content-name-mismatch, serious), so a voice-control user saying 'click Cancel request' might not reach it. On My groups and the group page. The aria-label is now 'Cancel request to join <group>'; visible text, popover, routes and layout unchanged.", "fragments/cancel-join-request.html:15", "Fixed; baseline entries removed (a stale baseline entry now fails a11y.spec.js)", "EAX (a waiting join request passes axe), ECF (cancelling a join request...), MAN-54", "Fixed (2026-11-07)."),
    ("GAP-FS1", "Test note", "The full-stack flows run in one rolled-back transaction, so a refusal that the database only makes when it commits can't happen there: deleting a course that is still used (MAN-24) or a student with activity (MAN-20). Those two are tested in the browser (ECF), against a server that really commits. Also: a refused action inside a @Transactional service only marks the shared test transaction for rollback, so any change it made before refusing stays visible to the next page in that test (not seen in any result).", "studybuddy/src/test/java/com/studybuddy/flow/FlowTestSupport.java", "Recorded", "ECF (a course that is still used..., a student who is still used...)", "Recorded."),
    ("GAP-E2E1", "Test note", "An admin can only be created by another admin, so the e2e tests log in as the seeded admin (admin@studybuddy.com, studybuddy.seed.password) only to add a fresh admin; they never change it. The suite therefore needs AdminDataSeeder and that password (or E2E_SEED_ADMIN_PASSWORD).", "e2e/helpers/app.js (newAdmin)", "Recorded", "-", "Recorded."),
    ("GAP-E2E2", "Test note", "Playwright doesn't repeat projects that another project depends on, so --repeat-each=2 on the full suite only repeated the settings project. Independence was proven with desktop + phone repeated (settings left out) plus the settings project repeated on its own.", "e2e/playwright.config.js", "Recorded", "-", "Recorded."),
    ("GAP-DEAD1", "Removed (2026-11-09)", "Not called by any production code (found by the coverage report): AvailabilitySlot.setDayOfWeek, setStartTime, setEndTime, getStudyPreference; StudyPreference.setCourse, setAvailabilitySlots; MatchRequest.setMessage; StudyBuddyConnection.getStudent1, getStudent2; Student.isLeaderOf; StudyPreferenceService.getPreference. All 11 deleted on 2026-11-09 after checking each for Java callers (main and test, incl. method references, overrides and reflection), Thymeleaf expressions, request binding, JPA property access (all entities use field access), derived queries (student1/student2 resolve through the fields, which stay) and Jackson/validation use: none found. The only test caller, StudyPreferenceServiceTest.anotherStudentCannotTouchAPreference, lost its getPreference step. MatchRequestService.getRequestById is tested but no page uses it (K7); it stays.", "AvailabilitySlot.java; StudyPreference.java; MatchRequest.java; StudyBuddyConnection.java; Student.java; StudyPreferenceService.java", "Removed", "SPF (anotherStudentCannotTouchAPreference)", "Removed (2026-11-09)."),
    ("GAP-DEAD2", "Team decision (reversed 2026-11-09)", "Unused methods: left in place by team decision on 2026-11-07; removed on 2026-11-09 (see GAP-DEAD1). Full Java and browser suites passed before and after.", "see GAP-DEAD1", "Removed", "-", "Reversed (2026-11-09)."),
    ("GAP-COV1", "Coverage gap (left, narrowed 2026-11-09)", "Still not tested on purpose: the seeders' fallbacks for missing seed data. StudyGroupController 211-212 and 299-300 were described too narrowly here (a leader losing the right to edit between opening and saving the form): any hand-made POST to Save, '+ Add slot' or 'Remove' from a non-leader, or for a closed or missing group, reaches them. Tested since 2026-11-09 (SGC). The other code the coverage report still marks is defensive and can't be reached through the app: a null login email, re-requesting a pending or accepted membership, buddyOf for a student outside the pair, equals() with another type, a group with no slots, and main().", "*DataSeeder.java", "Seeder fallbacks skipped (agreed 2026-11-07); controller lines tested", "SGC (saveEditRefusedWithReason, addOrRemoveSlotOnEditRefusedWithReason)", "Narrowed (2026-11-09)."),
]

# --------------------------------------------------------------------------------------
# Read the code and the last run
# --------------------------------------------------------------------------------------


def java_level(text):
    """The test level, read from how the class starts Spring (or doesn't)."""
    if "extends FlowTestSupport" in text:
        return "Full stack (HTTP)"
    if "@SpringBootTest(classes" in text:
        return "Slice (narrow context)"
    if "@SpringBootTest" in text:
        return "Context (service level)"
    if "@DataJpaTest" in text:
        return "Slice (JPA)"
    if "@WebMvcTest" in text:
        return "Slice (Web MVC)"
    return "Unit"


def comment_above(lines, i):
    """The // comment lines straight above line i, skipping annotations such as @WithMockUser."""
    k = i - 1
    while k >= 0 and lines[k].strip().startswith("@"):
        k -= 1
    comment = []
    while k >= 0 and lines[k].strip().startswith("//"):
        comment.insert(0, lines[k].strip()[2:].strip())
        k -= 1
    return comment


def ids_in(comment):
    """Requirement IDs named before the first colon of the comment's first line."""
    head = (comment[0] if comment else "").split(":")[0]
    return ", ".join(dict.fromkeys(ID_PATTERN.findall(head)))


E2E_TEST = re.compile(r"""^test\((['"])(.+?)\1\s*,""")


def parse_sources():
    """{ClassName or spec: [{method, ids, comment, disabled, parameterized}]} for every @Test in src/test
    and every test(...) in e2e/tests. LEVELS gets each one's test level."""
    found = {}
    for path in sorted(TEST_SRC.rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        lines = text.splitlines()
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
            comment = comment_above(lines, i)
            tests.append({"method": method, "ids": ids_in(comment), "comment": " ".join(comment), "disabled": disabled,
                          "parameterized": parameterized})
        if tests:
            found[path.stem] = tests
            LEVELS[path.stem] = java_level(text)
    for path in sorted(E2E_TESTS.glob("*.spec.js")) if E2E_TESTS.exists() else []:
        lines = path.read_text(encoding="utf-8").splitlines()
        tests = []
        for i, line in enumerate(lines):
            match = E2E_TEST.match(line)
            if match:
                comment = comment_above(lines, i)
                tests.append({"method": match.group(2), "ids": ids_in(comment), "comment": " ".join(comment),
                              "disabled": None, "parameterized": False})
        if tests:
            stem = path.name[:-len(".js")]
            found[stem] = tests
            LEVELS[stem] = E2E_LEVEL
    return found


def is_e2e(cls):
    return LEVELS.get(cls) == E2E_LEVEL


class Reports(dict):
    """{(ClassName, method): status} from the surefire XML reports, one entry per test method, and
    {(spec, title): status} from the Playwright JSON report.
    A parameterized method runs once per value and surefire reports each run ("method(Route)[3]"),
    so its runs are merged: Fail if any run failed, Skipped if all were skipped, else Pass. An e2e test
    runs once per project (and per repeat), merged the same way.
    runs counts every surefire run (what ./mvnw test prints); invocations holds how many runs each
    parameterized method or e2e test had. Reports left behind by test classes that no longer exist are
    ignored (surefire never deletes old reports)."""

    def __init__(self):
        super().__init__()
        self.runs = Counter()
        self.invocations = Counter()
        self.surefire_at = None
        self.e2e_runs = Counter()
        self.e2e_projects = defaultdict(set)
        self.e2e_started = None
        self.e2e_stale = []


def merge(reports, key, status):
    earlier = reports.get(key)
    # A failed run wins; a skipped one only stands if nothing else ran
    if earlier is None or earlier == "Skipped" or status == "Fail":
        reports[key] = status


def parse_reports(sources):
    reports = Reports()
    xmls = list(REPORTS.glob("TEST-*.xml"))
    if xmls:
        reports.surefire_at = datetime.fromtimestamp(max(x.stat().st_mtime for x in xmls))
    for xml in xmls:
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
            merge(reports, key, status)
    parse_e2e_report(reports)
    return reports


def parse_e2e_report(reports):
    """Adds the last Playwright run. With no report the e2e rows stay 'Not run'; a report older than any
    e2e source file marks every e2e row 'Stale', so an old pass can't pass for a current one."""
    if not E2E_REPORT.exists():
        print(f"WARNING: no Playwright report at {E2E_REPORT.relative_to(ROOT)}; the e2e rows show 'Not run'. "
              "Run npx playwright test in e2e/ first.")
        return
    data = json.loads(E2E_REPORT.read_text(encoding="utf-8"))
    started = datetime.fromisoformat(data["stats"]["startTime"].replace("Z", "+00:00"))
    reports.e2e_started = started.astimezone()
    sources = [*E2E_DIR.glob("tests/*.js"), *E2E_DIR.glob("helpers/*.js"), E2E_DIR / "playwright.config.js",
               E2E_DIR / "a11y-baseline.json"]
    reports.e2e_stale = sorted(str(p.relative_to(E2E_DIR)) for p in sources
                               if p.exists() and datetime.fromtimestamp(p.stat().st_mtime, timezone.utc) > started)
    if reports.e2e_stale:
        print(f"WARNING: the Playwright report ({reports.e2e_started:%Y-%m-%d %H:%M}) is older than "
              f"{', '.join(reports.e2e_stale)}; the e2e rows show 'Stale'. Run npx playwright test in e2e/ again.")

    def walk(suite):
        for child in suite.get("suites", []):
            walk(child)
        for spec in suite.get("specs", []):
            key = (Path(spec["file"]).name[:-len(".js")], spec["title"])
            for test in spec["tests"]:
                status = {"expected": "Pass", "skipped": "Skipped"}.get(test["status"], "Fail")
                reports.e2e_runs[status] += 1
                reports.invocations[key] += 1
                reports.e2e_projects[key].add(test["projectName"])
                merge(reports, key, "Stale" if reports.e2e_stale and status == "Pass" else status)

    for suite in data["suites"]:
        walk(suite)


MANUAL_STATUSES = ["Not run", "Pass", "Fail"]
WRITTEN_BY = {"Existing": "Existing (teammates)", "New": "New (Batch A)", BATCH_B: "New (Batch B)",
              BATCH_C: "New (Batch C)", BATCH_D: "New (Batch D)",
              BATCH_E: "New (Batch E)", SYNC: "New (sync 2026-10-28)",
              TD56: "New (2026-10-30)", CANCEL: "New (2026-11-06)", LEAVE: "New (2026-11-06, leave)",
              FLOW: "New (full-stack flows 2026-11-07)", E2E: "New (end to end 2026-11-07)",
              RESIDUAL: "New (residual manual 2026-11-07)", COVERAGE: "New (coverage gaps 2026-11-09)"}
RESULT_COLUMNS = {"tester": "Tester", "date": "Date", "actual": "Actual result"}
HISTORY = "Manual result (history)"


def read_previous_manual_results():
    """{Test ID: {status, tester, date, actual}} for every MAN-nn row of the existing workbook, so rebuilding
    never wipes results typed in by hand. While a row is Manual its Status is the manual result; once it is
    replaced by automated tests its Status is theirs, and the manual result lives on under 'Manual result
    (history)'. Columns are found by their header text, so older workbooks still work."""
    if not OUT.exists():
        return {}
    ws = load_workbook(OUT)["Test Cases"]
    header = [cell.value for cell in ws[1]]
    col = {name: header.index(name) for name in ["Test ID", "Automated or Manual", "Status", HISTORY,
                                                 *RESULT_COLUMNS.values()] if name in header}
    results = {}
    for row in ws.iter_rows(min_row=2, values_only=True):
        test_id = row[col["Test ID"]] if "Test ID" in col else None
        if not test_id or not str(test_id).startswith("MAN-"):
            continue
        kept = {key: row[col[name]] for key, name in RESULT_COLUMNS.items() if name in col and row[col[name]]}
        manual = row[col["Automated or Manual"]] == "Manual"
        status = row[col["Status"]] if manual else (row[col[HISTORY]] if HISTORY in col else None)
        if status in MANUAL_STATUSES:
            kept["status"] = status
        results[test_id] = kept
    return results


def load_test_ids():
    """The saved Class#method -> Test ID map. The first time (no map yet) it is seeded from the
    existing workbook, so the IDs people already use stay as they are."""
    if TEST_IDS.exists():
        return json.loads(TEST_IDS.read_text(encoding="utf-8"))
    seeded = {}
    if OUT.exists():
        ws = load_workbook(OUT)["Test Cases"]
        header = [cell.value for cell in ws[1]]
        id_col, where_col = header.index("Test ID"), header.index("Test class and method")
        for row in ws.iter_rows(min_row=2, values_only=True):
            if row[id_col] and row[where_col]:
                seeded[row[where_col]] = row[id_col]
        print(f"Seeded {TEST_IDS.name} with {len(seeded)} Test IDs from the existing workbook")
    return seeded


def next_test_id(test_ids, prefix):
    """The next number for the prefix. Retired IDs stay in the map, so they're never reused."""
    used = [int(test_id.rsplit("-", 1)[1]) for test_id in test_ids.values() if test_id.rsplit("-", 1)[0] == prefix]
    return f"{prefix}-{max(used, default=0) + 1:02d}"


def save_test_ids(test_ids, sources):
    """Writes the map back and reports new and retired IDs (retired: still listed, no longer in the code)."""
    TEST_IDS.write_text(json.dumps(dict(sorted(test_ids.items())), indent=2) + "\n", encoding="utf-8")
    live = {f"{cls}#{test['method']}" for cls, tests in sources.items() for test in tests}
    retired = sorted(test_ids[key] for key in test_ids.keys() - live)
    if retired:
        print(f"Retired Test IDs (kept, never reused): {', '.join(retired)}")


def merged_status(statuses):
    """One status for a manual case from the automated tests that replace it."""
    for status in ["Fail", "Stale", "Not run", "Skipped"]:
        if status in statuses:
            return status
    return "Pass" if statuses else "Not run"


def run_at(reports, cls):
    when = reports.e2e_started if is_e2e(cls) else reports.surefire_at
    return f"{when:%Y-%m-%d %H:%M}" if when else ""


def build_rows(sources, reports, previous, test_ids):
    problems = []
    for cls in sources:
        if cls not in CLASSES:
            problems.append(f"Test class or spec {cls} has no entry in CLASSES")
    rows = []
    replaced = defaultdict(list)  # MAN-nn -> rows of the automated tests that replace it
    for cls, (prefix, written_by, pre) in CLASSES.items():
        if cls not in sources:
            problems.append(f"{cls} is described here but has no tests in src/test or e2e/tests")
            continue
        code = {test["method"]: test for test in sources[cls]}
        described = {case["method"]: case for case in CASES.get(cls, [])}
        for name in code.keys() - described.keys():
            problems.append(f"{cls}.{name} exists in the code but has no description")
        for name in described.keys() - code.keys():
            problems.append(f"{cls}.{name} is described but doesn't exist in the code")
        for test in sources[cls]:
            case = described.get(test["method"])
            if case is None:
                continue
            key = f"{cls}#{test['method']}"
            if key not in test_ids:
                test_ids[key] = next_test_id(test_ids, prefix)
                print(f"New Test ID {test_ids[key]} for {key}")
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
            if is_e2e(cls) and runs:
                projects = ", ".join(sorted(reports.e2e_projects[(cls, test["method"])]))
                notes.insert(0, f"{runs} run(s) in the last Playwright run ({projects}); Pass only if every run passed.")
            if test["disabled"]:
                status = "Known bug (disabled)" if status in ("Skipped", "Not run") else status
                notes.insert(0, f"@Disabled: {test['disabled']} Confirmed failing when enabled (2026-10-24).")
            where = f"e2e/tests/{cls}.js > {test['method']}" if is_e2e(cls) else key
            row = {
                "id": test_ids[key], "ids": ids, "title": case["title"], "type": case["type"], "level": LEVELS[cls],
                "pre": pre, "steps": case["steps"], "data": case["data"], "expected": case["expected"],
                "mode": "Automated", "where": where, "status": status, "run_at": run_at(reports, cls),
                "by": written_by, "notes": " ".join(notes), "cls": cls,
                "tester": "", "date": "", "actual": "", "history": "", "runs": runs, "replaces": False,
                "parameterized": test["parameterized"],
            }
            rows.append(row)
            for mid in dict.fromkeys(re.findall(r"\bMAN-\d+\b", test["comment"])):
                replaced[mid].append(row)
    by_key = {row["where"]: row for row in rows}
    for mid, keys in EXTRA_REPLACED.items():
        for key in keys:
            if key not in by_key:
                problems.append(f"EXTRA_REPLACED names {key}, which is not an automated test")
            else:
                replaced[mid].append(by_key[key])

    manual_ids = Counter(case["id"] for case in MANUAL)
    problems += [f"Manual ID {mid} is used {n} times" for mid, n in manual_ids.items() if n > 1]
    problems += [f"A test comment names {mid}, which is not a manual case" for mid in replaced if mid not in manual_ids]
    for case in MANUAL:
        # Results typed into the last workbook are kept, matched by Test ID
        kept = previous.get(case["id"], {})
        row = {
            "id": case["id"], "ids": case["ids"], "title": case["title"], "type": case["type"], "level": "Manual",
            "pre": case["pre"], "steps": case["steps"], "data": case["data"], "expected": case["expected"],
            "mode": "Manual", "where": "", "status": kept.get("status", "Not run"), "run_at": "",
            "by": case["batch"], "notes": case["notes"], "cls": "", "tester": kept.get("tester", ""),
            "date": kept.get("date", ""), "actual": kept.get("actual", ""), "history": "", "replaces": False,
        }
        if case["retired"]:
            row.update(status="Retired", notes=f"RETIRED: {case['retired']} " + case["notes"])
        elif case["id"] in replaced:
            tests = replaced[case["id"]]
            history = kept.get("status", "")
            row.update(mode="Automated", level="Replaced by automated tests", replaces=True,
                       where=", ".join(t["id"] for t in tests), status=merged_status([t["status"] for t in tests]),
                       run_at=max(t["run_at"] for t in tests), history=history if history != "Not run" else "")
            note = f"Replaced by automated tests (2026-11-07): {row['where']}. The steps above stay as documentation."
            if row["history"]:
                note += f" Manual result kept as history: {row['history']}, {row['tester']}, {row['date']}."
            row["notes"] = note + (" " + case["notes"] if case["notes"] else "")
        rows.append(row)
    for mid in sorted(previous.keys() - manual_ids.keys()):
        print(f"WARNING: manual case {mid} is in the old workbook but no longer exists; its results were dropped "
              f"({previous[mid]})")
    duplicates = [test_id for test_id, n in Counter(test_ids.values()).items() if n > 1]
    problems += [f"Test ID {test_id} is given to more than one test in {TEST_IDS.name}" for test_id in duplicates]
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
    "Stale": PatternFill("solid", fgColor="FFEB9C"),
    "Not run": PatternFill("solid", fgColor="EDEDED"),
    "Skipped": PatternFill("solid", fgColor="EDEDED"),
    "Retired": PatternFill("solid", fgColor="D9D9D9"),
    "No test": PatternFill("solid", fgColor="FFC7CE"),
    "amber": PatternFill("solid", fgColor="FFEB9C"),
}
WRAP = Alignment(wrap_text=True, vertical="top")
STATUSES = ["Pass", "Fail", "Stale", "Known bug (disabled)", "Not run"]
LEVEL_ORDER = ["Unit", "Slice (JPA)", "Slice (Web MVC)", "Slice (narrow context)", "Context (service level)",
               "Full stack (HTTP)", E2E_LEVEL]


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


def coverage_rows():
    """Per-package line and branch coverage from the last JaCoCo report, plus the total."""
    if not JACOCO_CSV.exists():
        return None, []
    import csv
    totals = defaultdict(lambda: [0, 0, 0, 0])
    with JACOCO_CSV.open(encoding="utf-8") as f:
        for r in csv.DictReader(f):
            t = totals[r["PACKAGE"]]
            for i, k in enumerate(["LINE_MISSED", "LINE_COVERED", "BRANCH_MISSED", "BRANCH_COVERED"]):
                t[i] += int(r[k])

    def line(name, t):
        lines, branches = t[0] + t[1], t[2] + t[3]
        return [name, t[1], lines, round(100 * t[1] / lines, 1) if lines else None,
                t[3], branches, round(100 * t[3] / branches, 1) if branches else None]

    rows = [line(p, t) for p, t in sorted(totals.items())]
    total = [sum(t[i] for t in totals.values()) for i in range(4)]
    rows.append(line("Total", total))
    return datetime.fromtimestamp(JACOCO_CSV.stat().st_mtime), rows


def write(rows, reports):
    wb = Workbook()
    wb.remove(wb.active)

    # --- Test Cases ---
    headers = ["Test ID", "Requirement/Rule IDs", "Title", "Type", "Test level", "Preconditions", "Steps", "Test data",
               "Expected result", "Automated or Manual", "Test class and method", "Status", "Run at", "Tester", "Date",
               "Actual result", HISTORY, "Written by", "Notes"]
    col = {name: i for i, name in enumerate(headers)}
    lines = []
    for r in rows:
        # One banner line above the first manual case
        if r["id"].startswith("MAN-") and (not lines or not str(lines[-1][0] or "").startswith("MAN-")):
            lines.append([None, None, MANUAL_BANNER] + [None] * (len(headers) - 3))
        lines.append([r["id"], r["ids"], r["title"], r["type"], r["level"], r["pre"], r["steps"], r["data"],
                      r["expected"], r["mode"], r["where"], r["status"], r["run_at"], r["tester"], r["date"],
                      r["actual"], r["history"], WRITTEN_BY[r["by"]], r["notes"]])
    ws = sheet(wb, "Test Cases", headers, lines,
               [11, 18, 34, 12, 16, 40, 48, 26, 48, 12, 44, 14, 16, 14, 12, 40, 14, 14, 44])
    # Manual Status is typed in by the tester: offer only Not run / Pass / Fail
    status_choice = DataValidation(type="list", formula1='"' + ",".join(MANUAL_STATUSES) + '"', allow_blank=False)
    ws.add_data_validation(status_choice)
    for row in ws.iter_rows(min_row=2):
        if row[0].value is None:
            for cell in row:
                cell.fill, cell.font = FILLS["amber"], Font(bold=True)
            continue
        fill = FILLS.get(row[col["Status"]].value)
        if fill:
            row[col["Status"]].fill = fill
        if row[col["Automated or Manual"]].value == "Manual" and row[col["Status"]].value != "Retired":
            status_choice.add(row[col["Status"]])
            row[col["Date"]].number_format = "yyyy-mm-dd"

    # Retired manual cases stay on the Test Cases sheet only; a replaced manual case is counted through its tests
    retired = [r["id"] for r in rows if r["status"] == "Retired"]
    replaced = [r for r in rows if r["replaces"]]
    rows = [r for r in rows if r["status"] != "Retired" and not r["replaces"]]

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

    # --- Coverage ---
    measured, cov = coverage_rows()
    ws = sheet(wb, "Coverage", ["Package", "Lines covered", "Lines", "Line %", "Branches covered", "Branches",
                                "Branch %"], cov, [44, 14, 10, 10, 16, 10, 10])
    ws.append([])
    ws.append([f"From the JaCoCo report written {measured:%Y-%m-%d %H:%M} (report only, no threshold)." if measured
               else "No JaCoCo report: run the coverage command in studybuddy/ (see e2e/README.md)."])
    ws.append(["Command: ./mvnw org.jacoco:jacoco-maven-plugin:0.8.14:prepare-agent test "
               "org.jacoco:jacoco-maven-plugin:0.8.14:report  (no pom.xml change)"])

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
          + [[f"Last run (reports written {reports.surefire_at:%Y-%m-%d %H:%M})" if reports.surefire_at
              else "Last run"] + after])
    parameterized = [r for r in rows if r.get("parameterized")]
    ws.append([f"Of the last run, {sum(r['runs'] for r in parameterized)} runs come from {len(parameterized)} "
               f"parameterized test methods (one workbook row each)."])
    e2e = reports.e2e_runs
    block("Playwright end-to-end results (e2e/)", ["Run started", "Test runs", "Passed", "Failed", "Skipped", "Stale?"],
          [[f"{reports.e2e_started:%Y-%m-%d %H:%M}" if reports.e2e_started else "No report",
            sum(e2e.values()), e2e["Pass"], e2e["Fail"], e2e["Skipped"],
            ("Yes: older than " + ", ".join(reports.e2e_stale)) if reports.e2e_stale else "No"]])
    if retired:
        ws.append([f"Retired manual cases, not counted below: {', '.join(retired)} (IDs never reused)."])
    ws.append([f"Manual cases replaced by automated tests, counted through those tests and not again below: "
               f"{len(replaced)} ({', '.join(r['id'] for r in replaced)})."])

    automated = [r for r in rows if r["mode"] == "Automated"]
    manual_rows = [r for r in rows if r["mode"] == "Manual"]
    block("Test cases", ["Written by", "Automated", "Manual", "Total"],
          [[WRITTEN_BY[by], sum(r["by"] == by for r in automated), sum(r["by"] == by for r in manual_rows),
            sum(r["by"] == by for r in rows)] for by in WRITTEN_BY]
          + [["Total", len(automated), len(manual_rows), len(rows)]])

    levels = Counter(r["level"] for r in automated)
    level_pass = Counter(r["level"] for r in automated if r["status"] == "Pass")
    block("By test level (one row per test method or e2e test)", ["Test level", "Tests", "Passing"],
          [[level, levels[level], level_pass[level]] for level in LEVEL_ORDER]
          + [["Manual (residual checklist)", len(manual_rows), sum(r["status"] == "Pass" for r in manual_rows)],
             ["Total", len(automated) + len(manual_rows), sum(level_pass.values())]])

    status_counts = Counter(r["status"] for r in rows)
    mode_status = Counter((r["mode"], r["status"]) for r in rows)
    block("By status", ["Status", "Automated", "Manual", "Total"],
          [[s, mode_status[("Automated", s)], mode_status[("Manual", s)], status_counts[s]] for s in STATUSES])

    type_counts = Counter((r["type"], r["mode"]) for r in rows)
    block("By type", ["Type", "Automated", "Manual"],
          [[kind, type_counts[(kind, "Automated")], type_counts[(kind, "Manual")]] for kind in TYPES])

    groups = ["S", "L", "A", "D", "ACC", "CRS", "PRF", "PRE", "REQ", "GRP", "MAT", "SEC", "UI", "TD", "K"]
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
    for c in "BCDEFG":
        ws.column_dimensions[c].width = 18
    for row in ws.iter_rows():
        if row[0].value and str(row[0].value).startswith("Total"):
            for cell in row:
                cell.font = bold

    wb.move_sheet("Summary", offset=-4)
    wb.save(OUT)
    return after, status_counts, coverage, levels, replaced


def main():
    if not REPORTS.exists():
        sys.exit("No surefire reports: run ./mvnw test in studybuddy/ first")
    sources = parse_sources()
    reports = parse_reports(sources)
    # Read before writing: the workbook is rebuilt from scratch
    previous = read_previous_manual_results()
    test_ids = load_test_ids()
    rows = build_rows(sources, reports, previous, test_ids)
    save_test_ids(test_ids, sources)
    after, status_counts, coverage, levels, replaced = write(rows, reports)
    manual = [r for r in rows if r["mode"] == "Manual" and r["status"] != "Retired"]
    history = [r["id"] for r in replaced if r["history"]]
    print(f"Manual cases: {len(replaced)} replaced by automated tests (manual result kept as history for "
          f"{', '.join(history) or 'none'}); {len(manual)} still manual")
    print(f"Wrote {OUT}")
    print(f"Last ./mvnw test: {after[0]} run, {after[1]} passed, {after[2]} failed, {after[3]} skipped")
    e2e = reports.e2e_runs
    print(f"Last Playwright run: {sum(e2e.values())} runs, {e2e['Pass']} passed, {e2e['Fail']} failed, "
          f"{e2e['Skipped']} skipped" + (f" (started {reports.e2e_started:%Y-%m-%d %H:%M})" if reports.e2e_started else ""))
    print(f"By level: {dict((level, levels[level]) for level in LEVEL_ORDER)}")
    print(f"Test cases: {len(rows)} rows ({dict(status_counts)})")
    print(f"Coverage: {dict(coverage)}")


if __name__ == "__main__":
    main()

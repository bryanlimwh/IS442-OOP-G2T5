# Study Buddy Matcher: Requirements and Rules

Source: the project brief plus a code scan of main (Oct 2026). "Current:" lines say what the code does today. If a test would contradict a "Current:" line, treat it as a Gap to raise, never guess.

## Brief requirements

### Student
- S1 Create and update a profile (name, school, programme, year, contact number, courses taken).
  Current: StudentProfileService.createProfile/updateProfile/addCourse/removeCourse; POST /register, /student/profile, /student/profile/courses. Students cannot change their own email or password.
- S2 Study preferences (course, study mode, weekly slots, group-size preference, goal).
  Current: StudyPreferenceService; /student/preferences/**. One preference per course, only for a course the student takes. Slots belong to each preference.
- S3 Search buddies by course or goal. Current: MatchingService.findMatches, GET /matches.
- S4 Ranked list by matching score. Current: ScoreBreakdown.weightedScore plus the chosen strategy's ordering.
- S5 Filter matches. Current: MatchSearch.accepts (course, goal, mode, group size, sharedTimeOnly). Availability filter means "shares some time" only.
- S6 View another student's public profile; contact number hidden until a match is accepted.
  Current (partial): StudentPublicProfile appears inside match cards only. No profile page route for getPublicProfile.
- S7 Send a buddy request with optional message. Current: MatchRequestService.createRequest, POST /match-requests.
- S8 Accept or decline; contact number visible after accept. Current: acceptRequest/rejectRequest; contact number only on /connections.
- S9 View connections and end one. Current: StudyBuddyConnectionService, /connections, POST /connections/{id}/end.

### Study Group Leader
- L1 Create a group for a course. Current: StudyGroupService.createGroup, POST /groups. Leader need not take the course.
- L2 Group fields: name, description, goals, mode, availability, max size.
- L3 Update or close a group. Current: updateGroup/closeGroup. The course cannot be changed.
- L4 View join requests. Current: GroupMembershipService.getPendingRequests, GET /groups/{id}/members.
- L5 Accept or reject join requests.
- L6 Remove a member.

### System Administrator
- A1 Create, update, delete user accounts.
  Current (partial): admins can create ADMIN accounts only (students sign up). Update means email and password reset (plus deactivate/reactivate). Delete fails for students with activity.
- A2 View account status and usage. Current: getAllUsers returns AccountStatus with usage counts.
- A3 Configure matching criteria. Current: five weights (0 switches a criterion off), POST /admin/matching.
- A4 Configure strategy and weights. Current: strategy enum, weights, targetSessionMinutes.

### Data and design
- D1 At least 10 courses and 50 students seeded (CourseDataSeeder, StudentDataSeeder).
- D2 Interchangeable strategies (Balanced, AvailabilityFirst, CourseFirst) as MatchingStrategy components. They differ only in ordering; the score is identical.
- D3 Authorization: admin-only pages refuse non-admins; student-only pages refuse admins and anonymous users.
  Current (partial): URL rules in SecurityConfig; @PreAuthorize only on AccountManagementService and the CourseController POSTs.

## Business rules (use these IDs for traceability)

### Accounts
- ACC-1 Email is trimmed, lower-cased, must match name@domain.tld and not be blank (AccountCredentials.normaliseEmail).
- ACC-2 Password: at least 8 characters, not all spaces, at most 72 UTF-8 bytes; not trimmed (checkPassword).
- ACC-3 Email must not belong to another account (requireEmailAvailable) and is unique in the database (User.email).
- ACC-4 The last active admin cannot be deactivated or deleted (LastAdminException).
- ACC-5 Deleting a student with requests, groups or connections fails on foreign keys; the admin is told to deactivate instead.
- ACC-6 Deactivated accounts cannot log in (AuthService sets disabled). Open sessions are not ended.
- ACC-7 AccountManagementService is admin-only (class-level @PreAuthorize). passwordHash is never exposed (AccountStatus).
- ACC-8 AuthService looks up the email exactly as typed (no normalising).

### Courses
- CRS-1 Any logged-in user can list courses. Create, update and delete are admin-only (@PreAuthorize on the controller POSTs). CourseService itself has no checks and throws CourseNotFoundException for a missing id. The controller rejects blank code or name.

### Profile and preferences
- PRF-1 Name, school and programme are required; year of study 1 to 5; contact number exactly 8 digits.
- PRF-2 A course already taken cannot be added; a course not taken, or one with a preference, cannot be removed.
- PRF-3 The public profile never includes the contact number.
- PRE-1 Mode, arrangement and goal are required; the course must be taken; one preference per course.
- PRE-2 Only the owner can read, update, delete a preference or its slots.
- PRE-3 Slot: start before end; no overlap with the preference's other slots (touching is allowed).

### Match requests and connections
- REQ-1 No request to yourself; none if a pending request exists in either direction; none if already connected.
- REQ-2 Only the recipient accepts or rejects, and only while pending. Sender or recipient may view a request; anyone else is refused.
- REQ-3 Accepting creates a connection (connect is idempotent); rejecting does not.
- REQ-4 The contact number appears only in ConnectionSummary (/connections), never in request or profile summaries.
- REQ-5 Only a participant can end a connection; ending deletes it. A connection with yourself is impossible.

### Study groups
- GRP-1 Create needs an existing course; name required (max 255), description max 1000, size at least 2 (leader included).
- GRP-2 Only the leader can update, close, view requests, accept, reject or remove (NotGroupLeaderException for others).
- GRP-3 Update needs an active group; the new size cannot be below the current member count.
- GRP-4 Join needs an active, non-full group; not the leader; no duplicate pending or accepted membership; a rejected or removed student may ask again.
- GRP-5 Accept needs a pending membership that belongs to the group and free space. Remove needs an accepted member who is not the leader.
- GRP-6 Closing turns pending requests into GROUP_CLOSED and freezes the group. removeFromMyGroups works only for closed groups.
- GRP-7 Group slots: 1 to 3, times 08:00 to 23:00 in 15-minute steps from the list, end after start, no overlap (StudyGroupFormValidator).

### Matching
- MAT-1 Config: strategy required, weights not negative, total weight above 0, session minutes above 0 (InvalidMatchingConfigException). /admin/matching is protected by the URL rule only.
- MAT-2 Scores 0 to 1: course 1 or 0; availability = min(overlap minutes / target session, 1); mode and group size 1, 0.8 if either side chose EITHER, otherwise 0; goal 1 or 0. Overall = 100 x sum(score x weight) / sum(weights). The Appendix A example scores 92.
- MAT-3 Each student appears once with their best result; inactive students are excluded. Ordering: Balanced = score then name; AvailabilityFirst = shared minutes then score; CourseFirst = course score then score.
- MAT-4 The "either" score must be between 0 and 1 (property).

### Security
- SEC-1 Public: /login, /register, /error, /css, /js, /images. ADMIN: /admin/**. STUDENT: /matches, /groups, /match-requests, /connections, /student/**. Everything else: any logged-in user.
- SEC-2 Anonymous users are redirected to /login; the wrong role gets 403; a POST without a CSRF token gets 403.
- SEC-3 The H2 console is open with CSRF off (development only).

## Candidate bugs from the scan (not confirmed by tests; list in Gaps, do not assume)
- K1 Login is case-sensitive, but sign-up lower-cases the email.
- K2 Changing a logged-in student's email breaks their session (404s); deactivation and password reset do not end open sessions.
- K3 Any DataIntegrityViolationException shows "Another account already uses that email" (AuthController, AccountManagementController), including over-long fields.
- K4 Inputs over 255 characters give a 500: match message, profile fields, course code and name (limit is an HTML maxlength only).
- K5 Duplicate course codes allowed; CourseService has no @Transactional.
- K6 A match request can be sent to a deactivated student by direct POST.
- K7 getPublicProfile and getRequestById have no route (S6 gap).
- K8 A student can drop a course while leading or joined to a group for it; creating or joining a group does not require taking the course.
- K9 Preference slots are looser than group slots (any times, no maximum).
- K10 /register does not strip spaces from the contact number; profile update does.
- K11 A non-numeric yearOfStudy gives a raw 400.
- K12 An empty message is stored as ""; th:if hides messages like "no", "false", "off".
- K13 The duplicate-request error shows internal student ids.
- K14 No DB constraint for one pending request or one connection per pair (race).
- K15 H2 console open to everyone.
- K16 No @ControllerAdvice or custom error pages.
- K17 /matches still lists existing buddies.
- K18 Open-session-in-view is relied on; non-transactional tests can hit LazyInitializationException.
- K19 StudyPreferenceServiceTest.newPreferenceShowsUpInMatching depends on seeded random data.
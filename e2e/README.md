# e2e: browser tests for Study Buddy Matcher

Playwright tests that drive a real Chromium browser against the real app. They cover what only a browser can
show: the confirmation boxes (HTML `popover`), the browser's own form checks, buttons that are switched off,
the phone layout and accessibility (axe). Everything else is tested in Java under `studybuddy/src/test`.

Nothing here is part of the Maven build: `./mvnw test` is unchanged and doesn't need Node.

## Decision record (team decision under CLAUDE.md section 9, proposed 2026-11-07)

**Decision.** Add (1) this `e2e/` folder, using Node and Playwright, kept outside the Maven build, and (2) a
JaCoCo coverage report run from the command line, with no change to `pom.xml`.

**Why.**
- The 47 manual test cases needed an app restart per run and depended on seeded data. Most of them are now
  automated: as full-stack Java flows (`studybuddy/src/test/java/com/studybuddy/flow/`) and as the browser
  tests here. A short manual checklist is left for what needs a person (see the workbook).
- Confirmation boxes, HTML form validation, disabled buttons and the phone layout only exist in a browser;
  MockMvc can't check them.
- Kept outside Maven so nobody's `./mvnw test` changes and no dependency is added (section 1.3).

**Alternatives considered.**
- Selenium in Java: needs pom dependencies and driver setup; no built-in phone emulation, screenshots or traces.
- HtmlUnit: no CSS, layout or popovers.
- Cypress: also Node, but weaker with several users in one test and a heavier install.
- Staying manual: slow, needs restarts, no regression safety.
- JaCoCo in the pom: permanent, and touches a shared file; possible later if the team wants it.
- IDE coverage: not reproducible between people.

**Cost.** Node 18 or later and a one-time ~120 MB Chromium download, only for people who run these tests.

## Running

```
cd e2e
npm ci
npx playwright install chromium
npx playwright test                  # all projects (about 4-5 minutes)
npx playwright test --project=phone  # just the phone checks
npx playwright show-report           # screenshots, traces and axe results
```

`playwright.config.js` starts the app itself (`mvnw spring-boot:run`) on port **8089**, so it never touches an
app you have running on 8080, and every run starts with a fresh in-memory database. Port 8089 must be free.

The suite runs 4 workers (browsers in parallel). It starts its own Spring app as well as the browsers, so many
workers can cause page-load timeouts on machines with little free memory. `npx playwright test --workers=N`
overrides it.

Projects:
- `desktop` (1280x800) and `phone` (375x667, touch): run in parallel.
- `settings`: the spec that changes the matching settings, which every user shares. It runs after the other
  two, one test at a time, and puts the settings back after every test, even a failed one.

The JSON report (`test-results/results.json`) is read by `docs/test-cases/build_workbook.py`. The workbook
records when the report was made, and marks the e2e rows "Stale" if any e2e file changed after that run.

## Rules every test follows

- Every test makes its own students, courses and groups with a random suffix. No test relies on another test,
  on test order or on seeded data.
- The seeded admin (`admin@studybuddy.com`, `studybuddy.seed.password`) is used **only** to create a fresh admin
  for the test. Its email, password and status are never changed. Set `E2E_SEED_ADMIN_PASSWORD` if the seed
  password changes.
- Google Fonts are blocked so runs don't depend on the network (the stylesheet falls back to system fonts).

## Accessibility baseline

`a11y.spec.js` runs axe (WCAG 2.0 and 2.1, A and AA) on every main page at desktop and phone size.
`a11y-baseline.json` lists the violations already known, one entry per page and rule, with the reason. A
violation that isn't listed fails the test, and so does an entry that no longer occurs, so remove an entry
once it is fixed. It is empty since A11Y-1 (the Cancel request label) was fixed on 2026-11-07.

## Coverage report (Java, no pom change)

```
cd studybuddy
./mvnw org.jacoco:jacoco-maven-plugin:0.8.14:prepare-agent test org.jacoco:jacoco-maven-plugin:0.8.14:report
```

The report is in `studybuddy/target/site/jacoco/index.html`; the workbook's Coverage sheet is built from its CSV.
JaCoCo 0.8.14 is the first release with official Java 25 support (the JDK in use is 25, compiling to release 21).

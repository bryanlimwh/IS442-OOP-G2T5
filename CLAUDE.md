# Project Constitution — Study Buddy Matcher System
### IS442 OOP — Group 2 Team 5

This document exists so that six people, working in parallel over several weeks, produce **one coherent codebase** instead of six different styles stitched together. It draws on the core philosophy of *The Art of Clean Code* (Christian Mayer) — radical simplicity, doing less but better, and treating complexity as a cost to be justified, not a default — applied concretely to this project's architecture and rubric.

This is a **living document**. If the team agrees to change a rule here, update it and note why in the change itself, the same way we document architectural decisions.

---

## 1. Core Philosophy

Everything below traces back to five ideas:

**1. Focus — do less, but better.**
Not every feature, every edge case, or every design pattern is worth building. The brief explicitly leaves the matching algorithm and weights as *our* design decision — that's permission to keep it simple, not an invitation to build the most sophisticated version we can imagine. Before adding a class, a layer, or a pattern, ask: *does this solve a real requirement, or are we adding it because it seems impressive?*

**2. Simplicity over cleverness.**
Code is read far more often than it's written. A method that's obvious to read beats one that's technically shorter but requires the reader to think hard. If you have to explain your own code to a teammate before they can follow it, it's probably too clever.

**3. Say no to unnecessary complexity.**
Every class, every dependency, every configuration option we add has an ongoing cost — someone has to understand it, maintain it, and explain it in Q&A. We already made this call once (choosing not to use Docker, not over-adding Spring dependencies). Keep applying that same filter throughout the project.

**4. Small, continuous improvement (the "boy scout rule").**
Leave code slightly better than you found it. If you touch a file and notice a small inconsistency (a missing `@Enumerated(STRING)`, an outdated comment), fix it in the same change if it's cheap — don't leave a trail of "someone else's problem."

**5. Fix root causes, not symptoms.**
When something breaks (like the `DataSeeder` package-mismatch crash), find and fix the actual cause rather than just working around the immediate error. A `./mvnw clean` workaround is fine short-term, but the underlying wrong package declaration still needs fixing — don't let a patch stand in for a fix.

---

## 2. Architecture Principles

- **Layered, not tangled.** Every class lives in exactly one layer, and layers only talk to the one directly below them:

  ```
  controller/  → HTTP endpoints, no business logic
  service/     → business logic, orchestrates repositories/entities
  entity/      → entities + entity enums; business rules that belong to one entity only
  matching/    → the matching engine subsystem (strategy, config, results)
  repository/  → JPA interfaces, no logic beyond query definitions
  config/      → seeders, security config, app-wide setup
  ```

- **Single Responsibility Principle, concretely applied:** if you're about to add a method to an entity and it needs data from *outside* that entity (another repository, another service), it belongs in a service, not on the entity. `Student.isLeaderOf(group)` is fine (only touches `Student`'s own data). `Student.sendMatchRequestTo(otherStudent)` involving persistence is not — that's `MatchRequestService`'s job.

- **No God classes.** If a class is doing three or more unrelated things (e.g., validating input *and* computing scores *and* formatting output), split it.

- **Capabilities over subtypes, when the "type" isn't fixed at creation.** We chose *not* to make `StudyGroupLeader` a subclass of `Student`, because leadership is something a student can acquire or lose — it's a capability (`groupsLed` list), not an identity fixed at object creation. Reach for the same reasoning before creating a new subclass: is this genuinely a different *kind* of thing, or just a *state* an existing thing can be in?

- **Derive, don't duplicate, state.** If a fact can be computed from data you already store (`isLeader() { return !groupsLed.isEmpty(); }`), don't also store it as a separate field. Two sources of truth for one fact is a bug waiting to happen.

---

## 3. Class & Data Design Rules

- **JPA entities get a `protected` no-arg constructor**, never `public`. JPA needs it internally; nobody else should be able to construct a blank, half-filled entity. Pair it with a `public` constructor that takes real, required data.
- **Enums over strings or magic constants** for any field with a fixed, known set of values (`StudyMode`, `Arrangement`, `StudyGoal`, `GroupStatus`, `RequestStatus`, `MembershipStatus`, `Role`). Enums are compiler-enforced; strings invite silent typos.
- **Always `@Enumerated(EnumType.STRING)`**, never leave it as the default ORDINAL. Ordinal storage silently corrupts existing data the moment someone reorders an enum's constants.
- **Bidirectional relationships: set both sides.** If `AvailabilitySlot` has a back-reference to `StudyPreference`, setting only one side (adding to a list, but not calling the setter on the other object) will silently save a `null` foreign key. Always set both.
- **A class should be immediately explainable in one sentence.** If you can't describe what `StudyPreference` is for in one sentence without saying "and," it's carrying too much responsibility.

---

## 4. Configuration

- Nothing that a reasonable admin or developer might want to change should be hardcoded — this is a direct rubric requirement. Matching weights, matching strategy choice, and similar tunables live in configuration (`MatchingConfig`, `application.properties`), not as literals buried in a service method.
- If you write a number or string more than once across the codebase, ask whether it should be a named constant, an enum, or a config value instead.

---

## 5. Package & File Conventions

- **One canonical package for entities: `entity/`.** We first consolidated everything into `domain/`, then the team switched to `entity/` (Oct 2026, when merging the Match Request PR, which had renamed the package). Either name works; what matters is that there is only one, so don't reintroduce `domain/` or any second package for the same purpose. When in doubt about where a new class goes, check this document's layer list in Section 2 first, then ask the team before creating a new top-level package.
- **One repository interface per entity**, immediately after the entity is built, by the same person, in the same sitting — this is boilerplate, don't treat it as separate scheduled work.
- **One seeder class per entity** (`CourseDataSeeder`, `StudyPreferenceDataSeeder`, etc.), not one shared mega-seeder — this avoids 6 people editing the same file and causing constant merge conflicts. Use `@Order(n)` when one seeder's data depends on another's existing first.

---

## 6. Java Conventions (non-negotiable baseline)

- Classes: `PascalCase`. Methods/variables: `camelCase`. Constants/enum values: `UPPER_SNAKE_CASE`.
- Consistent indentation — match whatever the file you're editing already uses (check before assuming your editor's default is correct).
- No unnecessary `public` fields — encapsulate, use getters/setters (or Lombok, if the team later agrees to add it — currently **not** in use, per `User.java`'s existing convention, so don't introduce it unilaterally).
- A comment should explain **why**, not **what**. `// running total for the semester` is fine; `// increment i` is noise.

---

## 7. Verification Discipline

No change is "done" on the strength of a clean compile alone. Before considering a change finished:

1. `./mvnw clean compile` → BUILD SUCCESS
2. **Actually run the app** — a clean compile only proves syntax validity, not that Spring can wire everything correctly at runtime
3. If the change touches seeded data, confirm the seeder output in the console log, and spot-check the actual rows via the H2 console (`/h2-console`) when it matters
4. Stop the app afterward — don't leave it running across unrelated work

This checklist exists because we've already caught real bugs (`ConflictingBeanDefinitionException`, a missing foreign key from an unset bidirectional relationship) that a compile-only check would have missed.

---

## 8. Git & Collaboration Norms

- **Feature branches, not direct pushes to `main`**, once more than one person is actively building at once.
- **Commit messages**: a short present-tense summary line (`Add Course, AvailabilitySlot, StudyPreference entities with enums`), followed by a bullet-point description of what changed and what was verified. State explicitly what wasn't touched and why, if relevant (e.g., "Not touched: User.java, Admin.java — already correctly placed").
- **Don't touch a teammate's files without asking**, even to fix something you've spotted — flag it to them, or raise it in the team chat, unless it's a trivial, clearly-agreed fix (like the package-naming consolidation, which was explicitly discussed before being made).
- **`./mvnw clean` after pulling**, if you hit a bean-conflict or class-not-found error that doesn't make sense — stale `target/` build artifacts from an old package structure are a known cause on this project.
- **Flag scope ambiguities to the instructor/TA rather than silently assuming** — e.g., whether real student login/authentication is required was ambiguous in the brief; we chose a documented, justified simplification rather than guessing and hoping it's not questioned later.

---

## 9. Decision-Making Process

For any non-trivial architectural choice (a new pattern, a new package, a structural change affecting more than one person's files):

1. State the decision and the reasoning
2. State the alternatives considered
3. Get explicit agreement from affected teammates before acting — especially if it touches files someone else owns
4. Document it (in this file, in a `docs/decisions.md`, or in the relevant commit description)

We've already done this for: `StudyGroupLeader` as a capability vs. subclass, computed vs. stored `isLeader()`, `User`/`Student`/`Admin` inheritance, `domain/` vs. `entity/` package consolidation (settled on `entity/`). Keep the pattern going.

---

## 10. Anti-Patterns to Actively Avoid

- ❌ A stored boolean/flag for a fact you could compute from existing data
- ❌ A public no-arg constructor on a JPA entity
- ❌ `@Enumerated` left at its ORDINAL default
- ❌ Business logic inside a controller
- ❌ A repository method that duplicates logic already expressible as a derived query name
- ❌ A new top-level package created without checking Section 2/5 first
- ❌ A "just in case" dependency, feature, or configuration option nobody asked for
- ❌ Committing code that compiles but was never actually run
- ❌ A class doing more than one job because splitting it "felt like overkill"
- ❌ Silently guessing on an ambiguous requirement instead of flagging it

---

## 11. What "Done" Looks Like for a Feature Slice

A feature (e.g., "Match Requests") is done when:

- [ ] Entity, repository, service, and controller each exist in their correct layer
- [ ] All fields use the correct types (enums where applicable, `@Enumerated(STRING)` set)
- [ ] Seeder data exists for it, in its own seeder class
- [ ] The app runs cleanly end-to-end with this feature active, verified by actually running it
- [ ] No hardcoded values that should be configurable
- [ ] A teammate has reviewed the PR
- [ ] The commit message documents what changed, what was verified, and what remains open

---

## 12. Closing Note

None of these rules exist for their own sake — every one traces back to something we actually hit during this project (a real bug, a real inconsistency, a real rubric requirement) or to the core idea that **simple, well-scoped code that six people can all understand beats sophisticated code that only its author can explain.** If a rule here stops making sense for a situation, that's a conversation to have as a team — not a reason to quietly ignore it.
# Design decisions

Why the code is the way it is, for the team and for presentation Q&A.
Add new decisions at the end. Each one says what was decided, why, and what
else was considered (Constitution, Section 9).

---

## Matching engine

### How a match is scored

Each of five criteria is scored from 0 (no match) to 1 (full match) by
`CompatibilityCalculator`:

| Criterion | Score |
|---|---|
| Course | 1 if both preferences are for the same course, else 0 |
| Availability | shared minutes / target session length, capped at 1 |
| Study mode | 1 if the same; 0.8 if one student chose EITHER; 0 if IN_PERSON vs ONLINE |
| Study goal | 1 if the same, else 0 |
| Group size | same rule as study mode |

The overall score out of 100 is the weighted average:
`100 × Σ(score × weight) / Σ(weight)`.

**Worked example (brief, Appendix A).** Alex Tan and Ben Koh are seeded as
Students A and B. With the default equal weights they score
20 × (1 + 1 + 0.8 + 1 + 0.8) = **92**. `CompatibilityCalculatorTest` and
`MatchingServiceTest` both check this.

### Why availability is capped at a "target session length"

Raw overlap minutes have no upper limit, so they must be scaled to 0–1 before
they can be weighted against the other criteria. We divide by a target (the
admin's `targetSessionMinutes`, default 120) and cap at 1. What a pair needs is
*enough shared time for a session*; beyond that, more overlap barely helps.

The 120 is not from the brief (Appendix A's 2-hour overlap is just an
example). It's roughly one study session, and the admin can change it.

Alternatives considered:

- *overlap / my total free time*: penalises students who list a lot of availability.
- *overlap / the smaller of the two totals*: gives a student with one fully
  covered 1-hour slot full marks.
- *overlap / the best overlap in the results*: the same pair would score
  differently depending on who else is in the search.

### Why the EITHER score is 0.8, and why it isn't on the admin page

"Either" is compatible, but an exact match should still rank higher: two
students who both want in-person should rank above in-person + either. The
value is in `application.properties` (`studybuddy.matching.either-preference-score`)
rather than the admin page, because it tunes *how a criterion is scored*, not
*how much it counts*. The admin controls the latter through the weights.

### Why weights are relative, not percentages

A criterion's share is its weight divided by the total, so an admin can type
3/1/1/1/1 without making it add up to 100. A weight of 0 switches a criterion
off, which covers the brief's "configure the criteria used". Defaults are
equal (20 each), which is what "balanced weighting" in the brief means. The
admin page shows each criterion's resulting share.

### The three strategies (Strategy pattern)

Every strategy sees the same scores; they differ only in how they sort
(`MatchingStrategy.ranking()`). Ties are broken by the weighted score, then by
name, so results always come back in the same order.

- **Balanced**: weighted score.
- **Availability first**: most shared *minutes* first. This uses raw minutes,
  not the capped availability score, because the brief asks for "the strongest
  timetable overlap".
- **Course first**: same-course matches first. This only changes the order when
  searching across courses (e.g. by study goal), since a course search only
  returns that course.

Each strategy is a Spring `@Component`. `MatchingService` looks one up by the
type the admin picked, and refuses to start if a type has no implementation.
Adding a strategy means adding an enum value and one class.

### What a search compares

- **Search by course**: the searcher's preference for that course is compared
  with every other student's preference for that course.
- **Search by study goal**: only students with that goal are shown, across all
  of the searcher's courses.
- **No course chosen**: all of the searcher's preferences are used.

If both students have a preference for the same course, those two are
compared. Only when the searcher has no preference for the other student's
course is it compared with whichever of the searcher's preferences fits best
(scoring 0 for course). Without this rule, someone's CS201 preference could be
matched against the searcher's IS112 preference and show up as "CS201,
different course", even though the searcher also wants CS201 help.
`MatchingServiceTest` checks this.

Each student appears once, through their best match. Deactivated accounts and
the searcher are never shown.

### Filters

Filters remove students; they don't lower anyone's score. The study mode and
group size filters keep students who chose EITHER, since they're happy either
way. "Only students free when I am" means at least one minute of shared time.

### Contact numbers

Results carry `StudentPublicProfile` and `PreferenceSummary` records, never
entities. Neither record has a contact number or email, so no page can show
one by mistake.

### Not done yet (depends on other features)

- **Hiding students you're already connected with or have a pending request
  to**: needs the Match Request feature. Add the check in
  `MatchingService.findMatches`, next to the existing "skip the searcher /
  inactive students" check.
- **Admin settings reset on restart**: H2 runs in memory, so
  `MatchingConfigDataSeeder` recreates the settings from
  `application.properties` on every start.

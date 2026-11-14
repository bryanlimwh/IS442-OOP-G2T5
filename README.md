# Study Buddy Matcher

IS442 Object-Oriented Programming, Group 2 Team 5.

A web app that matches university students with study buddies and study groups, based on their courses,
weekly free time, study mode, study goal and preferred group size.

## Features

**Students**
- Sign up, then manage a profile and the courses taken
- Set one study preference per course: study mode, study goal, group size and weekly availability slots
- Find study buddies, ranked by a compatibility score, and filter by course, goal, mode, group size or shared time
- View other students' public profiles (the contact number stays hidden until a match is accepted)
- Send, accept or decline buddy requests, then view or end connections
- Browse and join study groups, or create and lead one: edit, close, accept or reject join requests, and remove members

**Admins**
- Create admin accounts, reset emails and passwords, deactivate, reactivate or delete accounts, and view usage
- Manage courses
- Configure the matching engine at `/admin/matching`: the ranking strategy and the weight of each criterion

## Tech stack

Java 21, Spring Boot 4 (Web MVC, Data JPA, Security), Thymeleaf and an in-memory H2 database. Built with the
Maven wrapper, so no Maven install is needed.

## Getting started

Requires JDK 21 or later.

```
cd studybuddy
./mvnw spring-boot:run
```

Then open http://localhost:8080. On Windows, use `mvnw.cmd` instead of `./mvnw`.

The database is in memory, so it is rebuilt and reseeded with 10+ courses and 50 students on every start.

### Demo accounts

Every seeded account uses the password `password123`.

| Role    | Email                    |
|---------|--------------------------|
| Admin   | `admin@studybuddy.com`   |
| Student | `student@studybuddy.com` |

The H2 console is at http://localhost:8080/h2-console (JDBC URL `jdbc:h2:mem:studybuddydb`, user `sa`, no
password). It is for development only.

## How matching works

1. `CompatibilityCalculator` scores two study preferences from 0 to 1 on each of five criteria: course,
   availability (shared weekly minutes, with full marks at the target session length), study mode, study goal
   and group size. A specific choice paired with "Either" gets partial credit.
2. `ScoreBreakdown` combines them into a score out of 100, weighting each criterion by the admin's settings.
3. A `MatchingStrategy` (Strategy pattern) decides the order:
   - **Balanced**: by score
   - **Availability first**: most shared free time first, then by score
   - **Course first**: same course first, then by score

The starting weights and strategy are set in `studybuddy/src/main/resources/application.properties`. They
are copied into the database at startup, where an admin can change them while the app runs.

## Project structure

```
studybuddy/src/main/java/com/studybuddy/
  controller/   HTTP endpoints, no business logic
  service/      business logic, grouped by feature
  entity/       JPA entities and enums
  matching/     matching engine: scoring, strategies, results
  repository/   Spring Data JPA interfaces
  config/       security, data seeders, app settings
  dto/          form and view objects
  exception/    domain exceptions
docs/           requirements, design decisions, test cases
e2e/            Playwright browser tests
```

## Testing

```
cd studybuddy
./mvnw test
```

Browser tests (Node 18 or later) are separate from the Maven build. See [e2e/README.md](e2e/README.md) for
those and for the coverage report.

## Documentation

- [docs/requirements.md](docs/requirements.md): brief requirements and business rules
- [docs/decisions.md](docs/decisions.md): design decisions and the reasons for them
- [CLAUDE.md](CLAUDE.md): team coding conventions

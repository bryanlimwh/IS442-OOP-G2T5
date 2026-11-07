package com.studybuddy.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Full-stack flows for the admin's matching settings and their effect on a student's search.
 * The settings are shared by everyone, so each change here only lives inside the test's
 * rolled-back transaction. Replaces MAN-22, MAN-23 and MAN-43.
 * <p>
 * Alex (Mon 10:00-14:00, In person, One-to-one, Exam preparation) searches. Pia matches
 * everything but shares only 30 minutes (85 with the default settings); Quinn shares all
 * 4 hours but nothing else (40). Rex is in another course.
 */
class MatchingSettingsFlowTest extends FlowTestSupport {

    private record World(Browser admin, long courseId, Browser alex) {
    }

    private World world() {
        Browser admin = newAdmin();
        String code = "SET" + unique();
        long courseId = createCourse(admin, code);
        String otherCode = "SEU" + unique();
        long otherCourseId = createCourse(admin, otherCode);
        Browser alex = studentWithPreference("Alex Searcher", courseId, code, "IN_PERSON", "ONE_TO_ONE",
                "EXAM_PREPARATION", "MONDAY", "10:00", "14:00");
        studentWithPreference("Pia Precise", courseId, code, "IN_PERSON", "ONE_TO_ONE", "EXAM_PREPARATION",
                "MONDAY", "10:00", "10:30");
        studentWithPreference("Quinn Free", courseId, code, "ONLINE", "SMALL_GROUP", "CONCEPT_REVIEW",
                "MONDAY", "10:00", "14:00");
        studentWithPreference("Rex Elsewhere", otherCourseId, otherCode, "IN_PERSON", "ONE_TO_ONE",
                "EXAM_PREPARATION", "MONDAY", "10:00", "14:00");
        return new World(admin, courseId, alex);
    }

    private static void save(Browser admin, String strategy, int course, int availability, int mode, int goal,
            int size) {
        admin.open("/admin/matching");
        admin.submit("/admin/matching", "strategy", strategy, "courseWeight", String.valueOf(course),
                "availabilityWeight", String.valueOf(availability), "studyModeWeight", String.valueOf(mode),
                "studyGoalWeight", String.valueOf(goal), "groupSizeWeight", String.valueOf(size),
                "targetSessionMinutes", "120");
    }

    private static List<Integer> scoresInOrder(Browser page) {
        List<Integer> scores = new ArrayList<>();
        Matcher matcher = Pattern.compile("match-score-value\">(\\d+)<").matcher(page.html());
        while (matcher.find()) {
            scores.add(Integer.parseInt(matcher.group(1)));
        }
        return scores;
    }

    // A3, A4, MAT-1: the admin saves new settings; all-zero and negative weights are refused (MAN-22)
    @Test
    void adminSavesSettingsAndBadOnesAreRefused() {
        Browser admin = newAdmin();

        save(admin, "AVAILABILITY_FIRST", 0, 1, 0, 0, 0);
        assertThat(admin.url()).isEqualTo("/admin/matching");
        assertThat(admin.text()).contains("Settings saved. New searches use them straight away.");
        assertThat(admin.text()).containsPattern("Shared free time \\S* ?100%");

        save(admin, "AVAILABILITY_FIRST", 0, 0, 0, 0, 0);
        assertThat(admin.text()).contains("At least one criterion needs a weight above 0.");
        save(admin, "AVAILABILITY_FIRST", -1, 1, 0, 0, 0);
        assertThat(admin.text()).contains("Weights can't be negative.");
        admin.open("/admin/matching");
        assertThat(admin.html()).containsPattern("value=\"AVAILABILITY_FIRST\"[^>]*checked");
    }

    // D2, A4, MAT-3: switching from Balanced to Availability first changes the order the student sees (MAN-23)
    @Test
    void strategyChangesTheOrderStudentsSee() {
        World world = world();
        Browser alex = world.alex();
        String search = "/matches?courseId=" + world.courseId();
        save(world.admin(), "BALANCED", 20, 20, 20, 20, 20);

        alex.open(search);
        assertThat(alex.text()).contains("ranked by Balanced matching.");
        assertThat(alex.text().indexOf("Pia Precise")).isLessThan(alex.text().indexOf("Quinn Free"));
        assertThat(scoresInOrder(alex)).containsExactly(85, 40);

        save(world.admin(), "AVAILABILITY_FIRST", 20, 20, 20, 20, 20);
        alex.open(search);
        assertThat(alex.text()).contains("ranked by Availability first matching.");
        assertThat(alex.text().indexOf("Quinn Free")).isLessThan(alex.text().indexOf("Pia Precise"));
        // Same scores, only the order changes
        assertThat(scoresInOrder(alex)).containsExactly(40, 85);
    }

    // A3, A4, D2, MAT-2: with Course first and only the course weight, every course mate scores 100 and comes before everyone else (MAN-43)
    @Test
    void courseFirstWithOnlyTheCourseWeight() {
        World world = world();
        save(world.admin(), "COURSE_FIRST", 1, 0, 0, 0, 0);
        assertThat(world.admin().text()).contains("Settings saved.");
        assertThat(world.admin().text()).containsPattern("Same course \\S* ?100%");

        Browser alex = world.alex();
        alex.open("/matches");
        assertThat(alex.text()).contains("ranked by Course first matching.");
        List<Integer> scores = scoresInOrder(alex);
        assertThat(scores).isSortedAccordingTo((a, b) -> b - a).contains(100, 0);
        String text = alex.text();
        assertThat(text).contains("Pia Precise", "Quinn Free", "Rex Elsewhere");
        assertThat(text.indexOf("Rex Elsewhere")).isGreaterThan(text.indexOf("Pia Precise"))
                .isGreaterThan(text.indexOf("Quinn Free"));
    }
}

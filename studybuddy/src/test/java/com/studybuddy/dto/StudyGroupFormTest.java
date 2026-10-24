package com.studybuddy.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.studybuddy.entity.AvailabilitySlot;
import com.studybuddy.entity.Course;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyGroup;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * The single-field rules on the create / edit group form (its annotations, run with a plain
 * jakarta Validator, no Spring) and the form's slot-row behaviour. The form starts valid:
 * "IS442 Revision", course 1, Exam preparation, Online, size 4, no slots (slots are
 * checked by StudyGroupFormValidator).
 */
class StudyGroupFormTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    private final StudyGroupForm form = validForm();

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    // L2, GRP-1
    @Test
    void validFormHasNoErrors() {
        assertThat(messages()).isEmpty();
    }

    // GRP-1
    @Test
    void nameIsRequiredAndAtMost255Characters() {
        form.setName(null);
        assertThat(messages()).containsExactly("Please enter a group name");
        form.setName("   ");
        assertThat(messages()).containsExactly("Please enter a group name");

        form.setName("N".repeat(255));
        assertThat(messages()).isEmpty();
        form.setName("N".repeat(256));
        assertThat(messages()).containsExactly("Please keep the group name under 255 characters");
    }

    // GRP-1
    @Test
    void descriptionIsOptionalAndAtMost1000Characters() {
        form.setDescription(null);
        assertThat(messages()).isEmpty();
        form.setDescription("D".repeat(1000));
        assertThat(messages()).isEmpty();

        form.setDescription("D".repeat(1001));
        assertThat(messages()).containsExactly("Please keep the description under 1000 characters");
    }

    // GRP-1
    @Test
    void sizeIsRequiredAndAtLeastTwo() {
        form.setMaxSize(null);
        assertThat(messages()).containsExactly("Please enter a maximum group size");
        form.setMaxSize(1);
        assertThat(messages()).containsExactly("A group needs room for at least 2 members, including you");

        form.setMaxSize(2);
        assertThat(messages()).isEmpty();
    }

    // L1, L2, GRP-1
    @Test
    void courseGoalAndModeAreRequired() {
        form.setCourseId(null);
        form.setStudyGoal(null);
        form.setStudyMode(null);

        assertThat(messages()).containsExactlyInAnyOrder("Please choose a course", "Please choose a study goal",
                "Please choose a study mode");
    }

    // GRP-7: the Add slot / Remove buttons
    @Test
    void addSlotStopsAtTheMaximumAndRemoveKeepsOneRow() {
        StudyGroupForm blank = StudyGroupForm.blank();
        assertThat(blank.getSlots()).hasSize(1);

        blank.addSlot(3);
        blank.addSlot(3);
        blank.addSlot(3);
        assertThat(blank.getSlots()).hasSize(3);

        // An index that doesn't exist is ignored
        blank.removeSlot(5);
        blank.removeSlot(-1);
        assertThat(blank.getSlots()).hasSize(3);

        blank.removeSlot(0);
        blank.removeSlot(0);
        blank.removeSlot(0);
        assertThat(blank.getSlots()).hasSize(1);
    }

    // L3: the edit form starts with the group's details, slots in day and time order
    @Test
    void editFormIsFilledFromTheGroup() {
        StudyGroup group = new StudyGroup("IS442 Revision", "Past papers", new Course("IS442", "OOP"),
                StudyGoal.PROBLEM_SOLVING, StudyMode.IN_PERSON, 5, new Student("alex@test.com", "dummyHash",
                        "Alex Tan", "School of Computing and Information Systems", "Information Systems", 2,
                        "91234567"));
        group.addAvailabilitySlot(new AvailabilitySlot(DayOfWeek.FRIDAY, LocalTime.of(14, 0), LocalTime.of(16, 0)));
        group.addAvailabilitySlot(new AvailabilitySlot(DayOfWeek.MONDAY, LocalTime.of(18, 0), LocalTime.of(20, 0)));
        group.addAvailabilitySlot(new AvailabilitySlot(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(10, 0)));

        StudyGroupForm edit = StudyGroupForm.from(group);

        assertThat(edit.getName()).isEqualTo("IS442 Revision");
        assertThat(edit.getDescription()).isEqualTo("Past papers");
        assertThat(edit.getStudyGoal()).isEqualTo(StudyGoal.PROBLEM_SOLVING);
        assertThat(edit.getStudyMode()).isEqualTo(StudyMode.IN_PERSON);
        assertThat(edit.getMaxSize()).isEqualTo(5);
        assertThat(edit.getSlots()).extracting(SlotForm::getDayOfWeek, SlotForm::getStartTime)
                .containsExactly(
                        tuple(DayOfWeek.MONDAY, LocalTime.of(9, 0)),
                        tuple(DayOfWeek.MONDAY, LocalTime.of(18, 0)),
                        tuple(DayOfWeek.FRIDAY, LocalTime.of(14, 0)));
        assertThat(edit.toAvailabilitySlots()).hasSize(3);
    }

    private List<String> messages() {
        return validator.validate(form).stream().map(ConstraintViolation::getMessage).toList();
    }

    private static StudyGroupForm validForm() {
        StudyGroupForm form = new StudyGroupForm();
        form.setName("IS442 Revision");
        form.setDescription("Past papers");
        form.setCourseId(1L);
        form.setStudyGoal(StudyGoal.EXAM_PREPARATION);
        form.setStudyMode(StudyMode.ONLINE);
        form.setMaxSize(4);
        return form;
    }
}

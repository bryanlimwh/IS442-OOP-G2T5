package com.studybuddy.dto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.studybuddy.entity.AvailabilitySlot;
import com.studybuddy.entity.StudyGroup;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The values on the create and edit study group forms.
 * <p>
 * {@code StudyGroup} has no setters, so the form binds to this object instead,
 * and {@code StudyGroupService} passes the values to the entity's constructor or
 * {@code updateDetails()}. The checks here let the page show friendly errors
 * before the service is called; the entity still enforces the same rules.
 * The time-slot rows are checked by {@code StudyGroupFormValidator}, because
 * their rules (complete, in order, no overlaps) span several fields.
 */
public class StudyGroupForm {

    // The size limits match the database columns, so a long entry gets a message instead of an error page
    @NotBlank(message = "Please enter a group name")
    @Size(max = 255, message = "Please keep the group name under 255 characters")
    private String name;

    @Size(max = 1000, message = "Please keep the description under 1000 characters")
    private String description; // optional

    // Only used when creating a group: the course can't be changed afterwards.
    @NotNull(message = "Please choose a course")
    private Long courseId;

    @NotNull(message = "Please choose a study goal")
    private StudyGoal studyGoal;

    @NotNull(message = "Please choose a study mode")
    private StudyMode studyMode;

    // Integer rather than int, so a blank field fails @NotNull with a clear message
    // instead of a type-conversion error. The edit page also refuses a size below the current member count.
    @NotNull(message = "Please enter a maximum group size")
    @Min(value = StudyGroup.MIN_GROUP_SIZE, message = "A group needs room for at least 2 members, including you")
    private Integer maxSize;

    private List<SlotForm> slots = new ArrayList<>();

    /**
     * Empty form, filled in by Spring from the submitted fields.
     */
    public StudyGroupForm() {
    }

    /**
     * The create form as it first appears: one empty time-slot row.
     */
    public static StudyGroupForm blank() {
        StudyGroupForm form = new StudyGroupForm();
        form.slots.add(new SlotForm());
        return form;
    }

    /**
     * The edit form, pre-filled with the group's current details and slots
     * (in day and time order, as the group's page lists them).
     */
    public static StudyGroupForm from(StudyGroup group) {
        StudyGroupForm form = new StudyGroupForm();
        form.name = group.getName();
        form.description = group.getDescription();
        form.courseId = group.getCourse().getCourseId();
        form.studyGoal = group.getStudyGoal();
        form.studyMode = group.getStudyMode();
        form.maxSize = group.getMaxSize();
        // Copied into an ArrayList so the Add slot / Remove buttons can change it
        form.slots = new ArrayList<>(group.getAvailability().stream()
                .sorted(Comparator.comparing(AvailabilitySlot::getDayOfWeek)
                        .thenComparing(AvailabilitySlot::getStartTime))
                .map(SlotForm::from)
                .toList());
        if (form.slots.isEmpty()) {
            form.slots.add(new SlotForm());
        }
        return form;
    }

    // ---- Behaviour ----

    /**
     * Adds an empty row for the "Add slot" button, unless the form already has
     * the most rows allowed.
     */
    public void addSlot(int maxSlots) {
        if (slots.size() < maxSlots) {
            slots.add(new SlotForm());
        }
    }

    /**
     * Removes a row for its "Remove" button. The last remaining row is kept, so
     * the form always has somewhere to enter a slot; an index that doesn't
     * exist is ignored.
     */
    public void removeSlot(int index) {
        if (slots.size() > 1 && index >= 0 && index < slots.size()) {
            slots.remove(index);
        }
    }

    // Call only once the validator has passed every row.
    public List<AvailabilitySlot> toAvailabilitySlots() {
        return slots.stream()
                .map(SlotForm::toAvailabilitySlot)
                .toList();
    }

    // ---- Getters / Setters (no Lombok in this project) ----

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }

    public StudyGoal getStudyGoal() {
        return studyGoal;
    }

    public void setStudyGoal(StudyGoal studyGoal) {
        this.studyGoal = studyGoal;
    }

    public StudyMode getStudyMode() {
        return studyMode;
    }

    public void setStudyMode(StudyMode studyMode) {
        this.studyMode = studyMode;
    }

    public Integer getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(Integer maxSize) {
        this.maxSize = maxSize;
    }

    public List<SlotForm> getSlots() {
        return slots;
    }

    public void setSlots(List<SlotForm> slots) {
        this.slots = slots;
    }
}

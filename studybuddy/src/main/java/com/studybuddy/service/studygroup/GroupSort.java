package com.studybuddy.service.studygroup;

import java.util.Comparator;

import com.studybuddy.entity.StudyGroup;

/**
 * The orders a student can choose on the study groups Browse page.
 * <p>
 * Each option carries its own {@link Comparator}, so adding an order means
 * adding one constant here, with no if/else in the service: a small use of
 * the Strategy pattern, like the matching strategies. Whatever the order,
 * {@link StudyGroupService} puts full groups last, because nobody can join
 * them until a place opens up.
 */
public enum GroupSort {
    NEWEST("Newest first", Comparator.comparing(StudyGroup::getCreatedAt).reversed()),
    NAME("Name (A–Z)", Comparator.comparing(StudyGroup::getName, String.CASE_INSENSITIVE_ORDER)),
    MOST_PLACES("Most places left", Comparator.comparingInt(GroupSort::placesLeft).reversed()),
    COURSE("Course (A–Z)", Comparator.comparing((StudyGroup group) -> group.getCourse().getCourseCode())
            .thenComparing(StudyGroup::getName, String.CASE_INSENSITIVE_ORDER));

    private final String label;
    private final Comparator<StudyGroup> order;

    GroupSort(String label, Comparator<StudyGroup> order) {
        this.label = label;
        this.order = order;
    }

    /**
     * The option named in the page's URL, or the fallback if there is none or
     * the name isn't a real option (e.g. a mistyped link), so the page still loads.
     */
    public static GroupSort fromName(String name, GroupSort fallback) {
        for (GroupSort option : values()) {
            if (option.name().equals(name)) {
                return option;
            }
        }
        return fallback;
    }

    // The text in the "Sort by" dropdown
    public String getLabel() {
        return label;
    }

    /**
     * This option's order. Ties fall back to the newest group first, so the
     * list is the same on every refresh.
     */
    public Comparator<StudyGroup> comparator() {
        return order.thenComparing(StudyGroup::getGroupId, Comparator.reverseOrder());
    }

    private static int placesLeft(StudyGroup group) {
        return group.getMaxSize() - group.getMemberCount();
    }
}

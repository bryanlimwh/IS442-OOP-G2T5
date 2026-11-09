package com.studybuddy.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Course's equals() and hashCode(), with no Spring and no database. The ids are set by hand,
 * as the database would, so two separate objects can stand for the same saved course.
 */
class CourseTest {

    // MAT-2, PRE-1: matching and the one-preference-per-course rule ask "same course?" of objects loaded separately
    @Test
    void separateObjectsWithTheSameIdAreEqual() {
        Course first = course(7L, "IS442", "Object Oriented Programming");
        Course second = course(7L, "IS442", "Object Oriented Programming");

        assertThat(first).isNotSameAs(second).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(course(8L, "IS442", "Object Oriented Programming"));
    }

    private static Course course(long id, String code, String name) {
        Course course = new Course(code, name);
        ReflectionTestUtils.setField(course, "courseId", id);
        return course;
    }
}

package com.studybuddy.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.studybuddy.entity.Course;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyGroup;
import com.studybuddy.entity.enums.GroupStatus;

public interface StudyGroupRepository extends JpaRepository<StudyGroup, Long> {

    // Spring Data builds each query from the method name.

    // SELECT * FROM study_group WHERE status = ?
    List<StudyGroup> findByStatus(GroupStatus status);

    // SELECT * FROM study_group WHERE course_id = ? AND status = ?
    List<StudyGroup> findByCourseAndStatus(Course course, GroupStatus status);

    // SELECT * FROM study_group WHERE leader_id = ?
    List<StudyGroup> findByLeader(Student leader);
}

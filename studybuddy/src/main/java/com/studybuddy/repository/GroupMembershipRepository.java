package com.studybuddy.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.studybuddy.entity.GroupMembership;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.enums.MembershipStatus;

public interface GroupMembershipRepository extends JpaRepository<GroupMembership, Long> {

    // Spring Data builds the query from the method name.
    // Needed because Student has no list of its memberships.

    // SELECT * FROM group_membership WHERE student_id = ? AND status = ?
    List<GroupMembership> findByStudentAndStatus(Student student, MembershipStatus status);
}

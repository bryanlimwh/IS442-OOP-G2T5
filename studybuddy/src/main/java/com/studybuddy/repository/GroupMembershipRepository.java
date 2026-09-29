package com.studybuddy.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.studybuddy.entity.GroupMembership;

public interface GroupMembershipRepository extends JpaRepository<GroupMembership, Long> {
}

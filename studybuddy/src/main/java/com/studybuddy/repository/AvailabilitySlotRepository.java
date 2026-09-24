package com.studybuddy.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.studybuddy.domain.AvailabilitySlot;

public interface AvailabilitySlotRepository extends JpaRepository<AvailabilitySlot, Long> {
}

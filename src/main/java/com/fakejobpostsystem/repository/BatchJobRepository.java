package com.fakejobpostsystem.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fakejobpostsystem.model.BatchJob;

public interface BatchJobRepository extends JpaRepository<BatchJob, Long> {

    List<BatchJob> findTop10ByInstitution_IdOrderBySubmittedAtDesc(Long institutionId);
}

package com.fakejobpostsystem.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fakejobpostsystem.model.CompanyReputationAggregate;
import java.util.List;

public interface CompanyReputationAggregateRepository extends JpaRepository<CompanyReputationAggregate, String> {

    List<CompanyReputationAggregate> findByScamReportCountGreaterThanEqualAndManualReviewRequiredFalse(long minimumScamReports);
}

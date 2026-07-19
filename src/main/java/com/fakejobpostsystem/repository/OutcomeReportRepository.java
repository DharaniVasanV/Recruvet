package com.fakejobpostsystem.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fakejobpostsystem.model.OutcomeReport;
import com.fakejobpostsystem.model.OutcomeReport.ConfirmedStatus;
import com.fakejobpostsystem.model.OutcomeReport.ModerationStatus;

public interface OutcomeReportRepository extends JpaRepository<OutcomeReport, Long> {

    List<OutcomeReport> findByModerationStatusOrderBySubmittedAtDesc(ModerationStatus moderationStatus);

    List<OutcomeReport> findByModerationStatus(ModerationStatus moderationStatus);

    long countByCompanyIdentifierAndModerationStatusAndConfirmedStatus(
            String companyIdentifier,
            ModerationStatus moderationStatus,
            ConfirmedStatus confirmedStatus);

    long countByCompanyIdentifierAndSubmittedAtAfter(String companyIdentifier, LocalDateTime submittedAfter);

    @Query("""
            select count(distinct r.user.id)
            from OutcomeReport r
            where r.companyIdentifier = :companyIdentifier
              and r.moderationStatus = :moderationStatus
              and r.confirmedStatus = :confirmedStatus
              and r.user is not null
            """)
    long countDistinctUsersByCompanyAndStatus(
            @Param("companyIdentifier") String companyIdentifier,
            @Param("moderationStatus") ModerationStatus moderationStatus,
            @Param("confirmedStatus") ConfirmedStatus confirmedStatus);

    @Query("""
            select distinct r.companyIdentifier
            from OutcomeReport r
            where r.moderationStatus = :moderationStatus
            """)
    List<String> findDistinctCompanyIdentifiersByModerationStatus(@Param("moderationStatus") ModerationStatus moderationStatus);

    Optional<OutcomeReport> findTopByCompanyIdentifierOrderBySubmittedAtDesc(String companyIdentifier);
}

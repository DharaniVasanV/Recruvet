package com.fakejobpostsystem.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fakejobpostsystem.model.Prediction;

public interface PredictionRepository extends JpaRepository<Prediction, Long> {
    List<Prediction> findTop10ByUser_IdOrderByTimestampDesc(Long userId);
    List<Prediction> findByUser_IdOrderByTimestampDesc(Long userId);
    Optional<Prediction> findByIdAndUser_Id(Long id, Long userId);
    Optional<Prediction> findByPublicToken(String publicToken);
    List<Prediction> findByInstitution_IdOrderByTimestampDesc(Long institutionId);
    List<Prediction> findByInstitution_IdAndScoreGreaterThanEqualOrderByTimestampDesc(Long institutionId, Double minimumScore);
    List<Prediction> findByInstitution_IdAndBatchJob_IdOrderByTimestampDesc(Long institutionId, Long batchJobId);
    Optional<Prediction> findByIdAndInstitution_Id(Long id, Long institutionId);
    List<Prediction> findByInstitution_IdAndSharedWithInstitutionTrueAndUser_RoleOrderByTimestampDesc(Long institutionId, String userRole);

    @Query("""
            select p from Prediction p
            where p.institution.id = :institutionId
              and p.sharedWithInstitution = true
              and p.user is not null
              and p.user.role = 'ROLE_USER'
              and p.user.shareWithInstitution = true
            order by p.timestamp desc
            """)
    List<Prediction> findVisibleStudentSharedPredictions(@Param("institutionId") Long institutionId);
}

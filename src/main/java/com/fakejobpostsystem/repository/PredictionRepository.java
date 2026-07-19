package com.fakejobpostsystem.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

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
}

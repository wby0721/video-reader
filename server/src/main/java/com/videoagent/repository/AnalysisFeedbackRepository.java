package com.videoagent.repository;

import com.videoagent.entity.AnalysisFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnalysisFeedbackRepository extends JpaRepository<AnalysisFeedback, Long> {

    Optional<AnalysisFeedback> findByUserIdAndMediaIdAndGoalKey(Long userId, Long mediaId, String goalKey);

    List<AnalysisFeedback> findByMediaIdAndGoalKey(Long mediaId, String goalKey);

    void deleteByMediaId(Long mediaId);
}

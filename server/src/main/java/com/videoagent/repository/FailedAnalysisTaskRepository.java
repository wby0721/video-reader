package com.videoagent.repository;

import com.videoagent.entity.FailedAnalysisTask;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FailedAnalysisTaskRepository extends JpaRepository<FailedAnalysisTask, Long> {

    void deleteByMediaId(Long mediaId);
}

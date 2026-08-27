package com.videoagent.repository;

import com.videoagent.entity.AgentCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentCheckpointRepository extends JpaRepository<AgentCheckpoint, Long> {

    Optional<AgentCheckpoint> findByMediaIdAndCheckpointName(Long mediaId, String checkpointName);

    List<AgentCheckpoint> findByMediaId(Long mediaId);

    void deleteByMediaId(Long mediaId);
}

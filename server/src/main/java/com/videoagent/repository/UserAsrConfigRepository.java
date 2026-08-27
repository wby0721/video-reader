package com.videoagent.repository;

import com.videoagent.entity.UserAsrConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAsrConfigRepository extends JpaRepository<UserAsrConfig, Long> {

    Optional<UserAsrConfig> findByUserId(Long userId);
}

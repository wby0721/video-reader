package com.videoagent.repository;

import com.videoagent.entity.MediaFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MediaFileRepository extends JpaRepository<MediaFile, Long> {

    Optional<MediaFile> findByIdAndUserId(Long id, Long userId);

    List<MediaFile> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 内容级复用（同用户限定）：同 contentHash 且已产出上下文的视频，直接复用预处理结果。
     * 复用源必须限定在同一用户内——不同用户的媒体记录与用户数据（聊天/分析等）相互独立。
     */
    Optional<MediaFile> findFirstByUserIdAndContentHashAndStatusOrderByIdDesc(
            Long userId, String contentHash, String status);
}

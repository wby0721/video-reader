-- =====================================================================
-- Video Agent — 阶段二：媒体与失败任务表补充字段
-- media_files.duration_ms：视频时长（毫秒），ingest 阶段写入
-- failed_analysis_task.error_message：失败详情
-- =====================================================================

ALTER TABLE media_files
    ADD COLUMN duration_ms BIGINT NULL COMMENT '视频时长（毫秒）' AFTER content_hash;

ALTER TABLE failed_analysis_task
    ADD COLUMN error_message TEXT NULL COMMENT '失败详情' AFTER error_type;

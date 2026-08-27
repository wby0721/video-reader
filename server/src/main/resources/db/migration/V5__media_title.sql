-- 视频标题（LLM 依据分析结果自动生成的简短标题，支持用户手动修改）
ALTER TABLE media_files ADD COLUMN title VARCHAR(64) NULL;

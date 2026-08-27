/**
 * ingest —— 视频预处理（感知层）。
 *
 * <p>职责：FFmpeg 切片 + ASR（faster-whisper）+ OCR（PaddleOCR）双分支并行抽取，
 * 按时间轴对齐产出时序多模态上下文 {@code VideoContext}（阶段二实现）。
 */
package com.videoagent.service.ingest;

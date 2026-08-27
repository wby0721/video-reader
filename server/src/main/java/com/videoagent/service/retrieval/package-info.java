/**
 * retrieval —— 长视频检索（记忆层）。
 *
 * <p>职责：5 分钟分块 + 摘要 + Embedding 索引（Qdrant），
 * 混合检索（语义 × 0.6 + 关键词 × 0.25 + 画面文字 × 0.15）与优雅降级（阶段三实现）。
 */
package com.videoagent.service.retrieval;

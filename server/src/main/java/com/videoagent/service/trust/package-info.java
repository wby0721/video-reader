/**
 * trust —— 可信证据验证（可信层）⭐⭐ 最大差异化。
 *
 * <p>职责：L1 原文保真 → L2 语义蕴含（embedding 闸门 + LLM 判定 + 降级矩阵）→
 * L3 独立复核，产出 hallucinationRate / semanticSupportRate 量化指标（阶段五实现）。
 */
package com.videoagent.service.trust;

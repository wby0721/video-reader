package com.videoagent.service.trust;

import com.videoagent.dto.AnalysisEvidence;
import com.videoagent.dto.VideoContext;
import com.videoagent.dto.VideoSegment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * L1 原文保真（方案 §6.4）：evidence.content 必须逐字命中 ASR/OCR 原文 →
 * 保证「引用不是编造的」。
 *
 * <p>匹配范围：整个视频上下文（全部时间片的语音转写 + 画面 OCR 文字），
 * 并按证据来源过滤（ASR / OCR / ASR+OCR）。
 * 说明：证据时间戳是分块级粗粒度（如 0/300000/600000），若按时间戳 ±窗口检索
 * 会把「文本确实在视频中」的引用误判为编造（文本在块内但不在窗口内），
 * 因此 L1 只负责「原文存在性（非编造）」，时间戳仅作展示定位。
 */
@Component
public class FidelityChecker {

    /**
     * L1 保真校验：evidence.content 是否逐字出现在视频上下文的原文中（忽略空白差异）。
     * 全部时间片按序拼接后检索；ASR 文本在片段拼接处/口语词周围常有空格、换行差异，
     * 故比对前统一去除所有空白——「逐字命中」指的是字符序列一致，而非空白分布一致。
     *
     * @return true 表示原文命中（引用非编造）
     */
    public boolean verify(String content, long timestampMs, String source, VideoContext context) {
        if (content == null || content.isBlank() || context == null || context.segments() == null) {
            return false;
        }
        boolean checkAsr = source == null || source.contains("ASR");
        boolean checkOcr = source == null || source.contains("OCR");

        StringBuilder asr = new StringBuilder();
        StringBuilder ocr = new StringBuilder();
        for (VideoSegment seg : context.segments()) {
            if (checkAsr && seg.transcript() != null) {
                asr.append(seg.transcript());
            }
            if (checkOcr && seg.ocrTexts() != null) {
                ocr.append(String.join("；", seg.ocrTexts()));
            }
        }
        String target = normalize(content);
        return (checkAsr && normalize(asr.toString()).contains(target))
                || (checkOcr && normalize(ocr.toString()).contains(target));
    }

    /** 小写 + 去全部空白（含换行/制表），用于逐字比对。 */
    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /** 结论与证据的 claim 匹配：evidence.claim 与结论一致或互为包含。 */
    public static boolean claimMatches(String conclusion, AnalysisEvidence evidence) {
        if (conclusion == null || evidence == null || evidence.claim() == null) {
            return false;
        }
        String c = conclusion.strip();
        String e = evidence.claim().strip();
        return c.equals(e) || c.contains(e) || e.contains(c);
    }
}

package com.videoagent.service.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.utils.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 分块摘要与关键词生成（方案 §6.2）：LLM 优先，LLM 不可用/失败时降级为
 * 「抽取式摘要 + 词频关键词」（优雅降级，不阻断索引）。
 */
@Service
public class ChunkEnricher {

    private static final Logger log = LoggerFactory.getLogger(ChunkEnricher.class);

    private static final int SUMMARY_MAX_CHARS = 200;
    private static final int MAX_KEYWORDS = 8;
    private static final Pattern PUNCT = Pattern.compile("[\\p{P}\\p{S}\\s\\d]+");
    private static final Set<String> STOPWORDS = Set.of(
            "的", "了", "和", "在", "是", "我们", "大家", "这个", "那个", "一个", "可以",
            "就是", "然后", "所以", "但是", "还有", "以及", "对于", "如果", "或者", "因为", "比如",
            "the", "and", "that", "this", "with", "for", "are", "you", "your", "not", "what"
    );

    private final ObjectMapper objectMapper;

    public ChunkEnricher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 生成摘要与关键词。
     *
     * @param model 当前用户的 LLM 客户端（可为 null → 抽取式降级）
     */
    public Enriched enrich(String transcript, List<String> visualTexts, LlmClient model) {
        if (model != null) {
            try {
                String json = model.chat(prompt(transcript, visualTexts), 500);
                JsonNode node = objectMapper.readTree(extractJson(json));
                String summary = node.path("summary").asText("").strip();
                List<String> keywords = new ArrayList<>();
                node.path("keywords").forEach(k -> keywords.add(k.asText().strip()));
                if (!summary.isBlank()) {
                    return new Enriched(truncate(summary),
                            keywords.stream().filter(s -> !s.isBlank()).distinct().limit(MAX_KEYWORDS).toList());
                }
            } catch (Exception e) {
                log.warn("LLM 摘要生成失败，降级为抽取式: {}", e.getMessage());
            }
        } else {
            log.debug("无可用 LLM，使用抽取式摘要");
        }
        return fallback(transcript, visualTexts);
    }

    /** 抽取式降级：摘要取转写前 200 字；关键词取画面 OCR 行 + 高频词。 */
    static Enriched fallback(String transcript, List<String> visualTexts) {
        String summary = truncate(transcript == null ? "" : transcript.strip());
        if (summary.isBlank()) {
            summary = truncate(String.join("，", visualTexts));
        }
        List<String> keywords = new ArrayList<>();
        // 画面 OCR 行本身就是关键词（幻灯片标题/要点）
        if (visualTexts != null) {
            for (String line : visualTexts) {
                String clean = PUNCT.matcher(line).replaceAll("").strip();
                if (clean.length() >= 2 && !STOPWORDS.contains(clean) && !keywords.contains(clean)) {
                    keywords.add(clean);
                }
                if (keywords.size() >= MAX_KEYWORDS) {
                    break;
                }
            }
        }
        // 转写高频 2-4 字词补充
        if (keywords.size() < MAX_KEYWORDS && transcript != null) {
            Map<String, Long> freq = tokenFreq(transcript);
            freq.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(Map.Entry::getKey)
                    .filter(k -> !keywords.contains(k))
                    .limit(MAX_KEYWORDS - keywords.size())
                    .forEach(keywords::add);
        }
        return new Enriched(summary, keywords);
    }

    private static String prompt(String transcript, List<String> visualTexts) {
        String visual = visualTexts == null ? "" : String.join("；", visualTexts);
        return """
                你是视频内容摘要助手。根据视频片段的语音转写和画面OCR文字，生成：
                1. 不超过%d字的片段摘要（中文，概括核心知识点，不要客套话）；
                2. 3-8个关键词（术语、概念）。
                只输出 JSON，不要任何其他内容：{"summary":"...","keywords":["...","..."]}
                语音转写：%s
                画面OCR：%s
                """.formatted(SUMMARY_MAX_CHARS, truncate(transcript, 3000), truncate(visual, 1000));
    }

    private static String extractJson(String text) {
        int s = text.indexOf('{');
        int e = text.lastIndexOf('}');
        return (s >= 0 && e > s) ? text.substring(s, e + 1) : "{}";
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String truncate(String s) {
        return truncate(s, SUMMARY_MAX_CHARS);
    }

    /** 简易中文分词兜底：2-4 字连续子串频次统计。 */
    private static Map<String, Long> tokenFreq(String text) {
        String clean = PUNCT.matcher(text).replaceAll("");
        Map<String, Long> freq = new java.util.HashMap<>();
        for (int i = 0; i < clean.length(); i++) {
            for (int len = 2; len <= 4 && i + len <= clean.length(); len++) {
                String token = clean.substring(i, i + len);
                if (STOPWORDS.contains(token) || token.chars().allMatch(Character::isDigit)) {
                    continue;
                }
                freq.merge(token, 1L, Long::sum);
            }
        }
        return freq.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Long::sum));
    }

    /** 摘要与关键词结果。 */
    public record Enriched(String summary, List<String> keywords) {}
}

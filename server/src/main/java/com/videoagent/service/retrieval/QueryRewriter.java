package com.videoagent.service.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.dto.ChatEntry;
import com.videoagent.utils.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索意图改写（方案 §6.2）：LLM 把用户目标改写为
 * semanticQuery + keywords（语音/文本） + visualKeywords（画面文字）；
 * LLM 不可用时降级为原样查询 + 简单切词。
 */
@Service
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);

    private final ObjectMapper objectMapper;

    public QueryRewriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 改写查询；model 为 null 时降级为原样查询。 */
    public Rewrite rewrite(String query, LlmClient model) {
        if (model != null) {
            try {
                String json = model.chat("""
                        你是视频检索意图改写助手。把用户查询改写为：
                        1. semanticQuery：适合语义检索的查询短语（保留原意，可扩展同义表达，10-25字）；
                        2. keywords：语音/文本关键词（术语、概念，3-6个）；
                        3. visualKeywords：可能出现在画面文字（PPT/板书）上的关键词（2-4个）。
                        只输出 JSON：{"semanticQuery":"...","keywords":["..."],"visualKeywords":["..."]}
                        用户查询：%s
                        """.formatted(query), 150);
                int s = json.indexOf('{');
                int e = json.lastIndexOf('}');
                if (s < 0 || e <= s) {
                    throw new IllegalStateException("LLM 响应未包含 JSON");
                }
                JsonNode node = objectMapper.readTree(json.substring(s, e + 1));
                String semanticQuery = node.path("semanticQuery").asText(query);
                List<String> keywords = new ArrayList<>();
                node.path("keywords").forEach(k -> keywords.add(k.asText()));
                List<String> visual = new ArrayList<>();
                node.path("visualKeywords").forEach(k -> visual.add(k.asText()));
                if (!semanticQuery.isBlank()) {
                    return new Rewrite(semanticQuery,
                            keywords.stream().filter(k -> !k.isBlank()).toList(),
                            visual.stream().filter(k -> !k.isBlank()).toList());
                }
            } catch (Exception ex) {
                log.warn("检索意图改写失败，降级为原样查询: {}", ex.getMessage());
            }
        }
        return fallback(query);
    }

    /** 视频连续追问专用：只消解最近对话中的指代，不回答问题。 */
    public ConversationRewrite rewriteConversation(String query, List<ChatEntry> history,
                                                    String videoTitle, LlmClient model) {
        if (model != null) {
            try {
                StringBuilder recent = new StringBuilder();
                List<ChatEntry> turns = history == null ? List.of() : history;
                int from = Math.max(0, turns.size() - 8); // 最近约 4 轮
                for (int i = from; i < turns.size(); i++) {
                    ChatEntry turn = turns.get(i);
                    if (turn != null && turn.content() != null && !turn.content().isBlank()) {
                        recent.append(turn.role()).append("：").append(turn.content()).append('\n');
                    }
                }
                String json = model.chat("""
                        你是视频问答的查询改写器，只负责消解连续追问中的指代，绝不回答问题。
                        保留用户原意，不新增视频中未出现的事实。输出严格 JSON：
                        {"standaloneQuestion":"脱离历史也能理解的问题","semanticQuery":"适合语义检索的表达","keywords":["术语"],"ocrKeywords":["画面词"]}
                        视频标题：%s
                        最近对话：
                        %s
                        当前问题：%s
                        """.formatted(value(videoTitle), recent, query), 220);
                int start = json.indexOf('{');
                int end = json.lastIndexOf('}');
                if (start < 0 || end <= start) {
                    throw new IllegalStateException("LLM 响应未包含 JSON");
                }
                JsonNode node = objectMapper.readTree(json.substring(start, end + 1));
                String standalone = node.path("standaloneQuestion").asText("").trim();
                String semantic = node.path("semanticQuery").asText("").trim();
                List<String> keywords = strings(node.path("keywords"));
                List<String> ocrKeywords = strings(node.path("ocrKeywords"));
                if (standalone.isBlank() || semantic.isBlank()) {
                    throw new IllegalStateException("改写结果缺少独立问题或语义查询");
                }
                return new ConversationRewrite(standalone, semantic, keywords, ocrKeywords);
            } catch (Exception e) {
                log.warn("连续追问改写失败，降级原问题: {}", e.getMessage());
            }
        }
        Rewrite fallback = fallback(query);
        return new ConversationRewrite(query, query, fallback.keywords(), fallback.visualKeywords());
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(value -> {
                String text = value.asText("").trim();
                if (!text.isBlank()) values.add(text);
            });
        }
        return values;
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }

    private static Rewrite fallback(String query) {
        List<String> terms = new ArrayList<>();
        for (String t : query.split("[\\s，。、；：,.!?;:]+")) {
            if (!t.isBlank()) {
                terms.add(t);
            }
        }
        // 中文无空格查询：整体作为语义查询与关键词（contains 匹配可覆盖）
        List<String> keywords = terms.isEmpty() ? List.of(query) : terms;
        return new Rewrite(query, keywords, keywords);
    }

    /** 改写结果。 */
    public record Rewrite(String semanticQuery, List<String> keywords, List<String> visualKeywords) {}

    public record ConversationRewrite(String standaloneQuestion, String semanticQuery,
                                      List<String> keywords, List<String> ocrKeywords) {}
}

package com.videoagent.service.retrieval;

import com.videoagent.dto.EvidenceHit;
import com.videoagent.dto.VideoChunk;
import com.videoagent.dto.VideoContext;
import com.videoagent.service.ai.LlmProvider;
import com.videoagent.utils.EmbeddingClient;
import com.videoagent.utils.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 混合检索（方案 §6.2）：
 *
 * <pre>
 * chunkScore = 语义相似度 × 0.6 + 关键词命中 × 0.25 + 画面文字命中 × 0.15
 * </pre>
 *
 * <ul>
 *   <li>语义：Qdrant 向量检索；Qdrant 不可用 → 本地余弦（缓存向量）；均无 → 0（纯关键词仍可用）；</li>
 *   <li>关键词/画面：查询改写后的 keywords / visualKeywords 在摘要、转写、OCR 文本中的命中率。</li>
 * </ul>
 */
@Service
public class VideoEvidenceRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(VideoEvidenceRetrievalService.class);

    private static final double W_SEMANTIC = 0.6;
    private static final double W_KEYWORD = 0.25;
    private static final double W_VISUAL = 0.15;

    private final RetrievalIndexService indexService;
    private final QdrantVectorStore vectorStore;
    private final EmbeddingClient embeddingClient;
    private final QueryRewriter queryRewriter;
    private final LlmProvider llmProvider;

    public VideoEvidenceRetrievalService(RetrievalIndexService indexService, QdrantVectorStore vectorStore,
                                         EmbeddingClient embeddingClient, QueryRewriter queryRewriter,
                                         LlmProvider llmProvider) {
        this.indexService = indexService;
        this.vectorStore = vectorStore;
        this.embeddingClient = embeddingClient;
        this.queryRewriter = queryRewriter;
        this.llmProvider = llmProvider;
    }

    public List<EvidenceHit> search(Long mediaId, String contentHash, VideoContext context, String query,
                                    int topK, Long userId) {
        return searchInternal(mediaId, contentHash, context, query, topK, userId, true);
    }

    /**
     * 检索但不做意图改写（证据打包用，任务本身已足够具体，省 LLM 调用）。
     */
    public List<EvidenceHit> searchNoRewrite(Long mediaId, String contentHash, VideoContext context, String query,
                                             int topK, Long userId) {
        return searchInternal(mediaId, contentHash, context, query, topK, userId, false);
    }

    private List<EvidenceHit> searchInternal(Long mediaId, String contentHash, VideoContext context, String query,
                                             int topK, Long userId, boolean rewrite) {
        List<VideoChunk> chunks = indexService.index(mediaId, contentHash, context, userId);
        if (chunks.isEmpty()) {
            return List.of();
        }

        LlmClient model = rewrite ? llmProvider.forUser(userId) : null;
        QueryRewriter.Rewrite rewritten = rewrite ? queryRewriter.rewrite(query, model) : null;        String semanticQuery = rewritten != null ? rewritten.semanticQuery() : query;
        List<String> keywords = rewritten != null ? rewritten.keywords() : simpleTerms(query);
        List<String> visualKeywords = rewritten != null ? rewritten.visualKeywords() : keywords;

        // 1) 语义相似度（Qdrant → 本地余弦 → 0）
        Map<Integer, Double> semantic = semanticScores(mediaId, contentHash, chunks, semanticQuery);
        String source = semanticSource;

        // 2) 关键词 + 画面文字命中
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            VideoChunk c = chunks.get(i);
            double kw = hitRate(textOf(c), keywords);
            double vis = hitRate(String.join(" ", c.visualTexts()), visualKeywords);
            double sem = semantic.getOrDefault(i, 0.0);
            double total = W_SEMANTIC * sem + W_KEYWORD * kw + W_VISUAL * vis;
            scored.add(new Scored(c, total, matchedTerms(textOf(c), keywords)));
        }

        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return scored.stream().limit(Math.max(1, topK))
                .map(s -> new EvidenceHit(
                        s.chunk().startTime(), s.chunk().endTime(),
                        s.chunk().segmentSummary(), s.chunk().keywords(),
                        Math.round(s.score() * 10000) / 10000.0, s.matched(), source))
                .toList();
    }

    /** 无 LLM 时的简单切词（对齐 QueryRewriter.fallback）。 */
    private static List<String> simpleTerms(String query) {
        List<String> terms = new ArrayList<>();
        for (String t : query.split("[\\s，。、；：,.!?;:]+")) {
            if (!t.isBlank()) {
                terms.add(t);
            }
        }
        return terms.isEmpty() ? List.of(query) : terms;
    }

    private String semanticSource = "KEYWORD_ONLY";

    private Map<Integer, Double> semanticScores(Long mediaId, String contentHash, List<VideoChunk> chunks, String semanticQuery) {
        Map<Integer, Double> scores = new HashMap<>();
        // 无向量 → 返回空（纯关键词）
        if (chunks.stream().noneMatch(c -> c.embedding() != null && !c.embedding().isEmpty())) {
            semanticSource = "KEYWORD_ONLY";
            return scores;
        }
        List<Float> queryVector = embeddingClient.embed(semanticQuery);
        // Qdrant 优先
        try {
            List<QdrantVectorStore.Hit> hits = vectorStore.search(queryVector, chunks.size());
            for (QdrantVectorStore.Hit h : hits) {
                if (h.index() >= 0 && h.index() < chunks.size()) {
                    scores.put(h.index(), Math.max(0, h.score()));
                }
            }
            semanticSource = "QDRANT";
        } catch (Exception e) {
            // 降级：本地余弦（缓存向量）
            log.warn("Qdrant 检索不可用，降级本地余弦: {}", e.getMessage());
            semanticSource = "LOCAL_COSINE";
            for (int i = 0; i < chunks.size(); i++) {
                VideoChunk c = chunks.get(i);
                if (c.embedding() != null && !c.embedding().isEmpty()) {
                    scores.put(i, cosine(queryVector, c.embedding()));
                }
            }
        }
        return scores;
    }

    /** 命中率：命中词数 / 查询词数。 */
    static double hitRate(String text, List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return 0;
        }
        String lower = (text == null ? "" : text).toLowerCase(Locale.ROOT);
        long hits = terms.stream()
                .filter(t -> t != null && !t.isBlank())
                .filter(t -> lower.contains(t.toLowerCase(Locale.ROOT)))
                .count();
        long total = terms.stream().filter(t -> t != null && !t.isBlank()).count();
        return total == 0 ? 0 : (double) hits / total;
    }

    static List<String> matchedTerms(String text, List<String> terms) {
        String lower = (text == null ? "" : text).toLowerCase(Locale.ROOT);
        return terms.stream()
                .filter(t -> t != null && !t.isBlank())
                .filter(t -> lower.contains(t.toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static String textOf(VideoChunk c) {
        return (c.segmentSummary() == null ? "" : c.segmentSummary() + " ")
                + String.join(" ", c.keywords()) + " "
                + (c.transcript() == null ? "" : c.transcript());
    }

    private static double cosine(List<Float> a, List<Float> b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            double x = a.get(i), y = b.get(i);
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private record Scored(VideoChunk chunk, double score, List<String> matched) {}
}

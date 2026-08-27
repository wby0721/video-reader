package com.videoagent.service.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Qdrant 向量存储（REST）：集合管理 + 点 upsert + 向量检索。
 * 所有操作失败时抛异常，由调用方降级（优雅降级，见 RetrievalIndexService / VideoEvidenceRetrievalService）。
 */
@Service
public class QdrantVectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);

    public static final String COLLECTION = "video-chunks";

    private final RestClient client;
    private final int dimensions;

    public QdrantVectorStore(AppProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(10_000);
        this.client = RestClient.builder()
                .baseUrl(properties.qdrant().baseUrl())
                .requestFactory(factory)
                .build();
        this.dimensions = properties.ai().embedding().dimensions();
    }

    public String collection() {
        return COLLECTION;
    }

    /** 确保集合存在（向量维度与距离度量）。 */
    public void ensureCollection() {
        JsonNode collections = client.get().uri("/collections").retrieve().body(JsonNode.class);
        boolean exists = false;
        if (collections != null && collections.path("result").path("collections").isArray()) {
            for (JsonNode c : collections.path("result").path("collections")) {
                if (COLLECTION.equals(c.path("name").asText())) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            client.put().uri("/collections/{name}", COLLECTION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("vectors", Map.of("size", dimensions, "distance", "Cosine")))
                    .retrieve().toBodilessEntity();
            log.info("Qdrant 集合 {} 已创建（dims={}）", COLLECTION, dimensions);
        }
    }

    /**
     * 批量写入索引点。点 ID 用确定性 UUID（由 contentHash+index 派生），
     * 同内容幂等覆盖、跨用户共享（内容级复用）。index 写入 payload 供检索回映射。
     */
    public void upsert(String contentHash, Long mediaId, List<Point> points) {
        List<Map<String, Object>> payload = points.stream().map(p -> Map.<String, Object>of(
                "id", pointId(contentHash, p.index()),
                "vector", p.vector(),
                "payload", Map.<String, Object>of(
                        "index", p.index(),
                        "contentHash", contentHash,
                        "mediaId", mediaId,
                        "startMs", p.startMs(),
                        "endMs", p.endMs(),
                        "summary", p.summary(),
                        "keywords", p.keywords())
        )).toList();
        client.put().uri("/collections/{name}/points?wait=true", COLLECTION)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("points", payload))
                .retrieve().toBodilessEntity();
    }

    /** 确定性点 ID：同 contentHash+index 恒为同一 UUID（幂等 + 内容级共享）。 */
    public static String pointId(String contentHash, int index) {
        return UUID.nameUUIDFromBytes((contentHash + "-" + index).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** 向量检索，返回 (index, 相似度) 列表（相似度 0-1）。 */
    public List<Hit> search(List<Float> vector, int limit) {
        JsonNode body = client.post().uri("/collections/{name}/points/search", COLLECTION)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("vector", vector, "limit", limit, "with_payload", true))
                .retrieve().body(JsonNode.class);
        if (body == null) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        for (JsonNode h : body.path("result")) {
            JsonNode payload = h.path("payload");
            hits.add(new Hit(
                    payload.path("index").asInt(-1),
                    h.path("score").asDouble(),
                    payload.path("startMs").asLong(),
                    payload.path("endMs").asLong(),
                    payload.path("summary").asText(),
                    payload.path("keywords").findValuesAsText("")));
        }
        return hits;
    }

    /** 索引点。 */
    public record Point(int index, long startMs, long endMs, String summary, List<String> keywords, List<Float> vector) {}

    /** 检索命中（index 为分块序号）。 */
    public record Hit(int index, double score, long startMs, long endMs, String summary, List<String> keywords) {}
}

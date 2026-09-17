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
import java.util.LinkedHashMap;
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
     * 批量写入索引点。点 ID 包含用户、内容、索引版本与块序号，
     * 同用户同内容幂等覆盖，跨用户或跨版本绝不覆盖。
     */
    public void upsert(Long userId, String contentHash, Long mediaId, int indexVersion,
                       List<Point> points) {
        List<Map<String, Object>> payload = points.stream().map(p -> Map.<String, Object>of(
                "id", pointId(userId, contentHash, indexVersion, p.index()),
                "vector", p.vector(),
                "payload", Map.<String, Object>of(
                        "index", p.index(),
                        "chunkId", p.chunkId(),
                        "userId", userId,
                        "contentHash", contentHash,
                        "mediaId", mediaId,
                        "indexVersion", indexVersion,
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

    /** 确定性点 ID：同一用户内按内容共享，跨用户绝不覆盖。 */
    public static String pointId(Long userId, String contentHash, int indexVersion, int index) {
        return UUID.nameUUIDFromBytes((userId + ":" + contentHash + ":" + indexVersion + ":" + index)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * 向量检索。userId 永远必选；contentHash 非空时进一步限定到当前视频内容，
     * 防止其他视频（包括其他用户的视频）用相同 chunk index 污染当前排序。
     */
    public List<Hit> search(List<Float> vector, int limit, Long userId, String contentHash,
                            int indexVersion) {
        JsonNode body = client.post().uri("/collections/{name}/points/search", COLLECTION)
                .contentType(MediaType.APPLICATION_JSON)
                .body(searchRequestBody(vector, limit, userId, contentHash, indexVersion))
                .retrieve().body(JsonNode.class);
        if (body == null) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        for (JsonNode h : body.path("result")) {
            JsonNode payload = h.path("payload");
            hits.add(new Hit(
                    payload.path("index").asInt(-1),
                    payload.path("chunkId").asText(null),
                    h.path("score").asDouble(),
                    payload.path("startMs").asLong(),
                    payload.path("endMs").asLong(),
                    payload.path("summary").asText(),
                    payload.path("keywords").findValuesAsText("")));
        }
        return hits;
    }

    /** 包级可见，供请求范围回归测试直接验证。 */
    static Map<String, Object> searchRequestBody(List<Float> vector, int limit,
                                                 Long userId, String contentHash, int indexVersion) {
        List<Map<String, Object>> must = new ArrayList<>();
        must.add(Map.of("key", "userId", "match", Map.of("value", userId)));
        if (contentHash != null && !contentHash.isBlank()) {
            must.add(Map.of("key", "contentHash", "match", Map.of("value", contentHash)));
        }
        must.add(Map.of("key", "indexVersion", "match", Map.of("value", indexVersion)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", vector);
        body.put("limit", Math.max(1, limit));
        body.put("with_payload", true);
        body.put("filter", Map.of("must", must));
        return body;
    }

    /** 索引点。 */
    public record Point(int index, String chunkId, long startMs, long endMs,
                        String summary, List<String> keywords, List<Float> vector) {}

    /** 检索命中（index 为分块序号）。 */
    public record Hit(int index, String chunkId, double score, long startMs, long endMs,
                      String summary, List<String> keywords) {}
}

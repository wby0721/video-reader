package com.videoagent.service.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.videoagent.config.AppProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/** bge-reranker-v2-m3 批量 Cross-Encoder 客户端。 */
@Component
public class RerankerClient {

    private final RestClient client;

    @org.springframework.beans.factory.annotation.Autowired
    public RerankerClient(AppProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(properties.ai().reranker().readTimeoutMs());
        this.client = RestClient.builder()
                .baseUrl(properties.ai().reranker().baseUrl())
                .requestFactory(factory)
                .build();
    }

    /** 测试构造器。 */
    RerankerClient(RestClient client) {
        this.client = client;
    }

    public List<RankedDocument> rerank(String query, List<DocumentInput> documents, int topN) {
        if (documents == null || documents.isEmpty() || topN <= 0) {
            return List.of();
        }
        JsonNode response = client.post().uri("/rerank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RerankRequest(query, documents, Math.min(topN, documents.size())))
                .retrieve()
                .body(JsonNode.class);
        if (response == null || !response.path("results").isArray()) {
            throw new IllegalStateException("Reranker 响应缺少 results");
        }
        return validated(response.path("results"), documents, Math.min(topN, documents.size()));
    }

    /** 多个独立查询一次 HTTP 精排；每个查询仍只和自己的候选文档配对。 */
    public List<List<RankedDocument>> rerankBatch(List<BatchInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        JsonNode response = client.post().uri("/rerank/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new BatchRequest(inputs))
                .retrieve()
                .body(JsonNode.class);
        if (response == null || !response.path("results").isArray()
                || response.path("results").size() != inputs.size()) {
            throw new IllegalStateException("Reranker 批量响应格式异常");
        }
        List<List<RankedDocument>> batches = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            BatchInput input = inputs.get(i);
            batches.add(validated(response.path("results").get(i).path("results"),
                    input.documents(), Math.min(input.topN(), input.documents().size())));
        }
        return batches;
    }

    private static List<RankedDocument> validated(JsonNode results, List<DocumentInput> documents, int expected) {
        if (!results.isArray() || results.size() != expected) {
            throw new IllegalStateException("Reranker results count mismatch");
        }
        var allowed = documents.stream().map(DocumentInput::id).collect(java.util.stream.Collectors.toSet());
        var seen = new java.util.HashSet<String>();
        List<RankedDocument> ranked = new ArrayList<>();
        for (JsonNode item : results) {
            String id = item.path("id").asText("");
            JsonNode value = item.path("score");
            double score = value.asDouble(Double.NaN);
            if (!allowed.contains(id) || !seen.add(id) || !value.isNumber()
                    || !Double.isFinite(score) || score < 0 || score > 1) {
                throw new IllegalStateException("Invalid reranker id or sigmoid score");
            }
            ranked.add(new RankedDocument(id, score));
        }
        ranked.sort(java.util.Comparator.comparingDouble(RankedDocument::score).reversed());
        return List.copyOf(ranked);
    }

    public record DocumentInput(String id, String text) {}
    public record RankedDocument(String id, double score) {}
    public record BatchInput(String query, List<DocumentInput> documents, int topN) {
        public BatchInput {
            documents = documents == null ? List.of() : documents;
            topN = Math.min(Math.max(1, topN), Math.max(1, documents.size()));
        }
    }
    private record RerankRequest(String query, List<DocumentInput> documents, int topN) {}
    private record BatchRequest(List<BatchInput> requests) {}
}

package com.videoagent.utils;

import com.videoagent.config.AppProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Embedding 客户端：BGE-M3 通过 OpenAI 兼容 {@code POST /embeddings} 接入，向量维度 1024。
 * 供阶段三「分块摘要 Embedding 索引」与阶段五「语义蕴含相似度闸门」使用。
 */
public class EmbeddingClient {

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final int dimensions;

    public EmbeddingClient(AppProperties properties) {
        AppProperties.Ai.Embedding embedding = properties.ai().embedding();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        this.restClient = RestClient.builder()
                .baseUrl(embedding.baseUrl())
                .requestFactory(factory)
                .build();
        this.apiKey = embedding.apiKey();
        this.model = embedding.model();
        this.dimensions = embedding.dimensions();
    }

    /** 计算单段文本的向量。 */
    public List<Float> embed(String text) {
        return embedAll(List.of(text)).getFirst();
    }

    /** 批量计算向量，减少索引阶段逐 Chunk HTTP 往返。 */
    public List<List<Float>> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        EmbeddingResponse response = restClient.post()
                .uri("/embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", model, "input", texts))
                .retrieve()
                .body(EmbeddingResponse.class);
        if (response == null || response.data() == null || response.data().size() != texts.size()
                || response.data().stream().anyMatch(item -> item.embedding() == null)) {
            throw new IllegalStateException("Embedding 响应为空或格式异常");
        }
        return response.data().stream()
                .map(item -> item.embedding().stream().map(Double::floatValue).toList())
                .toList();
    }

    public int dimensions() {
        return dimensions;
    }

    public record EmbeddingResponse(List<EmbeddingItem> data) {
        public record EmbeddingItem(List<Double> embedding) {}
    }
}

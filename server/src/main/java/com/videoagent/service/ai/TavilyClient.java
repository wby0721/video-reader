package com.videoagent.service.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.videoagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Tavily 网页搜索客户端（术语解释的联网检索源）。
 * 官方接口：POST https://api.tavily.com/search，Authorization: Bearer <api_key>。
 * 设计：10s 超时，任何失败返回空列表——调用方降级为"纯视频语境解释"，不阻断主流程。
 */
@Service
public class TavilyClient {

    private static final Logger log = LoggerFactory.getLogger(TavilyClient.class);

    private final RestClient restClient;
    private final String apiKey;

    public TavilyClient(AppProperties properties) {
        this.apiKey = properties.tavily().apiKey();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000); // 10s 超时 → 调用方降级
        this.restClient = RestClient.builder()
                .baseUrl(properties.tavily().baseUrl())
                .requestFactory(factory)
                .build();
    }

    /** 搜索网页，返回最多 maxResults 条结果（title/url/content 摘要）；失败返回空列表。 */
    public List<WebResult> search(String query, int maxResults) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Tavily API Key 未配置，跳过联网检索");
            return List.of();
        }
        try {
            SearchResponse resp = restClient.post()
                    .uri("/search")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(new SearchRequest(query, Math.max(1, maxResults), "basic"))
                    .retrieve()
                    .body(SearchResponse.class);
            if (resp == null || resp.results() == null) {
                return List.of();
            }
            // content 摘要可能很长，只保留前 500 字符
            return resp.results().stream()
                    .map(r -> new WebResult(
                            r.title() == null ? "" : r.title(),
                            r.url() == null ? "" : r.url(),
                            r.content() == null ? "" : (r.content().length() > 500 ? r.content().substring(0, 500) : r.content())))
                    .toList();
        } catch (Exception e) {
            log.warn("Tavily 检索失败（降级为纯语境解释）: {}", e.getMessage());
            return List.of();
        }
    }

    record SearchRequest(String query, int max_results, String search_depth) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(List<Result> results) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Result(String title, String url, String content) {}
    }

    /** 检索结果（已截断的摘要）。 */
    public record WebResult(String title, String url, String content) {}
}

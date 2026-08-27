package com.videoagent.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * DeepSeek（OpenAI 兼容）LLM 客户端：
 * <ul>
 *   <li>temperature=0（确定输出）+ seed，输出上限按调用方指定；</li>
 *   <li>{@code thinking: {type:"disabled"}} 关闭推理过程——实测同任务 completion token 降 ~85%（省成本）；</li>
 *   <li>失败抛异常，由调用方（摘要/Agent 角色）降级处理。</li>
 * </ul>
 */
public class DeepSeekLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekLlmClient.class);

    private final RestClient client;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;

    public DeepSeekLlmClient(String baseUrl, String apiKey, String model) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String chat(String prompt) {
        return chat(prompt, DEFAULT_MAX_TOKENS);
    }

    @Override
    public String chat(String prompt, int maxTokens) {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", 0,
                "seed", 42,
                "max_tokens", Math.max(50, maxTokens),
                "stream", false,
                "thinking", Map.of("type", "disabled") // 关闭推理，显著省 token
        );
        String raw = client.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()     // 4xx/5xx 由 RestClient 抛异常
                .body(String.class);
        try {
            JsonNode node = objectMapper.readTree(raw);
            JsonNode content = node.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                String err = node.path("error").path("message").asText("LLM 返回空内容");
                throw new IllegalStateException("LLM 调用失败: " + err);
            }
            return content.asText();
        } catch (Exception e) {
            throw new IllegalStateException("LLM 响应解析失败: " + e.getMessage() + " | 原文: " + truncate(raw, 200), e);
        }
    }

    private static String truncate(String s, int cap) {
        return s == null ? "" : (s.length() > cap ? s.substring(0, cap) : s);
    }
}

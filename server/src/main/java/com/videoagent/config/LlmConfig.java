package com.videoagent.config;

import com.videoagent.utils.EmbeddingClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 服务装配（真实服务接入）：
 * <ul>
 *   <li>LLM：DeepSeek（OpenAI 兼容），自研 {@code LlmClient} 封装（thinking 关闭省 token），
 *       由 {@code LlmProvider} 按用户解析（用户 Key 优先，服务端 Key 兜底）；</li>
 *   <li>Embedding：BGE-M3 本地服务（OpenAI 兼容 /embeddings），始终装配。</li>
 * </ul>
 * ASR / OCR 为本地独立推理服务（阶段二），不在此装配。
 */
@Configuration
public class LlmConfig {

    @Bean
    public EmbeddingClient embeddingClient(AppProperties properties) {
        return new EmbeddingClient(properties);
    }
}

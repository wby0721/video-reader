package com.videoagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用配置（app.*），覆盖 JWT / Redis / MinIO / Qdrant / 限流 / AI 服务接入。
 *
 * <p>AI 服务按「真实服务」接入：LLM 走 OpenAI 兼容接口（DeepSeek），Embedding 走 OpenAI 兼容
 * /embeddings（BGE-M3），ASR/OCR 为本地独立推理服务 HTTP 接口。全部通过环境变量可覆盖。
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        Redis redis,
        Minio minio,
        Qdrant qdrant,
        RateLimit rateLimit,
        Ai ai,
        Ffmpeg ffmpeg,
        Llm llm,
        Tavily tavily
) {

    /** JWT 签发配置 */
    public record Jwt(String secret, long expirationSeconds) {}

    /** Redis（Redisson 单节点） */
    public record Redis(String host, int port) {}

    /** MinIO 对象存储 */
    public record Minio(String endpoint, String accessKey, String secretKey, String bucket) {}

    /** Qdrant 向量检索 */
    public record Qdrant(String baseUrl) {}

    /** 令牌桶限流（成本护栏） */
    public record RateLimit(long userRps, long globalRps) {}

    /** AI 服务接入配置（真实服务，环境变量注入） */
    public record Ai(Llm llm, Embedding embedding, Asr asr, Ocr ocr) {

        /** LLM：DeepSeek，OpenAI 兼容接口 */
        public record Llm(String baseUrl, String apiKey, String model) {}

        /** Embedding：BGE-M3，OpenAI 兼容 /embeddings，维度 1024 */
        public record Embedding(String baseUrl, String apiKey, String model, int dimensions) {}

        /** ASR：本地 faster-whisper 独立推理服务 */
        public record Asr(String baseUrl) {}

        /** OCR：本地 PaddleOCR 独立推理服务 */
        public record Ocr(String baseUrl) {}
    }

    /** FFmpeg 可执行文件路径（阶段二视频预处理） */
    public record Ffmpeg(String path) {}

    /** 用户 API Key 静态加密主密钥（环境变量 LLM_MASTER_KEY；未配置时回退派生自 JWT secret） */
    public record Llm(String masterKey) {}

    /** Tavily 网页搜索（术语解释的联网检索源，环境变量 TAVILY_API_KEY） */
    public record Tavily(String baseUrl, String apiKey) {}
}

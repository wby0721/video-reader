package com.videoagent.utils;

/**
 * LLM 客户端接口（自研 OpenAI 兼容封装，支持 DeepSeek thinking 关闭等成本参数）。
 * 按用户解析（见 LlmProvider），未配置 Key 时为 null，调用方走降级路径。
 */
public interface LlmClient {

    /** 默认输出上限（token）。 */
    int DEFAULT_MAX_TOKENS = 800;

    /** 对话生成，返回文本。 */
    String chat(String prompt);

    /** 对话生成（指定输出上限）。 */
    String chat(String prompt, int maxTokens);
}

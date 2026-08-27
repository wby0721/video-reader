package com.videoagent.service.ai;

import com.videoagent.config.AppProperties;
import com.videoagent.service.auth.UserLlmConfigService;
import com.videoagent.utils.DeepSeekLlmClient;
import com.videoagent.utils.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 客户端提供者（自研 OpenAI 兼容封装）：
 * <ul>
 *   <li>按用户解析：用户自带 API Key（AES-GCM 加密存储）优先，服务端默认 Key 兜底；</li>
 *   <li>thinking 关闭 + temperature=0（成本护栏，实测 completion token 降 ~85%）；</li>
 *   <li>未配置任何 Key 时返回 null，调用方走降级路径。</li>
 * </ul>
 */
@Service
public class LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(LlmProvider.class);

    private final AppProperties properties;
    private final UserLlmConfigService userLlmConfigService;
    private final Map<String, LlmClient> clientCache = new ConcurrentHashMap<>();

    private volatile LlmClient serverClient;

    public LlmProvider(AppProperties properties, UserLlmConfigService userLlmConfigService) {
        this.properties = properties;
        this.userLlmConfigService = userLlmConfigService;
    }

    /**
     * 获取指定用户的 LLM 客户端；无用户配置时使用服务端默认。
     *
     * @param userId 当前用户（可为 null）
     * @return LlmClient；无任何可用 Key 时返回 null（调用方降级）
     */
    public LlmClient forUser(Long userId) {
        if (userId != null) {
            var user = userLlmConfigService.get(userId).orElse(null);
            if (user != null && user.apiKey() != null && !user.apiKey().isBlank()) {
                return clientCache.computeIfAbsent("user:" + userId,
                        k -> new DeepSeekLlmClient(user.baseUrl(), user.apiKey(), user.model()));
            }
        }
        return serverDefault();
    }

    private LlmClient serverDefault() {
        AppProperties.Ai.Llm llm = properties.ai().llm();
        if (llm.apiKey() == null || llm.apiKey().isBlank()) {
            return null;
        }
        if (serverClient == null) {
            serverClient = new DeepSeekLlmClient(llm.baseUrl(), llm.apiKey(), llm.model());
        }
        return serverClient;
    }
}

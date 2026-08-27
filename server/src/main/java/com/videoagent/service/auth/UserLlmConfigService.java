package com.videoagent.service.auth;

import com.videoagent.entity.UserLlmConfig;
import com.videoagent.repository.UserLlmConfigRepository;
import com.videoagent.utils.CryptoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 用户级 LLM 配置（上线后用户自带 API Key）：
 * Key 以 AES-GCM 密文落库（非明文），读取时解密后使用，接口返回仅脱敏。
 */
@Service
public class UserLlmConfigService {

    private final UserLlmConfigRepository repository;
    private final CryptoService crypto;

    public UserLlmConfigService(UserLlmConfigRepository repository, CryptoService crypto) {
        this.repository = repository;
        this.crypto = crypto;
    }

    @Transactional
    public void save(Long userId, String apiKey, String baseUrl, String model) {
        UserLlmConfig config = repository.findByUserId(userId).orElseGet(() -> {
            UserLlmConfig c = new UserLlmConfig();
            c.setUserId(userId);
            return c;
        });
        config.setApiKeyEncrypted(crypto.encrypt(apiKey));
        if (baseUrl != null && !baseUrl.isBlank()) {
            config.setBaseUrl(baseUrl);
        }
        if (model != null && !model.isBlank()) {
            config.setModel(model);
        }
        repository.save(config);
    }

    /** 读取用户 LLM 配置（已解密）。 */
    public Optional<Resolved> get(Long userId) {
        return repository.findByUserId(userId)
                .map(c -> new Resolved(c.getBaseUrl(), crypto.decrypt(c.getApiKeyEncrypted()), c.getModel()));
    }

    /** API Key 脱敏（仅显示末 4 位）。 */
    public static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        return apiKey.length() <= 8 ? "****" : apiKey.substring(0, 3) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    /** 已解密配置。 */
    public record Resolved(String baseUrl, String apiKey, String model) {}
}

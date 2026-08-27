package com.videoagent.service.auth;

import com.videoagent.entity.UserAsrConfig;
import com.videoagent.repository.UserAsrConfigRepository;
import com.videoagent.utils.CryptoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 用户级讯飞 ASR 配置：APPID/APIKey/APISecret 均以 AES-GCM 密文落库（非明文），
 * 读取时解密后使用（仅在内部转写调用链中短暂持有），接口返回仅脱敏。
 */
@Service
public class UserAsrConfigService {

    private final UserAsrConfigRepository repository;
    private final CryptoService crypto;

    public UserAsrConfigService(UserAsrConfigRepository repository, CryptoService crypto) {
        this.repository = repository;
        this.crypto = crypto;
    }

    @Transactional
    public void save(Long userId, String appId, String apiKey, String apiSecret) {
        UserAsrConfig config = repository.findByUserId(userId).orElseGet(() -> {
            UserAsrConfig c = new UserAsrConfig();
            c.setUserId(userId);
            return c;
        });
        config.setAppidEncrypted(crypto.encrypt(appId.strip()));
        config.setApikeyEncrypted(crypto.encrypt(apiKey.strip()));
        config.setApisecretEncrypted(crypto.encrypt(apiSecret.strip()));
        repository.save(config);
    }

    /** 读取用户讯飞凭据（已解密）。 */
    public Optional<Resolved> get(Long userId) {
        return repository.findByUserId(userId)
                .map(c -> new Resolved(
                        crypto.decrypt(c.getAppidEncrypted()),
                        crypto.decrypt(c.getApikeyEncrypted()),
                        crypto.decrypt(c.getApisecretEncrypted())));
    }

    /** 凭据脱敏（仅显示首尾）。 */
    public static String mask(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s.length() <= 6 ? "****" : s.substring(0, 2) + "****" + s.substring(s.length() - 2);
    }

    /** 已解密凭据。 */
    public record Resolved(String appId, String apiKey, String apiSecret) {}
}

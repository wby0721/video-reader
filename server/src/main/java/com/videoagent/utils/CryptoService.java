package com.videoagent.utils;

import com.videoagent.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * AES-GCM 加解密：用于用户 API Key 的静态加密（落库非明文）。
 *
 * <p>主密钥来源：环境变量 {@code LLM_MASTER_KEY}（生产必设）；
 * 未配置时回退派生自 JWT secret（仅开发便捷，生产请显式配置）。
 */
@Component
public class CryptoService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] SALT = "videoagent-llm-v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKey key;

    public CryptoService(AppProperties properties) {
        String master = properties.llm().masterKey();
        if (master == null || master.isBlank()) {
            master = properties.jwt().secret();
        }
        this.key = deriveKey(master);
    }

    private static SecretKey deriveKey(String master) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            KeySpec spec = new PBEKeySpec(master.toCharArray(), SALT, 100_000, 256);
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("加密主密钥派生失败", e);
        }
    }

    /** 加密：输出 base64(随机IV || 密文 || GCM标签)。 */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /** 解密 base64(IV||密文||标签)。 */
    public String decrypt(String ciphertextB64) {
        try {
            byte[] all = Base64.getDecoder().decode(ciphertextB64);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] decrypted = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败（主密钥可能已更换）", e);
        }
    }
}

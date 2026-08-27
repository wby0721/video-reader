package com.videoagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 用户级讯飞 ASR 配置（user_asr_config 表）：用户自带 XF 凭据，AES-GCM 加密存储（非明文）。
 */
@Entity
@Table(name = "user_asr_config")
public class UserAsrConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "appid_encrypted", nullable = false, length = 512)
    private String appidEncrypted;

    @Column(name = "apikey_encrypted", nullable = false, length = 512)
    private String apikeyEncrypted;

    @Column(name = "apisecret_encrypted", nullable = false, length = 512)
    private String apisecretEncrypted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getAppidEncrypted() {
        return appidEncrypted;
    }

    public void setAppidEncrypted(String appidEncrypted) {
        this.appidEncrypted = appidEncrypted;
    }

    public String getApikeyEncrypted() {
        return apikeyEncrypted;
    }

    public void setApikeyEncrypted(String apikeyEncrypted) {
        this.apikeyEncrypted = apikeyEncrypted;
    }

    public String getApisecretEncrypted() {
        return apisecretEncrypted;
    }

    public void setApisecretEncrypted(String apisecretEncrypted) {
        this.apisecretEncrypted = apisecretEncrypted;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

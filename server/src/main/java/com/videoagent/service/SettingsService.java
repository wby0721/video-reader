package com.videoagent.service;

import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/**
 * 系统设置（Redis）：ASR 识别引擎选择 + 讯飞在线配额展示。
 *
 * <p>当前为全局设置（讯飞账号共享、仅 1 路并发，按用户隔离无意义）；
 * 后续如需按用户隔离，把 key 改为 user:{userId}:xxx 即可。
 */
@Service
public class SettingsService {

    private static final String KEY_ASR_ENGINE = "settings:asr:engine";
    private static final String KEY_XF_REMAINING_HOURS = "settings:asr:xf-remaining-hours";

    private final RedissonClient redisson;
    /** 默认引擎（Redis 无值时生效）：环境变量 ASR_ENGINE_DEFAULT 可覆盖（服务器部署设 xfyun，本地保持 local）。 */
    private final String defaultEngine;

    public SettingsService(RedissonClient redisson) {
        this.redisson = redisson;
        this.defaultEngine = "xfyun".equals(System.getenv("ASR_ENGINE_DEFAULT")) ? "xfyun" : "local";
    }

    /** 当前 ASR 引擎：local（本地 Qwen3-ASR）/ xfyun（讯飞实时语音转写）。 */
    public String getAsrEngine() {
        Object v = redisson.getBucket(KEY_ASR_ENGINE).get();
        return v == null ? defaultEngine : String.valueOf(v);
    }

    public void setAsrEngine(String engine) {
        redisson.getBucket(KEY_ASR_ENGINE).set("xfyun".equals(engine) ? "xfyun" : "local");
    }

    /** 讯飞在线剩余时长（小时），默认 5.00（由用户按讯飞控制台维护）。 */
    public double getXfyunRemainingHours() {
        Object v = redisson.getBucket(KEY_XF_REMAINING_HOURS).get();
        if (v == null) {
            return 5.0;
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 5.0;
        }
    }

    public void setXfyunRemainingHours(double hours) {
        redisson.getBucket(KEY_XF_REMAINING_HOURS).set(hours);
    }
}

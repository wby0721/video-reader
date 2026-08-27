package com.videoagent.service.auth;

import com.videoagent.config.AppProperties;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/**
 * 基于 Redisson 令牌桶的 AI 请求限流（成本护栏，见方案 §8.3）。
 *
 * <p>两级限流：用户级 {@code limit:ai:user:{userId}} + 全局级 {@code limit:ai:global}。
 * 后续 AI 相关接口（分析提交 / 追问 / 修订）在入口处调用 {@link #tryAcquireUser}/{@link #tryAcquireGlobal}。
 */
@Service
public class RateLimitService {

    private static final String USER_KEY = "limit:ai:user:";
    private static final String GLOBAL_KEY = "limit:ai:global";

    private final RedissonClient redisson;
    private final long userRps;
    private final long globalRps;

    public RateLimitService(RedissonClient redisson, AppProperties properties) {
        this.redisson = redisson;
        this.userRps = properties.rateLimit().userRps();
        this.globalRps = properties.rateLimit().globalRps();
    }

    /** 用户级限流：允许则返回 true。 */
    public boolean tryAcquireUser(Long userId) {
        RRateLimiter limiter = redisson.getRateLimiter(USER_KEY + userId);
        limiter.trySetRate(RateType.OVERALL, userRps, 1, RateIntervalUnit.SECONDS);
        return limiter.tryAcquire();
    }

    /** 全局级限流：允许则返回 true。 */
    public boolean tryAcquireGlobal() {
        RRateLimiter limiter = redisson.getRateLimiter(GLOBAL_KEY);
        limiter.trySetRate(RateType.OVERALL, globalRps, 1, RateIntervalUnit.SECONDS);
        return limiter.tryAcquire();
    }
}

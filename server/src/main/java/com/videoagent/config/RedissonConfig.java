package com.videoagent.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 单节点配置：为分布式锁、令牌桶限流、消费幂等提供支撑。
 * 连接/命令超时收敛到 3s，避免中间件不可用时请求线程长时间挂起。
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(AppProperties properties) {
        AppProperties.Redis redis = properties.redis();
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redis.host() + ":" + redis.port())
                .setConnectTimeout(3000)
                .setTimeout(3000)
                .setRetryAttempts(1);
        return Redisson.create(config);
    }
}

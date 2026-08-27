package com.videoagent.controller;

import com.videoagent.common.ApiResponse;
import com.videoagent.config.AppProperties;
import org.apache.kafka.clients.admin.AdminClient;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 健康检查（免鉴权）：逐项探测 MySQL / Redis / Kafka / MinIO / Qdrant，
 * 任一组件不可用仅标记 DOWN，不阻断主链路（优雅降级原则）。
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final JdbcTemplate jdbcTemplate;
    private final RedissonClient redissonClient;
    private final AdminClient adminClient;
    private final S3Client s3Client;
    private final RestClient qdrantClient;

    public HealthController(JdbcTemplate jdbcTemplate,
                            RedissonClient redissonClient,
                            AdminClient adminClient,
                            S3Client s3Client,
                            AppProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.redissonClient = redissonClient;
        this.adminClient = adminClient;
        this.s3Client = s3Client;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(3_000);
        this.qdrantClient = RestClient.builder()
                .baseUrl(properties.qdrant().baseUrl())
                .requestFactory(factory)
                .build();
    }

    @GetMapping("/health")
    public ApiResponse<HealthResult> health() {
        Map<String, HealthResult.Component> components = new LinkedHashMap<>();
        components.put("mysql", probe("mysql", this::probeMysql));
        components.put("redis", probe("redis", this::probeRedis));
        components.put("kafka", probe("kafka", this::probeKafka));
        components.put("minio", probe("minio", this::probeMinio));
        components.put("qdrant", probe("qdrant", this::probeQdrant));

        long upCount = components.values().stream().filter(c -> "UP".equals(c.status())).count();
        String overall = upCount == components.size() ? "UP" : (upCount > 0 ? "DEGRADED" : "DOWN");
        return ApiResponse.ok(new HealthResult(overall, Instant.now(), components));
    }

    private HealthResult.Component probe(String name, Probe probe) {
        try {
            String detail = probe.run();
            return new HealthResult.Component("UP", detail);
        } catch (Exception e) {
            log.warn("Health probe '{}' failed: {}", name, e.getMessage());
            return new HealthResult.Component("DOWN", e.getMessage());
        }
    }

    private String probeMysql() {
        Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        return "SELECT 1 -> " + one;
    }

    private String probeRedis() {
        long keys = redissonClient.getKeys().count();
        return "keys=" + keys;
    }

    private String probeKafka() throws Exception {
        var nodes = adminClient.describeCluster().nodes().get(3, TimeUnit.SECONDS);
        return "brokers=" + nodes.size();
    }

    private String probeMinio() {
        var buckets = s3Client.listBuckets();
        return "buckets=" + buckets.buckets().size();
    }

    private String probeQdrant() {
        qdrantClient.get().uri("/readyz").retrieve().toBodilessEntity();
        return "readyz OK";
    }

    @FunctionalInterface
    private interface Probe {
        String run() throws Exception;
    }

    public record HealthResult(String overall, Instant timestamp, Map<String, HealthResult.Component> components) {
        public record Component(String status, String detail) {}
    }
}

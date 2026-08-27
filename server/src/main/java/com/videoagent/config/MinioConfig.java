package com.videoagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * MinIO（S3 兼容）配置：AWS SDK v2 S3Client（path-style 访问），启动时确保业务桶存在。
 */
@Configuration
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    @Bean
    public S3Client s3Client(AppProperties properties) {
        AppProperties.Minio minio = properties.minio();
        return S3Client.builder()
                .endpointOverride(URI.create(minio.endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(minio.accessKey(), minio.secretKey())))
                .forcePathStyle(true)
                .build();
    }

    @Bean
    public ApplicationRunner ensureBucket(S3Client s3Client, AppProperties properties) {
        return args -> {
            String bucket = properties.minio().bucket();
            try {
                boolean exists = s3Client.listBuckets().buckets().stream()
                        .anyMatch(b -> bucket.equals(b.name()));
                if (!exists) {
                    s3Client.createBucket(b -> b.bucket(bucket));
                    log.info("MinIO bucket '{}' created", bucket);
                } else {
                    log.info("MinIO bucket '{}' ready", bucket);
                }
            } catch (Exception e) {
                log.warn("MinIO bucket init skipped: {}", e.getMessage());
            }
        };
    }
}

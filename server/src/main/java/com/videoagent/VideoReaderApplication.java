package com.videoagent;

import com.videoagent.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Video Reader — 可信长视频理解 Agent 后端入口。
 *
 * <p>按五关注点分层：ingest（视频预处理）/ retrieval（长视频检索）/ agent（Agent 编排）/
 * trust（证据可信验证）/ eval（评估与可观测），配合 consumer（Kafka 消费）、auth（鉴权与配额）。
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class VideoReaderApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoReaderApplication.class, args);
    }
}

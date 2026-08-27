package com.videoagent.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 配置（方案 §7.1/§7.2）：分析任务主题 + 死信主题；AdminClient 供健康探测与运维使用。
 * 消费可靠性：有限重试（3 次退避重试）→ 毒消息收敛转死信主题；主题由 KafkaAdmin 启动时自动创建。
 */
@Configuration
public class KafkaConfig {

    public static final String ANALYSIS_TOPIC = "video-analysis-topic";
    public static final String ANALYSIS_DEAD_TOPIC = "video-analysis-dead-topic";

    @Bean
    public NewTopic videoAnalysisTopic() {
        return new NewTopic(ANALYSIS_TOPIC, 1, (short) 1);
    }

    @Bean
    public NewTopic videoAnalysisDeadTopic() {
        return new NewTopic(ANALYSIS_DEAD_TOPIC, 1, (short) 1);
    }

    @Bean(destroyMethod = "close")
    public AdminClient adminClient(KafkaProperties kafkaProperties, SslBundles sslBundles) {
        return AdminClient.create(kafkaProperties.buildAdminProperties(sslBundles));
    }

    /**
     * 消费错误处理：退避重试 3 次后转入死信主题（毒消息收敛，避免无限重投）。
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception ex) -> new TopicPartition(ANALYSIS_DEAD_TOPIC, record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(2_000L, 3));
    }

    /** 覆盖默认容器工厂，挂上自定义错误处理器。 */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> kafkaConsumerFactory,
            ObjectProvider<DefaultErrorHandler> errorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, kafkaConsumerFactory);
        factory.setCommonErrorHandler(errorHandler.getIfAvailable());
        return factory;
    }
}

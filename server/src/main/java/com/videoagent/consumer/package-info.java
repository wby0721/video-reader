/**
 * consumer —— Kafka 消费者。
 *
 * <p>异步消费视频分析任务（video-analysis-topic），消费幂等 + 有限重试 + 死信收敛，
 * 状态机 QUEUED → … → COMPLETED，Checkpoint 断点恢复（阶段二/四实现）。
 */
package com.videoagent.consumer;

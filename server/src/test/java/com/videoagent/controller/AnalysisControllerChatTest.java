package com.videoagent.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.common.ApiResponse;
import com.videoagent.dto.ChatEntry;
import com.videoagent.dto.ChatRequest;
import com.videoagent.dto.EvidenceHit;
import com.videoagent.dto.RetrievalTrace;
import com.videoagent.dto.VideoChunk;
import com.videoagent.dto.VideoContext;
import com.videoagent.dto.VideoSegment;
import com.videoagent.entity.MediaFile;
import com.videoagent.repository.AnalysisFeedbackRepository;
import com.videoagent.repository.MediaFileRepository;
import com.videoagent.service.CheckpointService;
import com.videoagent.service.ChatHistoryViewService;
import com.videoagent.service.StageEventPublisher;
import com.videoagent.service.agent.AgentLoopService;
import com.videoagent.service.ai.LlmProvider;
import com.videoagent.service.auth.RateLimitService;
import com.videoagent.service.eval.AgentEvaluationService;
import com.videoagent.service.eval.AgentTelemetry;
import com.videoagent.service.retrieval.ChatEvidenceService;
import com.videoagent.service.retrieval.GlobalKnowledgeSearchService;
import com.videoagent.service.retrieval.HybridQuery;
import com.videoagent.service.retrieval.QueryRewriter;
import com.videoagent.service.retrieval.RetrievalIndexService;
import com.videoagent.service.retrieval.VideoEvidenceRetrievalService;
import com.videoagent.service.trust.FidelityChecker;
import com.videoagent.utils.CurrentUser;
import com.videoagent.utils.LlmClient;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RedissonClient;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisControllerChatTest {

    @Test
    @SuppressWarnings("unchecked")
    void chatRewritesOnceRetrievesOriginalEvidenceAndDoesNotEnterAgentLoop() {
        MediaFileRepository mediaRepo = mock(MediaFileRepository.class);
        CheckpointService checkpoints = mock(CheckpointService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        VideoEvidenceRetrievalService retrieval = mock(VideoEvidenceRetrievalService.class);
        AgentLoopService agentLoop = mock(AgentLoopService.class);
        LlmProvider llmProvider = mock(LlmProvider.class);
        QueryRewriter queryRewriter = mock(QueryRewriter.class);
        LlmClient model = mock(LlmClient.class);

        AnalysisController controller = new AnalysisController(
                mediaRepo, checkpoints, mock(StageEventPublisher.class), rateLimit,
                mock(KafkaTemplate.class), new ObjectMapper(), retrieval, agentLoop,
                mock(RedissonClient.class), mock(AnalysisFeedbackRepository.class),
                mock(AgentTelemetry.class), mock(AgentEvaluationService.class),
                mock(FidelityChecker.class), llmProvider,
                mock(GlobalKnowledgeSearchService.class), queryRewriter, new ChatEvidenceService(),
                new ChatHistoryViewService(checkpoints));

        MediaFile media = new MediaFile();
        media.setId(11L);
        media.setUserId(7L);
        media.setContentHash("hash");
        media.setTitle("网络课程");
        media.setFilename("network.mp4");
        when(mediaRepo.findByIdAndUserId(11L, 7L)).thenReturn(Optional.of(media));
        when(rateLimit.tryAcquireUser(7L)).thenReturn(true);
        when(rateLimit.tryAcquireGlobal()).thenReturn(true);
        VideoContext context = VideoContext.of("11", "goal", List.of());
        when(checkpoints.loadVideoContext(11L)).thenReturn(Optional.of(context));
        List<ChatEntry> prior = new ArrayList<>(List.of(
                new ChatEntry("user", "TCP 有几次握手？", 1L, List.of()),
                new ChatEntry("assistant", "视频介绍了三次握手。", 2L, List.of())));
        when(checkpoints.load(eq(11L), eq("media-chat"), any(TypeReference.class)))
                .thenReturn(Optional.of(prior));
        when(llmProvider.forUser(7L)).thenReturn(model);
        when(queryRewriter.rewriteConversation("它为什么需要第三次？", prior, "网络课程", model))
                .thenReturn(new QueryRewriter.ConversationRewrite(
                        "TCP 三次握手为什么需要第三次确认？",
                        "TCP 第三次确认原因", List.of("TCP", "第三次确认"), List.of("ACK")));

        VideoSegment segment = VideoSegment.of(75_000, 90_000,
                "第三次确认用于确认客户端能够接收服务端响应。", List.of("ACK"), List.of());
        VideoChunk chunk = VideoChunk.indexed(75_000, 165_000, segment.transcript(),
                        List.of("ACK"), List.of(segment), "chunk-a", 0, "hash",
                        RetrievalIndexService.INDEX_VERSION)
                .withEnrichment("第三次确认", List.of("TCP"))
                .withEmbedding(List.of(1f));
        EvidenceHit hit = new EvidenceHit(75_000, 165_000, "chunk-a", "第三次确认",
                List.of("TCP"), .9, List.of("TCP"), "QDRANT+BM25",
                EvidenceHit.ScoreType.RERANKER_SIGMOID);
        RetrievalTrace rawTrace = new RetrievalTrace(
                new RetrievalTrace.Query("它为什么需要第三次？", null, "TCP 第三次确认原因",
                        "TCP 第三次确认", "ACK", List.of("TCP", "第三次确认"), List.of("ACK")),
                new RetrievalTrace.Parameters(25, 25, 10, 10, 5, 60,
                        Map.of("DENSE", .5, "BM25", .35, "OCR", .15), null, null),
                List.of(new RetrievalTrace.RecallHit(1, "chunk-a", .88, "COSINE_SIMILARITY",
                        75_000, 165_000, "第三次确认", List.of("TCP"))),
                List.of(), List.of(), List.of(), List.of(), "QDRANT", "APPLIED", 123, null);
        when(retrieval.searchPrepared(eq(11L), eq("hash"), eq(context), any(HybridQuery.class), eq(5), eq(7L)))
                .thenReturn(new VideoEvidenceRetrievalService.PreparedSearch(
                        List.of(hit), List.of(chunk), "QDRANT", rawTrace));
        when(model.chat(anyString(), eq(1_200))).thenReturn("第三次确认用于确认响应已被接收。[证据1]");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(CurrentUser.ATTR_USER_ID)).thenReturn(7L);

        ApiResponse<AnalysisController.ChatResponse> response = controller.chat(
                new ChatRequest(11L, "它为什么需要第三次？", List.of()), request);

        assertThat(response.data().answer()).contains("[证据1]");
        assertThat(response.data().insufficientEvidence()).isFalse();
        assertThat(response.data().evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.chunkIds()).containsExactly("chunk-a");
            assertThat(evidence.quote()).contains("第三次确认用于确认客户端");
        });
        ArgumentCaptor<String> answerPrompt = ArgumentCaptor.forClass(String.class);
        verify(model).chat(answerPrompt.capture(), eq(1_200));
        assertThat(answerPrompt.getValue())
                .contains("独立问题：TCP 三次握手为什么需要第三次确认？",
                        "EvidencePack", "chunkIds: chunk-a", "转写原文:", "[证据1]",
                        "不能断言整段视频绝对没有提及", "回答应完整收尾");
        ChatEntry savedAssistant = response.data().history().getLast();
        assertThat(savedAssistant.evidencePack()).singleElement().satisfies(saved -> {
            assertThat(saved.chunkIds()).containsExactly("chunk-a");
            assertThat(saved.startMs()).isEqualTo(75_000);
        });
        assertThat(savedAssistant.retrievalTrace()).isNotNull();
        assertThat(savedAssistant.retrievalTrace().query().standaloneQuestion())
                .isEqualTo("TCP 三次握手为什么需要第三次确认？");
        assertThat(savedAssistant.retrievalTrace().parameters().minRerankerScore()).isEqualTo(.5);
        assertThat(savedAssistant.retrievalTrace().parameters().rejectLowRelevance()).isFalse();
        assertThat(savedAssistant.retrievalTrace().assessment().status())
                .isEqualTo(com.videoagent.dto.RetrievalAssessment.Status.CANDIDATE_EVIDENCE);
        verify(agentLoop, never()).run(any(), anyString(), any(), any());
        verify(checkpoints).save(eq(11L), eq("media-chat"), eq("CHAT"), any());
    }
}

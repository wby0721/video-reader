package com.videoagent.service.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.dto.ChatEntry;
import com.videoagent.utils.LlmClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryRewriterTest {

    private final QueryRewriter rewriter = new QueryRewriter(new ObjectMapper());

    @Test
    void conversationRewriteUsesOnlyRecentHistoryAndParsesStructuredQuery() {
        LlmClient model = mock(LlmClient.class);
        when(model.chat(anyString(), eq(220))).thenReturn("""
                ```json
                {"standaloneQuestion":"TCP 三次握手为什么需要第三次确认？",
                 "semanticQuery":"TCP 三次握手第三次确认的原因",
                 "keywords":["TCP","三次握手","第三次确认"],
                 "ocrKeywords":["SYN","ACK"]}
                ```
                """);
        List<ChatEntry> history = new ArrayList<>();
        history.add(entry("应该被裁掉的最旧问题"));
        history.add(entry("应该被裁掉的最旧回答"));
        for (int i = 0; i < 8; i++) history.add(entry("最近消息" + i));

        QueryRewriter.ConversationRewrite result = rewriter.rewriteConversation(
                "它为什么需要第三次？", history, "网络原理", model);

        assertThat(result.standaloneQuestion()).isEqualTo("TCP 三次握手为什么需要第三次确认？");
        assertThat(result.semanticQuery()).isEqualTo("TCP 三次握手第三次确认的原因");
        assertThat(result.keywords()).containsExactly("TCP", "三次握手", "第三次确认");
        assertThat(result.ocrKeywords()).containsExactly("SYN", "ACK");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(model).chat(prompt.capture(), eq(220));
        assertThat(prompt.getValue()).contains("最近消息0", "最近消息7", "网络原理", "它为什么需要第三次？")
                .doesNotContain("应该被裁掉的最旧问题");
    }

    @Test
    void malformedConversationRewriteFallsBackToOriginalQuestion() {
        LlmClient model = mock(LlmClient.class);
        when(model.chat(anyString(), eq(220))).thenReturn("这不是 JSON");

        QueryRewriter.ConversationRewrite result = rewriter.rewriteConversation(
                "TCP 和 UDP 区别", List.of(), "课程", model);

        assertThat(result.standaloneQuestion()).isEqualTo("TCP 和 UDP 区别");
        assertThat(result.semanticQuery()).isEqualTo("TCP 和 UDP 区别");
        assertThat(result.keywords()).containsExactly("TCP", "和", "UDP", "区别");
        assertThat(result.ocrKeywords()).isEqualTo(result.keywords());
    }

    private static ChatEntry entry(String content) {
        return new ChatEntry("user", content, 1L, List.of());
    }
}

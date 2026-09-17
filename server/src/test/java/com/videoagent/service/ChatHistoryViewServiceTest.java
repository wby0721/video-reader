package com.videoagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.videoagent.dto.ChatEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ChatHistoryViewServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void undoAndClearHideWorkspaceRoundsWithoutDeletingAuditTrace() {
        CheckpointService checkpoints = mock(CheckpointService.class);
        AtomicReference<List<ChatEntry>> audit = new AtomicReference<>(new ArrayList<>(List.of(
                entry("user", "问题一", 1), entry("assistant", "回答一", 2),
                entry("user", "问题二", 3), entry("assistant", "回答二", 4))));
        AtomicReference<ChatHistoryViewService.ViewState> view = new AtomicReference<>();
        when(checkpoints.load(eq(9L), eq(ChatHistoryViewService.CP_CHAT), any(TypeReference.class)))
                .thenAnswer(invocation -> Optional.of(audit.get()));
        when(checkpoints.load(eq(9L), eq(ChatHistoryViewService.CP_CHAT_VIEW), any(TypeReference.class)))
                .thenAnswer(invocation -> Optional.ofNullable(view.get()));
        doAnswer(invocation -> {
            view.set(invocation.getArgument(3));
            return null;
        }).when(checkpoints).save(eq(9L), eq(ChatHistoryViewService.CP_CHAT_VIEW), eq("VIEW"), any());

        ChatHistoryViewService service = new ChatHistoryViewService(checkpoints);
        assertThat(service.undoLastRound(9L)).extracting(ChatEntry::content)
                .containsExactly("问题一", "回答一");
        assertThat(service.audit(9L)).hasSize(4);

        assertThat(service.clearVisible(9L)).isEmpty();
        assertThat(service.visible(9L)).isEmpty();
        assertThat(service.audit(9L)).extracting(ChatEntry::content)
                .containsExactly("问题一", "回答一", "问题二", "回答二");
        verify(checkpoints, never()).save(eq(9L), eq(ChatHistoryViewService.CP_CHAT), anyString(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void newRoundAfterClearIsVisibleAndOldRoundCannotInfluenceConversation() {
        CheckpointService checkpoints = mock(CheckpointService.class);
        List<ChatEntry> audit = new ArrayList<>(List.of(
                entry("user", "旧问题", 1), entry("assistant", "旧回答", 2)));
        AtomicReference<ChatHistoryViewService.ViewState> view = new AtomicReference<>();
        when(checkpoints.load(eq(10L), eq(ChatHistoryViewService.CP_CHAT), any(TypeReference.class)))
                .thenReturn(Optional.of(audit));
        when(checkpoints.load(eq(10L), eq(ChatHistoryViewService.CP_CHAT_VIEW), any(TypeReference.class)))
                .thenAnswer(invocation -> Optional.ofNullable(view.get()));
        doAnswer(invocation -> {
            view.set(invocation.getArgument(3));
            return null;
        }).when(checkpoints).save(eq(10L), eq(ChatHistoryViewService.CP_CHAT_VIEW), eq("VIEW"), any());

        ChatHistoryViewService service = new ChatHistoryViewService(checkpoints);
        service.clearVisible(10L);
        audit.add(entry("user", "新问题", 3));
        audit.add(entry("assistant", "新回答", 4));

        assertThat(service.visible(10L)).extracting(ChatEntry::content)
                .containsExactly("新问题", "新回答");
        assertThat(service.audit(10L)).hasSize(4);
    }

    private static ChatEntry entry(String role, String content, long ts) {
        return new ChatEntry(role, content, ts, List.of());
    }
}

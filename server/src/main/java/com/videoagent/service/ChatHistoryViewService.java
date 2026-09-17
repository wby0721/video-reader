package com.videoagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.videoagent.dto.ChatEntry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 将不可删除的追问审计记录与工作台可见状态分离。
 * 清空/撤销只记录被隐藏的审计下标，Trace 仍读取完整 media-chat。
 */
@Service
public class ChatHistoryViewService {

    public static final String CP_CHAT = "media-chat";
    public static final String CP_CHAT_VIEW = "media-chat-view";
    private static final TypeReference<List<ChatEntry>> CHAT_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<ViewState> VIEW_STATE_TYPE = new TypeReference<>() {};

    private final CheckpointService checkpointService;

    public ChatHistoryViewService(CheckpointService checkpointService) {
        this.checkpointService = checkpointService;
    }

    /** 完整审计历史：只追加，供后台 Trace 使用。 */
    public List<ChatEntry> audit(Long mediaId) {
        Optional<List<ChatEntry>> loaded = checkpointService.load(mediaId, CP_CHAT, CHAT_LIST_TYPE);
        return new ArrayList<>(loaded == null ? List.of() : loaded.orElse(List.of()));
    }

    public void saveAudit(Long mediaId, List<ChatEntry> audit) {
        checkpointService.save(mediaId, CP_CHAT, "CHAT", audit == null ? List.of() : audit);
    }

    /** 工作台可见历史，也是下一轮指代消解能够读取的历史。 */
    public List<ChatEntry> visible(Long mediaId) {
        return visible(mediaId, audit(mediaId));
    }

    public List<ChatEntry> visible(Long mediaId, List<ChatEntry> audit) {
        return visible(audit, state(mediaId).hiddenIndexes());
    }

    private static List<ChatEntry> visible(List<ChatEntry> audit, Set<Integer> hidden) {
        List<ChatEntry> result = new ArrayList<>();
        if (audit == null) return result;
        for (int i = 0; i < audit.size(); i++) {
            if (!hidden.contains(i)) result.add(audit.get(i));
        }
        return List.copyOf(result);
    }

    /** 隐藏当前全部可见记录；不改写 media-chat。 */
    public List<ChatEntry> clearVisible(Long mediaId) {
        List<ChatEntry> audit = audit(mediaId);
        Set<Integer> hidden = new LinkedHashSet<>(state(mediaId).hiddenIndexes());
        for (int i = 0; i < audit.size(); i++) hidden.add(i);
        saveState(mediaId, hidden);
        return List.of();
    }

    /** 撤销最近一轮可见的 user + assistant；异常单条 user 也能单独撤销。 */
    public List<ChatEntry> undoLastRound(Long mediaId) {
        List<ChatEntry> audit = audit(mediaId);
        Set<Integer> hidden = new LinkedHashSet<>(state(mediaId).hiddenIndexes());
        List<Integer> visible = new ArrayList<>();
        for (int i = 0; i < audit.size(); i++) if (!hidden.contains(i)) visible.add(i);
        if (visible.isEmpty()) return List.of();

        int last = visible.getLast();
        hidden.add(last);
        if ("assistant".equalsIgnoreCase(audit.get(last).role())) {
            for (int i = visible.size() - 2; i >= 0; i--) {
                int candidate = visible.get(i);
                if ("user".equalsIgnoreCase(audit.get(candidate).role())) {
                    hidden.add(candidate);
                    break;
                }
            }
        }
        saveState(mediaId, hidden);
        return visible(audit, hidden);
    }

    private ViewState state(Long mediaId) {
        Optional<ViewState> loaded = checkpointService.load(mediaId, CP_CHAT_VIEW, VIEW_STATE_TYPE);
        return loaded == null ? new ViewState(Set.of()) : loaded.orElseGet(() -> new ViewState(Set.of()));
    }

    private void saveState(Long mediaId, Set<Integer> hidden) {
        checkpointService.save(mediaId, CP_CHAT_VIEW, "VIEW", new ViewState(hidden));
    }

    public record ViewState(Set<Integer> hiddenIndexes) {
        public ViewState {
            hiddenIndexes = hiddenIndexes == null ? Set.of() : Set.copyOf(hiddenIndexes);
        }
    }
}

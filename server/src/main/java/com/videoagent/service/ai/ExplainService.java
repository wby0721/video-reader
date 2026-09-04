package com.videoagent.service.ai;

import com.videoagent.dto.VideoContext;
import com.videoagent.dto.VideoSegment;
import com.videoagent.service.CheckpointService;
import com.videoagent.utils.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 术语解释服务：用户在 ASR 转写中选中一个词/片段 →
 * 取选中位置前后各 120s 的视频语境 + Tavily 联网检索（10s 超时降级）→
 * LLM 融合解释。与「追问」的区别：单次、面向词/短句、即时悬浮窗展示。
 */
@Service
public class ExplainService {

    private static final Logger log = LoggerFactory.getLogger(ExplainService.class);

    /** 语境窗口：选中位置前后各 2 分钟的视频原话。 */
    private static final long CONTEXT_WINDOW_MS = 120_000L;
    private static final int MAX_CONTEXT_CHARS = 1500;

    private final CheckpointService checkpointService;
    private final TavilyClient tavilyClient;
    private final LlmProvider llmProvider;

    public ExplainService(CheckpointService checkpointService, TavilyClient tavilyClient,
                          LlmProvider llmProvider) {
        this.checkpointService = checkpointService;
        this.tavilyClient = tavilyClient;
        this.llmProvider = llmProvider;
    }

    public ExplainResult explain(Long userId, Long mediaId, String selectedText, long contextStartMs) {
        // 1) 视频语境：选中位置前后各 120s 的 ASR 文本
        String contextText = "";
        VideoContext context = checkpointService.loadVideoContext(mediaId).orElse(null);
        if (context != null && context.segments() != null) {
            String around = context.segments().stream()
                    .filter(s -> s.transcript() != null && !s.transcript().isBlank())
                    .filter(s -> s.startMs() >= contextStartMs - CONTEXT_WINDOW_MS
                            && s.startMs() <= contextStartMs + CONTEXT_WINDOW_MS)
                    .map(VideoSegment::transcript)
                    .collect(Collectors.joining(" "));
            contextText = around.length() > MAX_CONTEXT_CHARS ? around.substring(0, MAX_CONTEXT_CHARS) : around;
        }

        // 2) Tavily 联网检索（10s 超时，失败/无 Key → 空列表降级）
        List<TavilyClient.WebResult> web = tavilyClient.search(selectedText, 3);

        // 3) LLM 融合解释（用户自带 Key）
        LlmClient model = llmProvider.forUser(userId);
        if (model == null) {
            return new ExplainResult("未配置 LLM Key：请在「个人设置」提交自己的 Key 后使用术语解释。", false);
        }
        String explanation = model.chat(buildPrompt(selectedText, contextText, web), 300);
        if (explanation == null || explanation.isBlank()) {
            return new ExplainResult("解释生成失败，请稍后重试。", !web.isEmpty());
        }
        return new ExplainResult(explanation.strip(), !web.isEmpty());
    }

    private static String buildPrompt(String selected, String contextText, List<TavilyClient.WebResult> web) {
        String webText;
        if (web == null || web.isEmpty()) {
            webText = "（无网络资料）";
        } else {
            webText = web.stream()
                    .map(w -> "- " + w.title() + "\n  " + w.content())
                    .collect(Collectors.joining("\n"));
        }
        return """
                你是视频学习助手。用户在课程视频里选中了一个不理解的词/片段，请结合「视频语境」和「网络资料」（若有）给出通俗准确的中文解释。
                要求：
                - 直接输出解释正文（150 字以内），不要输出 JSON、不要加"解释："等前缀；
                - 优先贴合视频语境来讲；网络资料只用于补充视频没讲清楚的部分；
                - 网络资料为空时，仅依据视频语境解释即可。
                选中的内容：%s
                【视频语境】（选中位置前后各约 2 分钟的视频原话）：
                %s
                【网络资料】（若为空表示检索不可用）：
                %s
                """.formatted(selected,
                contextText == null || contextText.isBlank() ? "（无视频语境）" : contextText,
                webText);
    }

    /** 解释结果：解释正文 + 是否用到了网络资料。 */
    public record ExplainResult(String explanation, boolean webUsed) {}
}

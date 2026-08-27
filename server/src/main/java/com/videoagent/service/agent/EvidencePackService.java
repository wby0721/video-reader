package com.videoagent.service.agent;

import com.videoagent.dto.AgentPlan;
import com.videoagent.dto.EvidenceHit;
import com.videoagent.dto.VideoChunk;
import com.videoagent.dto.VideoContext;
import com.videoagent.dto.VideoSegment;
import com.videoagent.service.retrieval.RetrievalIndexService;
import com.videoagent.service.retrieval.VideoEvidenceRetrievalService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 证据打包：按计划任务对证据片段做 TopK 语义召回，裁剪为紧凑文本
 * （Agent 只看到相关片段，控制 Token 成本；Critic 反馈可定向补检索）。
 */
@Service
public class EvidencePackService {

    private static final int CHUNKS_PER_TASK = 2;
    private static final int MAX_TASK_CHUNKS = 8;
    /** 定向补检索上限：Critic 指认的时间戳必须进包，不被任务项挤掉。 */
    private static final int MAX_TARGETED_CHUNKS = 4;
    /**
     * 任务项转写上限：取「完整块」而非块头部。
     * 教训：5 分钟块的转写约 1500~2500 字，若只取头部 400 字，块内的关键细节
     * （如"百度网盘 54s""服务器长期运行 103s""10MB/s 670s"）会被截断，
     * Executor 只能看到泛泛的开头 → 结论绑到错误证据/被迫编造细节。
     */
    private static final int TRANSCRIPT_CAP = 2000;
    private static final int TARGETED_CAP = 2500;
    private static final int VISUAL_CAP = 200;

    private final VideoEvidenceRetrievalService retrievalService;
    private final RetrievalIndexService indexService;

    public EvidencePackService(VideoEvidenceRetrievalService retrievalService, RetrievalIndexService indexService) {
        this.retrievalService = retrievalService;
        this.indexService = indexService;
    }

    /**
     * 为 Agent 构建证据包。
     *
     * @param tasks    计划任务（语义召回查询）
     * @param required 定向补检索时间戳（Critic 反馈，可为空）
     */
    public EvidencePack build(Long mediaId, String contentHash, VideoContext context, List<VideoChunk> chunks,
                              List<String> tasks, List<Long> required, Long userId) {
        List<EvidenceItem> items = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();

        if (tasks != null) {
            for (String task : tasks) {
                // 任务本身已足够具体，跳过意图改写（省 LLM 调用）
                List<EvidenceHit> hits = retrievalService.searchNoRewrite(
                        mediaId, contentHash, context, task, CHUNKS_PER_TASK, userId);
                for (EvidenceHit hit : hits) {
                    if (!seen.add(hit.startMs())) {
                        continue;
                    }
                    VideoChunk chunk = findChunk(chunks, hit.startMs());
                    items.add(toItem(chunk, hit));
                    if (items.size() >= MAX_TASK_CHUNKS) {
                        break;
                    }
                }
                if (items.size() >= MAX_TASK_CHUNKS) {
                    break;
                }
            }
        }

        // 定向补检索：Critic 指认的时间戳是下一轮修正的关键依据，必须追加进包
        // （不能因任务项已占满而提前返回——否则 600000/900000 这类被点名的时间戳永远缺席，轮次空转）
        if (required != null) {
            for (long ts : required) {
                VideoChunk chunk = findChunk(chunks, ts);
                if (chunk == null || !seen.add(chunk.startTime())) {
                    continue;
                }
                items.add(new EvidenceItem(chunk.startTime(), chunk.endTime(), "TARGETED",
                        trim(chunk.transcript(), TARGETED_CAP), chunk.rawSegments()));
                if (items.size() >= MAX_TASK_CHUNKS + MAX_TARGETED_CHUNKS) {
                    break;
                }
            }
        }
        return new EvidencePack(items, seen);
    }

    private static EvidenceItem toItem(VideoChunk chunk, EvidenceHit hit) {
        if (chunk == null) {
            return new EvidenceItem(hit.startMs(), hit.endMs(), hit.source(),
                    trim(hit.summary(), TRANSCRIPT_CAP), null);
        }
        // 证据包只展示原文（转写 + OCR），供 Executor 逐字引用（L1 原文保真可核验）
        String content = chunk.transcript() == null ? "" : trim(chunk.transcript(), TRANSCRIPT_CAP);
        if (chunk.visualTexts() != null && !chunk.visualTexts().isEmpty()) {
            content += "\n画面文字：" + trim(String.join("；", chunk.visualTexts()), VISUAL_CAP);
        }
        // rawSegments 供证据绑定回精确的 ASR 片段时间戳（而非块级粗粒度）
        return new EvidenceItem(chunk.startTime(), chunk.endTime(), "ASR+OCR", content, chunk.rawSegments());
    }

    private static VideoChunk findChunk(List<VideoChunk> chunks, long tsMs) {
        if (chunks == null) {
            return null;
        }
        return chunks.stream()
                .filter(c -> tsMs >= c.startTime() && tsMs < c.endTime())
                .findFirst().orElse(null);
    }

    private static String trim(String s, int cap) {
        return s == null ? "" : (s.length() > cap ? s.substring(0, cap) : s);
    }

    /** Agent 视角的证据包（紧凑文本，控制 Token）。 */
    public record EvidencePack(List<EvidenceItem> items, Set<Long> coveredTimestamps) {
        public String toPromptText() {
            if (items == null || items.isEmpty()) {
                return "（未召回任何证据片段）";
            }
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (EvidenceItem item : items) {
                sb.append(String.format("[证据%d] 时间 %dms~%dms 来源=%s%n%s%n",
                        ++i, item.startMs(), item.endMs(), item.source(), item.content()));
            }
            return sb.toString();
        }
    }

    /**
     * 单条证据（时间戳 + 来源 + 裁剪文本）。
     *
     * @param rawSegments 块内原始片段（供绑定回精确 ASR 片段时间戳；可能为 null）
     */
    public record EvidenceItem(long startMs, long endMs, String source, String content,
                               List<VideoSegment> rawSegments) {}
}

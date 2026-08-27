package com.videoagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.dto.AnalysisMode;
import com.videoagent.dto.AnalysisResult;
import com.videoagent.entity.MediaFile;
import com.videoagent.repository.MediaFileRepository;
import com.videoagent.service.agent.AgentLoopService;
import com.videoagent.service.ai.LlmProvider;
import com.videoagent.utils.LlmClient;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 视频标题服务：LLM 依据最新分析结果自动生成简短标题（≤15 汉字），
 * 标题为空时生成，用户手动修改后不再覆盖。
 */
@Service
public class MediaTitleService {

    private static final Logger log = LoggerFactory.getLogger(MediaTitleService.class);

    private static final String LAST_GOAL_KEY = "analysis:last-goal";

    private final MediaFileRepository mediaFileRepository;
    private final CheckpointService checkpointService;
    private final LlmProvider llmProvider;
    private final ObjectMapper objectMapper;
    private final RedissonClient redisson;

    public MediaTitleService(MediaFileRepository mediaFileRepository, CheckpointService checkpointService,
                             LlmProvider llmProvider, ObjectMapper objectMapper, RedissonClient redisson) {
        this.mediaFileRepository = mediaFileRepository;
        this.checkpointService = checkpointService;
        this.llmProvider = llmProvider;
        this.objectMapper = objectMapper;
        this.redisson = redisson;
    }

    /** 分析完成后调用：标题为空时，用本次分析结果自动生成。 */
    @Transactional
    public void ensureTitleAfterAnalysis(Long mediaId, String goal, AnalysisMode mode, Long userId) {
        mediaFileRepository.findByIdAndUserId(mediaId, userId).ifPresent(m -> {
            if (m.getTitle() != null && !m.getTitle().isBlank()) {
                return;
            }
            generateAndSave(mediaId, goal, mode, m, userId);
        });
    }

    /** 前端「自动生成」按钮：用最近一次分析结果生成；标题已存在时不覆盖，返回是否生成。 */
    @Transactional
    public boolean autoGenerate(Long mediaId, Long userId) {
        MediaFile m = mediaFileRepository.findByIdAndUserId(mediaId, userId).orElse(null);
        if (m == null) {
            return false;
        }
        if (m.getTitle() != null && !m.getTitle().isBlank()) {
            return false;
        }
        Optional<LastGoal> lgOpt = lastGoal(mediaId);
        if (lgOpt.isEmpty()) {
            return false;
        }
        LastGoal lg = lgOpt.get();
        return generateAndSave(mediaId, lg.goal(), lg.mode(), m, userId);
    }

    private boolean generateAndSave(Long mediaId, String goal, AnalysisMode mode, MediaFile m, Long userId) {
        try {
            String goalKey = AgentLoopService.goalKey(goal, mode);
            AnalysisResult result = checkpointService.load(mediaId, goalKey + "-final", AnalysisResult.class).orElse(null);
            if (result == null || result.title() == null || result.title().isBlank()) {
                return false;
            }
            String title = generateTitle(result, userId);
            if (title == null || title.isBlank()) {
                return false;
            }
            m.setTitle(title.length() > 64 ? title.substring(0, 64) : title);
            mediaFileRepository.save(m);
            log.info("自动生成视频标题 mediaId={} title={}", mediaId, m.getTitle());
            return true;
        } catch (Exception e) {
            log.warn("自动标题生成失败 mediaId={}: {}", mediaId, e.getMessage());
            return false;
        }
    }

    private String generateTitle(AnalysisResult result, Long userId) {
        List<String> conclusions = result.conclusions();
        String conclusionText = conclusions == null ? ""
                : String.join("；", conclusions.stream().limit(3).toList());
        String prompt = "你是视频标题编辑。根据视频的分析结果，用不超过 15 个汉字给视频起一个简洁准确的标题。"
                + "只输出标题本身，不要引号、标点或任何解释。\n"
                + "分析标题：" + result.title() + "\n核心结论：" + conclusionText;
        LlmClient model = llmProvider.forUser(userId);
        String out = model.chat(prompt, 64);
        if (out == null) {
            return null;
        }
        return out.strip().replaceAll("^[\"'“”]+|[\"'“”]+$", "");
    }

    private record LastGoal(String goal, AnalysisMode mode) {}

    private Optional<LastGoal> lastGoal(Long mediaId) {
        Object json = redisson.getMap(LAST_GOAL_KEY).get(mediaId);
        if (json == null) {
            return Optional.empty();
        }
        try {
            var node = objectMapper.readTree(json.toString());
            return Optional.of(new LastGoal(node.path("goal").asText(),
                    AnalysisMode.parse(node.path("mode").asText())));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}

package com.videoagent.controller;

import com.videoagent.common.ApiResponse;
import com.videoagent.common.BusinessException;
import com.videoagent.dto.ExplainRequest;
import com.videoagent.repository.MediaFileRepository;
import com.videoagent.service.ai.ExplainService;
import com.videoagent.service.auth.RateLimitService;
import com.videoagent.utils.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 术语解释接口：用户在 ASR 转写中选中片段 → 结合视频语境 + 联网检索生成解释。
 * 与追问共用令牌桶额度（一次 LLM 消费 = 一个令牌）。
 */
@RestController
@RequestMapping("/analysis")
public class ExplainController {

    private final MediaFileRepository mediaFileRepository;
    private final RateLimitService rateLimitService;
    private final ExplainService explainService;

    public ExplainController(MediaFileRepository mediaFileRepository,
                             RateLimitService rateLimitService,
                             ExplainService explainService) {
        this.mediaFileRepository = mediaFileRepository;
        this.rateLimitService = rateLimitService;
        this.explainService = explainService;
    }

    @PostMapping("/explain")
    public ApiResponse<Map<String, Object>> explain(@Valid @RequestBody ExplainRequest request,
                                                    HttpServletRequest http) {
        Long userId = CurrentUser.userId(http);
        mediaFileRepository.findByIdAndUserId(request.mediaId(), userId)
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));
        // 成本护栏：与提交分析/追问/自动标题共用用户级 + 全局级令牌桶
        if (!rateLimitService.tryAcquireUser(userId)) {
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }
        if (!rateLimitService.tryAcquireGlobal()) {
            throw new BusinessException(429, "系统繁忙，请稍后再试");
        }
        ExplainService.ExplainResult r = explainService.explain(
                userId, request.mediaId(), request.selectedText(), request.contextStartMs());
        return ApiResponse.ok(Map.of("explanation", r.explanation(), "webUsed", r.webUsed()));
    }
}

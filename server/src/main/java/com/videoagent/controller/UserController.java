package com.videoagent.controller;

import com.videoagent.common.ApiResponse;
import com.videoagent.dto.AsrConfigDto;
import com.videoagent.dto.AsrConfigRequest;
import com.videoagent.dto.LlmConfigDto;
import com.videoagent.dto.LlmConfigRequest;
import com.videoagent.dto.UserDto;
import com.videoagent.service.auth.AuthService;
import com.videoagent.service.auth.UserAsrConfigService;
import com.videoagent.service.auth.UserLlmConfigService;
import com.videoagent.utils.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口（需鉴权）：数据隔离按 JWT 中 userId 归属校验。
 */
@RestController
@RequestMapping("/user")
public class UserController {

    private final AuthService authService;
    private final UserLlmConfigService userLlmConfigService;
    private final UserAsrConfigService userAsrConfigService;

    public UserController(AuthService authService, UserLlmConfigService userLlmConfigService,
                          UserAsrConfigService userAsrConfigService) {
        this.authService = authService;
        this.userLlmConfigService = userLlmConfigService;
        this.userAsrConfigService = userAsrConfigService;
    }

    @GetMapping("/me")
    public ApiResponse<UserDto> me(HttpServletRequest request) {
        return ApiResponse.ok(authService.getUser(CurrentUser.userId(request)));
    }

    /**
     * 配置用户自带的 LLM API Key（AES-GCM 加密落库，不存明文）。
     */
    @PostMapping("/llm-config")
    public ApiResponse<Void> saveLlmConfig(@Valid @RequestBody LlmConfigRequest request, HttpServletRequest http) {
        userLlmConfigService.save(CurrentUser.userId(http), request.apiKey(), request.baseUrl(), request.model());
        return ApiResponse.ok();
    }

    /**
     * 查询用户 LLM 配置（Key 仅脱敏返回）。
     */
    @GetMapping("/llm-config")
    public ApiResponse<LlmConfigDto> getLlmConfig(HttpServletRequest http) {
        var config = userLlmConfigService.get(CurrentUser.userId(http)).orElse(null);
        if (config == null) {
            return ApiResponse.ok(new LlmConfigDto(false, null, null, ""));
        }
        return ApiResponse.ok(new LlmConfigDto(true, config.baseUrl(), config.model(),
                UserLlmConfigService.mask(config.apiKey())));
    }

    /**
     * 配置用户自带的讯飞 ASR 凭据（AES-GCM 加密落库，不存明文）。
     */
    @PostMapping("/asr-config")
    public ApiResponse<Void> saveAsrConfig(@Valid @RequestBody AsrConfigRequest request, HttpServletRequest http) {
        userAsrConfigService.save(CurrentUser.userId(http), request.appId(), request.apiKey(), request.apiSecret());
        return ApiResponse.ok();
    }

    /**
     * 查询用户讯飞 ASR 配置（凭据仅脱敏返回）。
     */
    @GetMapping("/asr-config")
    public ApiResponse<AsrConfigDto> getAsrConfig(HttpServletRequest http) {
        var config = userAsrConfigService.get(CurrentUser.userId(http)).orElse(null);
        if (config == null) {
            return ApiResponse.ok(new AsrConfigDto(false, "", "", ""));
        }
        return ApiResponse.ok(new AsrConfigDto(true,
                UserAsrConfigService.mask(config.appId()),
                UserAsrConfigService.mask(config.apiKey()),
                UserAsrConfigService.mask(config.apiSecret())));
    }
}

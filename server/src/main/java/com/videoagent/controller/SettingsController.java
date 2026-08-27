package com.videoagent.controller;

import com.videoagent.common.ApiResponse;
import com.videoagent.dto.AsrSettingsDto;
import com.videoagent.dto.AsrSettingsRequest;
import com.videoagent.service.SettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统设置接口（需鉴权）：ASR 识别引擎选择等。
 */
@RestController
@RequestMapping("/settings")
public class SettingsController {

    private static final int XF_CHANNELS = 1; // 讯飞账号当前 1 路并发

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/asr")
    public ApiResponse<AsrSettingsDto> getAsrSettings() {
        return ApiResponse.ok(new AsrSettingsDto(
                settingsService.getAsrEngine(),
                settingsService.getXfyunRemainingHours(),
                XF_CHANNELS));
    }

    @PutMapping("/asr")
    public ApiResponse<AsrSettingsDto> updateAsrSettings(@Valid @RequestBody AsrSettingsRequest request) {
        settingsService.setAsrEngine(request.engine());
        settingsService.setXfyunRemainingHours(request.remainingHours());
        return ApiResponse.ok(new AsrSettingsDto(
                settingsService.getAsrEngine(),
                settingsService.getXfyunRemainingHours(),
                XF_CHANNELS));
    }
}

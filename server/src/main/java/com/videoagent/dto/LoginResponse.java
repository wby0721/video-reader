package com.videoagent.dto;

/**
 * 登录/注册成功响应：JWT + 用户基本信息。
 */
public record LoginResponse(
        String token,
        Long userId,
        String username,
        String nickname,
        String role
) {}

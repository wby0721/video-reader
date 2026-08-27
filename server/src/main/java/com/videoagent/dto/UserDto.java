package com.videoagent.dto;

import com.videoagent.entity.User;

/**
 * 用户信息（脱敏：不含密码）。
 */
public record UserDto(
        Long id,
        String username,
        String nickname,
        String avatar,
        String role
) {
    public static UserDto from(User user) {
        return new UserDto(user.getId(), user.getUsername(), user.getNickname(), user.getAvatar(), user.getRole());
    }
}

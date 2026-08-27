package com.videoagent.utils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 当前登录用户上下文：由 {@code AuthInterceptor} 校验 JWT 后写入请求属性。
 */
public final class CurrentUser {

    public static final String ATTR_USER_ID = "currentUserId";
    public static final String ATTR_USERNAME = "currentUsername";
    public static final String ATTR_ROLE = "currentRole";

    private CurrentUser() {
    }

    public static Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute(ATTR_USER_ID);
    }

    public static String username(HttpServletRequest request) {
        return (String) request.getAttribute(ATTR_USERNAME);
    }

    public static String role(HttpServletRequest request) {
        return (String) request.getAttribute(ATTR_ROLE);
    }
}

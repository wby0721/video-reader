package com.videoagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.common.ApiResponse;
import com.videoagent.service.auth.JwtService;
import com.videoagent.utils.CurrentUser;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 鉴权拦截器：校验 {@code Authorization: Bearer <token>}，
 * 解析出 userId / username / role 注入请求上下文；失败返回 401。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public AuthInterceptor(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            try {
                Claims claims = jwtService.parseToken(header.substring(BEARER_PREFIX.length()));
                request.setAttribute(CurrentUser.ATTR_USER_ID, ((Number) claims.get("userId")).longValue());
                request.setAttribute(CurrentUser.ATTR_USERNAME, claims.getSubject());
                request.setAttribute(CurrentUser.ATTR_ROLE, claims.get("role", String.class));
                return true;
            } catch (Exception ignored) {
                // 非法或过期 token，落到下方 401
            }
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(401, "未认证或登录已过期")));
        return false;
    }
}

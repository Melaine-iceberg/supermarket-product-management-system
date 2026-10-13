package com.smms.security;

import com.smms.common.api.ApiResponse;
import com.smms.common.api.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * 强制修改初始密码过滤器（GAP-P0-08，BR-SYS-02、FR-SYS-02）：
 * 新建用户/重置密码后 {@code mustChangePwd=1}，除改密、登出、当前用户与公开接口外，
 * 其余业务接口一律拒绝（错误码 {@link ErrorCode#MUST_CHANGE_PWD}），
 * 防止仅靠前端路由守卫被"旧 Token 直调 API"绕过。
 */
@Component
@RequiredArgsConstructor
public class MustChangePwdFilter extends OncePerRequestFilter {

    /** 强制改密期间仍须放行的接口（PUT /auth/password、POST /auth/logout、GET /auth/me 等） */
    private static final List<String> ALLOWED_PATHS = List.of(
            "/api/v1/auth/password",
            "/api/v1/auth/logout",
            "/api/v1/auth/me",
            "/api/v1/auth/store-name",
            "/api/v1/ping");

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        LoginUser user = SecurityUtils.getLoginUserOrNull();
        if (user != null && user.mustChangePwd() && !isAllowed(request)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(objectMapper.writeValueAsBytes(
                    ApiResponse.fail(ErrorCode.MUST_CHANGE_PWD)));
            response.getOutputStream().flush();
            return;
        }
        chain.doFilter(request, response);
    }

    /** 白名单按"方法 + 路径"精确匹配：改密 PUT、登出 POST、me/store-name/ping GET */
    private boolean isAllowed(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        if ("PUT".equals(method) && "/api/v1/auth/password".equals(uri)) {
            return true;
        }
        if ("POST".equals(method) && "/api/v1/auth/logout".equals(uri)) {
            return true;
        }
        return "GET".equals(method) && ALLOWED_PATHS.contains(uri);
    }
}

package com.smms.sys.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求（FR-SYS-01）。
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password,
        /** 记住我：延长 Token 有效期 */
        Boolean rememberMe
) {
}

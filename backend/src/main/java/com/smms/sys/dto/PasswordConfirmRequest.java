package com.smms.sys.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 敏感操作二次密码确认请求（BR-SYS-06）：重置密码等操作须携带当前账号密码。
 */
public record PasswordConfirmRequest(
        @NotBlank(message = "请输入当前账号密码")
        String password
) {
}

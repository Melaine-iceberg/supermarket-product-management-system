package com.smms.sys.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * V1.2 多方式登录请求。三种方式共用一套字段，由 {@code LoginType} 决定取哪些字段：
 *
 * <ul>
 *   <li>{@code PASSWORD}：username + password（原有方式）</li>
 *   <li>{@code PHONE}：phone + code</li>
 *   <li>{@code EMAIL}：email + code</li>
 * </ul>
 */
public record CodeLoginRequest(
        @NotBlank(message = "登录方式不能为空")
        @Pattern(regexp = "PASSWORD|PHONE|EMAIL", message = "不支持的登录方式")
        String loginType,

        String username,
        String password,

        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @Size(max = 128, message = "邮箱长度超出限制")
        String email,

        @Size(max = 10, message = "验证码长度超出限制")
        String code,

        Boolean rememberMe
) {

    public static final String TYPE_PASSWORD = "PASSWORD";
    public static final String TYPE_PHONE = "PHONE";
    public static final String TYPE_EMAIL = "EMAIL";
}

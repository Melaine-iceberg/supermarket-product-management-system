package com.smms.sys.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 发送短信/邮件验证码请求（V1.2）。
 *
 * <p>{@code channel} 为 SMS 时取 phone，为 EMAIL 时取 email。
 * {@code scene} 为 LOGIN / REGISTER / BIND。</p>
 */
public record SendCodeRequest(
        @NotBlank(message = "发送渠道不能为空")
        @Pattern(regexp = "SMS|EMAIL", message = "不支持的发送渠道")
        String channel,

        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @Size(max = 128, message = "邮箱长度超出限制")
        String email,

        @NotBlank(message = "业务场景不能为空")
        @Pattern(regexp = "LOGIN|REGISTER|BIND", message = "不支持的业务场景")
        String scene
) {

    public static final String CHANNEL_SMS = "SMS";
    public static final String CHANNEL_EMAIL = "EMAIL";

    public static final String SCENE_LOGIN = "LOGIN";
    public static final String SCENE_REGISTER = "REGISTER";
    public static final String SCENE_BIND = "BIND";
}

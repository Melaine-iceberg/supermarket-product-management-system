package com.smms.sys.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 员工自助注册请求（V1.2）。手机号与邮箱二选一即可，至少提供一个作为登录凭证。
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[a-zA-Z0-9_]{4,20}$", message = "用户名须为 4~20 位字母、数字或下划线")
        String username,

        @NotBlank(message = "密码不能为空")
        String password,

        @Size(max = 20, message = "姓名长度超出限制")
        String realName,

        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @Size(max = 128, message = "邮箱长度超出限制")
        String email,

        @Size(max = 10, message = "验证码长度超出限制")
        String code,

        /** 验证码渠道：SMS / EMAIL，与 phone/email 中实际填写的一项对应 */
        @Pattern(regexp = "^$|^SMS$|^EMAIL$", message = "不支持的验证码渠道")
        String codeChannel
) {
}

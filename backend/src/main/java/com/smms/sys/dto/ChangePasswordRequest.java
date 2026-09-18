package com.smms.sys.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 修改自身密码请求（FR-SYS-03）。
 */
public record ChangePasswordRequest(
        @NotBlank(message = "原密码不能为空") String oldPassword,
        @NotBlank(message = "新密码不能为空") String newPassword
) {
}

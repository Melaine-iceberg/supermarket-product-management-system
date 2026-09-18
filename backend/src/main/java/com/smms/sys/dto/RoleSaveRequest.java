package com.smms.sys.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增/编辑角色请求（FR-SYS-04）。
 */
public record RoleSaveRequest(
        @NotBlank(message = "角色名称不能为空")
        @Size(max = 30, message = "角色名称不能超过 30 字")
        String roleName,

        @NotBlank(message = "角色代码不能为空")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,29}$", message = "角色代码须为大写字母开头的字母/数字/下划线")
        String roleCode,

        @Size(max = 200, message = "描述不能超过 200 字")
        String description
) {
}

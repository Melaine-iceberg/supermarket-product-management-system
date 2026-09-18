package com.smms.sys.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 编辑用户请求（FR-SYS-02）：用户名不可修改；roleIds 为空表示不变更角色。
 */
public record UserUpdateRequest(
        @NotBlank(message = "真实姓名不能为空")
        @Size(max = 30, message = "真实姓名不能超过 30 字")
        String realName,

        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @Size(max = 200, message = "备注不能超过 200 字")
        String remark,

        List<Long> roleIds
) {
}

package com.smms.sys.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新增用户请求（FR-SYS-02）：初始密码由系统随机生成并强制首次登录修改（BR-SYS-02）。
 */
public record UserCreateRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 30, message = "用户名长度须为 3~30 位")
        @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字与下划线")
        String username,

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

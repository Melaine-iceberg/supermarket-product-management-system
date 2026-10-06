package com.smms.sys.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改系统参数请求（FR-SYS-06）。
 */
public record ConfigUpdateRequest(
        @NotBlank(message = "参数值不能为空")
        @Size(max = 500, message = "参数值不能超过 500 字")
        String configValue,

        @Size(max = 200, message = "备注不能超过 200 字")
        String remark,

        /** 当前账号密码二次确认（BR-SYS-06） */
        String password
) {
}

package com.smms.sys.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 启用/停用请求：status 1 启用 0 停用。
 */
public record UserStatusRequest(
        @NotNull(message = "状态不能为空") Integer status
) {
}

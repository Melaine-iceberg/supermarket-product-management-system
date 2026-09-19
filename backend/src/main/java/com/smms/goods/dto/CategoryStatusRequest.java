package com.smms.goods.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 分类停用/启用请求（FR-CAT-04）：status 1 启用 0 停用。
 */
public record CategoryStatusRequest(
        @NotNull(message = "状态不能为空")
        @Min(0) @Max(1)
        Integer status
) {
}

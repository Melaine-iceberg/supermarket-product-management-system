package com.smms.goods.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 商品停用/启用请求（FR-GOODS-05）：status 1 启用 0 停用。
 */
public record GoodsStatusRequest(
        @NotNull(message = "状态不能为空")
        @Min(0) @Max(1)
        Integer status
) {
}

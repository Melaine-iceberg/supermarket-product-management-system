package com.smms.goods.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 促销价设置请求（FR-GOODS-09）：支持单个或批量商品；
 * 同一商品同一时间段仅允许一条生效促销（BR-GOODS-04）。
 */
public record PromotionCreateRequest(
        @NotEmpty(message = "商品不能为空")
        List<Long> goodsIds,

        @NotNull(message = "促销价不能为空")
        @DecimalMin(value = "0.01", message = "促销价必须大于0")
        BigDecimal promoPrice,

        @NotNull(message = "开始时间不能为空")
        LocalDateTime startTime,

        @NotNull(message = "结束时间不能为空")
        LocalDateTime endTime,

        String remark
) {
}

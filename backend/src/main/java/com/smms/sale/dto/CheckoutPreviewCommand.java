package com.smms.sale.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.util.List;

/**
 * 结算金额试算命令（FR-SALE-06）：使用与结算相同的商品、会员与折扣入参，
 * 不含收款记录与请求令牌，只返回后端按 BR-GOODS-04/BR-SALE-03 计算的精确金额，
 * 供收银台在收款前校验储值上限、避免前端预估与后端应收不一致。
 */
public record CheckoutPreviewCommand(

        /** 会员ID（散客为空，FR-SALE-05） */
        Long memberId,

        /** 店长整单折扣率（0.7~1，BR-SALE-10） */
        @DecimalMin(value = "0.0001", message = "整单折扣率不合法")
        @DecimalMax(value = "1", message = "整单折扣率不合法")
        BigDecimal orderDiscountRate,

        /** 销售明细（与结算入参一致，BR-SALE-01） */
        @NotEmpty(message = "销售明细不能为空")
        @Valid
        List<CheckoutCommand.CheckoutItem> items
) {
}

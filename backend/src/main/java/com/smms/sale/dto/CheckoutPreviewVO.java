package com.smms.sale.dto;

import java.math.BigDecimal;

/**
 * 结算金额试算结果（FR-SALE-06）：与结算落库时的金额口径一致，
 * 用于收银台收款弹窗展示精确应收与储值可用上限。
 */
public record CheckoutPreviewVO(
        /** 商品总额（后端取价后的行金额合计） */
        BigDecimal goodsAmount,
        /** 会员等级折扣金额 */
        BigDecimal memberDiscountAmount,
        /** 店长整单折扣金额 */
        BigDecimal orderDiscountAmount,
        /** 优惠合计 */
        BigDecimal discountAmount,
        /** 应收金额 */
        BigDecimal payableAmount,
        /** 最终折扣率 = 会员等级折扣 × 整单折扣 */
        BigDecimal totalRate
) {
}

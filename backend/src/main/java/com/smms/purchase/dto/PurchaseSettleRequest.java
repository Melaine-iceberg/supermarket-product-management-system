package com.smms.purchase.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 采购结算登记请求（FR-PUR-09）：标记结算状态并登记结算金额、方式、日期；经办人取当前登录人。
 */
public record PurchaseSettleRequest(
        /** UNSETTLED未结算/PARTIAL部分结算/SETTLED已结算 */
        @NotBlank(message = "结算状态不能为空")
        String settleStatus,
        /** 已结算金额 ≥ 0 */
        @NotNull(message = "结算金额不能为空")
        @DecimalMin(value = "0", message = "结算金额不能为负")
        BigDecimal settleAmount,
        /** 结算方式（字典 settle_type）：CASH/MONTHLY/CREDIT */
        String settleMethod,
        LocalDate settleDate
) {
}

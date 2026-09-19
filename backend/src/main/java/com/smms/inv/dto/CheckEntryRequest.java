package com.smms.inv.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 实盘数量录入请求（FR-INV-08）：逐行手工录入 / 扫码定位后录入 / Excel 批量导入统一入口。
 * 差异数量与差异金额由服务端按 实盘-账面 计算，差异≠0 的行必须填写原因。
 */
public record CheckEntryRequest(
        @Valid @NotNull(message = "录入明细不能为空")
        @Size(max = 500, message = "单次最多录入 500 行")
        List<Item> items) {

    /** 单行录入：goodsId 定位明细行（不暴露明细ID给前端，防止跨单串号） */
    public record Item(
            @NotNull(message = "商品ID不能为空")
            Long goodsId,

            /** 实盘数量 ≥0（可为小数，称重商品 BR-INV-12） */
            @NotNull(message = "实盘数量不能为空")
            @DecimalMin(value = "0", message = "实盘数量不能为负数")
            @DecimalMax(value = "99999999.999", message = "实盘数量超出范围")
            BigDecimal actualQty,

            /** 差异原因（差异≠0 时必填，服务端校验） */
            String diffReason,

            /** 备注 */
            String remark) {
    }
}

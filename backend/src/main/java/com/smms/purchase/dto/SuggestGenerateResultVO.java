package com.smms.purchase.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 建议采购生成结果（FR-PUR-02）：按供应商分组生成的草稿采购单列表
 * 与因缺少供应商等原因跳过的商品清单。
 */
public record SuggestGenerateResultVO(
        List<GeneratedOrderVO> orders,
        List<SkippedGoodsVO> skippedGoods
) {

    public record GeneratedOrderVO(
            Long orderId,
            String orderNo,
            Long supplierId,
            String supplierName,
            Integer itemCount,
            BigDecimal totalAmount,
            LocalDate expectDate
    ) {
    }

    public record SkippedGoodsVO(
            Long goodsId,
            String goodsName,
            String reason
    ) {
    }
}

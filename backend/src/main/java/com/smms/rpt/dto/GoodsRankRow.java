package com.smms.rpt.dto;

import java.math.BigDecimal;

/**
 * 商品分析通用行（排行/品类结构/品牌/滞销）：按维度复用同一结构，
 * costAmount / profitAmount 等毛利类字段按 BR-RPT-07 由服务层按角色置空。
 */
public record GoodsRankRow(
        Long goodsId,
        String goodsCode,
        String goodsName,
        Long categoryId,
        String categoryName,
        Long brandId,
        String brandName,
        long orderCount,
        BigDecimal qty,
        BigDecimal salesAmount,
        BigDecimal discountAmount,
        BigDecimal netSalesAmount,
        BigDecimal refundAmount,
        BigDecimal costAmount,
        BigDecimal returnCostAmount,
        BigDecimal profitAmount,
        BigDecimal profitRate,
        BigDecimal shareRate,
        /** 滞销清单字段：当前库存数量 */
        BigDecimal stockQty,
        /** 滞销清单字段：成本单价 */
        BigDecimal costPrice,
        /** 滞销清单字段：占用资金 = 结存 × 成本单价 */
        BigDecimal occupiedAmount,
        /** 滞销清单字段：零售单价（当前档案价） */
        BigDecimal retailPrice) {

    /** 毛利额 = (净销售 − 退货额) − (成本 − 退货成本回冲)（BR-RPT-01） */
    public BigDecimal calcProfitAmount() {
        BigDecimal net = nvl(netSalesAmount).subtract(nvl(refundAmount));
        BigDecimal cost = nvl(costAmount).subtract(nvl(returnCostAmount));
        return net.subtract(cost);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}

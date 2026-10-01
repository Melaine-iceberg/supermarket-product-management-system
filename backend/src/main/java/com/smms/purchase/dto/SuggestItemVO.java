package com.smms.purchase.dto;

import java.math.BigDecimal;

/**
 * 建议采购清单行（FR-PUR-02，BR-INV-07）：
 * 展示触发缺货/预警商品的当前结存、日均销量、在途量、建议采购量及计算依据、
 * 默认供应商与最近采购价；suggestQty ≤ 0 表示不建议采购（不可勾选）。
 */
public record SuggestItemVO(
        Long goodsId,
        String goodsCode,
        String goodsName,
        String spec,
        Long categoryId,
        String categoryName,
        String unitName,
        Boolean isWeighed,
        /** 缺货 OUT / 预警 WARNING（与库存预警口径一致，FR-INV-05） */
        String suggestType,
        /** 当前结存 */
        BigDecimal quantity,
        /** 安全库存下限 */
        BigDecimal safetyStockLower,
        /** 近 N 日销售出库数量合计 */
        BigDecimal soldQty,
        /** 日均销量 = 近 N 日合计 / N */
        BigDecimal avgDailySales,
        /** 在途采购量 = 已审核待入库/部分入库采购单待入库数量之和（BR-PUR-08） */
        BigDecimal onWayQty,
        /** 采购提前期（默认供应商配置，缺省取系统参数） */
        Integer leadTimeDays,
        /** 建议采购量 = ceil(安全库存×K + 日均×提前期 − 结存 − 在途)；≤0 不建议 */
        BigDecimal suggestQty,
        /** 计算依据（BR-INV-07 公式展开，供界面展示） */
        String calcBasis,
        Long supplierId,
        String supplierName,
        /** 默认供应商供货价 */
        BigDecimal supplyPrice,
        /** 最近采购价（最近一次入库实收单价） */
        BigDecimal lastPurchasePrice
) {
}

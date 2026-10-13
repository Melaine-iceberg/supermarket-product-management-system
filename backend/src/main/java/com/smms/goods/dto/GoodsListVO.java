package com.smms.goods.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品列表行（FR-GOODS-03）：档案字段 + 关联名称 + 实时库存 +
 * 生效促销价（BR-GOODS-04 售价优先级展示）。
 */
public record GoodsListVO(
        Long id,
        String goodsCode,
        String barcode,
        String name,
        Long categoryId,
        String categoryName,
        /** 所属分类状态：1 启用 0 停用（FR-CAT-04：停用分类下已有商品保留但列表提示） */
        Integer categoryStatus,
        Long brandId,
        String brandName,
        Long unitId,
        String unitName,
        String spec,
        String origin,
        BigDecimal purchasePrice,
        BigDecimal retailPrice,
        BigDecimal memberPrice,
        /** 当前生效促销价（无则为 null，BR-GOODS-04） */
        BigDecimal promoPrice,
        LocalDateTime promoEndTime,
        String pluCode,
        Integer isWeighed,
        Integer allowReturn,
        BigDecimal safetyStockLower,
        BigDecimal safetyStockUpper,
        Integer status,
        /** 库存结存（无库存记录为 0） */
        BigDecimal quantity,
        BigDecimal costPrice,
        BigDecimal costAmount,
        /** 库存状态：NORMAL正常/WARNING预警/OUT缺货/OVERSTOCK积压（FR-INV-05 口径） */
        String stockStatus,
        LocalDateTime createTime
) {
}

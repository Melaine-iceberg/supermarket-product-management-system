package com.smms.goods.dto;

import com.smms.goods.entity.Goods;
import com.smms.goods.entity.Promotion;
import com.smms.inv.entity.Inventory;
import com.smms.inv.entity.InventoryRecord;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品详情（FR-GOODS-04）：完整档案 + 当前库存与成本 + 最近 10 条库存流水
 * + 最近 5 次采购价 + 近 30 天销量与销售额 + 关联供应商清单 + 当前生效促销。
 */
public record GoodsDetailVO(
        Goods goods,
        String categoryName,
        String unitName,
        String brandName,
        Inventory inventory,
        /** 当前生效促销（无则 null），供详情页展示与收银取价说明 */
        Promotion activePromotion,
        List<InventoryRecord> recentInventoryRecords,
        List<PurchasePriceVO> recentPurchasePrices,
        SalesSummaryVO salesSummary30d,
        List<SupplierBriefVO> suppliers
) {
}

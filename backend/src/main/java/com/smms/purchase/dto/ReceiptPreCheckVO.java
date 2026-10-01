package com.smms.purchase.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 入库预检请求与结果（BR-PUR-04/05）：录入实收时前端调用，
 * 返回每行的待入库数量、容差上限、价差率与最近均价提示，用于黄色预警展示；
 * needAuth 为 true 时提交须携带店长授权人。
 */
@Data
public class ReceiptPreCheckVO {

    private Long purchaseOrderId;

    private String purchaseOrderNo;

    /** 超收容差比例（%，系统参数，默认 5） */
    private BigDecimal toleranceRate;

    /** 价差容差比例（%，与采购单价比较，默认 5） */
    private BigDecimal priceDiffRate;

    /** 与近 3 次平均采购价偏离预警阈值（%，默认 20） */
    private BigDecimal priceWarnRate;

    private List<ItemCheckVO> items;

    @Data
    public static class ItemCheckVO {
        private Long purchaseItemId;
        private Long goodsId;
        private String goodsName;
        /** 待入库数量 = 订购 − 已入库 */
        private BigDecimal pendingQty;
        /** 容差上限 = 待入库 ×(1+容差比例)（BR-PUR-04） */
        private BigDecimal toleranceMax;
        private BigDecimal receivedQty;
        private BigDecimal receivedPrice;
        /** 采购单价 */
        private BigDecimal orderPrice;
        /** 与采购单价偏离百分比（±%） */
        private BigDecimal priceDiffPercent;
        /** 最近 3 次平均采购价（无记录为 null） */
        private BigDecimal recentAvgPrice;
        /** 与近 3 次均价偏离超阈值 → 黄色提示（不阻断，BR-PUR-05） */
        private boolean priceWarning;
        /** 超收容差（须授权，BR-PUR-04） */
        private boolean overTolerance;
        /** 与采购单价偏离超容差（须授权，BR-PUR-05） */
        private boolean priceAuthRequired;
        /** 是否整体须店长授权 */
        private boolean needAuth;
    }
}

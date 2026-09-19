package com.smms.goods.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品详情-近 30 天销量销售额汇总（FR-GOODS-04）：来自销售明细 join 销售单。
 */
@Data
public class SalesSummaryVO {

    /** 销量合计（扣除退货后的口径由销售模块实现时细化，此处先取正销量） */
    private BigDecimal totalQuantity;

    /** 实付销售额合计 */
    private BigDecimal totalAmount;
}

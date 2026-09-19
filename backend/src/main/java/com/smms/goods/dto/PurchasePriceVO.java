package com.smms.goods.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品详情-最近采购价行（FR-GOODS-04）：来自入库单明细 join 入库单。
 */
@Data
public class PurchasePriceVO {

    /** 入库单号 */
    private String orderNo;

    /** 入库时间 */
    private LocalDateTime receiptTime;

    /** 实收单价 */
    private BigDecimal price;

    /** 实收数量 */
    private BigDecimal quantity;
}

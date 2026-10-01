package com.smms.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 采购入库单明细（DR-10）：实收数量受超收容差约束（BR-PUR-04），
 * 实收单价即存货成本（BR-PUR-07）。
 */
@Data
@TableName("receipt_order_item")
public class ReceiptOrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long receiptId;

    private Long purchaseItemId;

    private Long goodsId;

    /** 实收数量 */
    private BigDecimal receivedQty;

    /** 实收单价（存货成本以此为准，BR-PUR-07） */
    private BigDecimal receivedPrice;

    /** 行金额 = 实收数量×实收单价 */
    private BigDecimal amount;

    /** 验收备注 */
    private String remark;
}

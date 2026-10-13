package com.smms.sale.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

import java.math.BigDecimal;

/**
 * 销售退货明细（DR-20）：退货数量 ≤ 原数量 − 已退；
 * 退款单价为原单实际成交单价（含折扣分摊，BR-SALE-06）；
 * 成本按原销售明细快照回冲（BR-INV-06）。
 */
@Data
@TableName("return_order_item")
public class ReturnOrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 退货单ID */
    private Long returnId;

    /** 原销售明细ID */
    private Long salesItemId;

    /** 商品ID */
    private Long goodsId;

    /** 退货数量（≤原数量-已退） */
    private BigDecimal returnQty;

    /** 退款单价（原成交单价） */
    private BigDecimal refundPrice;

    /** 退款金额 */
    private BigDecimal refundAmount;

    /** 成本单价（原快照回冲） */
    private BigDecimal costPrice;

    /** 成本回冲额 */
    private BigDecimal costAmount;

    private String remark;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}

package com.smms.inv.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 盘点明细（DR-14）：创建时快照账面数量与成本单价；
 * 实盘数量 NULL=未盘，提交审核时按账面确认（FR-INV-08）。
 */
@Data
@TableName("stock_check_item")
public class StockCheckItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 盘点单ID */
    private Long orderId;

    /** 商品ID */
    private Long goodsId;

    /** 账面数量（快照） */
    private BigDecimal bookQty;

    /** 实盘数量（NULL=未盘，提交时按账面确认） */
    private BigDecimal actualQty;

    /** 差异数量 = 实盘-账面 */
    private BigDecimal diffQty;

    /** 成本单价（快照） */
    private BigDecimal costPrice;

    /** 差异金额 = 差异数量×成本单价 */
    private BigDecimal diffAmount;

    /** 差异原因（差异≠0时必填，FR-INV-08） */
    private String diffReason;

    /** 备注 */
    private String remark;
}

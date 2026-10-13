package com.smms.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

import java.math.BigDecimal;

/**
 * 采购退货单明细（FR-PUR-08）：退货数量 ≤ 已入库数量 − 已退数量；
 * 退货单价取入库时成本单价（按该采购明细的入库记录加权）。
 */
@Data
@TableName("purchase_return_item")
public class PurchaseReturnItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long returnId;

    private Long purchaseItemId;

    private Long goodsId;

    /** 退货数量 */
    private BigDecimal returnQty;

    /** 退货单价（入库时成本单价） */
    private BigDecimal returnPrice;

    /** 行金额 = 退货数量×退货单价 */
    private BigDecimal amount;

    private String remark;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}

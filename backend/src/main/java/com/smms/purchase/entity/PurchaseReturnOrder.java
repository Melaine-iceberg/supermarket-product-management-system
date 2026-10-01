package com.smms.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 采购退货单（FR-PUR-08）：PT+yyyyMMdd+4位流水（BR-PUR-01）。
 * 创建后 SUBMITTED 待店长审核；审核通过事务内执行退货（负数流水、扣结存、
 * 按入库时成本单价冲减库存成本、更新采购单退货数量与应付金额）。
 */
@Data
@TableName("purchase_return_order")
public class PurchaseReturnOrder {

    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 退货单号：PT+yyyyMMdd+4位流水（BR-PUR-01） */
    private String orderNo;

    private Long storeId;

    private Long purchaseOrderId;

    private Long supplierId;

    private String status;

    /** 退货原因（质量问题等，必填） */
    private String returnReason;

    /** 退货总额 = Σ(退货数量×入库时成本单价) */
    private BigDecimal totalAmount;

    private String createBy;

    private LocalDateTime createTime;

    private String auditBy;

    private LocalDateTime auditTime;

    private String auditRemark;

    private String remark;

    private LocalDateTime updateTime;
}

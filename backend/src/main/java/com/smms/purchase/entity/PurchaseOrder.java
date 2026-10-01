package com.smms.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 采购单（DR-07）。状态机见 §2.2.1（BR-PUR-02 严格遵循）：
 * DRAFT草稿 → SUBMITTED已提交 → APPROVED已审核待入库 → PARTIAL部分入库 → COMPLETED已完成
 * → PARTIAL_RETURNED部分退货 → ALL_RETURNED全部退货；
 * 分支：DRAFT→CANCELLED作废、SUBMITTED→DRAFT驳回/撤回、APPROVED→TERMINATED终止。
 * version 乐观锁用于入库并发控制（BR-PUR-06）。
 */
@Data
@TableName("purchase_order")
public class PurchaseOrder {

    /** 状态常量（§2.2.1） */
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_TERMINATED = "TERMINATED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_PARTIAL_RETURNED = "PARTIAL_RETURNED";
    public static final String STATUS_ALL_RETURNED = "ALL_RETURNED";

    /** 结算状态（FR-PUR-09） */
    public static final String SETTLE_UNSETTLED = "UNSETTLED";
    public static final String SETTLE_PARTIAL = "PARTIAL";
    public static final String SETTLE_SETTLED = "SETTLED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 采购单号：PO+yyyyMMdd+4位流水（BR-PUR-01，系统生成不可指定） */
    private String orderNo;

    private Long storeId;

    private Long supplierId;

    private String status;

    private String createBy;

    private LocalDateTime createTime;

    private LocalDateTime submitTime;

    private String auditBy;

    private LocalDateTime auditTime;

    /** 审核意见（驳回时必填） */
    private String auditRemark;

    /** 预计到货日期 */
    private LocalDate expectDate;

    /** 订购总额 = Σ(数量×单价)，2位小数（BR-PUR-03） */
    private BigDecimal totalAmount;

    /** 实收总额（按实收单价） */
    private BigDecimal receivedAmount;

    /** 退货总额 */
    private BigDecimal returnAmount;

    private String settleStatus;

    private BigDecimal settleAmount;

    /** 结算方式（字典 settle_type） */
    private String settleMethod;

    private LocalDate settleDate;

    private String settleBy;

    /** 终止/作废原因（须填写） */
    private String terminateReason;

    /** 版本号（乐观锁，BR-PUR-06）：入库等并发操作显式带 version 条件更新 */
    private Integer version;

    private String remark;

    private String updateBy;

    private LocalDateTime updateTime;
}

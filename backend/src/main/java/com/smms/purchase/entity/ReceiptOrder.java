package com.smms.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 采购入库单（DR-09，FR-PUR-06）：RK+yyyyMMdd+4位流水（BR-PUR-01）。
 * 入库在事务内完成：入库单生成、库存流水写入（PURCHASE_IN）、结存与移动加权成本更新、
 * 采购单状态与金额更新、供应商累计采购额更新（FR-PUR-06）。
 */
@Data
@TableName("receipt_order")
public class ReceiptOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 入库单号：RK+yyyyMMdd+4位流水（BR-PUR-01） */
    private String orderNo;

    private Long storeId;

    private Long purchaseOrderId;

    private Long supplierId;

    private LocalDateTime receiptTime;

    /** 验收操作人（库管员） */
    private String operatorName;

    /** 授权人（超收/价差授权时记录，BR-PUR-04/05） */
    private String authorizerName;

    /** 供应商送货单号 */
    private String deliveryOrderNo;

    /** 入库总金额 = Σ(实收数量×实收单价) */
    private BigDecimal totalAmount;

    private String remark;

    /** 请求令牌（提交防重，NFR-REL-02；uk_ro_token 唯一键兜底并发重放） */
    private String requestToken;
}

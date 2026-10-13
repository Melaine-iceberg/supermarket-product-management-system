package com.smms.inv.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 盘点单（DR-13，FR-INV-07）：状态机见 §2.2.4
 * CHECKING盘点中 → PENDING_AUDIT待审核 → AUDITED已审核；CHECKING可作废 CANCELLED。
 */
@Data
@TableName("stock_check_order")
public class StockCheckOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 盘点单号：PD+yyyyMMdd+4位流水 */
    private String orderNo;

    /** 门店ID（预留） */
    private Long storeId;

    /** 盘点范围：ALL全店/CATEGORY分类/GOODS指定商品/WARNING预警商品 */
    private String scopeType;

    /** 范围描述 */
    private String scopeDesc;

    /** 状态：CHECKING盘点中/PENDING_AUDIT待审核/AUDITED已审核/CANCELLED已作废 */
    private String status;

    /** 盘点人 */
    private String checkerName;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 账面快照基准时间（BR-INV-10） */
    private LocalDateTime baseTime;

    /** 营业日（BR-RPT-02，GAP-P1-05）：创建时按创建时刻归属，审核通过时改写为审核时刻所属营业日（报表按审核营业日统计） */
    private LocalDate businessDate;

    /** 盘点商品行数 */
    private Integer itemCount;

    /** 盘盈金额 */
    private BigDecimal surplusAmount;

    /** 盘亏金额 */
    private BigDecimal shortageAmount;

    /** 盘点期间发生出入库笔数（BR-INV-10，审核时统计） */
    private Integer duringChangeCount;

    /** 审核人（店长，须≠盘点人，BR-SYS-01） */
    private String auditorName;

    /** 审核时间 */
    private LocalDateTime auditTime;

    /** 审核意见（驳回必填） */
    private String auditRemark;

    /** 作废原因 */
    private String cancelReason;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

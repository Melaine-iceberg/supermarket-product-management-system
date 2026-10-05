package com.smms.inv.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报损/报溢单（DR-15，FR-INV-10）：单商品，与销售退货分开统计以便计算损耗率（FR-RPT-04⑤）。
 * 登记（PENDING待审核）→ 店长审核：通过生成 LOSS_OUT/OVERFLOW_IN 流水（AUDITED），驳回（REJECTED）。
 */
@Data
@TableName("loss_order")
public class LossOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 单号：BS+yyyyMMdd+4位流水（报损）/ BY+yyyyMMdd+4位流水（报溢） */
    private String orderNo;

    /** 门店ID（预留） */
    private Long storeId;

    /** 类型：LOSS报损出库 / OVERFLOW报溢入库 */
    private String bizType;

    private Long goodsId;

    /** 数量（恒正，最多 3 位小数） */
    private BigDecimal quantity;

    /** 成本单价：报溢为录入价；报损登记时按当前成本快照、审核时按实际结转成本定稿 */
    private BigDecimal costPrice;

    /** 金额 = 数量 × 成本单价 */
    private BigDecimal amount;

    /** 原因（数据字典 loss_reason 代码，FR-SYS-07） */
    private String reason;

    /** 申请人 */
    private String applyName;

    /** 状态：PENDING待审核 / AUDITED已审核 / REJECTED已驳回 */
    private String status;

    /** 审核人（店长） */
    private String auditorName;

    private LocalDateTime auditTime;

    /** 审核意见（驳回必填） */
    private String auditRemark;

    private LocalDateTime createTime;

    private String remark;
}

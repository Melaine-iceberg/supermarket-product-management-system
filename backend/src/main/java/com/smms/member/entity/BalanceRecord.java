package com.smms.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 储值流水（DR-25，FR-MEM-08，BR-MEM-07）：只增不改不删。
 * 变动类型：RECHARGE充值 / CONSUME消费 / REFUND退款回充 / MANUAL人工调整。
 */
@Data
@TableName("balance_record")
public class BalanceRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;

    /** 变动类型：RECHARGE充值 / CONSUME消费 / REFUND退款回充 / MANUAL人工调整 */
    private String changeType;

    /** 变动金额（充值+，消费-） */
    private BigDecimal changeAmount;

    /** 变动后余额（不可为负，BR-MEM-07） */
    private BigDecimal afterBalance;

    /** 收款方式（充值时） */
    private String payType;

    /** 来源单据号 */
    private String bizNo;

    private String operatorName;

    /** 授权人（充值需店长授权） */
    private String authorizerName;

    private LocalDateTime createTime;

    private String remark;
}

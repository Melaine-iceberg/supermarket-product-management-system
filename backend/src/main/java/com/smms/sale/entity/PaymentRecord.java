package com.smms.sale.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 销售收款记录（DR-18）：一单多收款方式组合登记（FR-SALE-06）。
 */
@Data
@TableName("payment_record")
public class PaymentRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 销售单ID */
    private Long orderId;

    /** 收款方式：CASH现金/BANK银行卡/MOBILE移动支付/BALANCE会员储值 */
    private String payType;

    /** 收款金额 */
    private BigDecimal amount;

    /** 收款时间 */
    private LocalDateTime payTime;

    /** 操作人（收银员） */
    private String operatorName;
}

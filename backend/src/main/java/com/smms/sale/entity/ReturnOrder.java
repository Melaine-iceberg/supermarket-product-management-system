package com.smms.sale.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 销售退货单（DR-19，FR-SALE-10）：基于原销售单冲正，
 * 按原成交单价退款（BR-SALE-06），回补库存并按成本快照回冲（BR-INV-06）。
 */
@Data
@TableName("return_order")
public class ReturnOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 退货单号：XT+yyyyMMdd+收银台2位+4位流水（BR-SALE-02） */
    private String orderNo;

    /** 门店ID（预留） */
    private Long storeId;

    /** 原销售单ID */
    private Long salesOrderId;

    /** 会员ID（原单会员） */
    private Long memberId;

    /** 退货原因（字典 return_reason）：QUALITY/WRONG_ITEM/MISTAKE/OTHER */
    private String returnReason;

    /** 原因说明 */
    private String reasonRemark;

    /** 退款金额（按原成交价，BR-SALE-06） */
    private BigDecimal refundAmount;

    /** 退款方式：CASH/BANK/MOBILE/BALANCE（退回储值） */
    private String refundType;

    /** 退货经办人（收银员） */
    private String cashierName;

    /** 授权人（店长，超阈值或误操作时，FR-SALE-11） */
    private String authorizerName;

    private LocalDateTime authorizeTime;

    private LocalDateTime returnTime;

    /** 营业日（BR-RPT-02） */
    private LocalDate businessDate;
}

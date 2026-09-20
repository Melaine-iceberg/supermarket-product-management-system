package com.smms.member.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 会员消费记录单条（FR-MEM-04）：销售单与退货单合并，时间倒序；
 * 展示单号、日期、金额、优惠、实付、积分变动、收银员。
 */
@Data
public class MemberConsumeVO {

    /** SALE销售单 / RETURN退货单 */
    private String type;

    private String orderNo;

    private LocalDateTime time;

    /** 商品总额（退货单为 0） */
    private BigDecimal goodsAmount;

    /** 优惠金额 */
    private BigDecimal discountAmount;

    /** 实付金额（退货单为退款金额） */
    private BigDecimal amount;

    /** 积分变动（消费+ / 退货-，服务层按单号回填） */
    private Integer pointsChange;

    private String cashierName;
}

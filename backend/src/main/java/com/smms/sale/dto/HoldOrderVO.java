package com.smms.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 挂单摘要 VO（FR-SALE-08）：挂单列表展示（时间/件数/金额/会员），
 * 挂单不扣库存、不计入报表（BR-RPT-03），超 24 小时自动清理（IR-SW-06）。
 */
public record HoldOrderVO(
        Long id,
        Integer counterNo,
        String cashierName,
        Long memberId,
        String memberName,
        Integer itemCount,
        BigDecimal amount,
        String remark,
        LocalDateTime holdTime
) {
}

package com.smms.sale.dto;

import java.math.BigDecimal;

/**
 * 收银台会员检索结果（FR-SALE-05）：手机号/会员编号/姓名检索，
 * 展示姓名、等级、当前积分、折扣率、储值余额；停用会员提示且不给折扣（BR-MEM-03）。
 * 手机号按 BR-MEM-08 脱敏（仅显示姓名与后 4 位）。
 */
public record MemberSearchVO(
        Long id,
        String memberNo,
        String name,
        /** 手机号（脱敏 138****5678，BR-MEM-08 收银台场景） */
        String phone,
        String levelName,
        /** 等级折扣率（停用会员返回 1，不享受折扣） */
        BigDecimal discountRate,
        Integer points,
        /** 储值余额（BALANCE 支付上限参考，BR-MEM-07） */
        BigDecimal balance,
        /** 状态：1正常 0停用 */
        Integer status
) {
}

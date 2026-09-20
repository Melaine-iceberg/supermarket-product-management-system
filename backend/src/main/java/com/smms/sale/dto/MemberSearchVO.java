package com.smms.sale.dto;

import java.math.BigDecimal;

/**
 * 收银台会员检索结果（FR-SALE-05）：手机号/会员编号/姓名检索，
 * 展示姓名、等级、当前积分、折扣率；停用会员提示且不给折扣（BR-MEM-03）。
 */
public record MemberSearchVO(
        Long id,
        String memberNo,
        String name,
        String phone,
        String levelName,
        /** 等级折扣率（停用会员返回 1，不享受折扣） */
        BigDecimal discountRate,
        Integer points,
        /** 状态：1正常 0停用 */
        Integer status
) {
}

package com.smms.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 取单详情 VO（FR-SALE-08）：取单成功后返回完整明细快照与挂单时的
 * 会员/整单折扣/备注信息，供收银台恢复购物车；授权密码不落库，结算时重新录入。
 */
public record HoldDetailVO(
        Long id,
        Integer counterNo,
        String cashierName,
        Long memberId,
        String memberName,
        Integer itemCount,
        BigDecimal amount,
        String remark,
        LocalDateTime holdTime,
        BigDecimal orderRate,
        String discountReason,
        String authorizerUsername,
        List<Item> items
) {

    /** 明细快照行（与落库 items_json 同构，序列化由 Jackson 处理） */
    public record Item(
            String lineType,
            Long goodsId,
            String name,
            String spec,
            String barcode,
            String unitName,
            BigDecimal retailPrice,
            Integer isWeighed,
            BigDecimal quantity,
            String remark,
            BigDecimal overridePrice,
            String priceReason
    ) {
    }
}

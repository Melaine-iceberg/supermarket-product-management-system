package com.smms.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 销售单查询条件（FR-SALE-09）：单号/收银员/收银台/会员/收款方式/
 * 销售日期区间/金额区间/退货状态/是否会员单组合筛选。
 */
public record SalesOrderQuery(
        String orderNo,
        String cashierName,
        Integer counterNo,
        /** 收银员ID（BR-SALE-09：收银员仅本人单据，服务端注入，不开放为请求参数） */
        Long cashierId,
        /** 会员姓名/手机号/会员编号模糊 */
        String memberKeyword,
        /** 收款方式（命中任一收款记录即返回） */
        String payType,
        LocalDate beginDate,
        LocalDate endDate,
        /** 应收金额区间 */
        BigDecimal amountMin,
        BigDecimal amountMax,
        /** 退货状态：NONE/PARTIAL/ALL */
        String returnStatus,
        /** true 仅会员单 false 仅散客单 null 全部 */
        Boolean memberOnly,
        int page,
        int size
) {
}

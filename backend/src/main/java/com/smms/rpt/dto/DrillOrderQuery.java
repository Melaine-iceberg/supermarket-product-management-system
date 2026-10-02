package com.smms.rpt.dto;

import java.time.LocalDate;

/**
 * 汇总数字下钻查询（BR-RPT-06）：报表行点击 → 构成该汇总的销售单列表
 * → 可再打开单据详情。与报表行筛选条件保持一致（同区间/商品/收银员/收银台/会员单）。
 */
public record DrillOrderQuery(
        LocalDate beginDate,
        LocalDate endDate,
        Long goodsId,
        String cashierName,
        Integer counterNo,
        Boolean memberOnly,
        int page,
        int size) {

    public DrillOrderQuery {
        page = Math.max(page, 1);
        size = size <= 0 ? 20 : Math.min(size, 100);
    }
}

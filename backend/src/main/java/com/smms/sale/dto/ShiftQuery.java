package com.smms.sale.dto;

/**
 * 班次分页查询条件（FR-SALE-12）：收银员/台号/状态/日期区间；
 * 收银员仅可查询本人班次（服务端注入 cashierId，BR-SALE-09 同口径）。
 */
public record ShiftQuery(
        String cashierName,
        Integer counterNo,
        String status,
        String beginDate,
        String endDate,
        /** 服务端注入：非全局销售角色时限定本人 */
        Long cashierId,
        int page,
        int size
) {
}

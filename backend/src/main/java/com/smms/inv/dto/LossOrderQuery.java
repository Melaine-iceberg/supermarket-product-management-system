package com.smms.inv.dto;

import java.time.LocalDate;

/**
 * 报损/报溢单列表查询条件（FR-INV-10）：单号/类型/状态/商品关键字/申请人/时间区间组合。
 * 日期为闭区间 [beginDate 00:00, endDate 24:00)。
 */
public record LossOrderQuery(
        String orderNo,
        String bizType,
        String status,
        /** 商品关键字：编码/条码/名称模糊匹配 */
        String goodsKeyword,
        String applyName,
        LocalDate beginDate,
        LocalDate endDate,
        int page,
        int size) {
}

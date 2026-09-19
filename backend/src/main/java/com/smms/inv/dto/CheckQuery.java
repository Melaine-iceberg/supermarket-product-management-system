package com.smms.inv.dto;

import java.time.LocalDate;

/**
 * 盘点单列表查询条件（FR-INV-07）：单号/状态/盘点人/审核人/时间区间组合。
 * 日期为闭区间 [beginDate 00:00, endDate 24:00)。
 */
public record CheckQuery(
        String orderNo,
        String status,
        String checkerName,
        String auditorName,
        LocalDate beginDate,
        LocalDate endDate,
        int page,
        int size) {
}

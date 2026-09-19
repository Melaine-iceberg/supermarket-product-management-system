package com.smms.inv.dto;

/**
 * 盘点实盘数 Excel 导入结果（FR-INV-08）：成功导入行数（实盘留空的行跳过不计）+ 录入后实时汇总。
 */
public record CheckImportVO(
        Integer importedCount,
        CheckSummaryVO summary) {
}

package com.smms.inv.dto;

import java.math.BigDecimal;

/**
 * 盘点实时汇总（FR-INV-08）：录入/提交后返回已录入行数、差异行数与盘盈/盘亏总额。
 * 盘盈/盘亏金额 = Σ差异金额（差异>0 计盈、差异<0 计亏，按快照成本单价）。
 */
public record CheckSummaryVO(
        Integer enteredCount,
        Integer diffCount,
        BigDecimal surplusAmount,
        BigDecimal shortageAmount) {
}

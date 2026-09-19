package com.smms.inv.dto;

import java.util.List;

/**
 * 库存预警分页结果（FR-INV-05）：total 为当前预警类型筛选下的条数，
 * summary 为去掉类型筛选后的各类型数量（页签切换不跳动）。
 */
public record AlertPageVO(
        List<AlertItemVO> list,
        long total,
        AlertSummaryVO summary
) {
}

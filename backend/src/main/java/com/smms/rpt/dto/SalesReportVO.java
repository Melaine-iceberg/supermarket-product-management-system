package com.smms.rpt.dto;

import java.util.List;

/**
 * 销售报表结果（FR-RPT-02）：明细行 + 合计行（合计与逐行之和一致，AC-16 验收要点）。
 */
public record SalesReportVO(List<SalesReportRow> rows, SalesReportRow total) {
}

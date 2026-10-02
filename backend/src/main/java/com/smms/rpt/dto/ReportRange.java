package com.smms.rpt.dto;

import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;

import java.time.LocalDate;

/**
 * 报表查询区间（BR-RPT-04）：默认"今日"；单次最大可查询区间 1 年，
 * 超限提示缩小范围（性能保护，NFR-PERF-05）。
 * 所有报表接口共用该解析逻辑，保证口径一致。
 */
public record ReportRange(LocalDate beginDate, LocalDate endDate) {

    /** 单次最大可查询跨度（含首尾，BR-RPT-04） */
    private static final long MAX_DAYS = 366;

    public ReportRange {
        if (beginDate == null && endDate == null) {
            beginDate = LocalDate.now();
            endDate = beginDate;
        } else if (beginDate == null) {
            beginDate = endDate;
        } else if (endDate == null) {
            endDate = beginDate;
        }
        if (beginDate.isAfter(endDate)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "开始日期不能晚于结束日期");
        }
        if (endDate.toEpochDay() - beginDate.toEpochDay() + 1 > MAX_DAYS) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "查询区间超过 1 年，请缩小范围后重试（BR-RPT-04）");
        }
    }

    /** 区间天数（含首尾） */
    public long days() {
        return endDate.toEpochDay() - beginDate.toEpochDay() + 1;
    }
}

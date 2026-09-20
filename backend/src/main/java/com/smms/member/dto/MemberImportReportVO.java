package com.smms.member.dto;

import java.util.List;

/**
 * 会员导入校验报告（FR-MEM-03，BR-GOODS-07 机制）：
 * 上传后全量校验、不写库，返回逐行结果供确认。
 */
public record MemberImportReportVO(
        int total,
        int passedCount,
        int failedCount,
        List<MemberImportRowVO> rows
) {
}

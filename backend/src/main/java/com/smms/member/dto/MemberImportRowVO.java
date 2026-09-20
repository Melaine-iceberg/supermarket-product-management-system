package com.smms.member.dto;

import java.util.List;
import java.util.Map;

/**
 * 会员导入单行校验结果（FR-MEM-03，复用 FR-GOODS-11 校验—确认机制）。
 */
public record MemberImportRowVO(
        /** Excel 行号（从 1 开始，与用户视角一致） */
        int rowNum,
        boolean passed,
        List<String> errors,
        Map<String, String> data
) {
}

package com.smms.member.dto;

import java.util.List;

/**
 * 会员导入提交结果（FR-MEM-03，BR-GOODS-07 机制）：
 * 单事务内仅写入通过行，失败行返回明细供修正重传。
 */
public record MemberImportResultVO(
        int imported,
        int failed,
        List<MemberImportRowVO> failedRows
) {
}

package com.smms.inv.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 盘点单列表行（FR-INV-07）：单头信息 + 盘盈/盘亏汇总。
 */
public record CheckOrderVO(
        Long id,
        String orderNo,
        String scopeType,
        String scopeDesc,
        String status,
        String checkerName,
        LocalDateTime startTime,
        LocalDateTime baseTime,
        Integer itemCount,
        /** 已录入实盘数的行数（0~itemCount），录入进度提示 */
        Integer enteredCount,
        /** 差异行数（diffQty≠0），供列表快速识别 */
        Integer diffCount,
        BigDecimal surplusAmount,
        BigDecimal shortageAmount,
        Integer duringChangeCount,
        String auditorName,
        LocalDateTime auditTime,
        String auditRemark,
        String cancelReason,
        LocalDateTime createTime) {
}

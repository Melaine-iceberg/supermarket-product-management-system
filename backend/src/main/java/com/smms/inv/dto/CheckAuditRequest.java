package com.smms.inv.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 盘点审核请求（FR-INV-09）：通过后在单事务内生成盘盈/盘亏流水并覆盖结存；
 * 驳回须填写意见并退回盘点中。审核人与盘点人必须不同（BR-SYS-01）。
 */
public record CheckAuditRequest(
        /** true=审核通过 false=驳回 */
        @NotNull(message = "审核结论不能为空")
        Boolean approved,

        /** 审核意见（驳回必填） */
        String remark) {
}

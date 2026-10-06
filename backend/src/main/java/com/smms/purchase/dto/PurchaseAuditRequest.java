package com.smms.purchase.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 采购单审核请求（FR-PUR-04）：店长审核通过/驳回；驳回时意见必填（BR-PUR-02）。
 */
public record PurchaseAuditRequest(
        @NotNull(message = "审核结论不能为空")
        Boolean approved,
        /** 审核意见：驳回时必填 */
        String remark,
        /** 当前账号密码二次确认（BR-SYS-06） */
        String password
) {
}

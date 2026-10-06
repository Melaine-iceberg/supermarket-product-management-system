package com.smms.inv.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 报损/报溢审核请求（FR-INV-10）：通过后在单事务内生成报损出库/报溢入库流水；
 * 驳回须填写意见（单据置为已驳回，不支持修改后重提）。
 */
public record LossAuditRequest(
        /** true=审核通过 false=驳回 */
        @NotNull(message = "审核结论不能为空")
        Boolean approved,

        /** 审核意见（驳回必填） */
        @Size(max = 200, message = "审核意见最多 200 字")
        String remark,

        /** 当前账号密码二次确认（BR-SYS-06） */
        String password) {
}

package com.smms.purchase.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 采购单作废/终止/关闭原因请求（FR-PUR-05）：三类操作均须填写原因。
 */
public record PurchaseReasonRequest(
        @NotBlank(message = "原因不能为空")
        String reason
) {
}

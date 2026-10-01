package com.smms.supplier.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 供应商停用/启用请求（FR-SUP-03）：status 取值 1启用 0停用。
 */
public record SupplierStatusRequest(
        @NotNull(message = "状态不能为空")
        @Pattern(regexp = "0|1", message = "状态只能为 0停用 或 1启用")
        Integer status
) {
}

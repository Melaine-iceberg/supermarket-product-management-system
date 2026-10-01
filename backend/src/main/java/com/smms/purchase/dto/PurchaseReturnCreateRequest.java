package com.smms.purchase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 采购退货创建请求（FR-PUR-08）：选择采购单 → 勾选商品与退货数量
 * （≤ 已入库数量 − 已退数量）→ 填写原因；创建后待店长审核。
 */
@Data
public class PurchaseReturnCreateRequest {

    @NotNull(message = "采购单不能为空")
    private Long purchaseOrderId;

    @NotBlank(message = "退货原因不能为空")
    private String returnReason;

    private String remark;

    @NotEmpty(message = "退货明细不能为空")
    @Valid
    private List<ItemRequest> items;

    @Data
    public static class ItemRequest {

        @NotNull(message = "采购明细不能为空")
        private Long purchaseItemId;

        /** 退货数量 > 0，且 ≤ 已入库 − 已退 */
        @NotNull(message = "退货数量不能为空")
        @DecimalMin(value = "0.001", message = "退货数量必须大于 0")
        private BigDecimal returnQty;

        private String remark;
    }
}

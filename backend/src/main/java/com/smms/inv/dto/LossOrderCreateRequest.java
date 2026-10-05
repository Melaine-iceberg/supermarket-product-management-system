package com.smms.inv.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 报损/报溢登记请求（FR-INV-10）。
 * bizType：LOSS报损出库（成本按当前成本快照，审核时按实际结转成本定稿）/
 * OVERFLOW报溢入库（须录入成本单价）。
 */
public record LossOrderCreateRequest(
        @NotBlank(message = "业务类型不能为空")
        String bizType,

        @NotNull(message = "商品不能为空")
        Long goodsId,

        @NotNull(message = "数量不能为空")
        BigDecimal quantity,

        /** 成本单价：报溢必填（FR-INV-10）；报损由服务端按当前成本快照，忽略传入值 */
        BigDecimal costPrice,

        @NotBlank(message = "报损报溢原因不能为空")
        String reason,

        @Size(max = 200, message = "备注最多 200 字")
        String remark) {
}

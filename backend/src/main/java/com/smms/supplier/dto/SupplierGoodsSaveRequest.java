package com.smms.supplier.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 维护供应商供货商品请求（FR-SUP-04）：全量覆盖保存；
 * 一个商品至多一个默认供应商（设为默认时自动清除其他供应商的默认标记）。
 */
@Data
public class SupplierGoodsSaveRequest {

    /** 供货商品清单（空列表 = 清空该供应商全部关联） */
    @Valid
    private List<Item> items;

    @Data
    public static class Item {

        @NotNull(message = "商品不能为空")
        private Long goodsId;

        /** 供货单价 ≥0 */
        @DecimalMin(value = "0", message = "供货单价不能为负数")
        private BigDecimal supplyPrice;

        /** 是否默认供应商：1是 0否 */
        private Integer isDefault;
    }
}

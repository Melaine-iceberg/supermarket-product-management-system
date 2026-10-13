package com.smms.purchase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 采购单创建/编辑请求（FR-PUR-01）：选择供应商后录入明细；
 * 明细金额与单据总额由系统自动计算（BR-PUR-03）。
 */
@Data
public class PurchaseSaveRequest {

    @NotNull(message = "供应商不能为空")
    private Long supplierId;

    /** 预计到货日期 */
    private LocalDate expectDate;

    private String remark;

    /** 明细行数上限 200（BR-PUR-03） */
    @NotEmpty(message = "采购明细不能为空")
    @Size(max = 200, message = "单张采购单明细最多 200 行（BR-PUR-03）")
    @Valid
    private List<ItemRequest> items;

    @Data
    public static class ItemRequest {

        @NotNull(message = "商品不能为空")
        private Long goodsId;

        /** 采购数量 > 0（BR-PUR-03） */
        @NotNull(message = "采购数量不能为空")
        @DecimalMin(value = "0.001", message = "采购数量必须大于 0（BR-PUR-03）")
        private BigDecimal quantity;

        /** 采购单价 ≥ 0（BR-PUR-03） */
        @NotNull(message = "采购单价不能为空")
        @Min(value = 0, message = "采购单价不能为负（BR-PUR-03）")
        private BigDecimal price;

        /** 建议采购原值：建议量与建议价（建议采购生成时记录，UC-INV-002 6a；手工建单为空） */
        @DecimalMin(value = "0", message = "建议量不能为负")
        private BigDecimal suggestedQty;

        @DecimalMin(value = "0", message = "建议价不能为负")
        private BigDecimal suggestedPrice;

        private String remark;
    }
}

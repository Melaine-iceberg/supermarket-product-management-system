package com.smms.purchase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 建议采购生成草稿请求（FR-PUR-02）：勾选商品后按默认供应商分组一键生成草稿采购单；
 * 数量与单价可覆盖（默认取建议量与最近采购价/供货价）。
 */
@Data
public class SuggestGenerateRequest {

    @NotEmpty(message = "请勾选需要采购的商品")
    @Valid
    private List<ItemRequest> items;

    @Data
    public static class ItemRequest {

        @NotNull(message = "商品不能为空")
        private Long goodsId;

        /** 采购数量（默认取建议采购量） */
        @DecimalMin(value = "0.001", message = "采购数量必须大于 0")
        private BigDecimal quantity;

        /** 采购单价（默认取最近采购价，其次供货价） */
        @DecimalMin(value = "0", message = "采购单价不能为负")
        private BigDecimal price;

        /** 供应商（可改，UC-INV-002 6a）：为空时取默认供应商；均无则须先指定 */
        private Long supplierId;
    }
}

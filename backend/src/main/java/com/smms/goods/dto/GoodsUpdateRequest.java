package com.smms.goods.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 商品档案编辑请求（FR-GOODS-02）。
 * 商品编码与初始库存不可修改（BR-GOODS-02、BR-INV-11）；
 * 条码修改须前端二次确认后携带 barcodeConfirmed=true（FR-GOODS-02）；
 * 价格变更记录调价历史（FR-GOODS-10），原因可选。
 */
public record GoodsUpdateRequest(

        @NotBlank(message = "商品名称不能为空")
        @Size(max = 100, message = "商品名称不能超过100个字符")
        String name,

        @NotNull(message = "所属分类不能为空")
        Long categoryId,

        @NotNull(message = "计量单位不能为空")
        Long unitId,

        Long brandId,

        @Size(max = 50, message = "规格不能超过50个字符")
        String spec,

        @Size(max = 100, message = "产地不能超过100个字符")
        String origin,

        @DecimalMin(value = "0", message = "进货参考价不能为负数")
        BigDecimal purchasePrice,

        @NotNull(message = "零售单价不能为空")
        @DecimalMin(value = "0.01", message = "零售单价必须大于0")
        BigDecimal retailPrice,

        @DecimalMin(value = "0", message = "会员价不能为负数")
        BigDecimal memberPrice,

        /** 条码全局唯一；与原值不同时必须携带 barcodeConfirmed=true */
        @NotBlank(message = "商品条码不能为空")
        @Size(max = 20, message = "条码不能超过20个字符")
        String barcode,

        /** 条码修改二次确认标记（FR-GOODS-02） */
        @NotNull(message = "条码变更确认标记不能为空")
        Boolean barcodeConfirmed,

        @Size(max = 10, message = "PLU码不能超过10个字符")
        String pluCode,

        @NotNull(message = "是否称重不能为空")
        @Min(0) @Max(1)
        Integer isWeighed,

        @NotNull(message = "是否允许退货不能为空")
        @Min(0) @Max(1)
        Integer allowReturn,

        @DecimalMin(value = "0", message = "安全库存下限不能为负数")
        BigDecimal safetyStockLower,

        @DecimalMin(value = "0", message = "库存上限不能为负数")
        BigDecimal safetyStockUpper,

        @Size(max = 255, message = "图片路径不能超过255个字符")
        String image,

        /** 调价原因（价格变更时随调价历史记录，FR-GOODS-10） */
        @Size(max = 200, message = "调价原因不能超过200个字符")
        String priceChangeReason,

        @Size(max = 500, message = "备注不能超过500个字符")
        String remark
) {
}

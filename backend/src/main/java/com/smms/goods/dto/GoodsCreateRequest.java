package com.smms.goods.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 商品档案新增请求（FR-GOODS-01）。
 * 必填：名称、分类、单位、零售单价；条码/编码留空时系统自动生成
 * （店内码 FR-GOODS-07、编码规则 BR-GOODS-02）。
 */
public record GoodsCreateRequest(

        @NotBlank(message = "商品名称不能为空")
        @Size(max = 100, message = "商品名称不能超过100个字符")
        String name,

        /** 所属分类（必须是启用状态的叶子分类，BR-CAT-04） */
        @NotNull(message = "所属分类不能为空")
        Long categoryId,

        /** 计量单位（须为启用状态） */
        @NotNull(message = "计量单位不能为空")
        Long unitId,

        /** 品牌（可选） */
        Long brandId,

        @Size(max = 50, message = "规格不能超过50个字符")
        String spec,

        @Size(max = 100, message = "产地不能超过100个字符")
        String origin,

        /** 进货参考价 ≥0（BR-GOODS-03） */
        @DecimalMin(value = "0", message = "进货参考价不能为负数")
        BigDecimal purchasePrice,

        /** 零售单价 >0（BR-GOODS-03） */
        @NotNull(message = "零售单价不能为空")
        @DecimalMin(value = "0.01", message = "零售单价必须大于0")
        BigDecimal retailPrice,

        /** 会员价 ≤零售价（BR-GOODS-03） */
        @DecimalMin(value = "0", message = "会员价不能为负数")
        BigDecimal memberPrice,

        /** 商品条码：留空自动生成店内码（FR-GOODS-07），全局唯一（BR-GOODS-01） */
        @Size(max = 20, message = "条码不能超过20个字符")
        String barcode,

        /** 商品编码：留空按 分类码+流水 自动生成（BR-GOODS-02），自定义须唯一且≤20 */
        @Size(max = 20, message = "商品编码不能超过20个字符")
        String goodsCode,

        /** PLU 码（称重商品必填，BR-GOODS-05） */
        @Size(max = 10, message = "PLU码不能超过10个字符")
        String pluCode,

        /** 是否称重商品：1是 0否 */
        @NotNull(message = "是否称重不能为空")
        @Min(0) @Max(1)
        Integer isWeighed,

        /** 是否允许退货：1是 0否 */
        @NotNull(message = "是否允许退货不能为空")
        @Min(0) @Max(1)
        Integer allowReturn,

        /** 安全库存下限 ≥0 */
        @DecimalMin(value = "0", message = "安全库存下限不能为负数")
        BigDecimal safetyStockLower,

        /** 库存上限（积压预警，须≥下限） */
        @DecimalMin(value = "0", message = "库存上限不能为负数")
        BigDecimal safetyStockUpper,

        /** 图片相对路径 */
        @Size(max = 255, message = "图片路径不能超过255个字符")
        String image,

        /** 初始库存：>0 时生成"期初建账"流水（BR-INV-01），仅创建时可设 */
        @DecimalMin(value = "0", message = "初始库存不能为负数")
        BigDecimal initialStock,

        @Size(max = 500, message = "备注不能超过500个字符")
        String remark
) {
}

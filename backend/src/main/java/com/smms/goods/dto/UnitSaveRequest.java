package com.smms.goods.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 计量单位新增/编辑请求（FR-GOODS-08）。
 */
public record UnitSaveRequest(

        /** 单位名称：唯一，≤10 字符 */
        @NotBlank(message = "单位名称不能为空")
        @Size(max = 10, message = "单位名称不能超过10个字符")
        String name,

        /** 单位符号 */
        @Size(max = 10, message = "单位符号不能超过10个字符")
        String symbol,

        /** 是否重量单位：1是 0否（称重商品只能选重量单位，BR-GOODS-05） */
        @NotNull(message = "是否重量单位不能为空")
        @Min(0) @Max(1)
        Integer isWeight,

        /** 状态：1启用 0停用 */
        @NotNull(message = "状态不能为空")
        @Min(0) @Max(1)
        Integer status
) {
}

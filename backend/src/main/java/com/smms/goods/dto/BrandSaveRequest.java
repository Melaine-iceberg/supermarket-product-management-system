package com.smms.goods.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 品牌新增/编辑请求（FR-GOODS-13）。
 */
public record BrandSaveRequest(

        /** 品牌名称：唯一 */
        @NotBlank(message = "品牌名称不能为空")
        @Size(max = 30, message = "品牌名称不能超过30个字符")
        String name,

        /** 首字母（1~2位字母） */
        @Pattern(regexp = "^[A-Za-z]{0,2}$", message = "首字母须为1~2位英文字母")
        String firstLetter,

        /** Logo 路径 */
        @Size(max = 255, message = "Logo路径不能超过255个字符")
        String logo,

        /** 状态：1启用 0停用 */
        @NotNull(message = "状态不能为空")
        @Min(0) @Max(1)
        Integer status
) {
}

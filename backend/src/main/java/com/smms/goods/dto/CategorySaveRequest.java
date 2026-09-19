package com.smms.goods.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 分类新增/编辑请求（FR-CAT-02/03）。
 */
public record CategorySaveRequest(

        /** 分类名称：1~30 字符，不含前后空格（BR-CAT-01） */
        @NotBlank(message = "分类名称不能为空")
        @Size(max = 30, message = "分类名称不能超过30个字符")
        String name,

        /** 上级分类ID，0=一级分类 */
        @NotNull(message = "上级分类不能为空")
        @Min(value = 0, message = "上级分类不合法")
        Long parentId,

        /** 同级排序号（FR-CAT-06） */
        @NotNull(message = "排序号不能为空")
        @Min(value = 0, message = "排序号不能为负数")
        @Max(value = 9999, message = "排序号不能超过9999")
        Integer sortNo,

        /** 状态：1启用 0停用 */
        @NotNull(message = "状态不能为空")
        @Min(0) @Max(1)
        Integer status
) {
}

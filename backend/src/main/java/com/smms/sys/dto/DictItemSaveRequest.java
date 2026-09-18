package com.smms.sys.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 新增/编辑字典项请求（FR-SYS-07）。
 */
public record DictItemSaveRequest(
        @NotBlank(message = "标签不能为空")
        @Size(max = 30, message = "标签不能超过 30 字")
        String label,

        @NotBlank(message = "值不能为空")
        @Size(max = 30, message = "值不能超过 30 字")
        String value,

        @NotNull(message = "排序号不能为空")
        @Min(value = 0, message = "排序号须 ≥ 0")
        @Max(value = 9999, message = "排序号须 ≤ 9999")
        Integer sortNo,

        /** 1 启用 0 停用（停用项不可被新单据选择） */
        @NotNull(message = "状态不能为空") Integer status,

        @Size(max = 200, message = "备注不能超过 200 字")
        String remark
) {
}

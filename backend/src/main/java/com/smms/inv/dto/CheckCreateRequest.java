package com.smms.inv.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 盘点单创建请求（FR-INV-07）：选择盘点范围，系统快照账面数量。
 * scopeType：ALL全店 / CATEGORY分类（categoryId 必填）/ GOODS指定商品（goodsIds 必填）/ WARNING预警商品。
 */
public record CheckCreateRequest(
        @NotBlank(message = "盘点范围类型不能为空")
        String scopeType,

        /** CATEGORY 范围：分类ID（含子树） */
        Long categoryId,

        /** GOODS 范围：商品ID列表 */
        @Size(max = 500, message = "指定商品一次最多 500 个")
        List<Long> goodsIds,

        /** 备注（可选），拼入范围描述 */
        String remark) {
}

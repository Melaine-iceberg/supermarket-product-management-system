package com.smms.inv.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.inv.dto.CategoryThresholdRequest;
import com.smms.inv.dto.SafetyStockUpdateRequest;
import com.smms.inv.service.SafetyStockService;
import com.smms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 安全库存阈值维护接口（FR-INV-06）：单个/批量设置与按分类批量设置默认阈值，
 * 修改逐商品留痕（操作人、前值、后值、时间）。
 * 阈值是档案属性，不影响库存结存（BR-INV-11）。
 */
@RestController
@RequestMapping("/api/v1/inventory/safety-stock")
@RequiredArgsConstructor
public class SafetyStockController {

    private final SafetyStockService safetyStockService;

    /** 单个/批量设置阈值（FR-INV-06）：body.items 每项提供目标阈值全量，返回实际变更数 */
    @PutMapping
    @RequirePerm("sys:inv:threshold")
    @OpLog(module = "库存管理", action = "修改安全库存阈值")
    public ApiResponse<Integer> update(@RequestBody SafetyStockUpdateRequest req) {
        return ApiResponse.ok(safetyStockService.updateItems(req,
                SecurityUtils.getLoginUser().username()));
    }

    /** 按分类批量设置默认阈值（FR-INV-06）：作用于分类（含子分类）下全部商品，返回实际变更数 */
    @PutMapping("/by-category")
    @RequirePerm("sys:inv:threshold")
    @OpLog(module = "库存管理", action = "按分类设置安全库存阈值")
    public ApiResponse<Integer> updateByCategory(@RequestBody CategoryThresholdRequest req) {
        return ApiResponse.ok(safetyStockService.updateByCategory(req,
                SecurityUtils.getLoginUser().username()));
    }
}

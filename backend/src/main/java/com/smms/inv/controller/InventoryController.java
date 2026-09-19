package com.smms.inv.controller;

import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.inv.dto.InventoryPageVO;
import com.smms.inv.dto.InventoryQuery;
import com.smms.inv.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实时库存接口（FR-INV-01）：结存数量、成本单价、库存成本金额、库存状态、
 * 最近变动时间；分类（含子树）/名称/编码/库存状态筛选；合计行含库存总成本金额。
 * 结存为派生数据，本接口只读，不提供任何直接修改入口（BR-INV-11）。
 */
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    /** 实时库存分页 + 合计行（FR-INV-01）：默认每页 20 条，可选 20/50/100 */
    @GetMapping
    @RequirePerm("sys:inv:list")
    public ApiResponse<InventoryPageVO> page(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String goodsCode,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String stockStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        InventoryQuery query = new InventoryQuery(name, goodsCode, categoryId, stockStatus,
                page, size, sortBy, sortDir);
        return ApiResponse.ok(inventoryService.page(query));
    }
}

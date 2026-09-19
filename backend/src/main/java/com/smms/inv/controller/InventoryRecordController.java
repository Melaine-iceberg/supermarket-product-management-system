package com.smms.inv.controller;

import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.PageVO;
import com.smms.inv.dto.InventoryRecordQuery;
import com.smms.inv.dto.InventoryRecordVO;
import com.smms.inv.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 库存流水接口（FR-INV-03）：不可变流水按时间倒序分页，
 * 支持按商品/类型/时间区间/单据号/操作人组合查询。流水只读（BR-INV-04）。
 */
@RestController
@RequestMapping("/api/v1/inventory/records")
@RequiredArgsConstructor
public class InventoryRecordController {

    private final InventoryService inventoryService;

    /** 流水分页（FR-INV-03）：日期区间为闭区间日粒度 */
    @GetMapping
    @RequirePerm("sys:inv-record:list")
    public ApiResponse<PageVO<InventoryRecordVO>> page(
            @RequestParam(required = false) Long goodsId,
            @RequestParam(required = false) String goodsName,
            @RequestParam(required = false) String recordType,
            @RequestParam(required = false) String bizNo,
            @RequestParam(required = false) String operatorName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        InventoryRecordQuery query = new InventoryRecordQuery(goodsId, goodsName, recordType,
                bizNo, operatorName, beginDate, endDate, page, size);
        return ApiResponse.ok(inventoryService.recordPage(query));
    }
}

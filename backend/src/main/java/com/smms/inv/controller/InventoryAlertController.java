package com.smms.inv.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.task.AsyncTaskService;
import com.smms.common.task.AsyncTaskVO;
import com.smms.common.task.TaskParams;
import com.smms.inv.dto.AlertPageVO;
import com.smms.inv.dto.AlertQuery;
import com.smms.inv.dto.AlertSummaryVO;
import com.smms.inv.service.InventoryAlertExcelService;
import com.smms.inv.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.time.format.DateTimeFormatter;

/**
 * 库存预警接口（FR-INV-05）：按安全库存下限/上限判定缺货、预警、积压；
 * 预警列表支持按缺口量排序与导出；汇总数量供首页看板角标（FR-RPT-01）。
 * 仅统计启用商品（FR-GOODS-05），缺货口径含负库存（BR-INV-02）。
 */
@RestController
@RequestMapping("/api/v1/inventory/alerts")
@RequiredArgsConstructor
public class InventoryAlertController {

    private final InventoryService inventoryService;
    private final AsyncTaskService taskService;
    private final InventoryAlertExcelService alertExcelService;

    /** 预警分页（FR-INV-05）：默认每页 20 条，默认按缺口量降序（最紧急优先） */
    @GetMapping
    @RequirePerm("sys:inv-warning:list")
    public ApiResponse<AlertPageVO> page(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String goodsCode,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String alertType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        AlertQuery query = new AlertQuery(name, goodsCode, categoryId, alertType,
                page, size, sortBy, sortDir);
        return ApiResponse.ok(inventoryService.alertPage(query));
    }

    /** 预警数量汇总（FR-INV-05/FR-RPT-01）：看板角标与页签数量，忽略类型条件 */
    @GetMapping("/summary")
    @RequirePerm("sys:inv-warning:list")
    public ApiResponse<AlertSummaryVO> summary(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String goodsCode,
            @RequestParam(required = false) Long categoryId) {
        AlertQuery query = new AlertQuery(name, goodsCode, categoryId, null, 1, 1, null, null);
        return ApiResponse.ok(inventoryService.alertSummary(query));
    }

    /** 预警清单导出（FR-INV-05）：与列表同一筛选条件，导出记录日志（FR-RPT-08） */
    @GetMapping("/export")
    @RequirePerm("sys:inv-warning:export")
    @OpLog(module = "库存管理", action = "导出预警清单")
    public ApiResponse<AsyncTaskVO> export(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String goodsCode,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String alertType,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        Map<String, Object> params = TaskParams.of("name", name, "goodsCode", goodsCode,
                "categoryId", categoryId, "alertType", alertType, "sortBy", sortBy, "sortDir", sortDir);
        return ApiResponse.ok(taskService.submitExport("INVENTORY_ALERT_EXPORT", "库存预警清单", params,
                ctx -> {
                    AlertQuery query = new AlertQuery(name, goodsCode, categoryId, alertType, 1, 20, sortBy, sortDir);
                    ctx.progress(50, "正在生成库存预警清单");
                    ctx.fileName("inventory-alert-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".xlsx");
                    return alertExcelService.export(query);
                }));
    }
}

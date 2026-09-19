package com.smms.inv.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.PageVO;
import com.smms.inv.dto.CheckCreateRequest;
import com.smms.inv.dto.CheckOrderDetailVO;
import com.smms.inv.dto.CheckOrderVO;
import com.smms.inv.dto.CheckQuery;
import com.smms.inv.service.StockCheckService;
import com.smms.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 盘点管理接口（FR-INV-07~09，§2.2.4 状态机）：
 * 创建（快照账面数）→ 录入实盘数 → 提交审核 → 店长审核（覆盖结存生成盘盈/亏流水）。
 * 审核人与盘点人必须不同（BR-SYS-01）；同范围不可重复创建未审核单（BR-INV-09）。
 */
@RestController
@RequestMapping("/api/v1/stock-checks")
@RequiredArgsConstructor
public class StockCheckController {

    private final StockCheckService stockCheckService;

    /** 创建盘点单（FR-INV-07）：选择范围并快照账面数量，返回单号供跳转录入页 */
    @PostMapping
    @RequirePerm("sys:check:create")
    @OpLog(module = "库存管理", action = "创建盘点单")
    public ApiResponse<CheckOrderVO> create(@Valid @RequestBody CheckCreateRequest req) {
        return ApiResponse.ok(stockCheckService.create(req, SecurityUtils.getLoginUser().username()));
    }

    /** 盘点单分页（FR-INV-07）：单号/状态/盘点人/审核人/时间区间 */
    @GetMapping
    @RequirePerm("sys:check:list")
    public ApiResponse<PageVO<CheckOrderVO>> page(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String checkerName,
            @RequestParam(required = false) String auditorName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        CheckQuery query = new CheckQuery(orderNo, status, checkerName, auditorName,
                beginDate, endDate, page, size);
        return ApiResponse.ok(stockCheckService.page(query));
    }

    /** 盘点单详情（FR-INV-07/08）：单头 + 明细分页，keyword 匹配编码/条码/名称（扫码定位行） */
    @GetMapping("/{id}")
    @RequirePerm("sys:check:list")
    public ApiResponse<CheckOrderDetailVO> detail(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean onlyDiff,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(stockCheckService.detail(id, page, size, onlyDiff, keyword));
    }

    /** 作废盘点单（§2.2.4：仅盘点中可作废），须填写原因 */
    @PostMapping("/{id}/cancel")
    @RequirePerm("sys:check:cancel")
    @OpLog(module = "库存管理", action = "作废盘点单")
    public ApiResponse<Void> cancel(@PathVariable Long id, @RequestBody Map<String, String> body) {
        stockCheckService.cancel(id, body.get("reason"));
        return ApiResponse.ok(null);
    }
}

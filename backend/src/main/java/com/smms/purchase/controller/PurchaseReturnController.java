package com.smms.purchase.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.PageVO;
import com.smms.purchase.dto.PurchaseAuditRequest;
import com.smms.purchase.dto.PurchaseReturnCreateRequest;
import com.smms.purchase.dto.PurchaseReturnQuery;
import com.smms.purchase.dto.PurchaseReturnVO;
import com.smms.purchase.service.PurchaseReturnService;
import com.smms.security.SecurityUtils;
import com.smms.sys.service.SensitiveAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 采购退货接口（FR-PUR-08）：创建退货单（待审核）、店长审核（通过执行退货/驳回）、
 * 退货单查询与详情。
 */
@RestController
@RequestMapping("/api/v1/purchase-returns")
@RequiredArgsConstructor
public class PurchaseReturnController {

    private final PurchaseReturnService purchaseReturnService;
    private final SensitiveAuthService sensitiveAuthService;

    /** 退货单分页：退货单号/采购单号/供应商/状态/日期区间 */
    @GetMapping
    @RequirePerm("sys:pur:list")
    public ApiResponse<PageVO<PurchaseReturnVO>> page(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String purchaseOrderNo,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PurchaseReturnQuery query = new PurchaseReturnQuery(orderNo, purchaseOrderNo, supplierId,
                status, beginDate, endDate, page, size);
        return ApiResponse.ok(purchaseReturnService.page(query));
    }

    /** 退货单详情 */
    @GetMapping("/{id}")
    @RequirePerm("sys:pur:list")
    public ApiResponse<PurchaseReturnVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(purchaseReturnService.detail(id));
    }

    /** 创建退货单（FR-PUR-08）：勾选商品与退货数量（≤ 已入库 − 已退），填写原因 */
    @PostMapping
    @RequirePerm("sys:pur:return")
    @OpLog(module = "采购管理", action = "创建采购退货单", target = "#req.purchaseOrderId")
    public ApiResponse<PurchaseReturnVO> create(@Valid @RequestBody PurchaseReturnCreateRequest req) {
        return ApiResponse.ok(purchaseReturnService.create(req, SecurityUtils.getLoginUser().realName()));
    }

    /** 店长审核（FR-PUR-08）：通过后事务内执行退货；驳回须填意见 */
    @PutMapping("/{id}/audit")
    @RequirePerm("sys:pur:return-audit")
    @OpLog(module = "采购管理", action = "审核采购退货单", target = "#id")
    public ApiResponse<Void> audit(@PathVariable Long id,
                                   @Valid @RequestBody PurchaseAuditRequest req) {
        sensitiveAuthService.confirmPassword(req.password());
        purchaseReturnService.audit(id, req, SecurityUtils.getLoginUser().realName());
        return ApiResponse.ok();
    }
}

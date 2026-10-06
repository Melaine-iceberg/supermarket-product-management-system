package com.smms.purchase.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.PageVO;
import com.smms.purchase.dto.PurchaseAuditRequest;
import com.smms.purchase.dto.PurchaseOrderDetailVO;
import com.smms.purchase.dto.PurchaseOrderQuery;
import com.smms.purchase.dto.PurchaseOrderVO;
import com.smms.purchase.dto.PurchaseReasonRequest;
import com.smms.purchase.dto.PurchaseSaveRequest;
import com.smms.purchase.dto.PurchaseSettleRequest;
import com.smms.purchase.service.PurchaseOrderService;
import com.smms.security.SecurityUtils;
import com.smms.sys.service.SensitiveAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 采购单接口（FR-PUR-01/03/04/05/09）：
 * 草稿创建/编辑/删除、提交审核、店长审核（通过/驳回）、创建人撤回、
 * 作废/终止/关闭短少、结算登记、多维分页查询。
 */
@RestController
@RequestMapping("/api/v1/purchase-orders")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;
    private final SensitiveAuthService sensitiveAuthService;

    /** 采购单分页查询（FR-PUR-03）：单号/供应商/状态/创建人/日期区间/商品筛选 */
    @GetMapping
    @RequirePerm("sys:pur:list")
    public ApiResponse<PageVO<PurchaseOrderVO>> page(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String createBy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createDateBegin,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createDateEnd,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expectDateBegin,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expectDateEnd,
            @RequestParam(required = false) String goodsKeyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PurchaseOrderQuery query = new PurchaseOrderQuery(orderNo, supplierId, status, createBy,
                createDateBegin, createDateEnd, expectDateBegin, expectDateEnd,
                goodsKeyword, page, size);
        return ApiResponse.ok(purchaseOrderService.page(query));
    }

    /** 采购单详情（FR-PUR-03）：单头 + 明细（含已入库/已退货/待入库数量） */
    @GetMapping("/{id}")
    @RequirePerm("sys:pur:list")
    public ApiResponse<PurchaseOrderDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(purchaseOrderService.detail(id));
    }

    /** 创建采购单草稿（FR-PUR-01）：单号自动生成，明细金额与总额自动计算 */
    @PostMapping
    @RequirePerm("sys:pur:create")
    @OpLog(module = "采购管理", action = "创建采购单", target = "#req.supplierId")
    public ApiResponse<PurchaseOrderDetailVO> create(@Valid @RequestBody PurchaseSaveRequest req) {
        return ApiResponse.ok(purchaseOrderService.create(req, SecurityUtils.getLoginUser().realName()));
    }

    /** 编辑草稿（FR-PUR-01）：仅草稿态可编辑（BR-PUR-02） */
    @PutMapping("/{id}")
    @RequirePerm("sys:pur:update")
    @OpLog(module = "采购管理", action = "编辑采购单", target = "#id")
    public ApiResponse<Void> update(@PathVariable Long id,
                                    @Valid @RequestBody PurchaseSaveRequest req) {
        purchaseOrderService.update(id, req, SecurityUtils.getLoginUser().realName());
        return ApiResponse.ok();
    }

    /** 删除草稿（FR-PUR-01）：仅草稿态 */
    @DeleteMapping("/{id}")
    @RequirePerm("sys:pur:delete")
    @OpLog(module = "采购管理", action = "删除采购单草稿", target = "#id")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        purchaseOrderService.delete(id);
        return ApiResponse.ok();
    }

    /** 提交审核（FR-PUR-04）：DRAFT → SUBMITTED */
    @PutMapping("/{id}/submit")
    @RequirePerm("sys:pur:submit")
    @OpLog(module = "采购管理", action = "提交采购单审核", target = "#id")
    public ApiResponse<Void> submit(@PathVariable Long id) {
        purchaseOrderService.submit(id, SecurityUtils.getLoginUser().realName());
        return ApiResponse.ok();
    }

    /** 创建人撤回（FR-PUR-04）：SUBMITTED → DRAFT，审核前可撤回 */
    @PutMapping("/{id}/withdraw")
    @RequirePerm("sys:pur:submit")
    @OpLog(module = "采购管理", action = "撤回采购单", target = "#id")
    public ApiResponse<Void> withdraw(@PathVariable Long id) {
        purchaseOrderService.withdraw(id, SecurityUtils.getLoginUser().realName());
        return ApiResponse.ok();
    }

    /** 店长审核（FR-PUR-04）：通过 → 已审核待入库；驳回须填意见退回草稿 */
    @PutMapping("/{id}/audit")
    @RequirePerm("sys:pur:audit")
    @OpLog(module = "采购管理", action = "审核采购单", target = "#id")
    public ApiResponse<Void> audit(@PathVariable Long id,
                                   @Valid @RequestBody PurchaseAuditRequest req) {
        sensitiveAuthService.confirmPassword(req.password());
        purchaseOrderService.audit(id, req, SecurityUtils.getLoginUser().realName());
        return ApiResponse.ok();
    }

    /** 作废草稿（FR-PUR-05）：须填原因 */
    @PutMapping("/{id}/cancel")
    @RequirePerm("sys:pur:terminate")
    @OpLog(module = "采购管理", action = "作废采购单", target = "#id")
    public ApiResponse<Void> cancel(@PathVariable Long id,
                                    @Valid @RequestBody PurchaseReasonRequest req) {
        purchaseOrderService.cancel(id, req, SecurityUtils.getLoginUser().realName());
        return ApiResponse.ok();
    }

    /** 终止已审核未入库单据（FR-PUR-05）：店长操作，须填原因 */
    @PutMapping("/{id}/terminate")
    @RequirePerm("sys:pur:terminate")
    @OpLog(module = "采购管理", action = "终止采购单", target = "#id")
    public ApiResponse<Void> terminate(@PathVariable Long id,
                                       @Valid @RequestBody PurchaseReasonRequest req) {
        purchaseOrderService.terminate(id, req, SecurityUtils.getLoginUser().realName());
        return ApiResponse.ok();
    }

    /** 关闭短少（FR-PUR-05）：部分入库单据关闭剩余数量转已完成 */
    @PutMapping("/{id}/close")
    @RequirePerm("sys:pur:terminate")
    @OpLog(module = "采购管理", action = "关闭采购单剩余数量", target = "#id")
    public ApiResponse<Void> close(@PathVariable Long id,
                                   @Valid @RequestBody PurchaseReasonRequest req) {
        purchaseOrderService.closeRemaining(id, req, SecurityUtils.getLoginUser().realName());
        return ApiResponse.ok();
    }

    /** 结算登记（FR-PUR-09）：标记结算状态并登记金额/方式/日期（不做财务核销，X-05） */
    @PutMapping("/{id}/settle")
    @RequirePerm("sys:pur:settle")
    @OpLog(module = "采购管理", action = "采购结算登记", target = "#id")
    public ApiResponse<Void> settle(@PathVariable Long id,
                                    @Valid @RequestBody PurchaseSettleRequest req) {
        purchaseOrderService.settle(id, req, SecurityUtils.getLoginUser().realName());
        return ApiResponse.ok();
    }
}

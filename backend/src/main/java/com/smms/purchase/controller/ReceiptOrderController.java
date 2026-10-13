package com.smms.purchase.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.PageVO;
import com.smms.purchase.dto.PurchaseOrderDetailVO;
import com.smms.purchase.dto.PurchaseOrderVO;
import com.smms.purchase.dto.ReceiptCreateRequest;
import com.smms.purchase.dto.ReceiptOrderVO;
import com.smms.purchase.dto.ReceiptPreCheckVO;
import com.smms.purchase.dto.ReceiptQuery;
import com.smms.purchase.service.PurchaseOrderService;
import com.smms.purchase.service.ReceiptService;
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

/**
 * 采购入库接口（FR-PUR-06/07）：
 * 入库预检（容差/价差黄色提示）、入库验收（事务联动库存与供应商累计采购额）、
 * 入库单分页查询与详情（A4 打印数据源）、待入库采购单选单与详情（库管员专用只读，GAP-P0-05）。
 */
@RestController
@RequestMapping("/api/v1/receipt-orders")
@RequiredArgsConstructor
public class ReceiptOrderController {

    private final ReceiptService receiptService;
    private final PurchaseOrderService purchaseOrderService;

    /** 入库单分页（FR-PUR-07）：入库单号/采购单号/供应商/日期区间/操作人 */
    @GetMapping
    @RequirePerm("sys:rcv:list")
    public ApiResponse<PageVO<ReceiptOrderVO>> page(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String purchaseOrderNo,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String operatorName,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        ReceiptQuery query = new ReceiptQuery(orderNo, purchaseOrderNo, supplierId,
                beginDate, endDate, operatorName, page, size);
        return ApiResponse.ok(receiptService.page(query));
    }

    /** 入库单详情（FR-PUR-07）：逐行实收数量/单价/金额/验收备注，供打印视图 */
    @GetMapping("/{id}")
    @RequirePerm("sys:rcv:list")
    public ApiResponse<ReceiptOrderVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(receiptService.detail(id));
    }

    /**
     * 待入库采购单分页（GAP-P0-05）：仅"已审核待入库/部分入库"，供库管员验收选单；
     * 按 {@code sys:rcv:list} 鉴权，使库管员无需采购单菜单权限（sys:pur:list）即可走通验收主流程。
     */
    @GetMapping("/pickable")
    @RequirePerm("sys:rcv:list")
    public ApiResponse<PageVO<PurchaseOrderVO>> pickable(
            @RequestParam(required = false) String orderNo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(purchaseOrderService.pickable(orderNo, page, size));
    }

    /** 待入库采购单详情（GAP-P0-05）：验收录入所需明细（含待入库数量与采购单价），按 sys:rcv:list 鉴权 */
    @GetMapping("/pickable/{id}")
    @RequirePerm("sys:rcv:list")
    public ApiResponse<PurchaseOrderDetailVO> pickableDetail(@PathVariable Long id) {
        return ApiResponse.ok(purchaseOrderService.detail(id));
    }

    /** 入库预检（BR-PUR-04/05）：容差上限、价差率、近 3 次均价黄色提示、是否须授权 */
    @PostMapping("/precheck")
    @RequirePerm("sys:rcv:create")
    public ApiResponse<ReceiptPreCheckVO> preCheck(@Valid @RequestBody ReceiptCreateRequest req) {
        return ApiResponse.ok(receiptService.preCheck(req));
    }

    /** 入库验收（FR-PUR-06）：事务内完成入库单/流水/结存/成本/采购单/供应商累计采购额联动 */
    @PostMapping
    @RequirePerm("sys:rcv:create")
    @OpLog(module = "采购管理", action = "采购入库验收", target = "#req.purchaseOrderId")
    public ApiResponse<ReceiptOrderVO> create(@Valid @RequestBody ReceiptCreateRequest req) {
        return ApiResponse.ok(receiptService.create(req, SecurityUtils.getLoginUser()));
    }
}

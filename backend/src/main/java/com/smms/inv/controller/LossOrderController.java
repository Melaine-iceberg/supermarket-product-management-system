package com.smms.inv.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.PageVO;
import com.smms.inv.dto.LossAuditRequest;
import com.smms.inv.dto.LossOrderCreateRequest;
import com.smms.inv.dto.LossOrderQuery;
import com.smms.inv.dto.LossOrderVO;
import com.smms.inv.service.LossOrderService;
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
 * 报损/报溢登记接口（FR-INV-10）：
 * 登记（待审核）→ 店长审核（通过生成 LOSS_OUT/OVERFLOW_IN 流水，驳回必填意见）。
 * 与销售退货分开统计，已审核单据作为损耗报表（FR-RPT-04⑤）数据源。
 */
@RestController
@RequestMapping("/api/v1/loss-orders")
@RequiredArgsConstructor
public class LossOrderController {

    private final LossOrderService lossOrderService;

    /** 登记报损/报溢单（FR-INV-10）：报溢须录入成本单价，原因取自 loss_reason 字典 */
    @PostMapping
    @RequirePerm("sys:loss:create")
    @OpLog(module = "库存管理", action = "报损/报溢登记", target = "#req.bizType")
    public ApiResponse<LossOrderVO> create(@Valid @RequestBody LossOrderCreateRequest req) {
        return ApiResponse.ok(lossOrderService.create(req, SecurityUtils.getLoginUser().username()));
    }

    /** 报损/报溢单分页（FR-INV-10）：单号/类型/状态/商品关键字/申请人/时间区间 */
    @GetMapping
    @RequirePerm("sys:loss:list")
    public ApiResponse<PageVO<LossOrderVO>> page(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String goodsKeyword,
            @RequestParam(required = false) String applyName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        LossOrderQuery query = new LossOrderQuery(orderNo, bizType, status, goodsKeyword,
                applyName, beginDate, endDate, page, size);
        return ApiResponse.ok(lossOrderService.page(query));
    }

    /** 单据详情（FR-INV-10） */
    @GetMapping("/{id}")
    @RequirePerm("sys:loss:list")
    public ApiResponse<LossOrderVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(lossOrderService.detail(id));
    }

    /**
     * 店长审核（FR-INV-10）：通过 → 单事务生成报损出库/报溢入库流水（报损不足即拒绝 BR-INV-02）；
     * 驳回 → 意见必填，单据置为已驳回。
     */
    @PostMapping("/{id}/audit")
    @RequirePerm("sys:loss:audit")
    @OpLog(module = "库存管理", action = "报损报溢审核", target = "#id")
    public ApiResponse<LossOrderVO> audit(@PathVariable Long id,
                                          @Valid @RequestBody LossAuditRequest req) {
        return ApiResponse.ok(lossOrderService.audit(id, req, SecurityUtils.getLoginUser().username()));
    }
}

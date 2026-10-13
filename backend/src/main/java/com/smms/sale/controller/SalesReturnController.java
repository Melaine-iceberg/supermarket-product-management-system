package com.smms.sale.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.PageVO;
import com.smms.sale.dto.ReturnCreateRequest;
import com.smms.sale.dto.ReturnOrderDetailVO;
import com.smms.sale.dto.ReturnOrderVO;
import com.smms.sale.dto.ReturnQuery;
import com.smms.sale.service.SalesReturnService;
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
 * 销售退货接口（FR-SALE-10/11）：基于原销售单按原成交单价退款，
 * 事务内回补库存、按成本快照回冲、冲减会员消费额、更新原单退货状态；
 * 退款超阈值或"误操作"原因须店长授权且授权人 ≠ 发起人。
 */
@RestController
@RequestMapping("/api/v1/sales/returns")
@RequiredArgsConstructor
public class SalesReturnController {

    private final SalesReturnService salesReturnService;

    /** 办理退货（FR-SALE-10）：返回退货单详情（含明细与退款金额）；操作日志记退货单号（GAP-P2-03） */
    @PostMapping
    @RequirePerm("sys:ret:create")
    @OpLog(module = "销售管理", action = "办理销售退货", target = "#ret?.data.orderNo")
    public ApiResponse<ReturnOrderDetailVO> create(@Valid @RequestBody ReturnCreateRequest req) {
        return ApiResponse.ok(salesReturnService.createReturn(req, SecurityUtils.getLoginUser()));
    }

    /** 退货单分页（FR-SALE-10）：退货单号/原销售单号/会员/退款方式/原因/时间区间 */
    @GetMapping
    @RequirePerm("sys:ret:list")
    public ApiResponse<PageVO<ReturnOrderVO>> page(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String salesOrderNo,
            @RequestParam(required = false) String memberKeyword,
            @RequestParam(required = false) String refundType,
            @RequestParam(required = false) String returnReason,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        ReturnQuery query = new ReturnQuery(orderNo, salesOrderNo, memberKeyword, refundType,
                returnReason, beginDate, endDate, null, page, size);
        return ApiResponse.ok(salesReturnService.page(query, SecurityUtils.getLoginUser()));
    }

    /** 退货单详情（FR-SALE-10） */
    @GetMapping("/{id}")
    @RequirePerm("sys:ret:list")
    public ApiResponse<ReturnOrderDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(salesReturnService.detail(id));
    }
}

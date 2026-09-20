package com.smms.sale.controller;

import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.PageVO;
import com.smms.sale.dto.SalesOrderDetailVO;
import com.smms.sale.dto.SalesOrderQuery;
import com.smms.sale.dto.SalesOrderVO;
import com.smms.sale.service.SalesQueryService;
import com.smms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 销售单查询接口（FR-SALE-09）：分页 + 单号/收银员/收银台/会员/收款方式/
 * 日期区间/金额区间/退货状态/是否会员单筛选；详情含明细快照、收款与关联退货。
 * 收银员仅可查询本人经手的单据（BR-SALE-09）。
 */
@RestController
@RequestMapping("/api/v1/sales")
@RequiredArgsConstructor
public class SalesOrderController {

    private final SalesQueryService salesQueryService;

    /** 销售单分页（FR-SALE-09） */
    @GetMapping
    @RequirePerm("sys:so:list")
    public ApiResponse<PageVO<SalesOrderVO>> page(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String cashierName,
            @RequestParam(required = false) Integer counterNo,
            @RequestParam(required = false) String memberKeyword,
            @RequestParam(required = false) String payType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) BigDecimal amountMin,
            @RequestParam(required = false) BigDecimal amountMax,
            @RequestParam(required = false) String returnStatus,
            @RequestParam(required = false) Boolean memberOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        SalesOrderQuery query = new SalesOrderQuery(orderNo, cashierName, counterNo, null,
                memberKeyword, payType, beginDate, endDate, amountMin, amountMax,
                returnStatus, memberOnly, page, size);
        return ApiResponse.ok(salesQueryService.page(query, SecurityUtils.getLoginUser()));
    }

    /** 销售单详情（FR-SALE-09）：明细快照 + 收款信息 + 关联退货记录 */
    @GetMapping("/{id}")
    @RequirePerm("sys:so:list")
    public ApiResponse<SalesOrderDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(salesQueryService.detail(id, SecurityUtils.getLoginUser()));
    }
}

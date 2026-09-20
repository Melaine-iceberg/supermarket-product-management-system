package com.smms.sale.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.PageVO;
import com.smms.sale.dto.ShiftQuery;
import com.smms.sale.dto.ShiftVO;
import com.smms.sale.service.CashierShiftService;
import com.smms.security.SecurityUtils;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 收银班次接口（FR-SALE-12）：开班/交班登记与交班单查询；
 * 交班时统计销售单数、净销售额、各收款方式金额、退货额与应收现金；
 * 交班后该班次单据的退货须店长处理（BR-SALE-09）。
 */
@RestController
@RequestMapping("/api/v1/sales/shifts")
@RequiredArgsConstructor
public class CashierShiftController {

    private final CashierShiftService shiftService;

    /** 开班登记（FR-SALE-12）：同一收银员同一时刻仅一个在班班次 */
    @PostMapping("/open")
    @RequirePerm("sys:shift:open")
    @OpLog(module = "销售管理", action = "收银开班")
    public ApiResponse<ShiftVO> open(
            @RequestParam @NotNull @Min(1) @Max(99) Integer counterNo) {
        return ApiResponse.ok(shiftService.openShift(counterNo, SecurityUtils.getLoginUser()));
    }

    /** 交班对账（FR-SALE-12）：统计回写并返回交班单（含班次内销售单，供打印核对） */
    @PostMapping("/close")
    @RequirePerm("sys:shift:close")
    @OpLog(module = "销售管理", action = "收银交班")
    public ApiResponse<ShiftVO.Detail> close() {
        return ApiResponse.ok(shiftService.closeShift(SecurityUtils.getLoginUser()));
    }

    /** 当前在班班次（收银台展示；无在班班次返回 null） */
    @GetMapping("/current")
    @RequirePerm("sys:shift:list")
    public ApiResponse<ShiftVO> current() {
        return ApiResponse.ok(shiftService.current(SecurityUtils.getLoginUser()));
    }

    /** 班次分页（FR-SALE-12）：收银员仅可查询本人班次，店长/超管全局 */
    @GetMapping
    @RequirePerm("sys:shift:list")
    public ApiResponse<PageVO<ShiftVO>> page(
            @RequestParam(required = false) String cashierName,
            @RequestParam(required = false) Integer counterNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String beginDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        ShiftQuery query = new ShiftQuery(cashierName, counterNo, status,
                beginDate, endDate, null, page, size);
        return ApiResponse.ok(shiftService.page(query, SecurityUtils.getLoginUser()));
    }

    /** 班次详情（交班单视图，FR-RPT-09）：统计实时计算，含班次内销售单列表 */
    @GetMapping("/{id}")
    @RequirePerm("sys:shift:list")
    public ApiResponse<ShiftVO.Detail> detail(@PathVariable Long id) {
        return ApiResponse.ok(shiftService.detail(id));
    }
}

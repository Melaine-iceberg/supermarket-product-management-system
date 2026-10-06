package com.smms.sale.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.sale.dto.CashierGoodsVO;
import com.smms.sale.dto.CheckoutCommand;
import com.smms.sale.dto.CheckoutVO;
import com.smms.sale.dto.MemberSearchVO;
import com.smms.sale.service.SalesCheckoutService;
import com.smms.security.LoginUser;
import com.smms.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 收银结算接口（FR-SALE-06）：事务内完成销售单落库、库存条件扣减、
 * 出库流水、收款记录与会员消费统计；结算成功即"已完成"不可改删（BR-SALE-08）。
 * 附收银台会员检索（FR-SALE-05）。
 */
@RestController
@RequestMapping("/api/v1/sales")
@RequiredArgsConstructor
public class SalesCheckoutController {

    private final SalesCheckoutService checkoutService;

    /** 收银结算（FR-SALE-06）：返回单号与找零，供小票预览打印 */
    @PostMapping("/checkout")
    @RequirePerm("sys:cashier:enter")
    @OpLog(module = "销售管理", action = "收银结算")
    public ApiResponse<CheckoutVO> checkout(@Valid @RequestBody CheckoutCommand cmd) {
        LoginUser cashier = SecurityUtils.getLoginUser();
        return ApiResponse.ok(checkoutService.checkout(cmd, cashier));
    }

    /** 收银台会员检索（FR-SALE-05）：手机号/会员编号/姓名 */
    @GetMapping("/members")
    @RequirePerm("sys:cashier:enter")
    public ApiResponse<List<MemberSearchVO>> searchMembers(@RequestParam String keyword) {
        return ApiResponse.ok(checkoutService.searchMembers(keyword));
    }

    /** 收银台商品检索（FR-SALE-02）：条码/编码/PLU 精确 + 名称/拼音首字母模糊 */
    @GetMapping("/cashier/goods")
    @RequirePerm("sys:cashier:enter")
    public ApiResponse<List<CashierGoodsVO>> searchCashierGoods(@RequestParam String keyword) {
        return ApiResponse.ok(checkoutService.searchCashierGoods(keyword));
    }
}

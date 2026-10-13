package com.smms.sale.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.PageVO;
import com.smms.goods.dto.CategoryNode;
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
 * 附收银台商品检索（FR-SALE-02）与分类树浏览（GAP-P0-06，按 sys:cashier:enter 鉴权的只读查询）。
 */
@RestController
@RequestMapping("/api/v1/sales")
@RequiredArgsConstructor
public class SalesCheckoutController {

    private final SalesCheckoutService checkoutService;

    /** 收银结算（FR-SALE-06）：返回单号与找零，供小票预览打印；操作日志记单号（GAP-P2-03） */
    @PostMapping("/checkout")
    @RequirePerm("sys:cashier:enter")
    @OpLog(module = "销售管理", action = "收银结算", target = "#ret?.data.orderNo")
    public ApiResponse<CheckoutVO> checkout(@Valid @RequestBody CheckoutCommand cmd) {
        LoginUser cashier = SecurityUtils.getLoginUser();
        return ApiResponse.ok(checkoutService.checkout(cmd, cashier));
    }

    /** 收银台会员检索（FR-SALE-05）：手机号/会员编号/姓名；关键词为空返回空列表 */
    @GetMapping("/members")
    @RequirePerm("sys:cashier:enter")
    public ApiResponse<List<MemberSearchVO>> searchMembers(
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(checkoutService.searchMembers(keyword));
    }

    /** 收银台商品检索（FR-SALE-02）：条码/编码/PLU 精确 + 名称/拼音首字母模糊；关键词为空返回空列表 */
    @GetMapping("/cashier/goods")
    @RequirePerm("sys:cashier:enter")
    public ApiResponse<List<CashierGoodsVO>> searchCashierGoods(
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(checkoutService.searchCashierGoods(keyword));
    }

    /** 收银台分类树（GAP-P0-06，FR-SALE-02③）：仅启用分类，收银员无需 sys:cat:list */
    @GetMapping("/cashier/categories")
    @RequirePerm("sys:cashier:enter")
    public ApiResponse<List<CategoryNode>> cashierCategories() {
        return ApiResponse.ok(checkoutService.cashierCategories());
    }

    /** 收银台分类浏览商品（GAP-P0-06，FR-SALE-02③）：启用商品分页（分类含子分类），收银员无需 sys:goods:list */
    @GetMapping("/cashier/browse")
    @RequirePerm("sys:cashier:enter")
    public ApiResponse<PageVO<CashierGoodsVO>> browseCashierGoods(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(checkoutService.browseCashierGoods(categoryId, page, size));
    }
}

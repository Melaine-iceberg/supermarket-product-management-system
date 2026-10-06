package com.smms.sale.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.sale.dto.HoldCreateCommand;
import com.smms.sale.dto.HoldDetailVO;
import com.smms.sale.dto.HoldOrderVO;
import com.smms.sale.service.HoldOrderService;
import com.smms.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 收银挂单接口（FR-SALE-08）：挂单暂存明细快照、取单恢复、删除；
 * 挂单不扣库存、不计入报表（BR-RPT-03），仅挂单收银员本人可见；
 * 超 24 小时由定时任务自动清理（IR-SW-06），交班页据此提示处理未取挂单。
 */
@RestController
@RequestMapping("/api/v1/sales/holds")
@RequiredArgsConstructor
public class HoldOrderController {

    private final HoldOrderService holdService;

    /** 挂单（FR-SALE-08）：返回摘要（时间/件数/金额） */
    @PostMapping
    @RequirePerm("sys:cashier:enter")
    @OpLog(module = "销售管理", action = "挂单")
    public ApiResponse<HoldOrderVO> create(@Valid @RequestBody HoldCreateCommand cmd) {
        return ApiResponse.ok(holdService.create(cmd, SecurityUtils.getLoginUser()));
    }

    /** 本人未取挂单列表（取单对话框与角标） */
    @GetMapping
    @RequirePerm("sys:cashier:enter")
    public ApiResponse<List<HoldOrderVO>> list() {
        return ApiResponse.ok(holdService.list(SecurityUtils.getLoginUser()));
    }

    /** 取单（FR-SALE-08）：返回明细快照与会员/折扣信息供恢复购物车 */
    @PostMapping("/{id}/take")
    @RequirePerm("sys:cashier:enter")
    @OpLog(module = "销售管理", action = "取单")
    public ApiResponse<HoldDetailVO> take(@PathVariable Long id) {
        return ApiResponse.ok(holdService.take(id, SecurityUtils.getLoginUser()));
    }

    /** 删除挂单（FR-SALE-08） */
    @DeleteMapping("/{id}")
    @RequirePerm("sys:cashier:enter")
    @OpLog(module = "销售管理", action = "删除挂单")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        holdService.delete(id, SecurityUtils.getLoginUser());
        return ApiResponse.ok();
    }
}

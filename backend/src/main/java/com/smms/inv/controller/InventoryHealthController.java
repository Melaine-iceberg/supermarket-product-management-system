package com.smms.inv.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.inv.dto.InventoryHealthVO;
import com.smms.inv.service.InventoryHealthService;
import com.smms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存体检接口（FR-INV-11）：按流水重算理论结存并与结存字段比对（BR-INV-08），
 * 输出差异清单；一键按流水修正结存（仅超级管理员，操作留痕）。
 * 白盒校验能力，不提供任何直改结存的常规入口（BR-INV-11）。
 */
@RestController
@RequestMapping("/api/v1/inventory/health")
@RequiredArgsConstructor
public class InventoryHealthController {

    private final InventoryHealthService inventoryHealthService;

    /** 库存体检（FR-INV-11）：差异清单分页 + 参与校验商品数（AC-04 通过标准：差异清单为空） */
    @GetMapping
    @RequirePerm("sys:inv:health")
    public ApiResponse<InventoryHealthVO> check(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(inventoryHealthService.check(page, size));
    }

    /** 一键按流水修正结存（FR-INV-11）：仅超级管理员；修正过程与操作人由操作日志留痕（FR-SYS-08） */
    @PostMapping("/fix")
    @RequirePerm("sys:inv:health:fix")
    @OpLog(module = "库存管理", action = "库存体检修正", target = "全部差异商品")
    public ApiResponse<Integer> fix() {
        if (!SecurityUtils.isSuperAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "库存修正仅超级管理员可执行（FR-INV-11）");
        }
        return ApiResponse.ok(inventoryHealthService.fix());
    }
}

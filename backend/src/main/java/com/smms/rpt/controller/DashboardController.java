package com.smms.rpt.controller;

import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.rpt.dto.DashboardVO;
import com.smms.rpt.service.DashboardService;
import com.smms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 经营概览看板（FR-RPT-01）：今日 KPI、待办事项（按角色权限过滤）、
 * 近 7/30 日销售趋势、TOP10 热销商品。
 */
@RestController
@RequestMapping("/api/v1/report/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /** 看板汇总（FR-RPT-01）：trendDays 支持 7/30 */
    @GetMapping
    @RequirePerm("sys:rpt:dashboard")
    public ApiResponse<DashboardVO> summary(@RequestParam(defaultValue = "7") int trendDays) {
        return ApiResponse.ok(dashboardService.summary(trendDays, SecurityUtils.getLoginUser()));
    }
}

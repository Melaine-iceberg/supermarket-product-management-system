package com.smms.rpt.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 经营概览看板（FR-RPT-01）：今日 KPI、角色过滤后的待办事项、
 * 近 7/30 日销售趋势、TOP10 热销商品。
 */
public record DashboardVO(
        KpiVO kpi,
        List<TodoVO> todos,
        List<TrendPointVO> trend,
        List<TopGoodsVO> topGoods) {

    /** 今日 KPI：销售额/订单数/毛利额与毛利率/新增会员数/库存预警数 */
    public record KpiVO(
            BigDecimal salesAmount,
            long orderCount,
            BigDecimal profitAmount,
            BigDecimal profitRate,
            long newMemberCount,
            long warningCount) {
    }

    /** 待办事项：各角色待办按其权限过滤（FR-RPT-01） */
    public record TodoVO(
            /** 待办类型：PENDING_PURCHASE/PENDING_CHECK/STOCKOUT/OVERDUE_PURCHASE */
            String type,
            String title,
            long count,
            /** 跳转页面路由 */
            String route) {
    }

    /** 趋势点：营业日 + 净销售额 */
    public record TrendPointVO(
            LocalDate bizDate,
            long orderCount,
            BigDecimal netSalesAmount) {
    }

    /** TOP 热销商品 */
    public record TopGoodsVO(
            Long goodsId,
            String goodsName,
            BigDecimal qty,
            BigDecimal salesAmount) {
    }
}

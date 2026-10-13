package com.smms.rpt.service;

import com.smms.inv.dto.AlertSummaryVO;
import com.smms.inv.service.InventoryService;
import com.smms.rpt.dto.DashboardVO;
import com.smms.rpt.dto.PeriodAggRow;
import com.smms.rpt.dto.ReportRange;
import com.smms.rpt.dto.RptRoles;
import com.smms.rpt.dto.SalesGroupRow;
import com.smms.rpt.dto.SalesMetrics;
import com.smms.rpt.mapper.ReportMapper;
import com.smms.security.LoginUser;
import com.smms.sys.service.BusinessDayService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 经营概览看板（FR-RPT-01）：今日 KPI、角色过滤后的待办事项、
 * 近 7/30 日销售趋势、TOP10 热销商品。
 * 口径与销售报表一致（BR-RPT-01），营业日窗口取参数 business_day_start（BR-RPT-02）。
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ReportMapper reportMapper;
    private final BusinessDayService businessDayService;
    private final InventoryService inventoryService;

    /** 看板汇总：今日 KPI + 待办 + 近 N 日趋势 + TOP10 热销（trendDays 支持 7/30） */
    public DashboardVO summary(int trendDays, LoginUser user) {
        int days = trendDays == 30 ? 30 : 7;
        // GAP-P1-05：看板"今日"取当前营业日（business_day_start 起算），与销售报表 business_date 口径一致
        LocalDate today = businessDayService.today();
        boolean canProfit = RptRoles.canViewProfit(user);
        // GAP-P2-11：预警/缺货角标与预警页同源（InventoryService.alertSummary），口径逐项一致
        AlertSummaryVO alertSummary = inventoryService.alertSummary();

        DashboardVO.KpiVO kpi = kpi(today, canProfit, alertSummary);
        List<DashboardVO.TodoVO> todos = todos(user, alertSummary);
        List<DashboardVO.TrendPointVO> trend = trend(today, days);
        List<DashboardVO.TopGoodsVO> topGoods =
                reportMapper.selectTopGoods(today.minusDays((long) days - 1), today, 10);
        return new DashboardVO(kpi, todos, trend, topGoods);
    }

    /** 今日 KPI：销售额、订单数、毛利额与毛利率、新增会员数、库存预警数 */
    private DashboardVO.KpiVO kpi(LocalDate today, boolean canProfit, AlertSummaryVO alertSummary) {
        PeriodAggRow sales = reportMapper.selectSalesAgg(today, today);
        PeriodAggRow returns = reportMapper.selectReturnAgg(today, today);
        SalesMetrics m = new SalesMetrics(sales.orderCount(),
                sales.salesAmount(), sales.discountAmount(), sales.netSalesAmount(),
                returns.refundAmount(), returns.returnCostAmount(), sales.costAmount());
        BigDecimal profitAmount = canProfit ? m.profitAmount() : null;
        BigDecimal profitRate = canProfit ? m.profitRate() : null;
        return new DashboardVO.KpiVO(m.salesAmount(), m.orderCount(), profitAmount, profitRate,
                reportMapper.countNewMembers(businessDayService.dayBegin(today), businessDayService.dayEnd(today)),
                alertSummary.totalCount());
    }

    /** 待办事项：各角色按其权限过滤（FR-RPT-01） */
    private List<DashboardVO.TodoVO> todos(LoginUser user, AlertSummaryVO alertSummary) {
        List<DashboardVO.TodoVO> todos = new ArrayList<>();
        if (hasPerm(user, "sys:pur:list")) {
            todos.add(new DashboardVO.TodoVO("PENDING_PURCHASE", "待审核采购单",
                    reportMapper.countPendingPurchase(), "/purchase/order"));
            todos.add(new DashboardVO.TodoVO("OVERDUE_PURCHASE", "超期未到货采购单",
                    reportMapper.countOverduePurchase(), "/purchase/order"));
        }
        if (hasPerm(user, "sys:check:list")) {
            todos.add(new DashboardVO.TodoVO("PENDING_CHECK", "待审核盘点单",
                    reportMapper.countPendingCheck(), "/inventory/check"));
        }
        if (hasPerm(user, "sys:inv-warning:list")) {
            todos.add(new DashboardVO.TodoVO("STOCKOUT", "缺货商品",
                    alertSummary.outCount(), "/inventory/warning"));
        }
        return todos;
    }

    /** 近 N 日销售趋势：净销售折线（缺失日期补零） */
    private List<DashboardVO.TrendPointVO> trend(LocalDate today, int days) {
        LocalDate begin = today.minusDays((long) days - 1);
        Map<String, Cell> merged = new TreeMap<>();
        for (SalesGroupRow s : reportMapper.selectDailySales(begin, today, false)) {
            merged.computeIfAbsent(s.groupKey(), k -> new Cell()).sales = s;
        }
        for (SalesGroupRow r : reportMapper.selectDailyReturns(begin, today, false)) {
            merged.computeIfAbsent(r.groupKey(), k -> new Cell()).returns = r;
        }
        List<DashboardVO.TrendPointVO> points = new ArrayList<>(days);
        LocalDate d = begin;
        while (!d.isAfter(today)) {
            Cell c = merged.get(d.format(DAY_FORMAT));
            BigDecimal net = BigDecimal.ZERO;
            long orderCount = 0;
            if (c != null) {
                BigDecimal netSales = c.sales == null || c.sales.netSalesAmount() == null
                        ? BigDecimal.ZERO : c.sales.netSalesAmount();
                BigDecimal refund = c.returns == null || c.returns.refundAmount() == null
                        ? BigDecimal.ZERO : c.returns.refundAmount();
                net = netSales.subtract(refund);
                orderCount = c.sales == null ? 0 : c.sales.orderCount();
            }
            points.add(new DashboardVO.TrendPointVO(d, orderCount, net));
            d = d.plusDays(1);
        }
        return points;
    }

    private boolean hasPerm(LoginUser user, String perm) {
        return user.permCodes() != null && user.permCodes().contains(perm);
    }

    private static final class Cell {
        SalesGroupRow sales;
        SalesGroupRow returns;
    }
}

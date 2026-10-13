package com.smms.rpt.service;

import com.smms.inv.dto.AlertSummaryVO;
import com.smms.inv.service.InventoryService;
import com.smms.rpt.dto.DashboardVO;
import com.smms.rpt.dto.PeriodAggRow;
import com.smms.rpt.mapper.ReportMapper;
import com.smms.security.LoginUser;
import com.smms.sys.service.BusinessDayService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 经营看板用例（FR-RPT-01 / GAP-P2-11）：
 * 预警角标与预警页同源——KPI 预警数 = 预警汇总总数（含缺货/积压），
 * 缺货待办 = 预警汇总缺货数（含无结存行商品），不再复算易漂移的独立 SQL。
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock private ReportMapper reportMapper;
    @Mock private BusinessDayService businessDayService;
    @Mock private InventoryService inventoryService;

    @InjectMocks private DashboardService service;

    private static PeriodAggRow sales() {
        return new PeriodAggRow(3, 5L, new BigDecimal("100.00"), BigDecimal.ZERO,
                new BigDecimal("100.00"), new BigDecimal("60.00"), BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static PeriodAggRow returns() {
        return new PeriodAggRow(0, 0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("KPI 预警数 = 预警汇总总数；缺货待办 = 预警汇总缺货数（含无结存行与积压，GAP-P2-11）")
    void summary_usesAlertSummaryForWarningAndStockout() {
        LocalDate today = LocalDate.of(2026, 10, 13);
        when(businessDayService.today()).thenReturn(today);
        when(businessDayService.dayBegin(today)).thenReturn(today.atStartOfDay());
        when(businessDayService.dayEnd(today)).thenReturn(today.plusDays(1).atStartOfDay());
        when(inventoryService.alertSummary()).thenReturn(new AlertSummaryVO(2, 3, 1));
        when(reportMapper.selectSalesAgg(today, today)).thenReturn(sales());
        when(reportMapper.selectReturnAgg(today, today)).thenReturn(returns());
        when(reportMapper.countNewMembers(any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(5L);
        when(reportMapper.selectDailySales(any(), any(), anyBoolean())).thenReturn(List.of());
        when(reportMapper.selectDailyReturns(any(), any(), anyBoolean())).thenReturn(List.of());
        when(reportMapper.selectTopGoods(any(), any(), anyInt())).thenReturn(List.of());

        LoginUser cashierLike = new LoginUser(5L, "cashier", "陈收银",
                List.of("CASHIER"), List.of("收银员"), Set.of("sys:inv-warning:list"), "fp", false);

        DashboardVO vo = service.summary(7, cashierLike);

        // 预警数 = 2 + 3 + 1 = 6（含缺货与积压）；缺货待办 = 2
        assertThat(vo.kpi().warningCount()).isEqualTo(6);
        assertThat(vo.kpi().newMemberCount()).isEqualTo(5);
        assertThat(vo.todos()).hasSize(1);
        assertThat(vo.todos().get(0).type()).isEqualTo("STOCKOUT");
        assertThat(vo.todos().get(0).count()).isEqualTo(2);
        // 收银员无成本权限：毛利置空（BR-RPT-07）
        assertThat(vo.kpi().profitAmount()).isNull();
    }

    @Test
    @DisplayName("店长可见毛利，且待办按权限追加采购/盘点项")
    void summary_managerSeesProfitAndMoreTodos() {
        LocalDate today = LocalDate.of(2026, 10, 13);
        when(businessDayService.today()).thenReturn(today);
        when(businessDayService.dayBegin(today)).thenReturn(today.atStartOfDay());
        when(businessDayService.dayEnd(today)).thenReturn(today.plusDays(1).atStartOfDay());
        when(inventoryService.alertSummary()).thenReturn(new AlertSummaryVO(0, 0, 0));
        when(reportMapper.selectSalesAgg(today, today)).thenReturn(sales());
        when(reportMapper.selectReturnAgg(today, today)).thenReturn(returns());
        when(reportMapper.countNewMembers(any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(0L);
        when(reportMapper.selectDailySales(any(), any(), anyBoolean())).thenReturn(List.of());
        when(reportMapper.selectDailyReturns(any(), any(), anyBoolean())).thenReturn(List.of());
        when(reportMapper.selectTopGoods(any(), any(), anyInt())).thenReturn(List.of());
        when(reportMapper.countPendingPurchase()).thenReturn(2L);
        when(reportMapper.countOverduePurchase()).thenReturn(1L);
        when(reportMapper.countPendingCheck()).thenReturn(0L);

        LoginUser manager = new LoginUser(2L, "manager", "王店长",
                List.of("MANAGER"), List.of("店长"),
                Set.of("sys:pur:list", "sys:check:list", "sys:inv-warning:list"), "fp", false);

        DashboardVO vo = service.summary(30, manager);

        assertThat(vo.kpi().profitAmount()).isEqualByComparingTo("40.00");
        assertThat(vo.todos()).extracting(DashboardVO.TodoVO::type)
                .containsExactly("PENDING_PURCHASE", "OVERDUE_PURCHASE", "PENDING_CHECK", "STOCKOUT");
    }
}

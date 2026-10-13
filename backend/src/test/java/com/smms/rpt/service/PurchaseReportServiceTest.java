package com.smms.rpt.service;

import com.smms.rpt.dto.PriceTrendRow;
import com.smms.rpt.dto.PurchSummaryRow;
import com.smms.rpt.dto.ReportRange;
import com.smms.rpt.mapper.PurchaseReportMapper;
import com.smms.security.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采购报表服务单测（GAP-P0-10③/GAP-P1-06）：
 * 无毛利权限角色不返回采购金额/退货金额/结算金额/采购单价/占比（BR-RPT-07）；
 * 导出取数按 limit（export_max_rows+1）由调用方校验，交互查询不限行。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PurchaseReportServiceTest {

    private static final LoginUser MANAGER = new LoginUser(2L, "manager", "王店长",
            List.of("MANAGER"), List.of("店长"), Set.of("sys:rpt:purchase"), null, false);
    private static final LoginUser PURCHASER = new LoginUser(3L, "buyer", "李采购",
            List.of("PURCHASER"), List.of("采购员"), Set.of("sys:rpt:purchase"), null, false);

    private static final ReportRange RANGE =
            new ReportRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

    @Mock
    private PurchaseReportMapper mapper;

    private PurchaseReportService service;

    @BeforeEach
    void setUp() {
        service = new PurchaseReportService(mapper);
    }

    private static PurchSummaryRow summaryRow() {
        return new PurchSummaryRow(1L, "供应商A", null, null, null, null,
                2L, new BigDecimal("100"), new BigDecimal("1500.00"), new BigDecimal("80"),
                new BigDecimal("20"), new BigDecimal("5"), new BigDecimal("75.00"),
                new BigDecimal("1000.00"), new BigDecimal("300.00"), null);
    }

    @Test
    @DisplayName("采购汇总：无毛利权限时金额/退货/结算字段不返回，数量与名称保留")
    void summaryMasksAmountsForNonPrivileged() {
        when(mapper.selectGoodsSummary(any(), any(), any())).thenReturn(List.of(summaryRow()));

        PurchSummaryRow masked = service.summary(RANGE, "goods", PURCHASER).get(0);
        assertThat(masked.purchaseAmount()).isNull();
        assertThat(masked.returnAmount()).isNull();
        assertThat(masked.settledAmount()).isNull();
        assertThat(masked.partialSettledAmount()).isNull();
        assertThat(masked.purchaseQty()).isEqualByComparingTo("100");
        assertThat(masked.inTransitQty()).isEqualByComparingTo("20");

        PurchSummaryRow full = service.summary(RANGE, "goods", MANAGER).get(0);
        assertThat(full.purchaseAmount()).isEqualByComparingTo("1500.00");
        assertThat(full.returnAmount()).isEqualByComparingTo("75.00");
        assertThat(full.settledAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("供应商排行：占比按采购金额计算后再按角色置空（无权限连占比也不返回）")
    void supplierRankingComputesShareThenMasks() {
        PurchSummaryRow a = new PurchSummaryRow(1L, "供应商A", null, null, null, null, 1L,
                new BigDecimal("10"), new BigDecimal("300.00"), new BigDecimal("10"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
        PurchSummaryRow b = new PurchSummaryRow(2L, "供应商B", null, null, null, null, 1L,
                new BigDecimal("10"), new BigDecimal("100.00"), new BigDecimal("10"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
        when(mapper.selectSupplierSummary(any(), any(), any())).thenReturn(List.of(a, b));

        List<PurchSummaryRow> ranked = service.supplierRanking(RANGE, PURCHASER);
        assertThat(ranked.get(0).shareRate()).isNull();
        assertThat(ranked.get(0).purchaseAmount()).isNull();

        List<PurchSummaryRow> full = service.supplierRanking(RANGE, MANAGER);
        assertThat(full.get(0).shareRate()).isEqualByComparingTo("75.00");
        assertThat(full.get(1).shareRate()).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("采购价格波动：无毛利权限时采购单价不返回，单号/数量保留")
    void priceTrendMasksPrice() {
        when(mapper.selectPriceTrend(eq(1L), any(), any(), any())).thenReturn(List.of(
                new PriceTrendRow(10L, "PO202603010001", 1L, "供应商A",
                        LocalDateTime.of(2026, 3, 1, 10, 0), new BigDecimal("20"), new BigDecimal("12.50"))));

        PriceTrendRow masked = service.priceTrend(1L, RANGE, PURCHASER).get(0);
        assertThat(masked.price()).isNull();
        assertThat(masked.quantity()).isEqualByComparingTo("20");

        PriceTrendRow full = service.priceTrend(1L, RANGE, MANAGER).get(0);
        assertThat(full.price()).isEqualByComparingTo("12.50");
    }

    @Test
    @DisplayName("未选择商品：价格波动返回空序列，不进入 SQL（既有防 500 行为保持）")
    void priceTrendWithoutGoodsReturnsEmpty() {
        assertThat(service.priceTrend(null, RANGE, MANAGER)).isEmpty();
        verify(mapper, never()).selectPriceTrend(any(), any(), any(), any());
    }

    @Test
    @DisplayName("导出取数按 limit 传给 SQL；交互查询 limit 为空（GAP-P1-06）")
    void exportForwardsLimitWhileInteractiveDoesNot() {
        when(mapper.selectGoodsSummary(any(), any(), eq(5))).thenReturn(List.of(summaryRow()));
        assertThat(service.summaryForExport(RANGE, "goods", MANAGER, 5)).hasSize(1);
        verify(mapper).selectGoodsSummary(any(), any(), eq(5));

        when(mapper.selectGoodsSummary(any(), any(), isNull())).thenReturn(List.of(summaryRow()));
        assertThat(service.summary(RANGE, "goods", MANAGER)).hasSize(1);
        verify(mapper).selectGoodsSummary(any(), any(), isNull());
    }
}

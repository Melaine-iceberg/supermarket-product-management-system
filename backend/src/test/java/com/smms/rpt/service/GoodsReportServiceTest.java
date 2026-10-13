package com.smms.rpt.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.PageVO;
import com.smms.rpt.dto.GoodsRankRow;
import com.smms.rpt.dto.ReportRange;
import com.smms.rpt.mapper.ReportMapper;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 商品分析服务单测（GAP-P0-10①②）：
 * ①毛利额/毛利率按 BR-RPT-01 扣减退货（与销售日报同口径）；
 * ②无毛利权限角色的占用资金（=结存×成本单价，可反推成本）必须一并置空。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoodsReportServiceTest {

    private static final LoginUser MANAGER = new LoginUser(2L, "manager", "王店长",
            List.of("MANAGER"), List.of("店长"), Set.of("sys:rpt:goods"), null, false);
    private static final LoginUser CASHIER = new LoginUser(5L, "cashier", "陈收银",
            List.of("CASHIER"), List.of("收银员"), Set.of("sys:rpt:goods"), null, false);

    private static final ReportRange RANGE =
            new ReportRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

    @Mock
    private ReportMapper reportMapper;

    private GoodsReportService service;

    @BeforeEach
    void setUp() {
        service = new GoodsReportService(reportMapper);
    }

    private static GoodsRankRow row(BigDecimal refund, BigDecimal returnCost, BigDecimal profit) {
        return new GoodsRankRow(1L, "G001", "测试商品", 9L, "分类", null, null,
                3L, new BigDecimal("12"), new BigDecimal("240.00"), new BigDecimal("20.00"),
                new BigDecimal("220.00"), refund, new BigDecimal("144.00"), returnCost,
                profit, null, null, new BigDecimal("8"), new BigDecimal("18.00"),
                new BigDecimal("144.00"), new BigDecimal("25.00"));
    }

    private IPage<GoodsRankRow> pageOf(GoodsRankRow row) {
        Page<GoodsRankRow> page = new Page<>(1, 20, false);
        page.setRecords(List.of(row));
        return page;
    }

    @Test
    @DisplayName("无毛利权限：成本/毛利字段与占用资金（可反推成本）全部置空（BR-RPT-07/GAP-P0-10②）")
    void nonPrivilegedCannotInferCostFromOccupiedAmount() {
        when(reportMapper.selectGoodsRanking(any(), any(), any(), any(), any()))
                .thenReturn(pageOf(row(new BigDecimal("30.00"), new BigDecimal("10.00"), new BigDecimal("76.00"))));
        when(reportMapper.countSlowMoving(any(), any(), any(), any())).thenReturn(1L);
        when(reportMapper.selectSlowMoving(any(), any(), any(), any(), any()))
                .thenReturn(pageOf(row(null, null, null)));

        PageVO<GoodsRankRow> masked = service.ranking(RANGE, "salesAmount", null, 1, 20, CASHIER);
        GoodsRankRow r = masked.list().get(0);
        assertThat(r.costAmount()).isNull();
        assertThat(r.returnCostAmount()).isNull();
        assertThat(r.profitAmount()).isNull();
        assertThat(r.profitRate()).isNull();
        assertThat(r.costPrice()).isNull();
        assertThat(r.occupiedAmount()).as("占用资金 = 结存×成本单价，不置空可反推成本").isNull();
        assertThat(r.shareRate()).isNull();
        // 非成本字段保留（前端继续可读）
        assertThat(r.salesAmount()).isEqualByComparingTo("240.00");
        assertThat(r.refundAmount()).isEqualByComparingTo("30.00");
        assertThat(r.stockQty()).isEqualByComparingTo("8");
        assertThat(r.retailPrice()).isEqualByComparingTo("25.00");

        PageVO<GoodsRankRow> slow = service.slowMoving(RANGE, new BigDecimal("5"), null, 1, 20, CASHIER);
        assertThat(slow.list().get(0).costPrice()).isNull();
        assertThat(slow.list().get(0).occupiedAmount()).isNull();
    }

    @Test
    @DisplayName("店长及以上：成本/毛利/占用资金完整可见")
    void privilegedSeesCostAndProfitFields() {
        when(reportMapper.selectGoodsRanking(any(), any(), any(), any(), any()))
                .thenReturn(pageOf(row(new BigDecimal("30.00"), new BigDecimal("10.00"), new BigDecimal("76.00"))));

        GoodsRankRow r = service.ranking(RANGE, "salesAmount", null, 1, 20, MANAGER).list().get(0);
        assertThat(r.costAmount()).isEqualByComparingTo("144.00");
        assertThat(r.returnCostAmount()).isEqualByComparingTo("10.00");
        assertThat(r.profitAmount()).isEqualByComparingTo("76.00");
        assertThat(r.costPrice()).isEqualByComparingTo("18.00");
        assertThat(r.occupiedAmount()).isEqualByComparingTo("144.00");
    }

    @Test
    @DisplayName("毛利率分母为净销售（净销售额−退货额），与日报 BR-RPT-01 一致（GAP-P0-10①）")
    void profitRateUsesNetSalesAfterReturns() {
        // 净销售额 220 − 退货额 20 = 净销售 200；成本 144 − 退货成本回冲 24 = 120 → 毛利额 80（毛利率 40.00%）
        GoodsRankRow row = new GoodsRankRow(1L, "G001", "测试商品", 9L, "分类", null, null,
                3L, new BigDecimal("12"), new BigDecimal("240.00"), new BigDecimal("20.00"),
                new BigDecimal("220.00"), new BigDecimal("20.00"), new BigDecimal("144.00"),
                new BigDecimal("24.00"), new BigDecimal("80.00"), null, null,
                null, null, null, null);
        when(reportMapper.selectGoodsRanking(any(), any(), any(), any(), any())).thenReturn(pageOf(row));

        GoodsRankRow r = service.ranking(RANGE, "salesAmount", null, 1, 20, MANAGER).list().get(0);
        assertThat(r.calcProfitAmount()).isEqualByComparingTo("80.00");
        assertThat(r.profitRate()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("净销售为 0：毛利率显示空（不做除零，BR-RPT-01）")
    void profitRateNullWhenNetSalesZero() {
        GoodsRankRow row = new GoodsRankRow(1L, "G001", "退货冲平商品", null, null, null, null,
                1L, new BigDecimal("1"), new BigDecimal("10.00"), BigDecimal.ZERO,
                new BigDecimal("10.00"), new BigDecimal("10.00"), new BigDecimal("6.00"),
                new BigDecimal("6.00"), new BigDecimal("4.00"), null, null,
                null, null, null, null);
        when(reportMapper.selectGoodsRanking(any(), any(), any(), any(), any())).thenReturn(pageOf(row));

        GoodsRankRow r = service.ranking(RANGE, "salesAmount", null, 1, 20, MANAGER).list().get(0);
        assertThat(r.profitRate()).isNull();
    }
}

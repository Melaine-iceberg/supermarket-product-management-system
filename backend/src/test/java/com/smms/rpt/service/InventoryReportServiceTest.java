package com.smms.rpt.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.PageVO;
import com.smms.rpt.dto.InvChangeRow;
import com.smms.rpt.dto.InvSummaryRow;
import com.smms.rpt.dto.LossRow;
import com.smms.rpt.dto.ReportRange;
import com.smms.rpt.dto.TurnoverRow;
import com.smms.rpt.mapper.InventoryReportMapper;
import com.smms.security.LoginUser;
import com.smms.sys.service.SysConfigService;
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
import static org.mockito.Mockito.when;

/**
 * 库存报表服务单测（GAP-P0-10③/GAP-P1-06）：
 * 无毛利权限角色的成本单价/成本金额/变动金额/周转成本/损耗金额全部置空（BR-RPT-07）；
 * 分类周转去掉 5000 行静默截断，导出按调用方 limit 取数并由调用方校验。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryReportServiceTest {

    private static final LoginUser MANAGER = new LoginUser(2L, "manager", "王店长",
            List.of("MANAGER"), List.of("店长"), Set.of("sys:rpt:inventory"), null, false);
    private static final LoginUser KEEPER = new LoginUser(4L, "keeper", "赵库管",
            List.of("KEEPER"), List.of("库管员"), Set.of("sys:rpt:inventory"), null, false);

    private static final ReportRange RANGE =
            new ReportRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

    @Mock
    private InventoryReportMapper mapper;

    @Mock
    private SysConfigService configService;

    private InventoryReportService service;

    @BeforeEach
    void setUp() {
        service = new InventoryReportService(mapper, configService);
        when(mapper.selectFlowAmountByGoods(any(), any())).thenReturn(List.of());
        when(mapper.selectSaleCostByGoods(any(), any())).thenReturn(List.of());
        when(mapper.selectReturnCostByGoods(any(), any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("库存汇总/变动：无毛利权限时成本单价、成本金额、变动金额不返回（BR-RPT-07）")
    void summaryAndChangesMaskCostFields() {
        InvSummaryRow summary = new InvSummaryRow(9L, "饮料", 1L, "G001", "测试商品", 0L,
                new BigDecimal("8"), new BigDecimal("18.00"), new BigDecimal("144.00"),
                new BigDecimal("200.00"), "NORMAL");
        when(mapper.selectCategorySummary()).thenReturn(List.of(summary));
        when(mapper.selectChangeSummary(any(), any())).thenReturn(List.of(
                new InvChangeRow("SALE_OUT", 5L, new BigDecimal("-20"), new BigDecimal("360.00"))));

        InvSummaryRow masked = service.summaryByCategory(KEEPER).get(0);
        assertThat(masked.costPrice()).isNull();
        assertThat(masked.costAmount()).isNull();
        assertThat(masked.retailValue()).isEqualByComparingTo("200.00");
        assertThat(service.changes(RANGE, KEEPER).get(0).amount()).isNull();
        assertThat(service.changes(RANGE, KEEPER).get(0).changeQty()).isEqualByComparingTo("-20");

        InvSummaryRow full = service.summaryByCategory(MANAGER).get(0);
        assertThat(full.costPrice()).isEqualByComparingTo("18.00");
        assertThat(full.costAmount()).isEqualByComparingTo("144.00");
        assertThat(service.changes(RANGE, MANAGER).get(0).amount()).isEqualByComparingTo("360.00");
    }

    @Test
    @DisplayName("商品明细汇总：无毛利权限时成本单价/成本金额不返回，数量与零售价值保留")
    void summaryByGoodsMasksPerRole() {
        Page<InvSummaryRow> page = new Page<>(1, 20, false);
        page.setRecords(List.of(new InvSummaryRow(null, "饮料", 1L, "G001", "测试商品", 0L,
                new BigDecimal("8"), new BigDecimal("18.00"), new BigDecimal("144.00"),
                new BigDecimal("200.00"), "NORMAL")));
        when(mapper.countGoodsSummary(any())).thenReturn(1L);
        when(mapper.selectGoodsSummary(any(), any())).thenAnswer(inv -> {
            Page<InvSummaryRow> fresh = new Page<>(1, 20, false);
            fresh.setRecords(page.getRecords());
            return fresh;
        });

        PageVO<InvSummaryRow> masked = service.summaryByGoods(null, 1, 20, KEEPER);
        assertThat(masked.list().get(0).costPrice()).isNull();
        assertThat(masked.list().get(0).costAmount()).isNull();
        assertThat(masked.list().get(0).stockQty()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("库存周转：无毛利权限时四个成本金额置空，周转率/天数保留（非成本指标）")
    void turnoverMasksCostAmountsButKeepsRate() {
        TurnoverRow base = new TurnoverRow(1L, "G001", "测试商品", 9L, "饮料",
                new BigDecimal("8"), new BigDecimal("144.00"), new BigDecimal("100.00"),
                new BigDecimal("122.00"), new BigDecimal("72.00"), new BigDecimal("6.00"),
                new BigDecimal("20.00"), null, null);
        when(mapper.countGoodsSummary(any())).thenReturn(1L);
        // 区间流水金额由独立查询提供（期初 = 期末成本 − Σ区间流水金额）
        when(mapper.selectFlowAmountByGoods(any(), any())).thenReturn(List.of(
                new TurnoverRow(1L, null, null, null, null, null,
                        null, null, null, null, null, new BigDecimal("20.00"), null, null)));
        when(mapper.selectTurnoverPage(any(), any())).thenAnswer(inv -> {
            Page<TurnoverRow> fresh = new Page<>(1, 20, false);
            fresh.setRecords(List.of(base));
            return fresh;
        });

        TurnoverRow masked = service.turnoverByGoods(RANGE, null, 1, 20, KEEPER).list().get(0);
        assertThat(masked.costAmount()).isNull();
        assertThat(masked.beginAmount()).isNull();
        assertThat(masked.avgAmount()).isNull();
        assertThat(masked.saleCostAmount()).isNull();
        assertThat(masked.returnCostAmount()).isNull();
        assertThat(masked.flowAmount()).isNull();
        assertThat(masked.turnoverRate()).isNotNull();
        assertThat(masked.stockQty()).isEqualByComparingTo("8");

        TurnoverRow full = service.turnoverByGoods(RANGE, null, 1, 20, MANAGER).list().get(0);
        assertThat(full.costAmount()).isEqualByComparingTo("144.00");
        assertThat(full.flowAmount()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("分类周转：按 limit 取数并内存聚合，无 5000 行静默截断（GAP-P1-06）")
    void turnoverByCategoryAggregatesWithLimit() {
        when(mapper.selectTurnoverPage(any(), any())).thenAnswer(inv -> turnoverPage());
        when(mapper.selectSaleCostByGoods(any(), any())).thenReturn(List.of(
                partial(1L, new BigDecimal("50.00"), null),
                partial(2L, new BigDecimal("42.00"), null)));
        when(mapper.selectReturnCostByGoods(any(), any())).thenReturn(List.of(
                partial(1L, null, new BigDecimal("4.00")),
                partial(2L, null, new BigDecimal("2.00"))));

        List<TurnoverRow> rows = service.turnoverByCategory(RANGE, null, 1000, MANAGER);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).costAmount()).isEqualByComparingTo("154.00");
        assertThat(rows.get(0).saleCostAmount()).isEqualByComparingTo("92.00");
        assertThat(rows.get(0).returnCostAmount()).isEqualByComparingTo("6.00");
    }

    /** 仅填充销售成本或退货成本的映射行（其余字段由区间流水/期末库存补齐） */
    private TurnoverRow partial(Long goodsId, BigDecimal saleCost, BigDecimal returnCost) {
        return new TurnoverRow(goodsId, null, null, null, null, null,
                null, null, null, saleCost, returnCost, null, null, null);
    }

    @Test
    @DisplayName("损耗报表：无毛利权限时报损/盘亏金额与合计、损耗率不返回，数量保留")
    void lossMasksAmountsForNonPrivileged() {
        when(mapper.selectLossByGoods(any(), any())).thenReturn(List.of(
                new LossRow(1L, "测试商品", "饮料", "LOSS", new BigDecimal("2"), new BigDecimal("36.00"))));
        when(mapper.selectShortageByGoods(any(), any())).thenReturn(List.of(
                new LossRow(1L, "测试商品", "饮料", "CHECK", new BigDecimal("1"), new BigDecimal("18.00"))));
        when(mapper.selectBeginInventoryAmount(any(), any())).thenReturn(new BigDecimal("1000.00"));

        InventoryReportService.LossReportVO masked = service.loss(RANGE, KEEPER);
        assertThat(masked.totalLossAmount()).isNull();
        assertThat(masked.beginInventoryAmount()).isNull();
        assertThat(masked.lossRate()).isNull();
        assertThat(masked.rows().get(0).lossQty()).isEqualByComparingTo("2");
        assertThat(masked.rows().get(0).lossAmount()).isNull();
        assertThat(masked.rows().get(0).checkAmount()).isNull();
        assertThat(masked.rows().get(0).totalAmount()).isNull();

        InventoryReportService.LossReportVO full = service.loss(RANGE, MANAGER);
        assertThat(full.totalLossAmount()).isEqualByComparingTo("54.00");
        assertThat(full.lossRate()).isEqualByComparingTo("5.40");
        assertThat(full.rows().get(0).lossAmount()).isEqualByComparingTo("36.00");
    }

    private IPage<TurnoverRow> turnoverPage() {
        Page<TurnoverRow> page = new Page<>(1, 1000, false);
        page.setRecords(List.of(
                new TurnoverRow(1L, "G001", "商品A", 9L, "饮料", new BigDecimal("1"),
                        new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                        new BigDecimal("50.00"), new BigDecimal("4.00"), BigDecimal.ZERO, null, null),
                new TurnoverRow(2L, "G002", "商品B", 9L, "饮料", new BigDecimal("2"),
                        new BigDecimal("54.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                        new BigDecimal("42.00"), new BigDecimal("2.00"), BigDecimal.ZERO, null, null)));
        return page;
    }
}

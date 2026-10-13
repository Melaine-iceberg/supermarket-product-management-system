package com.smms.rpt;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.goods.dto.GoodsListVO;
import com.smms.goods.dto.GoodsQuery;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.goods.service.GoodsService;
import com.smms.rpt.dto.DrillInvRecordQuery;
import com.smms.rpt.dto.DrillInvRecordRow;
import com.smms.rpt.dto.DrillOrderQuery;
import com.smms.rpt.dto.DrillOrderRow;
import com.smms.rpt.dto.GoodsRankRow;
import com.smms.rpt.dto.InvSummaryRow;
import com.smms.rpt.dto.PurchSummaryRow;
import com.smms.rpt.dto.ReportRange;
import com.smms.rpt.dto.SalesReportRow;
import com.smms.rpt.dto.SalesReportVO;
import com.smms.rpt.dto.WarningRptRow;
import com.smms.rpt.service.DrillDownService;
import com.smms.rpt.service.GoodsReportService;
import com.smms.rpt.service.InventoryReportService;
import com.smms.rpt.service.PurchaseReportService;
import com.smms.rpt.service.ReportExcelService;
import com.smms.rpt.service.SalesReportService;
import com.smms.security.LoginUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批次一/批次三回归集成测试，Testcontainers 起真 MariaDB（无 Docker 自动跳过）：
 *
 * <ul>
 *   <li>GAP-P0-02：销售月报按“月”正确聚合（修复前月报只保留每月最后一天）；</li>
 *   <li>GAP-P0-03：日报/月报退货额与退货单数按“退货单”口径（修复前一单多明细时退款额被重复计、单数记成明细行数）；</li>
 *   <li>GAP-P0-01：导出查询突破 IR-COM-04 单页 500 条上限，同时全局保护仍在（普通分页仍截断），
 *       并校验 FR-RPT-08 的 5 万行超限提示可达；</li>
 *   <li>GAP-P0-10①：商品/品类毛利扣减退货后与销售日报 BR-RPT-01 口径一致；</li>
 *   <li>GAP-P0-10②③：无毛利权限角色在库存/采购汇总与下钻中看不到成本类字段（真实 SQL 路径）；</li>
 *   <li>GAP-P1-05：采购/库存/损耗报表按 business_date 聚合，不再按 create_time/operate_time/audit_time；</li>
 *   <li>GAP-P2-21：品牌销售行按 brandId 下钻、库存流水分类型/商品/分类下钻（真实 SQL 过滤）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class ReportAggregationExportIntegrationTest {

    @Container
    static final MariaDBContainer MARIADB =
            new MariaDBContainer("mariadb:11")
                    .withDatabaseName("smms")
                    .withUsername("smms")
                    .withPassword("smms");

    static {
        // 建表先于 Spring 上下文初始化（与 InventoryConcurrencyIntegrationTest 同一模式）
        if (DockerClientFactory.instance().isDockerAvailable()) {
            if (!MARIADB.isRunning()) {
                MARIADB.start();
            }
            initSchema();
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MARIADB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIADB::getUsername);
        registry.add("spring.datasource.password", MARIADB::getPassword);
    }

    private static final LoginUser MANAGER = new LoginUser(2L, "manager", "王店长",
            List.of("MANAGER"), List.of("店长"), Set.of(), "fp", false);

    private static final LoginUser CASHIER = new LoginUser(5L, "cashier", "陈收银",
            List.of("CASHIER"), List.of("收银员"), Set.of(), "fp", false);

    @Autowired
    private SalesReportService salesReportService;

    @Autowired
    private GoodsReportService goodsReportService;

    @Autowired
    private InventoryReportService inventoryReportService;

    @Autowired
    private PurchaseReportService purchaseReportService;

    @Autowired
    private DrillDownService drillDownService;

    @Autowired
    private ReportExcelService reportExcelService;

    @Autowired
    private GoodsService goodsService;

    @Autowired
    private GoodsMapper goodsMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== GAP-P0-02 / P0-03：报表聚合口径 ====================

    @Test
    @DisplayName("月报按月聚合、退货额不被明细行数放大、退货单数=退货单张数（GAP-P0-02/P0-03）")
    void monthlyAndReturnAggregationAreCorrect() {
        long soJan30 = insertSalesOrder("XS202601300001", LocalDate.of(2026, 1, 30), "100.00", "60.00", 1);
        long soJan31 = insertSalesOrder("XS202601310001", LocalDate.of(2026, 1, 31), "200.00", "120.00", 7);
        insertSalesOrder("XS202602010001", LocalDate.of(2026, 2, 1), "50.00", "30.00", 1);

        // 1/31：一张 3 明细退货单（退款 30 / 成本回冲 12）+ 一张 1 明细退货单（退款 10 / 成本 3）
        long rt1 = insertReturnOrder("XT202601310001", soJan31, LocalDate.of(2026, 1, 31), "30.00");
        insertReturnItem(rt1, "10.00", "4.00");
        insertReturnItem(rt1, "10.00", "4.00");
        insertReturnItem(rt1, "10.00", "4.00");
        long rt2 = insertReturnOrder("XT202601310002", soJan31, LocalDate.of(2026, 1, 31), "10.00");
        insertReturnItem(rt2, "10.00", "3.00");
        // 1/30：一张 1 明细退货单（退款 5 / 成本 2）
        long rt3 = insertReturnOrder("XT202601300001", soJan30, LocalDate.of(2026, 1, 30), "5.00");
        insertReturnItem(rt3, "5.00", "2.00");

        SalesReportVO monthly = salesReportService.daily(
                new ReportRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28)), true, MANAGER);

        // 月报必须按月各一行，而不是同月多天被覆盖为“每月最后一天”
        assertThat(monthly.rows()).extracting(SalesReportRow::groupKey)
                .containsExactly("2026-01", "2026-02");

        SalesReportRow jan = monthly.rows().get(0);
        assertThat(jan.salesAmount()).isEqualByComparingTo("300.00");   // 100 + 200
        assertThat(jan.netSalesAmount()).isEqualByComparingTo("300.00");
        assertThat(jan.costAmount()).isEqualByComparingTo("163.00");    // 销售成本 = (60+120) − 退货成本回冲(12+3+2)（BR-RPT-01）
        assertThat(jan.refundAmount()).isEqualByComparingTo("45.00");   // 30 + 10 + 5
        assertThat(jan.returnCount()).isEqualTo(3L);                    // 3 张退货单（修复前 1/31 单日即被放大为 30×3+10=100 且月内只保留最后一天）
        assertThat(jan.netAmount()).isEqualByComparingTo("255.00");     // 300 − 45
        assertThat(jan.profitAmount()).isEqualByComparingTo("92.00");   // 255 − (180 − 17)

        SalesReportRow feb = monthly.rows().get(1);
        assertThat(feb.salesAmount()).isEqualByComparingTo("50.00");
        assertThat(feb.refundAmount()).isEqualByComparingTo("0");

        assertThat(monthly.total().refundAmount()).isEqualByComparingTo("45.00");

        // 日报（同日）与月报口径一致：按天各自聚合
        SalesReportVO daily = salesReportService.daily(
                new ReportRange(LocalDate.of(2026, 1, 30), LocalDate.of(2026, 2, 1)), false, MANAGER);
        Map<String, SalesReportRow> byDay = daily.rows().stream()
                .collect(Collectors.toMap(SalesReportRow::groupKey, Function.identity()));
        assertThat(byDay.get("2026-01-30").refundAmount()).isEqualByComparingTo("5.00");
        assertThat(byDay.get("2026-01-31").refundAmount()).isEqualByComparingTo("40.00");
        assertThat(byDay.get("2026-01-31").returnCount()).isEqualTo(2L);
        assertThat(byDay.get("2026-02-01").refundAmount()).isEqualByComparingTo("0");
    }

    // ==================== GAP-P0-01：导出不被单页上限截断 ====================

    @Test
    @DisplayName("导出查询突破 500 条单页上限（520 行全量返回），普通分页保护与超限提示仍生效（GAP-P0-01）")
    void exportBypassesSinglePageLimitWhileGuardRemains() {
        long categoryId = ensureCategory("导出回归分类");
        long unitId = ensureUnit("件");
        insertGoods(categoryId, unitId, 520);

        GoodsQuery query = new GoodsQuery(null, null, null, null, null, null,
                null, null, null, 1, 100_000, null, null);

        // 导出路径（ExportPage 豁免）：520 > 500，全量返回（修复前只有 500 行）
        List<GoodsListVO> exported = goodsService.listForExport(query, 1000);
        assertThat(exported).hasSize(520);

        // 普通分页路径：全局 500 条上限仍生效（IR-COM-04 保护不因本修复失效）
        IPage<GoodsListVO> capped = goodsMapper.selectGoodsPage(new Page<>(1, 1000), query, null);
        assertThat(capped.getRecords()).hasSize(500);

        // FR-RPT-08：超过 export_max_rows 时提示缩小范围（默认 5 万）
        assertThat(reportExcelService.maxRows()).isEqualTo(50_000);
        assertThatThrownBy(() -> reportExcelService.checkLimit(50_001))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }

    // ==================== GAP-P0-10：报表口径统一与成本掩码 ====================

    @Test
    @DisplayName("商品/品类毛利扣减退货后与销售日报 BR-RPT-01 口径一致（GAP-P0-10①）")
    void goodsRankingDeductsReturnsAndMatchesDailyReport() {
        long categoryId = ensureCategory("批次三毛利口径分类");
        long unitId = ensureUnit("件");
        Long goodsId = insertGoodsRow(categoryId, unitId, "T3G001", "口径测试商品");
        LocalDate day = LocalDate.of(2026, 4, 15);

        // 两笔销售：200 + 100（成本 120 + 60）；一笔退货：退 50、成本回冲 60
        long so1 = insertSalesOrder("XS202604150001", day, "200.00", "120.00", 2);
        insertSalesItem(so1, goodsId, "口径测试商品", "100.00", "2", "200.00", "60.00", "120.00");
        long so2 = insertSalesOrder("XS202604150002", day, "100.00", "60.00", 1);
        insertSalesItem(so2, goodsId, "口径测试商品", "100.00", "1", "100.00", "60.00", "60.00");
        long rt = insertReturnOrder("XT202604150001", so1, day, "50.00");
        insertReturnItemForGoods(rt, goodsId, "50.00", "60.00");

        ReportRange range = new ReportRange(day, day);

        // 商品排行：净销售 = 300 − 50 = 250；销售成本 = 180 − 60 = 120；毛利 = 130；毛利率 = 52%
        GoodsRankRow rank = goodsReportService.ranking(range, "salesAmount", categoryId, 1, 20, MANAGER)
                .list().stream().filter(r -> goodsId.equals(r.goodsId())).findFirst().orElseThrow();
        assertThat(rank.refundAmount()).isEqualByComparingTo("50.00");
        assertThat(rank.returnCostAmount()).isEqualByComparingTo("60.00");
        assertThat(rank.costAmount()).isEqualByComparingTo("180.00");
        assertThat(rank.profitAmount()).isEqualByComparingTo("130.00");
        assertThat(rank.profitRate()).isEqualByComparingTo("52.00");

        // 品类维度复用同一口径
        GoodsRankRow catRow = goodsReportService.categoryStructure(range, MANAGER).stream()
                .filter(r -> Long.valueOf(categoryId).equals(r.categoryId())).findFirst().orElseThrow();
        assertThat(catRow.refundAmount()).isEqualByComparingTo("50.00");
        assertThat(catRow.profitAmount()).isEqualByComparingTo("130.00");

        // 与同日销售日报逐项一致（BR-RPT-01 全系统唯一定义）
        SalesReportRow daily = salesReportService.daily(range, false, MANAGER).rows().stream()
                .filter(r -> day.equals(r.bizDate())).findFirst().orElseThrow();
        assertThat(daily.netAmount()).isEqualByComparingTo("250.00");
        assertThat(daily.refundAmount()).isEqualByComparingTo("50.00");
        assertThat(daily.profitAmount()).isEqualByComparingTo("130.00");
        assertThat(daily.profitRate()).isEqualByComparingTo("52.00");
    }

    @Test
    @DisplayName("无毛利权限角色：库存/采购汇总与下钻成本类字段不返回（GAP-P0-10②③）")
    void nonPrivilegedUserSeesMaskedCostFields() {
        long categoryId = ensureCategory("批次三掩码分类");
        long unitId = ensureUnit("件");
        Long goodsId = insertGoodsRow(categoryId, unitId, "T3G002", "掩码测试商品");
        LocalDate day = LocalDate.of(2026, 4, 20);

        jdbcTemplate.update("""
                INSERT INTO inventory (goods_id, store_id, quantity, cost_price, cost_amount)
                VALUES (?, 1, 5, 12.0000, 60.00)
                """, goodsId);
        long supplierId = ensureSupplier("批次三掩码供应商", "S-T3-002");
        long poId = insertPurchaseOrderRow("PO202604200001", supplierId, day, day, "APPROVED", "100.00");
        jdbcTemplate.update("""
                INSERT INTO purchase_order_item (order_id, goods_id, quantity, price, amount, received_qty, returned_qty)
                VALUES (?, ?, 10, 10.00, 100.00, 0, 0)
                """, poId, goodsId);
        jdbcTemplate.update("""
                INSERT INTO inventory_record (goods_id, store_id, record_type, change_qty, before_qty, after_qty,
                                              unit_cost, biz_type, operator_name, operate_time, business_date)
                VALUES (?, 1, 'PURCHASE_IN', 10, 0, 10, 10.0000, 'RK', '赵库管', ?, ?)
                """, goodsId, day.atTime(9, 0), day);

        ReportRange range = new ReportRange(day, day);

        // 库存汇总：收银员（非店长）看不到成本单价/成本金额，数量与零售价值保留
        InvSummaryRow maskedInv = inventoryReportService.summaryByGoods(categoryId, 1, 50, CASHIER)
                .list().stream().filter(r -> goodsId.equals(r.goodsId())).findFirst().orElseThrow();
        assertThat(maskedInv.costPrice()).isNull();
        assertThat(maskedInv.costAmount()).isNull();
        assertThat(maskedInv.stockQty()).isEqualByComparingTo("5");

        // 采购汇总：采购金额/退货金额/结算金额不返回，数量保留
        PurchSummaryRow maskedPurch = purchaseReportService.summary(range, "goods", CASHIER).stream()
                .filter(r -> goodsId.equals(r.goodsId())).findFirst().orElseThrow();
        assertThat(maskedPurch.purchaseAmount()).isNull();
        assertThat(maskedPurch.settledAmount()).isNull();
        assertThat(maskedPurch.purchaseQty()).isEqualByComparingTo("10");

        // 下钻流水：单位成本不返回；分类筛选（GAP-P2-21）在真实 SQL 下同路径生效
        PageVO<DrillInvRecordRow> drills = drillDownService.inventoryRecords(
                new DrillInvRecordQuery(day, day, "PURCHASE_IN", goodsId, categoryId, 1, 20), CASHIER);
        assertThat(drills.list()).isNotEmpty();
        assertThat(drills.list().get(0).unitCost()).isNull();

        // 店长完整可见（同一 SQL 路径）
        InvSummaryRow fullInv = inventoryReportService.summaryByGoods(categoryId, 1, 50, MANAGER)
                .list().stream().filter(r -> goodsId.equals(r.goodsId())).findFirst().orElseThrow();
        assertThat(fullInv.costPrice()).isEqualByComparingTo("12.0000");
        assertThat(fullInv.costAmount()).isEqualByComparingTo("60.00");
        PurchSummaryRow fullPurch = purchaseReportService.summary(range, "goods", MANAGER).stream()
                .filter(r -> goodsId.equals(r.goodsId())).findFirst().orElseThrow();
        assertThat(fullPurch.purchaseAmount()).isEqualByComparingTo("100.00");
        assertThat(drillDownService.inventoryRecords(
                        new DrillInvRecordQuery(day, day, "PURCHASE_IN", goodsId, categoryId, 1, 20), MANAGER)
                .list().get(0).unitCost()).isEqualByComparingTo("10.0000");

        // 预警建议采购量：近 N 营业日销量子查询（business_date）在真实 SQL 下可执行
        jdbcTemplate.update("UPDATE goods SET safety_stock_lower = 10 WHERE id = ?", goodsId);
        WarningRptRow warning = inventoryReportService.warnings("WARNING", 1, 50).list().stream()
                .filter(r -> goodsId.equals(r.goodsId())).findFirst().orElseThrow();
        assertThat(warning.stockQty()).isEqualByComparingTo("5");
        assertThat(warning.suggestQty()).isNotNull();
    }

    // ==================== GAP-P1-05：报表按营业日聚合 ====================

    @Test
    @DisplayName("采购/库存/损耗报表按 business_date 聚合，不再按 create_time/operate_time/audit_time（GAP-P1-05）")
    void reportsAggregateByBusinessDate() {
        long categoryId = ensureCategory("批次三营业日分类");
        long unitId = ensureUnit("件");
        Long goodsId = insertGoodsRow(categoryId, unitId, "T3G003", "营业日测试商品");
        LocalDate bizDay = LocalDate.of(2026, 5, 1);
        LocalDate actualDay = LocalDate.of(2026, 5, 10);

        // 采购单：创建时间为实际时刻（5/10），营业日为 5/1
        long supplierId = ensureSupplier("批次三营业日供应商", "S-T3-003");
        long poId = insertPurchaseOrderRow("PO202605100001", supplierId, bizDay, actualDay, "APPROVED", "100.00");
        jdbcTemplate.update("""
                INSERT INTO purchase_order_item (order_id, goods_id, quantity, price, amount, received_qty, returned_qty)
                VALUES (?, ?, 5, 20.00, 100.00, 0, 0)
                """, poId, goodsId);

        // 库存流水：记账时间为 5/10，营业日为 5/1
        jdbcTemplate.update("""
                INSERT INTO inventory_record (goods_id, store_id, record_type, change_qty, before_qty, after_qty,
                                              unit_cost, biz_type, operator_name, operate_time, business_date)
                VALUES (?, 1, 'LOSS_OUT', -1, 10, 9, 20.0000, 'BS', '赵库管', ?, ?)
                """, goodsId, actualDay.atTime(10, 0), bizDay);

        // 报损单：审核时间为 5/10，营业日为 5/1
        jdbcTemplate.update("""
                INSERT INTO loss_order (order_no, biz_type, goods_id, quantity, cost_price, amount, reason,
                                        apply_name, status, auditor_name, audit_time, business_date)
                VALUES ('BS202605100001', 'LOSS', ?, 2, 12.0000, 24.00, 'EXPIRED', '赵库管', 'AUDITED', '王店长', ?, ?)
                """, goodsId, actualDay.atTime(11, 0), bizDay);

        // 营业日 5/1：三张报表都命中
        assertThat(purchaseReportService.summary(new ReportRange(bizDay, bizDay), "goods", MANAGER))
                .anyMatch(r -> goodsId.equals(r.goodsId()) && r.purchaseAmount().compareTo(new BigDecimal("100.00")) == 0);
        assertThat(inventoryReportService.changes(new ReportRange(bizDay, bizDay), MANAGER))
                .anyMatch(r -> "LOSS_OUT".equals(r.recordType()) && r.recordCount() == 1);
        assertThat(inventoryReportService.loss(new ReportRange(bizDay, bizDay), MANAGER).totalLossAmount())
                .isEqualByComparingTo("24.00");

        // 实际日历日 5/10（旧口径 create_time/operate_time/audit_time）不得命中
        assertThat(purchaseReportService.summary(new ReportRange(actualDay, actualDay), "goods", MANAGER))
                .noneMatch(r -> goodsId.equals(r.goodsId()));
        assertThat(inventoryReportService.changes(new ReportRange(actualDay, actualDay), MANAGER))
                .noneMatch(r -> "LOSS_OUT".equals(r.recordType()));
        assertThat(inventoryReportService.loss(new ReportRange(actualDay, actualDay), MANAGER).totalLossAmount())
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("采购导出按 limit 取数（真实 SQL LIMIT），交互查询不限行（GAP-P1-06）")
    void purchaseExportLimitBoundsRows() {
        LocalDate day = LocalDate.of(2026, 5, 20);
        long supplierA = ensureSupplier("批次三限额供应商A", "S-T3-004");
        long supplierB = ensureSupplier("批次三限额供应商B", "S-T3-005");
        Long goodsId = insertGoodsRow(ensureCategory("批次三限额分类"), ensureUnit("件"), "T3G004", "限额测试商品");
        long poA = insertPurchaseOrderRow("PO202605200001", supplierA, day, day, "APPROVED", "10.00");
        long poB = insertPurchaseOrderRow("PO202605200002", supplierB, day, day, "APPROVED", "20.00");
        jdbcTemplate.update("""
                INSERT INTO purchase_order_item (order_id, goods_id, quantity, price, amount, received_qty, returned_qty)
                VALUES (?, ?, 1, 10.00, 10.00, 0, 0), (?, ?, 2, 10.00, 20.00, 0, 0)
                """, poA, goodsId, poB, goodsId);

        ReportRange range = new ReportRange(day, day);
        assertThat(purchaseReportService.summary(range, "supplier", MANAGER)).hasSize(2);
        assertThat(purchaseReportService.summaryForExport(range, "supplier", MANAGER, 1)).hasSize(1);
    }

    // ==================== GAP-P2-21：品牌下钻与库存分类下钻 ====================

    @Test
    @DisplayName("品牌销售行下钻：drill-orders 按 brandId 筛选，与品牌口径一致（GAP-P2-21）")
    void brandDrillFiltersOrdersByBrand() {
        long categoryId = ensureCategory("批次六品牌下钻分类");
        long unitId = ensureUnit("件");
        long brandA = ensureBrand("批次六品牌A");
        long brandB = ensureBrand("批次六品牌B");
        Long goodsA = insertGoodsRow(categoryId, unitId, "T6G001", "品牌下钻商品A", brandA);
        Long goodsB = insertGoodsRow(categoryId, unitId, "T6G002", "品牌下钻商品B", brandB);
        LocalDate day = LocalDate.of(2026, 6, 1);

        long soA = insertSalesOrder("XS202606010001", day, "100.00", "60.00", 1);
        insertSalesItem(soA, goodsA, "品牌下钻商品A", "100.00", "1", "100.00", "60.00", "60.00");
        long soB = insertSalesOrder("XS202606010002", day, "50.00", "30.00", 1);
        insertSalesItem(soB, goodsB, "品牌下钻商品B", "50.00", "1", "50.00", "30.00", "30.00");

        // 品牌 A 仅命中其商品所在销售单（真实 SQL EXISTS 过滤）
        PageVO<DrillOrderRow> brandAOrders = drillDownService.orders(new DrillOrderQuery(
                day, day, null, null, brandA, null, null, null, null, null, null, 1, 20), MANAGER);
        assertThat(brandAOrders.list()).hasSize(1);
        assertThat(brandAOrders.list().get(0).orderNo()).isEqualTo("XS202606010001");

        // 品牌 + 分类条件可组合，且分类筛选既有行为不受影响
        assertThat(drillDownService.orders(new DrillOrderQuery(
                day, day, null, categoryId, brandB, null, null, null, null, null, null, 1, 20), MANAGER)
                .list()).extracting(DrillOrderRow::orderNo).containsExactly("XS202606010002");
    }

    // ==================== 测试数据 ====================

    private long insertGoodsRow(long categoryId, long unitId, String code, String name) {
        jdbcTemplate.update("""
                INSERT INTO goods (goods_code, barcode, name, category_id, unit_id, retail_price)
                VALUES (?, ?, ?, ?, ?, 9.90)
                """, code, "899" + code + "0", name, categoryId, unitId);
        return jdbcTemplate.queryForObject("SELECT id FROM goods WHERE goods_code = ?", Long.class, code);
    }

    private long insertGoodsRow(long categoryId, long unitId, String code, String name, long brandId) {
        jdbcTemplate.update("""
                INSERT INTO goods (goods_code, barcode, name, category_id, unit_id, brand_id, retail_price)
                VALUES (?, ?, ?, ?, ?, ?, 9.90)
                """, code, "899" + code + "0", name, categoryId, unitId, brandId);
        return jdbcTemplate.queryForObject("SELECT id FROM goods WHERE goods_code = ?", Long.class, code);
    }

    private long ensureBrand(String name) {
        jdbcTemplate.update("""
                INSERT INTO brand (name, status) SELECT ?, 1 WHERE NOT EXISTS (SELECT 1 FROM brand WHERE name = ?)
                """, name, name);
        return jdbcTemplate.queryForObject("SELECT id FROM brand WHERE name = ?", Long.class, name);
    }

    private void insertSalesItem(long orderId, long goodsId, String goodsName, String unitPrice, String qty,
                                 String paidAmount, String costPrice, String costAmount) {
        BigDecimal unit = new BigDecimal(unitPrice);
        BigDecimal quantity = new BigDecimal(qty);
        jdbcTemplate.update("""
                INSERT INTO sales_order_item (order_id, goods_id, goods_name, unit_price, quantity, amount,
                                              discount_rate, discount_amount, paid_amount, cost_price, cost_amount,
                                              profit_amount, returned_qty, line_type)
                VALUES (?, ?, ?, ?, ?, ?, 1, 0, ?, ?, ?, ?, 0, 'NORMAL')
                """, orderId, goodsId, goodsName, unit, quantity, unit.multiply(quantity),
                new BigDecimal(paidAmount), new BigDecimal(costPrice), new BigDecimal(costAmount),
                new BigDecimal(paidAmount).subtract(new BigDecimal(costAmount)));
    }

    private void insertReturnItemForGoods(long returnId, long goodsId, String refundAmount, String costAmount) {
        jdbcTemplate.update("""
                INSERT INTO return_order_item (return_id, sales_item_id, goods_id, return_qty, refund_price,
                                               refund_amount, cost_price, cost_amount)
                VALUES (?, 1, ?, 1, ?, ?, ?, ?)
                """, returnId, goodsId, new BigDecimal(refundAmount), new BigDecimal(refundAmount),
                new BigDecimal(costAmount), new BigDecimal(costAmount));
    }

    private long ensureSupplier(String name, String code) {
        jdbcTemplate.update("""
                INSERT INTO supplier (supplier_code, name, status)
                SELECT ?, ?, 1 WHERE NOT EXISTS (SELECT 1 FROM supplier WHERE name = ?)
                """, code, name, name);
        return jdbcTemplate.queryForObject("SELECT id FROM supplier WHERE name = ?", Long.class, name);
    }

    /** 采购单：businessDate 为报表口径，createTime 为实际落库时刻（用于验证两者解耦） */
    private long insertPurchaseOrderRow(String orderNo, long supplierId, LocalDate businessDate,
                                        LocalDate createDate, String status, String totalAmount) {
        jdbcTemplate.update("""
                INSERT INTO purchase_order (order_no, supplier_id, status, create_by, create_time, business_date,
                                            total_amount, received_amount, return_amount, settle_status, version)
                VALUES (?, ?, ?, '李采购', ?, ?, ?, 0, 0, 'UNSETTLED', 0)
                """, orderNo, supplierId, status, createDate.atTime(9, 0), businessDate,
                new BigDecimal(totalAmount));
        return jdbcTemplate.queryForObject("SELECT id FROM purchase_order WHERE order_no = ?", Long.class, orderNo);
    }

    private long insertSalesOrder(String orderNo, LocalDate bizDate, String amount, String cost, int itemCount) {
        BigDecimal amt = new BigDecimal(amount);
        BigDecimal cst = new BigDecimal(cost);
        jdbcTemplate.update("""
                INSERT INTO sales_order (order_no, counter_no, cashier_id, cashier_name, sale_time, business_date,
                                         item_count, goods_amount, discount_amount, payable_amount, paid_amount,
                                         change_amount, cost_amount, profit_amount)
                VALUES (?, 1, 1, '报表测试收银', ?, ?, ?, ?, 0, ?, ?, 0, ?, ?)
                """, orderNo, bizDate.atTime(10, 0), bizDate, itemCount, amt, amt, amt, cst, amt.subtract(cst));
        return jdbcTemplate.queryForObject("SELECT id FROM sales_order WHERE order_no = ?", Long.class, orderNo);
    }

    private long insertReturnOrder(String orderNo, long salesOrderId, LocalDate bizDate, String refund) {
        jdbcTemplate.update("""
                INSERT INTO return_order (order_no, sales_order_id, return_reason, refund_amount, refund_type,
                                          cashier_name, return_time, business_date)
                VALUES (?, ?, 'QUALITY', ?, 'CASH', '报表测试收银', ?, ?)
                """, orderNo, salesOrderId, new BigDecimal(refund), bizDate.atTime(11, 0), bizDate);
        return jdbcTemplate.queryForObject("SELECT id FROM return_order WHERE order_no = ?", Long.class, orderNo);
    }

    private void insertReturnItem(long returnId, String refundPrice, String costAmount) {
        jdbcTemplate.update("""
                INSERT INTO return_order_item (return_id, sales_item_id, return_qty, refund_price,
                                               refund_amount, cost_price, cost_amount)
                VALUES (?, 1, 1, ?, ?, ?, ?)
                """, returnId, new BigDecimal(refundPrice), new BigDecimal(refundPrice),
                new BigDecimal(costAmount), new BigDecimal(costAmount));
    }

    private long ensureCategory(String name) {
        jdbcTemplate.update("""
                INSERT INTO category (name) SELECT ? WHERE NOT EXISTS (SELECT 1 FROM category WHERE name = ?)
                """, name, name);
        return jdbcTemplate.queryForObject("SELECT id FROM category WHERE name = ?", Long.class, name);
    }

    private long ensureUnit(String name) {
        jdbcTemplate.update("""
                INSERT INTO unit (name) SELECT ? WHERE NOT EXISTS (SELECT 1 FROM unit WHERE name = ?)
                """, name, name);
        return jdbcTemplate.queryForObject("SELECT id FROM unit WHERE name = ?", Long.class, name);
    }

    private void insertGoods(long categoryId, long unitId, int count) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update("""
                    INSERT INTO goods (goods_code, barcode, name, category_id, unit_id, retail_price)
                    VALUES (?, ?, ?, ?, ?, 9.90)
                    """, "EXP%05d".formatted(i), "899%010d".formatted(i),
                    "导出回归商品" + i, categoryId, unitId);
        }
    }

    // ==================== 建库脚本（与 InventoryConcurrencyIntegrationTest 同模式） ====================

    private static void initSchema() {
        Path schemaFile = locateSchema();
        String script = readSchemaWithoutCreateDatabase(schemaFile);
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)));
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        try (Connection connection = DriverManager.getConnection(
                MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword())) {
            populator.populate(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("初始化测试库 schema 失败（" + schemaFile + "）", e);
        }
    }

    private static Path locateSchema() {
        for (Path candidate : List.of(Path.of("db", "schema.sql"), Path.of("..", "db", "schema.sql"))) {
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("未找到 db/schema.sql，请从仓库根目录或 backend/ 运行测试");
    }

    private static String readSchemaWithoutCreateDatabase(Path schemaFile) {
        try (Stream<String> lines = Files.lines(schemaFile, StandardCharsets.UTF_8)) {
            return lines.filter(line -> {
                        String upper = line.stripLeading().toUpperCase(Locale.ROOT);
                        return !upper.startsWith("CREATE DATABASE") && !upper.startsWith("USE ");
                    })
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new IllegalStateException("读取建表脚本失败：" + schemaFile, e);
        }
    }
}

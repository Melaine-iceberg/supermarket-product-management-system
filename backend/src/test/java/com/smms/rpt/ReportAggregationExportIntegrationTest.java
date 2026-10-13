package com.smms.rpt;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.goods.dto.GoodsListVO;
import com.smms.goods.dto.GoodsQuery;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.goods.service.GoodsService;
import com.smms.rpt.dto.ReportRange;
import com.smms.rpt.dto.SalesReportRow;
import com.smms.rpt.dto.SalesReportVO;
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
 * 批次一（数据正确性）回归集成测试，Testcontainers 起真 MariaDB（无 Docker 自动跳过）：
 *
 * <ul>
 *   <li>GAP-P0-02：销售月报按“月”正确聚合（修复前月报只保留每月最后一天）；</li>
 *   <li>GAP-P0-03：日报/月报退货额与退货单数按“退货单”口径（修复前一单多明细时退款额被重复计、单数记成明细行数）；</li>
 *   <li>GAP-P0-01：导出查询突破 IR-COM-04 单页 500 条上限，同时全局保护仍在（普通分页仍截断），
 *       并校验 FR-RPT-08 的 5 万行超限提示可达。</li>
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

    @Autowired
    private SalesReportService salesReportService;

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

    // ==================== 测试数据 ====================

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

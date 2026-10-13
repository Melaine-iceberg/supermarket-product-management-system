package com.smms.rpt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 存量数据库升级脚本回归（GAP-P1-05）：Testcontainers 起真 MariaDB，
 * 用 V1.4 之前的表结构（无 business_date）造数后执行 `db/upgrade-1.4-report-audit.sql`，
 * 校验列/索引补齐与按 business_day_start 的存量回填口径（无 Docker 自动跳过）。
 */
@Testcontainers(disabledWithoutDocker = true)
class UpgradeScriptIntegrationTest {

    @Container
    static final MariaDBContainer MARIADB =
            new MariaDBContainer("mariadb:11")
                    .withDatabaseName("smms")
                    .withUsername("smms")
                    .withPassword("smms");

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            if (!MARIADB.isRunning()) {
                MARIADB.start();
            }
            preparePreUpgradeSchema();
        }
    }

    @Test
    @DisplayName("V1.4 升级脚本：补齐 business_date 列/索引，并按 06:00 起始时刻回填存量数据")
    void upgradeScriptBackfillsBusinessDate() throws Exception {
        try (Connection conn = open()) {
            // 采购单：05:30 创建归前一日，07:00 创建归当日
            assertThat(queryDate(conn, "SELECT business_date FROM purchase_order WHERE order_no = 'PO-BD-0530'"))
                    .isEqualTo(LocalDate.of(2026, 6, 10));
            assertThat(queryDate(conn, "SELECT business_date FROM purchase_order WHERE order_no = 'PO-BD-0700'"))
                    .isEqualTo(LocalDate.of(2026, 6, 11));

            // 入库单：按入库时刻
            assertThat(queryDate(conn, "SELECT business_date FROM receipt_order WHERE order_no = 'RK-BD-0530'"))
                    .isEqualTo(LocalDate.of(2026, 6, 10));

            // 库存流水：按记账时刻
            assertThat(queryDate(conn, "SELECT business_date FROM inventory_record WHERE biz_no = 'BD-0530'"))
                    .isEqualTo(LocalDate.of(2026, 6, 10));

            // 盘点单：已审核按审核时刻（05:30 → 前一日），未审核暂存创建时刻（07:00 → 当日）
            assertThat(queryDate(conn, "SELECT business_date FROM stock_check_order WHERE order_no = 'PD-BD-AUDITED'"))
                    .isEqualTo(LocalDate.of(2026, 6, 10));
            assertThat(queryDate(conn, "SELECT business_date FROM stock_check_order WHERE order_no = 'PD-BD-PENDING'"))
                    .isEqualTo(LocalDate.of(2026, 6, 11));

            // 报损单：已审核按审核时刻
            assertThat(queryDate(conn, "SELECT business_date FROM loss_order WHERE order_no = 'BS-BD-0530'"))
                    .isEqualTo(LocalDate.of(2026, 6, 10));

            // 列均为 NOT NULL（存在任何可空行即失败）
            assertThat(queryLong(conn, """
                    SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = 'smms' AND COLUMN_NAME = 'business_date' AND IS_NULLABLE = 'YES'
                      AND TABLE_NAME IN ('purchase_order','receipt_order','purchase_return_order',
                                         'inventory_record','stock_check_order','loss_order')
                    """)).isZero();

            // 报表聚合索引补齐（含销售退货单）
            assertThat(queryLong(conn, """
                    SELECT COUNT(*) FROM (
                        SELECT DISTINCT TABLE_NAME, INDEX_NAME FROM information_schema.STATISTICS
                        WHERE TABLE_SCHEMA = 'smms' AND INDEX_NAME IN
                              ('idx_po_biz_date','idx_ro_biz_date','idx_pr_biz_date',
                               'idx_ir_biz_date','idx_sc_biz_date','idx_lo_biz_date','idx_rt_biz_date')
                    ) t
                    """)).isEqualTo(7L);
        }
    }

    // ==================== 老结构（V1.4 之前）与造数 ====================

    private static void preparePreUpgradeSchema() {
        execute(readSchemaWithoutCreateDatabase(locate("db", "schema.sql")));
        // 退回到 V1.4 之前：删除新增列与索引，模拟存量数据库
        execute("""
                ALTER TABLE purchase_order DROP INDEX idx_po_biz_date, DROP COLUMN business_date;
                ALTER TABLE receipt_order DROP INDEX idx_ro_biz_date, DROP COLUMN business_date;
                ALTER TABLE purchase_return_order DROP INDEX idx_pr_biz_date, DROP COLUMN business_date;
                ALTER TABLE inventory_record DROP INDEX idx_ir_biz_date, DROP COLUMN business_date;
                ALTER TABLE stock_check_order DROP INDEX idx_sc_biz_date, DROP COLUMN business_date;
                ALTER TABLE loss_order DROP INDEX idx_lo_biz_date, DROP COLUMN business_date;
                ALTER TABLE return_order DROP INDEX idx_rt_biz_date;
                """);
        // 营业日起始时刻 06:00（验证回填按参数偏移，而非简单取日期）
        execute("""
                INSERT INTO sys_config (config_name, config_key, config_value, value_type)
                VALUES ('营业日起始时刻', 'business_day_start', '06:00', 'TIME');
                """);
        execute("""
                INSERT INTO supplier (id, supplier_code, name, status) VALUES (1, 'S-BD-1', '升级脚本供应商', 1);
                INSERT INTO category (id, name) VALUES (1, '升级脚本分类');
                INSERT INTO unit (id, name) VALUES (1, '件');
                INSERT INTO goods (id, goods_code, barcode, name, category_id, unit_id, retail_price)
                VALUES (1, 'BD0001', '8990000000001', '升级脚本商品', 1, 1, 9.90);

                INSERT INTO purchase_order (id, order_no, supplier_id, status, create_by, create_time, total_amount)
                VALUES (1, 'PO-BD-0530', 1, 'APPROVED', '李采购', '2026-06-11 05:30:00', 10),
                       (2, 'PO-BD-0700', 1, 'APPROVED', '李采购', '2026-06-11 07:00:00', 10);

                INSERT INTO receipt_order (id, order_no, purchase_order_id, supplier_id, receipt_time, operator_name)
                VALUES (1, 'RK-BD-0530', 1, 1, '2026-06-11 05:30:00', '赵库管');

                INSERT INTO inventory_record (goods_id, store_id, record_type, change_qty, before_qty, after_qty,
                                              unit_cost, biz_type, biz_no, operator_name, operate_time)
                VALUES (1, 1, 'PURCHASE_IN', 1, 0, 1, 9.9, 'RK', 'BD-0530', '赵库管', '2026-06-11 05:30:00');

                INSERT INTO stock_check_order (id, order_no, scope_type, status, checker_name, start_time, base_time,
                                               audit_time, auditor_name, create_time)
                VALUES (1, 'PD-BD-AUDITED', 'ALL', 'AUDITED', '赵库管', '2026-06-10 20:00:00', '2026-06-10 20:00:00',
                        '2026-06-11 05:30:00', '王店长', '2026-06-10 20:00:00'),
                       (2, 'PD-BD-PENDING', 'ALL', 'CHECKING', '赵库管', '2026-06-11 07:00:00', '2026-06-11 07:00:00',
                        NULL, NULL, '2026-06-11 07:00:00');

                INSERT INTO loss_order (id, order_no, biz_type, goods_id, quantity, cost_price, amount, reason,
                                        apply_name, status, auditor_name, audit_time, create_time)
                VALUES (1, 'BS-BD-0530', 'LOSS', 1, 1, 9.9, 9.90, 'EXPIRED', '赵库管', 'AUDITED', '王店长',
                        '2026-06-11 05:30:00', '2026-06-10 20:00:00');
                """);
        // 执行升级脚本
        execute(read(locate("db", "upgrade-1.4-report-audit.sql")));
    }

    // ==================== JDBC 工具 ====================

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
    }

    private static LocalDate queryDate(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            return rs.getDate(1).toLocalDate();
        }
    }

    private static String queryString(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static long queryLong(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            return rs.getLong(1);
        }
    }

    private static void execute(String script) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)));
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        try (Connection conn = open()) {
            populator.populate(conn);
        } catch (SQLException e) {
            throw new IllegalStateException("执行脚本失败", e);
        }
    }

    private static Path locate(String dir, String file) {
        for (Path candidate : List.of(Path.of(dir, file), Path.of("..", dir, file))) {
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("未找到 " + dir + "/" + file + "，请从仓库根目录或 backend/ 运行测试");
    }

    private static String read(String path) {
        return read(Path.of(path));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取脚本失败：" + path, e);
        }
    }

    private static String readSchemaWithoutCreateDatabase(Path schemaFile) {
        try (Stream<String> lines = Files.lines(schemaFile, StandardCharsets.UTF_8)) {
            return lines.filter(line -> {
                        String upper = line.stripLeading().toUpperCase(Locale.ROOT);
                        return !upper.startsWith("CREATE DATABASE") && !upper.startsWith("USE ");
                    })
                    .collect(java.util.stream.Collectors.joining("\n"));
        } catch (Exception e) {
            throw new IllegalStateException("读取建表脚本失败：" + schemaFile, e);
        }
    }
}

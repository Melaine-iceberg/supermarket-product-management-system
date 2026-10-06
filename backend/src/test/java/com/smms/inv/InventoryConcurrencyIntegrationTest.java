package com.smms.inv;

import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.inv.dto.StockChange;
import com.smms.inv.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-MNT-05 / AC-08：库存并发扣减集成测试（Testcontainers 起真 MariaDB）。
 * 100 线程并抢 10 件：恰好 10 单成功、90 单库存不足；结存归零、流水恰好 10 条且前后结存连续（BR-INV-08）。
 * 扣减依赖 MySQL/MariaDB 语义（条件扣减单语句原子更新 + 行锁），H2 无法复现，故必须用真库；
 * 无 Docker 环境由 disabledWithoutDocker 自动跳过（本地/CI 均可稳定运行）。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class InventoryConcurrencyIntegrationTest {

    private static final long STORE_ID = 1L;
    /** AC-08 场景参数：初始结存 10 件，100 线程各扣 1 件 */
    private static final int INITIAL_STOCK = 10;
    private static final int THREADS = 100;

    @Container
    static final MariaDBContainer MARIADB =
            new MariaDBContainer("mariadb:11")
                    .withDatabaseName("smms")
                    .withUsername("smms")
                    .withPassword("smms");

    static {
        // 建表先于 Spring 上下文初始化（容器由扩展启动前，静态块完成启动与建表，保证启动期查询可用）
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

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long goodsId;

    @BeforeEach
    void insertFixture() {
        jdbcTemplate.update("INSERT INTO category (name) VALUES (?)", "并发测试分类");
        Long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = ?", Long.class, "并发测试分类");
        jdbcTemplate.update("INSERT INTO unit (name) VALUES (?)", "件");
        Long unitId = jdbcTemplate.queryForObject(
                "SELECT id FROM unit WHERE name = ?", Long.class, "件");
        jdbcTemplate.update("""
                INSERT INTO goods (goods_code, barcode, name, category_id, unit_id, retail_price)
                VALUES (?, ?, ?, ?, ?, ?)
                """, "CONC-0001", "9000000000001", "并发测试商品", categoryId, unitId,
                new BigDecimal("12.00"));
        goodsId = jdbcTemplate.queryForObject(
                "SELECT id FROM goods WHERE barcode = ?", Long.class, "9000000000001");
        jdbcTemplate.update("""
                INSERT INTO inventory (goods_id, store_id, quantity, cost_price, cost_amount, last_change_time)
                VALUES (?, ?, ?, ?, ?, NOW())
                """, goodsId, STORE_ID, BigDecimal.valueOf(INITIAL_STOCK),
                new BigDecimal("10.0000"), new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("100 线程并发抢扣 10 件：成功数=10、失败均库存不足、结存=0、流水=10 条且前后结存链完整")
    void concurrentStockOutNeverOversells() throws Exception {
        // 前置校验：连接池隔离级别为 READ COMMITTED——MariaDB 11+ 默认快照隔离下 RR 会抛 1020（见 application.yaml）
        assertThat(jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class))
                .isEqualTo("READ-COMMITTED");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger stockNotEnough = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < THREADS; i++) {
                int seq = i;
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        inventoryService.stockOut(new StockChange(goodsId, "SALE_OUT", BigDecimal.ONE,
                                null, "XS", "CONC-IT-" + seq, "并发测试", null));
                        success.incrementAndGet();
                    } catch (BusinessException e) {
                        if (e.getCode() == ErrorCode.STOCK_NOT_ENOUGH.getCode()) {
                            stockNotEnough.incrementAndGet();
                        } else {
                            unexpected.add(e);
                        }
                    } catch (Throwable t) {
                        unexpected.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // 不超卖：恰好 10 单成功，其余均以"库存不足"拒绝
        assertThat(unexpected).isEmpty();
        assertThat(success.get()).isEqualTo(INITIAL_STOCK);
        assertThat(stockNotEnough.get()).isEqualTo(THREADS - INITIAL_STOCK);

        // AC-08：结存归零（不多扣、不少扣）
        BigDecimal quantity = jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventory WHERE goods_id = ? AND store_id = ?",
                BigDecimal.class, goodsId, STORE_ID);
        assertThat(quantity).isEqualByComparingTo(BigDecimal.ZERO);

        // BR-INV-08：流水恰好 10 条，前后结存连续（10→9→…→0），每笔 -1
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                "SELECT change_qty, before_qty, after_qty FROM inventory_record WHERE goods_id = ? ORDER BY id",
                goodsId);
        assertThat(records).hasSize(INITIAL_STOCK);
        BigDecimal expectedBefore = BigDecimal.valueOf(INITIAL_STOCK);
        for (Map<String, Object> record : records) {
            assertThat((BigDecimal) record.get("change_qty")).isEqualByComparingTo(BigDecimal.ONE.negate());
            assertThat((BigDecimal) record.get("before_qty")).isEqualByComparingTo(expectedBefore);
            expectedBefore = expectedBefore.subtract(BigDecimal.ONE);
            assertThat((BigDecimal) record.get("after_qty")).isEqualByComparingTo(expectedBefore);
        }
        assertThat(expectedBefore).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ==================== 建库脚本 ====================

    /** 在容器库内执行 db/schema.sql（整体脚本体，去除建库/切库语句——权限仅限 smms 库） */
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

    /** 兼容从仓库根目录或 backend/ 运行（Gradle 工作目录为 backend/，IDE 常为仓库根） */
    private static Path locateSchema() {
        for (Path candidate : List.of(Path.of("db", "schema.sql"), Path.of("..", "db", "schema.sql"))) {
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("未找到 db/schema.sql，请从仓库根目录或 backend/ 运行测试");
    }

    private static String readSchemaWithoutCreateDatabase(Path schemaFile) {
        try {
            return Files.readString(schemaFile, StandardCharsets.UTF_8).lines()
                    .filter(line -> {
                        String upper = line.stripLeading().toUpperCase(Locale.ROOT);
                        return !upper.startsWith("CREATE DATABASE") && !upper.startsWith("USE ");
                    })
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new IllegalStateException("读取建表脚本失败：" + schemaFile, e);
        }
    }
}

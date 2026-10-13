package com.smms.inv;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smms.common.exception.BusinessException;
import com.smms.goods.entity.Goods;
import com.smms.goods.mapper.CategoryMapper;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.inv.dto.CheckEntryRequest;
import com.smms.inv.dto.CheckImportVO;
import com.smms.inv.dto.CheckItemVO;
import com.smms.inv.dto.CheckSummaryVO;
import com.smms.inv.entity.StockCheckItem;
import com.smms.inv.entity.StockCheckOrder;
import com.smms.inv.mapper.StockCheckItemMapper;
import com.smms.inv.mapper.StockCheckOrderMapper;
import com.smms.inv.service.StockCheckExcelService;
import com.smms.inv.service.StockCheckService;
import com.smms.sys.dto.DictVO;
import com.smms.sys.service.DutySeparationService;
import com.smms.sys.service.SysDictService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 盘点录入与提交单元测试（FR-INV-08）：
 * 差异服务端计算、差异行原因必填、实时盘盈/盘亏汇总、未盘行按账面确认提交、
 * Excel 导入（编码/条码定位行、实盘留空跳过）。
 */
@ExtendWith(MockitoExtension.class)
class StockCheckEntryServiceTest {

    @Mock private StockCheckOrderMapper orderMapper;
    @Mock private StockCheckItemMapper itemMapper;
    @Mock private com.smms.inv.mapper.InventoryMapper inventoryMapper;
    @Mock private com.smms.inv.mapper.InventoryRecordMapper inventoryRecordMapper;
    @Mock private CategoryMapper categoryMapper;
    @Mock private GoodsMapper goodsMapper;
    @Mock private SysDictService dictService;
    @Mock private DutySeparationService dutySeparationService;

    private StockCheckService service;
    private StockCheckExcelService excelService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Goods.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), StockCheckOrder.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), StockCheckItem.class);
        service = new StockCheckService(orderMapper, itemMapper, inventoryMapper,
                inventoryRecordMapper, categoryMapper, goodsMapper, dictService, dutySeparationService);
        excelService = new StockCheckExcelService(itemMapper, service, dictService);
    }

    /** 差异原因字典（FR-SYS-07/GAP-P1-09）：代码集 + 名称映射（Excel 名称归一用）；lenient 供不同用例按需触发 */
    private void stubDiffReasonDict() {
        lenient().when(dictService.enabledValues("check_diff_reason")).thenReturn(
                Set.of("COUNT_ERROR", "NATURAL_LOSS", "STEAL", "RECORD_ERROR", "OTHER"));
        lenient().when(dictService.enabledItems("check_diff_reason")).thenReturn(List.of(
                new DictVO.ItemVO(1L, "计数误差", "COUNT_ERROR", 1, 1, null),
                new DictVO.ItemVO(2L, "损耗", "NATURAL_LOSS", 2, 1, null),
                new DictVO.ItemVO(3L, "失窃", "STEAL", 3, 1, null),
                new DictVO.ItemVO(4L, "单据错误", "RECORD_ERROR", 4, 1, null),
                new DictVO.ItemVO(5L, "其他", "OTHER", 5, 1, null)));
    }

    // ---------- 造数 ----------

    private StockCheckOrder checkingOrder() {
        StockCheckOrder order = new StockCheckOrder();
        order.setId(1L);
        order.setOrderNo("PD202609190001");
        order.setStatus("CHECKING");
        order.setCheckerName("keeper");
        return order;
    }

    private StockCheckItem item(long goodsId, String book, String cost) {
        StockCheckItem item = new StockCheckItem();
        item.setId(goodsId * 100);
        item.setOrderId(1L);
        item.setGoodsId(goodsId);
        item.setBookQty(new BigDecimal(book));
        item.setCostPrice(new BigDecimal(cost));
        return item;
    }

    private Goods goods(long id, String name) {
        Goods g = new Goods();
        g.setId(id);
        g.setName(name);
        return g;
    }

    private CheckEntryRequest entry(Object... pairs) {
        // pairs: goodsId, actualQty, reason（三元组）
        List<CheckEntryRequest.Item> items = new java.util.ArrayList<>();
        for (int i = 0; i < pairs.length; i += 3) {
            items.add(new CheckEntryRequest.Item((Long) pairs[i], (BigDecimal) pairs[i + 1],
                    (String) pairs[i + 2], null));
        }
        return new CheckEntryRequest(items);
    }

    // ---------- 录入（FR-INV-08） ----------

    @Test
    @DisplayName("录入：差异由服务端按实盘-账面计算，差异金额=差异×快照成本（2位），并实时汇总盈亏")
    void enter_serverSideDiff() {
        stubDiffReasonDict();
        when(orderMapper.selectById(1L)).thenReturn(checkingOrder());
        when(itemMapper.selectList(any())).thenReturn(
                List.of(item(9L, "10", "2.5"), item(10L, "5", "4.00")),
                // refreshTotals 重查：9 盘盈 2（金额 5.00）、10 盘亏 3（金额 12.00）
                List.of(entered(9L, "10", "2.5", "12", "5.00", "COUNT_ERROR"),
                        entered(10L, "5", "4.00", "2", "-12.00", "NATURAL_LOSS")));
        when(goodsMapper.selectBatchIds(anyList())).thenReturn(
                List.of(goods(9L, "可乐"), goods(10L, "纸巾")));

        CheckSummaryVO summary = service.enter(1L,
                entry(9L, new BigDecimal("12"), "COUNT_ERROR",
                        10L, new BigDecimal("2"), "NATURAL_LOSS"));

        assertThat(summary.enteredCount()).isEqualTo(2);
        assertThat(summary.diffCount()).isEqualTo(2);
        assertThat(summary.surplusAmount()).isEqualByComparingTo("5.00");
        assertThat(summary.shortageAmount()).isEqualByComparingTo("12.00");
        // 两行各更新一次明细
        verify(itemMapper, org.mockito.Mockito.times(2)).update(eq(null), any());
        // 汇总回写一次
        verify(orderMapper).update(eq(null), any());
    }

    private StockCheckItem entered(long goodsId, String book, String cost, String actual,
                                   String diffAmount, String reason) {
        StockCheckItem item = item(goodsId, book, cost);
        item.setActualQty(new BigDecimal(actual));
        item.setDiffQty(new BigDecimal(actual).subtract(new BigDecimal(book)));
        item.setDiffAmount(new BigDecimal(diffAmount));
        item.setDiffReason(reason);
        return item;
    }

    @Test
    @DisplayName("录入：差异≠0 且未填原因时拒绝，提示实盘/账面数值（FR-INV-08 差异行必须填写原因）")
    void enter_diffWithoutReasonRejected() {
        when(orderMapper.selectById(1L)).thenReturn(checkingOrder());
        when(itemMapper.selectList(any())).thenReturn(List.of(item(9L, "10", "2.5")));
        when(goodsMapper.selectBatchIds(anyList())).thenReturn(List.of(goods(9L, "可乐")));

        assertThatThrownBy(() -> service.enter(1L, entry(9L, new BigDecimal("8"), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("可乐")
                .hasMessageContaining("差异原因");

        assertThatThrownBy(() -> service.enter(1L, entry(9L, new BigDecimal("8"), " ")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须填写差异原因");
        verify(itemMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("GAP-P1-09：差异原因须为启用中的字典代码；非字典值/停用项拒绝，字典未初始化报错")
    void enter_diffReasonMustBeEnabledDictCode() {
        when(orderMapper.selectById(1L)).thenReturn(checkingOrder());
        when(itemMapper.selectList(any())).thenReturn(List.of(item(9L, "10", "2.5")));
        when(goodsMapper.selectBatchIds(anyList())).thenReturn(List.of(goods(9L, "可乐")));

        // 非法代码（含手填中文名称）拒绝
        when(dictService.enabledValues("check_diff_reason")).thenReturn(Set.of("COUNT_ERROR", "OTHER"));
        assertThatThrownBy(() -> service.enter(1L, entry(9L, new BigDecimal("8"), "手填原因")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("差异原因不合法");
        verify(itemMapper, never()).update(any(), any());

        // 字典未初始化（整体缺失/停用）报系统错误
        when(dictService.enabledValues("check_diff_reason")).thenReturn(Set.of());
        assertThatThrownBy(() -> service.enter(1L, entry(9L, new BigDecimal("8"), "COUNT_ERROR")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字典未初始化");
    }

    @Test
    @DisplayName("录入：仅盘点中状态可录入；实盘与账面一致时无需原因")
    void enter_statusGuard_and_zeroDiffNoReason() {
        StockCheckOrder audited = checkingOrder();
        audited.setStatus("AUDITED");
        when(orderMapper.selectById(1L)).thenReturn(audited);

        assertThatThrownBy(() -> service.enter(1L, entry(9L, BigDecimal.TEN, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅盘点中的单据可录入实盘数");

        when(orderMapper.selectById(1L)).thenReturn(checkingOrder());
        when(itemMapper.selectList(any())).thenReturn(List.of(item(9L, "10", "2.5")),
                List.of(entered(9L, "10", "2.5", "10", "0.00", null)));
        when(goodsMapper.selectBatchIds(anyList())).thenReturn(List.of(goods(9L, "可乐")));

        // 实盘=账面 → 差异 0，无需原因
        CheckSummaryVO summary = service.enter(1L, entry(9L, new BigDecimal("10"), null));
        assertThat(summary.diffCount()).isZero();
    }

    @Test
    @DisplayName("录入：商品不在盘点范围内 / 重复录入 / 小数位超限 时拒绝")
    void enter_lineValidation() {
        when(orderMapper.selectById(1L)).thenReturn(checkingOrder());
        when(itemMapper.selectList(any())).thenReturn(List.of(item(9L, "10", "2.5")));
        when(goodsMapper.selectBatchIds(anyList())).thenReturn(List.of(goods(9L, "可乐"), goods(10L, "纸巾")));

        assertThatThrownBy(() -> service.enter(1L, entry(10L, BigDecimal.ONE, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不在本次盘点范围内");
        assertThatThrownBy(() -> service.enter(1L,
                entry(9L, new BigDecimal("10"), null, 9L, new BigDecimal("11"), "x")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复录入");
        assertThatThrownBy(() -> service.enter(1L, entry(9L, new BigDecimal("1.2345"), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多 3 位小数");
    }

    // ---------- 提交审核（FR-INV-08） ----------

    @Test
    @DisplayName("提交：未盘行按账面确认后置待审核，差异行原因复核通过")
    void submit_success() {
        when(orderMapper.selectById(1L)).thenReturn(checkingOrder());
        when(itemMapper.confirmUnenteredAsBook(1L)).thenReturn(3);
        when(itemMapper.selectCount(any())).thenReturn(0L);
        when(itemMapper.selectList(any())).thenReturn(List.of(
                entered(9L, "10", "2.5", "12", "5.00", "理货补录"),
                entered(10L, "5", "4.00", "5", "0.00", null)));
        when(orderMapper.update(eq(null), any())).thenReturn(1);

        CheckSummaryVO summary = service.submit(1L);

        verify(itemMapper).confirmUnenteredAsBook(1L);
        assertThat(summary.diffCount()).isEqualTo(1);
        assertThat(summary.surplusAmount()).isEqualByComparingTo("5.00");
        // 汇总回写 + 状态置待审核，共两次单头更新
        verify(orderMapper, org.mockito.Mockito.times(2)).update(eq(null), any());
    }

    @Test
    @DisplayName("提交：存在差异行未填原因时拒绝（防御：正常录入路径已强制）")
    void submit_missingReasonRejected() {
        when(orderMapper.selectById(1L)).thenReturn(checkingOrder());
        when(itemMapper.confirmUnenteredAsBook(1L)).thenReturn(0);
        when(itemMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.submit(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2 行差异未填写原因");
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("提交：仅盘点中可提交；条件更新失败（并发状态变化）返回冲突")
    void submit_statusGuard_andConcurrentConflict() {
        StockCheckOrder pending = checkingOrder();
        pending.setStatus("PENDING_AUDIT");
        when(orderMapper.selectById(1L)).thenReturn(pending);

        assertThatThrownBy(() -> service.submit(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅盘点中的单据可提交审核");

        when(orderMapper.selectById(1L)).thenReturn(checkingOrder());
        when(itemMapper.confirmUnenteredAsBook(1L)).thenReturn(0);
        when(itemMapper.selectCount(any())).thenReturn(0L);
        when(itemMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.update(eq(null), any())).thenReturn(0);

        assertThatThrownBy(() -> service.submit(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请刷新后重试");
    }

    // ---------- Excel 导入（FR-INV-08） ----------

    @Test
    @DisplayName("Excel 导入：编码/条码定位行，实盘留空跳过，复用统一录入入口")
    void excelImport_matchesByCodeOrBarcode() throws IOException {
        stubDiffReasonDict();
        CheckItemVO vo = new CheckItemVO(1L, 9L, "01001", "6901234567890", "可乐",
                5L, "饮料", "罐", "330ml", 0,
                new BigDecimal("10"), null, null, new BigDecimal("2.5"), null, null, null);
        when(itemMapper.selectAllItems(1L)).thenReturn(List.of(vo));
        when(orderMapper.selectById(1L)).thenReturn(checkingOrder());
        when(itemMapper.selectList(any())).thenReturn(List.of(item(9L, "10", "2.5")),
                List.of(entered(9L, "10", "2.5", "12", "5.00", "COUNT_ERROR")));
        when(goodsMapper.selectBatchIds(anyList())).thenReturn(List.of(goods(9L, "可乐")));

        byte[] file = xlsx(new Object[][]{
                {"商品编码", "商品条码", "商品名称", "单位", "账面数量", "实盘数量", "差异原因", "备注"},
                {"01001", "6901234567890", "可乐", "罐", "10", "12", "计数误差", ""},
                {"01001", "6901234567890", "可乐", "罐", "10", "", "", ""},   // 实盘留空：跳过
        });

        CheckImportVO result = excelService.importActual(1L, new ByteArrayInputStream(file));

        assertThat(result.importedCount()).isEqualTo(1);
        assertThat(result.summary().surplusAmount()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("Excel 导入：编码不在范围内 / 实盘为非数字 时拒绝且不写库")
    void excelImport_invalidRows() throws IOException {
        CheckItemVO vo = new CheckItemVO(1L, 9L, "01001", "6901234567890", "可乐",
                5L, "饮料", "罐", "330ml", 0,
                new BigDecimal("10"), null, null, new BigDecimal("2.5"), null, null, null);
        when(itemMapper.selectAllItems(1L)).thenReturn(List.of(vo));

        byte[] unknownCode = xlsx(new Object[][]{
                {"商品编码", "商品条码", "商品名称", "单位", "账面数量", "实盘数量", "差异原因", "备注"},
                {"99999", "", "无", "罐", "1", "2", "", ""},
        });
        assertThatThrownBy(() -> excelService.importActual(1L, new ByteArrayInputStream(unknownCode)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不在本次盘点范围内");

        byte[] badNumber = xlsx(new Object[][]{
                {"商品编码", "商品条码", "商品名称", "单位", "账面数量", "实盘数量", "差异原因", "备注"},
                {"01001", "6901234567890", "可乐", "罐", "10", "abc", "", ""},
        });
        assertThatThrownBy(() -> excelService.importActual(1L, new ByteArrayInputStream(badNumber)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不是合法数字");

        byte[] allBlank = xlsx(new Object[][]{
                {"商品编码", "商品条码", "商品名称", "单位", "账面数量", "实盘数量", "差异原因", "备注"},
                {"01001", "6901234567890", "可乐", "罐", "10", "", "", ""},
        });
        assertThatThrownBy(() -> excelService.importActual(1L, new ByteArrayInputStream(allBlank)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有可导入的实盘数量");
    }

    /** 构造 xlsx 测试文件：数值列写数字、其余写字符串 */
    private byte[] xlsx(Object[][] rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("盘点录入");
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v instanceof Number n) {
                        row.createCell(c).setCellValue(n.doubleValue());
                    } else {
                        row.createCell(c).setCellValue(v == null ? "" : v.toString());
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}

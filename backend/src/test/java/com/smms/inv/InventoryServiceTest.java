package com.smms.inv;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.goods.entity.Category;
import com.smms.goods.entity.Goods;
import com.smms.goods.mapper.CategoryMapper;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.inv.dto.AlertItemVO;
import com.smms.inv.dto.AlertPageVO;
import com.smms.inv.dto.AlertQuery;
import com.smms.inv.dto.AlertSummaryVO;
import com.smms.inv.dto.InventoryItemVO;
import com.smms.inv.dto.InventoryPageVO;
import com.smms.inv.dto.InventoryQuery;
import com.smms.inv.dto.InventoryRecordQuery;
import com.smms.inv.dto.InventoryRecordVO;
import com.smms.inv.dto.StockChange;
import com.smms.inv.entity.Inventory;
import com.smms.inv.entity.InventoryRecord;
import com.smms.inv.mapper.InventoryMapper;
import com.smms.inv.mapper.InventoryRecordMapper;
import com.smms.inv.service.InventoryService;
import com.smms.sys.service.SysConfigService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存核心服务单元测试：覆盖 BR-INV-02（负库存策略）、BR-INV-03（原子条件扣减）、
 * BR-INV-04（不可变流水）、BR-INV-05（数量符号）、BR-INV-06（移动加权平均成本）、
 * FR-INV-01（实时库存+合计）、FR-INV-03（流水查询时间区间）。
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    private static final long GOODS_ID = 9L;
    private static final String KEY_ALLOW_NEGATIVE = "allow_negative_stock";

    @Mock private InventoryMapper inventoryMapper;
    @Mock private InventoryRecordMapper inventoryRecordMapper;
    @Mock private CategoryMapper categoryMapper;
    @Mock private GoodsMapper goodsMapper;
    @Mock private SysConfigService configService;

    private InventoryService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Category.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Goods.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Inventory.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InventoryRecord.class);
        service = new InventoryService(inventoryMapper, inventoryRecordMapper,
                categoryMapper, goodsMapper, configService);
    }

    // ---------- 造数 ----------

    private Inventory row(String quantity, String costPrice) {
        Inventory inv = new Inventory();
        inv.setGoodsId(GOODS_ID);
        inv.setStoreId(1L);
        inv.setQuantity(new BigDecimal(quantity));
        inv.setCostPrice(new BigDecimal(costPrice));
        return inv;
    }

    private StockChange out(String type, String qty) {
        return new StockChange(GOODS_ID, type, new BigDecimal(qty), null, "XS", "XS202609190001", "cashier01", null);
    }

    private StockChange in(String type, String qty, String unitCost) {
        return new StockChange(GOODS_ID, type, new BigDecimal(qty),
                unitCost == null ? null : new BigDecimal(unitCost), "RK", "RK202609190001", "keeper01", null);
    }

    private InventoryRecord capturedRecord() {
        ArgumentCaptor<InventoryRecord> captor = ArgumentCaptor.forClass(InventoryRecord.class);
        verify(inventoryRecordMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ---------- 出库（BR-INV-02/03/05/06） ----------

    @Test
    @DisplayName("出库：库存充足时原子扣减并写流水，成本按当前成本快照")
    void stockOut_success() {
        when(configService.getBool(KEY_ALLOW_NEGATIVE, false)).thenReturn(false);
        when(inventoryMapper.decreaseStock(eq(GOODS_ID), eq(1L), eq(new BigDecimal("2")), eq(false))).thenReturn(1);
        when(inventoryMapper.selectById(GOODS_ID)).thenReturn(row("3", "2.5"));

        service.stockOut(out("SALE_OUT", "2"));

        InventoryRecord r = capturedRecord();
        assertThat(r.getRecordType()).isEqualTo("SALE_OUT");
        assertThat(r.getChangeQty()).isEqualByComparingTo("-2");
        assertThat(r.getBeforeQty()).isEqualByComparingTo("5");
        assertThat(r.getAfterQty()).isEqualByComparingTo("3");
        assertThat(r.getUnitCost()).isEqualByComparingTo("2.5");
        assertThat(r.getBizNo()).isEqualTo("XS202609190001");
        assertThat(r.getOperatorName()).isEqualTo("cashier01");
    }

    @Test
    @DisplayName("出库：库存不足且禁止负库存时拒绝，提示含商品名与可售数，不写流水（BR-INV-02）")
    void stockOut_insufficient_rejected() {
        when(configService.getBool(KEY_ALLOW_NEGATIVE, false)).thenReturn(false);
        when(inventoryMapper.decreaseStock(eq(GOODS_ID), eq(1L), any(BigDecimal.class), eq(false))).thenReturn(0);
        when(inventoryMapper.selectById(GOODS_ID)).thenReturn(row("3", "2.5"));
        Goods goods = new Goods();
        goods.setId(GOODS_ID);
        goods.setName("可乐 330ml");
        when(goodsMapper.selectById(GOODS_ID)).thenReturn(goods);

        assertThatThrownBy(() -> service.stockOut(out("SALE_OUT", "5")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("可乐 330ml")
                .hasMessageContaining("3");

        verify(inventoryRecordMapper, never()).insert(any(InventoryRecord.class));
        verify(inventoryMapper, never()).ensureRow(anyLong(), anyLong());
    }

    @Test
    @DisplayName("出库：允许负库存时无条件扣减，行缺失则补 0 行后扣成负数（BR-INV-02 先卖后补录）")
    void stockOut_insufficient_negativeAllowed() {
        when(configService.getBool(KEY_ALLOW_NEGATIVE, false)).thenReturn(true);
        // 允许负库存时扣减不带余量条件：首次受影响行数=0 只可能是结存行不存在
        when(inventoryMapper.decreaseStock(eq(GOODS_ID), eq(1L), eq(new BigDecimal("2")), eq(true)))
                .thenReturn(0).thenReturn(1);
        // 第一次读（余量提示用）无行；补行扣减后读到 -2
        when(inventoryMapper.selectById(GOODS_ID)).thenReturn(null).thenReturn(row("-2", "0"));

        service.stockOut(out("SALE_OUT", "2"));

        verify(inventoryMapper).ensureRow(GOODS_ID, 1L);
        // 首次尝试（行不存在，受影响 0）+ 补行后重试，共两次
        verify(inventoryMapper, times(2)).decreaseStock(eq(GOODS_ID), eq(1L), eq(new BigDecimal("2")), eq(true));
        InventoryRecord r = capturedRecord();
        assertThat(r.getChangeQty()).isEqualByComparingTo("-2");
        assertThat(r.getBeforeQty()).isEqualByComparingTo("0");
        assertThat(r.getAfterQty()).isEqualByComparingTo("-2");
    }

    // ---------- 入库与移动加权平均成本（BR-INV-06） ----------

    @Test
    @DisplayName("采购入库：移动加权平均重算 (10×2+5×3)/15=2.3333，成本金额 35.00")
    void stockIn_purchase_recalcWeighted() {
        when(inventoryMapper.selectByGoodsIdForUpdate(GOODS_ID, 1L)).thenReturn(row("10", "2"));

        service.stockIn(in("PURCHASE_IN", "5", "3"));

        verify(inventoryMapper).updateQuantityAndCost(eq(GOODS_ID), eq(1L),
                eq(new BigDecimal("15")), eq(new BigDecimal("2.3333")), eq(new BigDecimal("35.00")));
        InventoryRecord r = capturedRecord();
        assertThat(r.getRecordType()).isEqualTo("PURCHASE_IN");
        assertThat(r.getChangeQty()).isEqualByComparingTo("5");
        assertThat(r.getBeforeQty()).isEqualByComparingTo("10");
        assertThat(r.getAfterQty()).isEqualByComparingTo("15");
        assertThat(r.getUnitCost()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("采购入库：原结存为 0 时直接取本次入库单价（BR-INV-06）")
    void stockIn_purchase_zeroStock_takesInCost() {
        when(inventoryMapper.selectByGoodsIdForUpdate(GOODS_ID, 1L)).thenReturn(row("0", "5"));

        service.stockIn(in("PURCHASE_IN", "5", "3"));

        verify(inventoryMapper).updateQuantityAndCost(eq(GOODS_ID), eq(1L),
                eq(new BigDecimal("5")), eq(new BigDecimal("3.0000")), eq(new BigDecimal("15.00")));
    }

    @Test
    @DisplayName("采购入库：原结存为负时直接取本次入库单价（BR-INV-06）")
    void stockIn_purchase_negativeStock_takesInCost() {
        when(inventoryMapper.selectByGoodsIdForUpdate(GOODS_ID, 1L)).thenReturn(row("-2", "5"));

        service.stockIn(in("PURCHASE_IN", "5", "3"));

        verify(inventoryMapper).updateQuantityAndCost(eq(GOODS_ID), eq(1L),
                eq(new BigDecimal("3")), eq(new BigDecimal("3.0000")), eq(new BigDecimal("9.00")));
        InventoryRecord r = capturedRecord();
        assertThat(r.getBeforeQty()).isEqualByComparingTo("-2");
        assertThat(r.getAfterQty()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("销售退货：仅回冲数量不重算成本，流水成本取原销售明细快照（BR-INV-06）")
    void stockIn_saleReturn_noRecalc() {
        when(inventoryMapper.increaseStock(eq(GOODS_ID), eq(1L), eq(new BigDecimal("2")))).thenReturn(1);
        when(inventoryMapper.selectById(GOODS_ID)).thenReturn(row("7", "2.5"));

        service.stockIn(in("SALE_RETURN", "2", "2"));

        verify(inventoryMapper, never()).selectByGoodsIdForUpdate(anyLong(), anyLong());
        verify(inventoryMapper, never()).updateQuantityAndCost(anyLong(), anyLong(), any(), any(), any());
        InventoryRecord r = capturedRecord();
        assertThat(r.getChangeQty()).isEqualByComparingTo("2");
        assertThat(r.getBeforeQty()).isEqualByComparingTo("5");
        assertThat(r.getAfterQty()).isEqualByComparingTo("7");
        assertThat(r.getUnitCost()).isEqualByComparingTo("2");
    }

    // ---------- 参数校验 ----------

    @Test
    @DisplayName("变动类型不在统一入口范围内时拒绝（INIT/CHECK_* 走各自专用路径，FR-INV-02）")
    void validate_typeRejected() {
        assertThatThrownBy(() -> service.stockIn(in("INIT", "5", "3")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的库存变动类型");
        assertThatThrownBy(() -> service.stockOut(out("CHECK_SHORTAGE", "5")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的库存变动类型");
    }

    @Test
    @DisplayName("变动数量必须大于 0 且最多 3 位小数")
    void validate_qtyInvalid() {
        assertThatThrownBy(() -> service.stockOut(out("SALE_OUT", "0")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("变动数量必须大于 0");
        assertThatThrownBy(() -> service.stockOut(out("LOSS_OUT", "1.2345")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多 3 位小数");
    }

    @Test
    @DisplayName("成本重算类入库必须带单价；操作人必填")
    void validate_costAndOperator() {
        assertThatThrownBy(() -> service.stockIn(in("PURCHASE_IN", "5", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("入库单价不能为空");
        StockChange noOperator = new StockChange(GOODS_ID, "SALE_OUT", BigDecimal.ONE, null, "XS", "XS1", " ", null);
        assertThatThrownBy(() -> service.stockOut(noOperator))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("操作人不能为空");
    }

    // ---------- 实时库存查询（FR-INV-01） ----------

    @Test
    @DisplayName("实时库存分页：分类按子树过滤，合计行返回库存总成本金额")
    void page_withSummary() {
        Category root = new Category();
        root.setId(1L);
        root.setParentId(0L);
        root.setPath("1");
        Category selected = new Category();
        selected.setId(5L);
        selected.setParentId(1L);
        selected.setPath("1/5");
        Category child = new Category();
        child.setId(9L);
        child.setParentId(5L);
        child.setPath("1/5/9");
        when(categoryMapper.selectById(5L)).thenReturn(selected);
        when(categoryMapper.selectList(any())).thenReturn(List.of(root, selected, child));

        Page<InventoryItemVO> pageResult = new Page<>(1, 20);
        pageResult.setRecords(List.of());
        pageResult.setTotal(3);
        when(inventoryMapper.selectInventoryPage(any(), any(), anyList())).thenReturn(pageResult);
        when(inventoryMapper.selectInventorySummary(any(), anyList())).thenReturn(new BigDecimal("123.45"));

        InventoryPageVO vo = service.page(new InventoryQuery(null, null, 5L, null, 1, 20, null, null));

        assertThat(vo.total()).isEqualTo(3);
        assertThat(vo.totalCostAmount()).isEqualByComparingTo("123.45");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(inventoryMapper).selectInventoryPage(any(), any(), idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(5L, 9L);
    }

    // ---------- 库存流水查询（FR-INV-03） ----------

    @Test
    @DisplayName("流水查询：日期区间换算为闭区间日粒度时间戳 [当日00:00, 次日00:00)")
    void recordPage_dateRange() {
        Page<InventoryRecordVO> pageResult = new Page<>(1, 20);
        pageResult.setRecords(List.of());
        pageResult.setTotal(0);
        when(inventoryRecordMapper.selectRecordPage(any(), any(), any(), any())).thenReturn(pageResult);

        PageVO<InventoryRecordVO> vo = service.recordPage(new InventoryRecordQuery(
                GOODS_ID, null, "SALE_OUT", null, null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 19), 1, 20));

        assertThat(vo.total()).isZero();
        verify(inventoryRecordMapper).selectRecordPage(any(), any(),
                eq(LocalDateTime.of(2026, 9, 1, 0, 0)),
                eq(LocalDateTime.of(2026, 9, 20, 0, 0)));
    }

    // ---------- 库存预警查询（FR-INV-05） ----------

    @Test
    @DisplayName("预警分页：分类按子树过滤，汇总忽略类型条件（页签切换不跳动）")
    void alertPage_withSummary() {
        Category root = new Category();
        root.setId(1L);
        root.setParentId(0L);
        root.setPath("1");
        Category selected = new Category();
        selected.setId(5L);
        selected.setParentId(1L);
        selected.setPath("1/5");
        Category child = new Category();
        child.setId(9L);
        child.setParentId(5L);
        child.setPath("1/5/9");
        when(categoryMapper.selectById(5L)).thenReturn(selected);
        when(categoryMapper.selectList(any())).thenReturn(List.of(root, selected, child));

        Page<AlertItemVO> pageResult = new Page<>(1, 20);
        pageResult.setRecords(List.of());
        pageResult.setTotal(7);
        when(inventoryMapper.selectAlertPage(any(), any(), anyList())).thenReturn(pageResult);
        when(inventoryMapper.selectAlertSummary(any(), anyList())).thenReturn(new AlertSummaryVO(2, 3, 4));

        AlertPageVO vo = service.alertPage(new AlertQuery("可乐", null, 5L, "OUT", 0, 0, "gap", "desc"));

        assertThat(vo.total()).isEqualTo(7);
        assertThat(vo.summary().outCount()).isEqualTo(2);
        assertThat(vo.summary().warningCount()).isEqualTo(3);
        assertThat(vo.summary().overstockCount()).isEqualTo(4);
        assertThat(vo.summary().totalCount()).isEqualTo(9);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(inventoryMapper).selectAlertPage(any(), any(), idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(5L, 9L);
        // 汇总查询保留名称/分类条件但去掉类型条件
        ArgumentCaptor<AlertQuery> summaryCaptor = ArgumentCaptor.forClass(AlertQuery.class);
        verify(inventoryMapper).selectAlertSummary(summaryCaptor.capture(), anyList());
        assertThat(summaryCaptor.getValue().alertType()).isNull();
        assertThat(summaryCaptor.getValue().name()).isEqualTo("可乐");
        assertThat(summaryCaptor.getValue().categoryId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("预警汇总：看板角标口径（FR-RPT-01），无分类条件时不解析子树")
    void alertSummary_badge() {
        when(inventoryMapper.selectAlertSummary(any(), any())).thenReturn(new AlertSummaryVO(1, 0, 0));

        AlertSummaryVO vo = service.alertSummary(new AlertQuery(null, null, null, null, 1, 1, null, null));

        assertThat(vo.totalCount()).isEqualTo(1);
        verify(categoryMapper, never()).selectById(anyLong());
    }
}

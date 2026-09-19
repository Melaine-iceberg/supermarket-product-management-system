package com.smms.inv;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smms.common.exception.BusinessException;
import com.smms.goods.entity.Category;
import com.smms.goods.entity.Goods;
import com.smms.goods.mapper.CategoryMapper;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.inv.dto.CategoryThresholdRequest;
import com.smms.inv.dto.SafetyStockItem;
import com.smms.inv.dto.SafetyStockUpdateRequest;
import com.smms.inv.service.SafetyStockService;
import com.smms.sys.entity.OperationLog;
import com.smms.sys.service.OperationLogService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 安全库存阈值维护单元测试（FR-INV-06）：
 * 单个/批量设置与按分类批量设置默认阈值的校验（BR-GOODS-03 数量约束）、
 * 未变化跳过、逐商品留痕（操作人、前值、后值）。
 */
@ExtendWith(MockitoExtension.class)
class SafetyStockServiceTest {

    private static final long GOODS_ID = 9L;

    @Mock private GoodsMapper goodsMapper;
    @Mock private CategoryMapper categoryMapper;
    @Mock private OperationLogService operationLogService;

    private SafetyStockService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Category.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Goods.class);
        service = new SafetyStockService(goodsMapper, categoryMapper, operationLogService);
    }

    // ---------- 造数 ----------

    private Goods goods(Integer isWeighed, String lower, String upper) {
        Goods g = new Goods();
        g.setId(GOODS_ID);
        g.setGoodsCode("010010001");
        g.setName("可乐 330ml");
        g.setIsWeighed(isWeighed);
        g.setSafetyStockLower(lower == null ? null : new BigDecimal(lower));
        g.setSafetyStockUpper(upper == null ? null : new BigDecimal(upper));
        return g;
    }

    private SafetyStockUpdateRequest request(SafetyStockItem... items) {
        return new SafetyStockUpdateRequest(List.of(items));
    }

    // ---------- 单个/批量设置：校验（BR-GOODS-03） ----------

    @Test
    @DisplayName("批量设置：空清单与超过 500 个商品时拒绝")
    void updateItems_sizeRejected() {
        assertThatThrownBy(() -> service.updateItems(new SafetyStockUpdateRequest(List.of()), "manager01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请至少选择一个商品");
        List<SafetyStockItem> tooMany = new ArrayList<>(
                IntStream.rangeClosed(1, 501).mapToObj(i -> new SafetyStockItem((long) i, BigDecimal.TEN, null)).toList());
        assertThatThrownBy(() -> service.updateItems(new SafetyStockUpdateRequest(tooMany), "manager01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("单次最多维护 500 个商品");
    }

    @Test
    @DisplayName("批量设置：同一商品重复提交时拒绝")
    void updateItems_duplicateRejected() {
        when(goodsMapper.selectById(GOODS_ID)).thenReturn(goods(0, "5", null));

        assertThatThrownBy(() -> service.updateItems(request(
                new SafetyStockItem(GOODS_ID, new BigDecimal("10"), null),
                new SafetyStockItem(GOODS_ID, new BigDecimal("20"), null)), "manager01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同一商品不能重复提交");
    }

    @Test
    @DisplayName("批量设置：商品不存在、下限缺失/负数、精度超限时拒绝")
    void updateItems_invalidValues() {
        when(goodsMapper.selectById(GOODS_ID)).thenReturn(goods(0, "5", null));
        when(goodsMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.updateItems(request(
                new SafetyStockItem(999L, BigDecimal.TEN, null)), "manager01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("商品不存在");
        assertThatThrownBy(() -> service.updateItems(request(
                new SafetyStockItem(GOODS_ID, null, null)), "manager01"))
                .hasMessageContaining("安全库存下限不能为空");
        assertThatThrownBy(() -> service.updateItems(request(
                new SafetyStockItem(GOODS_ID, new BigDecimal("-1"), null)), "manager01"))
                .hasMessageContaining("安全库存下限不能为负数");
        assertThatThrownBy(() -> service.updateItems(request(
                new SafetyStockItem(GOODS_ID, new BigDecimal("10.1234"), null)), "manager01"))
                .hasMessageContaining("精度超限");
    }

    @Test
    @DisplayName("批量设置：上限小于下限、非称重商品小数阈值时拒绝（BR-GOODS-03）")
    void updateItems_upperAndIntegralRejected() {
        when(goodsMapper.selectById(GOODS_ID)).thenReturn(goods(0, "5", null));

        assertThatThrownBy(() -> service.updateItems(request(
                new SafetyStockItem(GOODS_ID, new BigDecimal("10"), new BigDecimal("5"))), "manager01"))
                .hasMessageContaining("库存上限不能小于安全库存下限");
        assertThatThrownBy(() -> service.updateItems(request(
                new SafetyStockItem(GOODS_ID, new BigDecimal("10.5"), null)), "manager01"))
                .hasMessageContaining("必须为整数")
                .hasMessageContaining("可乐 330ml");
    }

    // ---------- 单个/批量设置：更新与留痕（FR-INV-06） ----------

    @Test
    @DisplayName("批量设置：值变化时更新并留痕前值→后值，称重商品允许小数（BR-INV-12）")
    void updateItems_changed_updateAndLog() {
        when(goodsMapper.selectById(GOODS_ID)).thenReturn(goods(1, "5", "100"));

        int changed = service.updateItems(request(
                new SafetyStockItem(GOODS_ID, new BigDecimal("10.5"), new BigDecimal("120"))), "manager01");

        assertThat(changed).isEqualTo(1);
        verify(goodsMapper).updateSafetyStock(eq(GOODS_ID),
                eq(new BigDecimal("10.5")), eq(new BigDecimal("120")), eq("manager01"));
        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(operationLogService).recordAsync(captor.capture());
        OperationLog log = captor.getValue();
        assertThat(log.getUsername()).isEqualTo("manager01");
        assertThat(log.getModule()).isEqualTo("库存管理");
        assertThat(log.getAction()).isEqualTo("修改安全库存阈值");
        assertThat(log.getTarget()).isEqualTo("010010001");
        assertThat(log.getParamSummary()).contains("下限 5 → 10.5").contains("上限 100 → 120");
    }

    @Test
    @DisplayName("批量设置：值未变化（含 10 与 10.000 等值）时跳过，不更新不留痕")
    void updateItems_unchanged_skipped() {
        when(goodsMapper.selectById(GOODS_ID)).thenReturn(goods(0, "10", null));

        int changed = service.updateItems(request(
                new SafetyStockItem(GOODS_ID, new BigDecimal("10.000"), null)), "manager01");

        assertThat(changed).isZero();
        verify(goodsMapper, never()).updateSafetyStock(anyLong(), any(), any(), anyString());
        verify(operationLogService, never()).recordAsync(any());
    }

    @Test
    @DisplayName("批量设置：清空上限（upper=null）与部分变更均正确计数")
    void updateItems_mixed() {
        Goods a = goods(0, "5", "100");
        a.setId(9L);
        Goods b = goods(0, "10", null);
        b.setId(10L);
        when(goodsMapper.selectById(9L)).thenReturn(a);
        when(goodsMapper.selectById(10L)).thenReturn(b);

        int changed = service.updateItems(request(
                new SafetyStockItem(9L, new BigDecimal("5"), null),
                new SafetyStockItem(10L, new BigDecimal("20"), null)), "manager01");

        assertThat(changed).isEqualTo(2);
        verify(goodsMapper).updateSafetyStock(eq(9L), eq(new BigDecimal("5")), isNull(), eq("manager01"));
        verify(goodsMapper).updateSafetyStock(eq(10L), eq(new BigDecimal("20")), isNull(), eq("manager01"));
        verify(operationLogService, times(2)).recordAsync(any(OperationLog.class));
    }

    // ---------- 按分类批量设置（FR-INV-06） ----------

    private void mockCategoryTree() {
        Category selected = new Category();
        selected.setId(5L);
        selected.setParentId(1L);
        selected.setPath("1/5");
        Category child = new Category();
        child.setId(9L);
        child.setParentId(5L);
        child.setPath("1/5/9");
        Category root = new Category();
        root.setId(1L);
        root.setParentId(0L);
        root.setPath("1");
        when(categoryMapper.selectById(5L)).thenReturn(selected);
        when(categoryMapper.selectList(any())).thenReturn(List.of(root, selected, child));
    }

    @Test
    @DisplayName("按分类设置：未选分类/分类不存在时拒绝")
    void updateByCategory_categoryInvalid() {
        assertThatThrownBy(() -> service.updateByCategory(
                new CategoryThresholdRequest(null, BigDecimal.TEN, null), "manager01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请选择分类");

        when(categoryMapper.selectById(5L)).thenReturn(null);
        assertThatThrownBy(() -> service.updateByCategory(
                new CategoryThresholdRequest(5L, BigDecimal.TEN, null), "manager01"))
                .hasMessageContaining("所选分类不存在");
    }

    @Test
    @DisplayName("按分类设置：范围含子分类，仅更新阈值不同的商品并逐行留痕")
    void updateByCategory_subtreeUpdateAndLog() {
        mockCategoryTree();
        Goods changedA = goods(0, "5", "100");
        changedA.setId(11L);
        Goods changedB = goods(1, "2.5", null);
        changedB.setId(12L);
        when(goodsMapper.selectThresholdDiff(anyList(), eq(new BigDecimal("10")), eq(new BigDecimal("200"))))
                .thenReturn(List.of(changedA, changedB));

        int changed = service.updateByCategory(
                new CategoryThresholdRequest(5L, new BigDecimal("10"), new BigDecimal("200")), "manager01");

        assertThat(changed).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(categoryMapper).selectById(5L);
        verify(goodsMapper).selectThresholdDiff(idsCaptor.capture(),
                eq(new BigDecimal("10")), eq(new BigDecimal("200")));
        assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(5L, 9L);
        verify(goodsMapper).updateSafetyStockBatch(eq(List.of(11L, 12L)),
                eq(new BigDecimal("10")), eq(new BigDecimal("200")), eq("manager01"));
        verify(operationLogService, times(2)).recordAsync(any(OperationLog.class));
    }

    @Test
    @DisplayName("按分类设置：范围内全部商品阈值已相同则不更新（返回 0）")
    void updateByCategory_noDiff() {
        mockCategoryTree();
        when(goodsMapper.selectThresholdDiff(anyList(), any(), any())).thenReturn(List.of());

        int changed = service.updateByCategory(
                new CategoryThresholdRequest(5L, new BigDecimal("10"), null), "manager01");

        assertThat(changed).isZero();
        verify(goodsMapper, never()).updateSafetyStockBatch(anyList(), any(), any(), anyString());
        verify(operationLogService, never()).recordAsync(any());
    }

    @Test
    @DisplayName("按分类设置：范围含非称重商品而阈值为小数时整批拒绝，不写库")
    void updateByCategory_integralRejected() {
        mockCategoryTree();
        Goods plain = goods(0, "5", null);
        plain.setId(11L);
        when(goodsMapper.selectThresholdDiff(anyList(), eq(new BigDecimal("10.5")), isNull()))
                .thenReturn(List.of(plain));

        assertThatThrownBy(() -> service.updateByCategory(
                new CategoryThresholdRequest(5L, new BigDecimal("10.5"), null), "manager01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须为整数")
                .hasMessageContaining("可乐 330ml");

        verify(goodsMapper, never()).updateSafetyStockBatch(anyList(), any(), any(), anyString());
    }
}

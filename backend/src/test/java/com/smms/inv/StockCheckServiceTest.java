package com.smms.inv;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.goods.entity.Category;
import com.smms.goods.entity.Goods;
import com.smms.goods.mapper.CategoryMapper;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.inv.dto.CheckCreateRequest;
import com.smms.inv.dto.CheckItemVO;
import com.smms.inv.dto.CheckOrderDetailVO;
import com.smms.inv.dto.CheckOrderVO;
import com.smms.inv.dto.CheckQuery;
import com.smms.inv.dto.CheckScopeGoods;
import com.smms.inv.entity.StockCheckItem;
import com.smms.inv.entity.StockCheckOrder;
import com.smms.inv.mapper.StockCheckItemMapper;
import com.smms.inv.mapper.StockCheckOrderMapper;
import com.smms.inv.service.StockCheckService;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 盘点单创建/查询/作废单元测试（FR-INV-07）：
 * 覆盖范围解析、账面快照、BR-INV-09（同范围不可重复创建未审核盘点单）、
 * 单号流水生成、状态机约束（§2.2.4 仅盘点中可作废）。
 */
@ExtendWith(MockitoExtension.class)
class StockCheckServiceTest {

    private static final String CHECKER = "keeper";

    @Mock private StockCheckOrderMapper orderMapper;
    @Mock private StockCheckItemMapper itemMapper;
    @Mock private com.smms.inv.mapper.InventoryMapper inventoryMapper;
    @Mock private com.smms.inv.mapper.InventoryRecordMapper inventoryRecordMapper;
    @Mock private CategoryMapper categoryMapper;
    @Mock private GoodsMapper goodsMapper;

    private StockCheckService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Category.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Goods.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), StockCheckOrder.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), StockCheckItem.class);
        service = new StockCheckService(orderMapper, itemMapper, inventoryMapper,
                inventoryRecordMapper, categoryMapper, goodsMapper);
    }

    // ---------- 造数 ----------

    private Category cat(long id, String path) {
        Category c = new Category();
        c.setId(id);
        c.setParentId(1L);
        c.setPath(path);
        c.setName("分类" + id);
        return c;
    }

    private StockCheckOrder unaudited(long id, String orderNo) {
        StockCheckOrder o = new StockCheckOrder();
        o.setId(id);
        o.setOrderNo(orderNo);
        o.setStatus("CHECKING");
        o.setScopeDesc("分类：饮料（含子分类）");
        return o;
    }

    // ---------- 创建（FR-INV-07） ----------

    @Test
    @DisplayName("分类范围创建：解析子树→快照账面数与成本→CHECKING 状态落库，明细批量插入")
    void create_categoryScope_snapshotsBookQty() {
        when(categoryMapper.selectById(5L)).thenReturn(cat(5L, "1/5"));
        when(categoryMapper.selectList(any())).thenReturn(
                List.of(cat(1L, "1"), cat(5L, "1/5"), cat(9L, "1/5/9"), cat(7L, "1/7")));
        when(itemMapper.selectScopeGoods(eq("CATEGORY"), anyList(), isNull()))
                .thenReturn(List.of(new CheckScopeGoods(9L, new BigDecimal("10"), new BigDecimal("2.5")),
                        new CheckScopeGoods(10L, BigDecimal.ZERO, BigDecimal.ZERO)));
        when(orderMapper.selectList(any())).thenReturn(List.of());

        CheckOrderVO vo = service.create(
                new CheckCreateRequest("CATEGORY", 5L, null, "月中饮料抽盘"), CHECKER);

        assertThat(vo.orderNo()).matches("^PD\\d{8}\\d{4}$");
        ArgumentCaptor<StockCheckOrder> orderCaptor = ArgumentCaptor.forClass(StockCheckOrder.class);
        verify(orderMapper).insert(orderCaptor.capture());
        StockCheckOrder order = orderCaptor.getValue();
        assertThat(order.getStatus()).isEqualTo("CHECKING");
        assertThat(order.getCheckerName()).isEqualTo(CHECKER);
        assertThat(order.getItemCount()).isEqualTo(2);
        assertThat(order.getBaseTime()).isNotNull();
        assertThat(order.getStartTime()).isEqualTo(order.getBaseTime());
        assertThat(order.getScopeDesc()).contains("分类：分类5").contains("含子分类").contains("月中饮料抽盘");
        assertThat(order.getSurplusAmount()).isEqualByComparingTo("0");
        assertThat(order.getShortageAmount()).isEqualByComparingTo("0");

        ArgumentCaptor<List<StockCheckItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(itemMapper).insertBatch(itemsCaptor.capture());
        List<StockCheckItem> items = itemsCaptor.getValue();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).getGoodsId()).isEqualTo(9L);
        assertThat(items.get(0).getBookQty()).isEqualByComparingTo("10");
        assertThat(items.get(0).getCostPrice()).isEqualByComparingTo("2.5");
        assertThat(items.get(0).getActualQty()).isNull();
    }

    @Test
    @DisplayName("BR-INV-09：范围内存在未审核盘点单的明细商品时拒绝，提示已有单号，不落库")
    void create_overlapRejected() {
        when(categoryMapper.selectById(5L)).thenReturn(cat(5L, "1/5"));
        when(categoryMapper.selectList(any())).thenReturn(List.of(cat(5L, "1/5")));
        when(itemMapper.selectScopeGoods(eq("CATEGORY"), anyList(), isNull()))
                .thenReturn(List.of(new CheckScopeGoods(9L, BigDecimal.TEN, BigDecimal.ONE),
                        new CheckScopeGoods(10L, BigDecimal.TEN, BigDecimal.ONE)));
        when(orderMapper.selectList(any())).thenReturn(List.of(unaudited(1L, "PD202609190001")));
        when(itemMapper.selectGoodsIdsOfOrders(List.of(1L))).thenReturn(List.of(9L));

        assertThatThrownBy(() -> service.create(new CheckCreateRequest("CATEGORY", 5L, null, null), CHECKER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PD202609190001");

        verify(orderMapper, never()).insert(any(StockCheckOrder.class));
        verify(itemMapper, never()).insertBatch(anyList());
    }

    @Test
    @DisplayName("BR-INV-09：未审核单不重叠（或已全部审核完成）时放行创建")
    void create_noOverlap_passes() {
        when(categoryMapper.selectById(5L)).thenReturn(cat(5L, "1/5"));
        when(categoryMapper.selectList(any())).thenReturn(List.of(cat(5L, "1/5")));
        when(itemMapper.selectScopeGoods(eq("CATEGORY"), anyList(), isNull()))
                .thenReturn(List.of(new CheckScopeGoods(10L, BigDecimal.TEN, BigDecimal.ONE)));
        when(orderMapper.selectList(any())).thenReturn(List.of(unaudited(1L, "PD202609190001")));
        when(itemMapper.selectGoodsIdsOfOrders(List.of(1L))).thenReturn(List.of(99L));

        CheckOrderVO vo = service.create(new CheckCreateRequest("CATEGORY", 5L, null, null), CHECKER);

        assertThat(vo.status()).isEqualTo("CHECKING");
        verify(orderMapper).insert(any(StockCheckOrder.class));
    }

    @Test
    @DisplayName("指定商品范围：商品不存在时拒绝并列出缺失ID")
    void create_goodsScope_missingGoods() {
        Goods exists = new Goods();
        exists.setId(1L);
        when(goodsMapper.selectBatchIds(anyList())).thenReturn(List.of(exists));

        assertThatThrownBy(() -> service.create(
                new CheckCreateRequest("GOODS", null, List.of(1L, 2L), null), CHECKER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("商品不存在")
                .hasMessageContaining("2");
    }

    @Test
    @DisplayName("范围内没有商品时拒绝（CHECK_SCOPE_EMPTY），如预警商品全处于正常状态")
    void create_emptyScopeRejected() {
        when(itemMapper.selectScopeGoods(eq("WARNING"), isNull(), isNull())).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(new CheckCreateRequest("WARNING", null, null, null), CHECKER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有可盘商品");
    }

    @Test
    @DisplayName("不支持的盘点范围类型拒绝")
    void create_invalidScopeType() {
        assertThatThrownBy(() -> service.create(new CheckCreateRequest("SHOP", null, null, null), CHECKER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的盘点范围类型");
    }

    @Test
    @DisplayName("单号流水：当日已有 2 张时取最大流水 +1（PD+yyyyMMdd+4位）")
    void create_orderNoTakesMaxPlusOne() {
        when(itemMapper.selectScopeGoods(eq("WARNING"), isNull(), isNull()))
                .thenReturn(List.of(new CheckScopeGoods(1L, BigDecimal.ZERO, BigDecimal.ZERO)));
        // selectList 共两处调用：①未审核单查询（BR-INV-09）返回昨日未审核单；
        // ②单号生成查询（当日同前缀）返回已有 2 张 → 新单取最大流水 +1 = 0003
        when(orderMapper.selectList(any())).thenReturn(
                List.of(unaudited(11L, "PD202609180002")), List.of(
                        unaudited(21L, "PD202609190001"),
                        unaudited(22L, "PD202609190002")));
        // 昨日未审核单明细与本范围无交集
        when(itemMapper.selectGoodsIdsOfOrders(List.of(11L))).thenReturn(List.of(99L));

        CheckOrderVO vo = service.create(new CheckCreateRequest("WARNING", null, null, null), CHECKER);

        String today = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        assertThat(vo.orderNo()).isEqualTo("PD" + today + "0003");
    }

    // ---------- 查询 ----------

    @Test
    @DisplayName("详情：单头 + 明细分页，录入进度与差异行数由明细统计")
    void detail_withItemPage() {
        StockCheckOrder order = unaudited(1L, "PD202609190001");
        order.setScopeType("CATEGORY");
        order.setItemCount(2);
        when(orderMapper.selectById(1L)).thenReturn(order);
        when(itemMapper.selectCount(any())).thenReturn(1L, 0L);
        Page<CheckItemVO> itemPage = new Page<>(1, 20);
        itemPage.setRecords(List.of(new CheckItemVO(1L, 9L, "01001", "6901234567890",
                "可乐 330ml", 5L, "饮料", "罐", "330ml", 0,
                new BigDecimal("10"), null, null, new BigDecimal("2.5"), null, null, null)));
        itemPage.setTotal(1);
        when(itemMapper.selectItemPage(any(), eq(1L), eq(false), isNull())).thenReturn(itemPage);

        CheckOrderDetailVO detail = service.detail(1L, 1, 20, false, null);

        assertThat(detail.order().orderNo()).isEqualTo("PD202609190001");
        assertThat(detail.order().enteredCount()).isEqualTo(1);
        assertThat(detail.order().diffCount()).isZero();
        assertThat(detail.items().total()).isEqualTo(1);
        assertThat(detail.items().list().get(0).name()).isEqualTo("可乐 330ml");
    }

    @Test
    @DisplayName("分页：时间区间换算为 [beginDate 00:00, endDate+1 00:00)")
    void page_dateRange() {
        Page<CheckOrderVO> pageResult = new Page<>(1, 20);
        pageResult.setRecords(List.of());
        pageResult.setTotal(0);
        when(orderMapper.selectCheckPage(any(), any(),
                eq(java.time.LocalDateTime.of(2026, 9, 1, 0, 0)),
                eq(java.time.LocalDateTime.of(2026, 9, 20, 0, 0)))).thenReturn(pageResult);

        PageVO<CheckOrderVO> vo = service.page(new CheckQuery(
                null, "CHECKING", null, null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 19), 1, 20));

        assertThat(vo.total()).isZero();
    }

    // ---------- 作废（§2.2.4） ----------

    @Test
    @DisplayName("作废：盘点中单据作废成功，置 CANCELLED 并记录原因")
    void cancel_checking_success() {
        StockCheckOrder order = unaudited(1L, "PD202609190001");
        when(orderMapper.selectById(1L)).thenReturn(order);

        service.cancel(1L, "录入错误，重新盘");

        verify(orderMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("作废：待审核/已审核单据不可作废（仅盘点中可作废）")
    void cancel_wrongStatus_rejected() {
        StockCheckOrder order = unaudited(1L, "PD202609190001");
        order.setStatus("PENDING_AUDIT");
        when(orderMapper.selectById(1L)).thenReturn(order);

        assertThatThrownBy(() -> service.cancel(1L, "作废"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅盘点中的单据可作废");
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("作废：原因必填；单据不存在返回 404")
    void cancel_reasonRequired_andNotFound() {
        StockCheckOrder order = unaudited(1L, "PD202609190001");
        when(orderMapper.selectById(1L)).thenReturn(order);

        assertThatThrownBy(() -> service.cancel(1L, " "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("作废原因不能为空");

        when(orderMapper.selectById(anyLong())).thenReturn(null);
        assertThatThrownBy(() -> service.cancel(99L, "原因"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }
}

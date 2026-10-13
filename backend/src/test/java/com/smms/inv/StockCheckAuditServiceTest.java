package com.smms.inv;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smms.common.exception.BusinessException;
import com.smms.goods.mapper.CategoryMapper;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.inv.dto.CheckAuditRequest;
import com.smms.inv.dto.CheckOrderVO;
import com.smms.inv.entity.Inventory;
import com.smms.inv.entity.InventoryRecord;
import com.smms.inv.entity.StockCheckItem;
import com.smms.inv.entity.StockCheckOrder;
import com.smms.inv.mapper.InventoryMapper;
import com.smms.inv.mapper.InventoryRecordMapper;
import com.smms.inv.mapper.StockCheckItemMapper;
import com.smms.inv.mapper.StockCheckOrderMapper;
import com.smms.inv.service.StockCheckService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 盘点审核与库存调整单元测试（FR-INV-09，AC-12）：
 * 审核通过单事务生成盘盈/盘亏流水并覆盖结存；流水差异 = 实盘 − 当前结存
 * （BR-INV-10 期间变动桥接，保证 BR-INV-08 不变式）；期间出入库笔数标注；
 * 审核人≠盘点人（BR-SYS-01）；驳回意见必填并退回盘点中（§2.2.4）。
 */
@ExtendWith(MockitoExtension.class)
class StockCheckAuditServiceTest {

    private static final String CHECKER = "keeper";
    private static final String AUDITOR = "manager";

    @Mock private StockCheckOrderMapper orderMapper;
    @Mock private StockCheckItemMapper itemMapper;
    @Mock private InventoryMapper inventoryMapper;
    @Mock private InventoryRecordMapper inventoryRecordMapper;
    @Mock private CategoryMapper categoryMapper;
    @Mock private GoodsMapper goodsMapper;

    private StockCheckService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), StockCheckOrder.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), StockCheckItem.class);
        service = new StockCheckService(orderMapper, itemMapper, inventoryMapper,
                inventoryRecordMapper, categoryMapper, goodsMapper);
    }

    // ---------- 造数 ----------

    private StockCheckOrder pendingOrder() {
        StockCheckOrder order = new StockCheckOrder();
        order.setId(1L);
        order.setOrderNo("PD202609190001");
        order.setStatus("PENDING_AUDIT");
        order.setCheckerName(CHECKER);
        order.setBaseTime(LocalDateTime.now().minusHours(2));
        order.setItemCount(1);
        return order;
    }

    private StockCheckItem item(long goodsId, String book, String actual, String cost, String reason) {
        StockCheckItem item = new StockCheckItem();
        item.setId(goodsId * 100);
        item.setOrderId(1L);
        item.setGoodsId(goodsId);
        item.setBookQty(new BigDecimal(book));
        item.setActualQty(actual == null ? null : new BigDecimal(actual));
        item.setCostPrice(new BigDecimal(cost));
        item.setDiffReason(reason);
        return item;
    }

    private Inventory inv(String quantity, String costPrice) {
        Inventory inv = new Inventory();
        inv.setGoodsId(9L);
        inv.setStoreId(1L);
        inv.setQuantity(new BigDecimal(quantity));
        inv.setCostPrice(new BigDecimal(costPrice));
        return inv;
    }

    // ---------- 审核通过（FR-INV-09） ----------

    @Test
    @DisplayName("审核通过：实盘>当前结存生成 CHECK_SURPLUS 流水（+2，前后结存快照），结存覆盖为实盘数")
    void auditPass_surplusRecord() {
        StockCheckOrder order = pendingOrder();
        when(orderMapper.selectById(1L)).thenReturn(order);
        when(itemMapper.countDuringChanges(eq(1L), any(), any())).thenReturn(2L);
        when(itemMapper.selectList(any())).thenReturn(List.of(item(9L, "10", "12", "2.5", "理货补录")));
        when(inventoryMapper.selectByGoodsIdForUpdate(9L, 1L)).thenReturn(inv("10", "2.5"));
        when(orderMapper.update(eq(null), any())).thenReturn(1);
        when(itemMapper.selectCount(any())).thenReturn(1L, 0L);

        CheckOrderVO vo = service.audit(1L, new CheckAuditRequest(true, "同意", null), AUDITOR);

        ArgumentCaptor<InventoryRecord> captor = ArgumentCaptor.forClass(InventoryRecord.class);
        verify(inventoryRecordMapper).insert(captor.capture());
        InventoryRecord r = captor.getValue();
        assertThat(r.getRecordType()).isEqualTo("CHECK_SURPLUS");
        assertThat(r.getChangeQty()).isEqualByComparingTo("2");
        assertThat(r.getBeforeQty()).isEqualByComparingTo("10");
        assertThat(r.getAfterQty()).isEqualByComparingTo("12");
        assertThat(r.getUnitCost()).isEqualByComparingTo("2.5");
        assertThat(r.getBizType()).isEqualTo("PD");
        assertThat(r.getBizNo()).isEqualTo("PD202609190001");
        assertThat(r.getOperatorName()).isEqualTo(AUDITOR);
        assertThat(r.getRemark()).contains("理货补录");
        verify(inventoryMapper).updateQuantityAndCost(9L, 1L,
                new BigDecimal("12"), new BigDecimal("2.5"), new BigDecimal("30.00"));
        assertThat(vo.status()).isEqualTo("AUDITED");
        assertThat(vo.auditorName()).isEqualTo(AUDITOR);
        assertThat(vo.duringChangeCount()).isEqualTo(2);
        assertThat(vo.auditTime()).isNotNull();
    }

    @Test
    @DisplayName("审核通过（BR-INV-10 桥接）：期间入库使结存 11，实盘 8 → CHECK_SHORTAGE −3，不变式仍成立")
    void auditPass_shortageBridgesDuringChanges() {
        StockCheckOrder order = pendingOrder();
        when(orderMapper.selectById(1L)).thenReturn(order);
        when(itemMapper.countDuringChanges(eq(1L), any(), any())).thenReturn(3L);
        // 账面 10、实盘 8（盘亏 2）；盘点期间采购入库 3 件使当前结存 11
        when(itemMapper.selectList(any())).thenReturn(List.of(item(9L, "10", "8", "2.5", "破损")));
        when(inventoryMapper.selectByGoodsIdForUpdate(9L, 1L)).thenReturn(inv("11", "2.5"));
        when(orderMapper.update(eq(null), any())).thenReturn(1);
        when(itemMapper.selectCount(any())).thenReturn(1L, 1L);

        CheckOrderVO vo = service.audit(1L, new CheckAuditRequest(true, null, null), AUDITOR);

        ArgumentCaptor<InventoryRecord> captor = ArgumentCaptor.forClass(InventoryRecord.class);
        verify(inventoryRecordMapper).insert(captor.capture());
        InventoryRecord r = captor.getValue();
        assertThat(r.getRecordType()).isEqualTo("CHECK_SHORTAGE");
        assertThat(r.getChangeQty()).isEqualByComparingTo("-3");
        assertThat(r.getBeforeQty()).isEqualByComparingTo("11");
        assertThat(r.getAfterQty()).isEqualByComparingTo("8");
        verify(inventoryMapper).updateQuantityAndCost(9L, 1L,
                new BigDecimal("8"), new BigDecimal("2.5"), new BigDecimal("20.00"));
        assertThat(vo.duringChangeCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("审核通过：期间变动恰好抵补（当前结存=实盘）时不生成流水、不覆盖（差异为 0 的桥）")
    void auditPass_compensatedMovement_noRecord() {
        StockCheckOrder order = pendingOrder();
        when(orderMapper.selectById(1L)).thenReturn(order);
        when(itemMapper.countDuringChanges(eq(1L), any(), any())).thenReturn(5L);
        // 账面 10、实盘 12；期间售出 2 件使当前结存恰好 12 → 无需调整
        when(itemMapper.selectList(any())).thenReturn(List.of(item(9L, "10", "12", "2.5", "理货补录")));
        when(inventoryMapper.selectByGoodsIdForUpdate(9L, 1L)).thenReturn(inv("12", "2.5"));
        when(orderMapper.update(eq(null), any())).thenReturn(1);
        when(itemMapper.selectCount(any())).thenReturn(1L, 1L);

        service.audit(1L, new CheckAuditRequest(true, null, null), AUDITOR);

        verify(inventoryRecordMapper, never()).insert(any(InventoryRecord.class));
        verify(inventoryMapper, never()).updateQuantityAndCost(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("审核通过：商品从未发生变动（结存行缺失）先补 0 行再从 0 盘盈入账")
    void auditPass_missingInventoryRow() {
        StockCheckOrder order = pendingOrder();
        when(orderMapper.selectById(1L)).thenReturn(order);
        when(itemMapper.countDuringChanges(eq(1L), any(), any())).thenReturn(0L);
        when(itemMapper.selectList(any())).thenReturn(List.of(item(9L, "0", "5", "0", "历史遗留")));
        when(inventoryMapper.selectByGoodsIdForUpdate(9L, 1L)).thenReturn(null, inv("0", "0"));
        when(orderMapper.update(eq(null), any())).thenReturn(1);
        when(itemMapper.selectCount(any())).thenReturn(1L, 1L);

        service.audit(1L, new CheckAuditRequest(true, null, null), AUDITOR);

        verify(inventoryMapper).ensureRow(9L, 1L);
        ArgumentCaptor<InventoryRecord> captor = ArgumentCaptor.forClass(InventoryRecord.class);
        verify(inventoryRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getRecordType()).isEqualTo("CHECK_SURPLUS");
        assertThat(captor.getValue().getBeforeQty()).isEqualByComparingTo("0");
        assertThat(captor.getValue().getAfterQty()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("审核通过：多商品按 goodsId 升序逐行加锁（与库存服务锁序一致，防交叉死锁）")
    void auditPass_locksInGoodsIdOrder() {
        StockCheckOrder order = pendingOrder();
        when(orderMapper.selectById(1L)).thenReturn(order);
        when(itemMapper.countDuringChanges(eq(1L), any(), any())).thenReturn(0L);
        when(itemMapper.selectList(any())).thenReturn(List.of(
                item(10L, "5", "7", "1", null), item(9L, "10", "12", "2.5", null)));
        Inventory inv9 = inv("10", "2.5");
        inv9.setGoodsId(9L);
        Inventory inv10 = inv("5", "1");
        inv10.setGoodsId(10L);
        when(inventoryMapper.selectByGoodsIdForUpdate(9L, 1L)).thenReturn(inv9);
        when(inventoryMapper.selectByGoodsIdForUpdate(10L, 1L)).thenReturn(inv10);
        when(orderMapper.update(eq(null), any())).thenReturn(1);
        when(itemMapper.selectCount(any())).thenReturn(2L, 2L);

        service.audit(1L, new CheckAuditRequest(true, null, null), AUDITOR);

        InOrder inOrder = inOrder(inventoryMapper);
        inOrder.verify(inventoryMapper).selectByGoodsIdForUpdate(9L, 1L);
        inOrder.verify(inventoryMapper).selectByGoodsIdForUpdate(10L, 1L);
        verify(inventoryRecordMapper, times(2)).insert(any(InventoryRecord.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    @DisplayName("审核通过：差异金额按审核时当前成本单价定稿并刷新单头汇总（GAP-P1-07 / FR-INV-08）")
    void auditPass_recomputesDiffAmountWithAuditTimeCost() {
        StockCheckOrder order = pendingOrder();
        when(orderMapper.selectById(1L)).thenReturn(order);
        when(itemMapper.countDuringChanges(eq(1L), any(), any())).thenReturn(0L);
        // 录入时快照成本 2.5（差异金额 2×2.5=5.00）；审核时当前成本已变为 2.0（盘点期间采购改变均价）
        StockCheckItem item = item(9L, "10", "12", "2.5", "理货补录");
        item.setDiffQty(new BigDecimal("2"));
        item.setDiffAmount(new BigDecimal("5.00"));
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        when(inventoryMapper.selectByGoodsIdForUpdate(9L, 1L)).thenReturn(inv("10", "2.0"));
        // 模拟明细更新落库：refreshTotals 重查（同一对象）后读到定稿值
        when(itemMapper.update(isNull(), any())).thenAnswer(inv -> {
            item.setCostPrice(new BigDecimal("2.0000"));
            item.setDiffAmount(new BigDecimal("4.00"));
            return 1;
        });
        when(orderMapper.update(eq(null), any())).thenReturn(1);
        when(itemMapper.selectCount(any())).thenReturn(1L, 1L);

        CheckOrderVO vo = service.audit(1L, new CheckAuditRequest(true, null, null), AUDITOR);

        // 明细定稿：成本单价 = 2.0000、差异金额 = (实盘 12 − 账面 10) × 2.0 = 4.00
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper).update(isNull(), captor.capture());
        assertThat(((AbstractWrapper) captor.getValue()).getParamNameValuePairs().values())
                .contains(new BigDecimal("2.0000"), new BigDecimal("4.00"));
        // 盘盈流水成本同样取审核时当前成本
        ArgumentCaptor<InventoryRecord> recordCaptor = ArgumentCaptor.forClass(InventoryRecord.class);
        verify(inventoryRecordMapper).insert(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getUnitCost()).isEqualByComparingTo("2.0");
        // 单头汇总刷新为定稿口径 4.00（修复前保持录入时的 5.00）
        assertThat(vo.surplusAmount()).isEqualByComparingTo("4.00");
    }

    // ---------- 审核约束（BR-SYS-01、§2.2.4） ----------

    @Test
    @DisplayName("职责分离（BR-SYS-01/AC-12）：盘点人自审被拒绝，不产生任何库存变动")
    void audit_selfAuditRejected() {
        StockCheckOrder order = pendingOrder();
        when(orderMapper.selectById(1L)).thenReturn(order);

        assertThatThrownBy(() -> service.audit(1L, new CheckAuditRequest(true, null, null), CHECKER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须为不同用户");

        verify(inventoryRecordMapper, never()).insert(any(InventoryRecord.class));
        verify(inventoryMapper, never()).updateQuantityAndCost(anyLong(), anyLong(), any(), any(), any());
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("仅待审核状态可审核（盘点中/已审核均拒绝）")
    void audit_statusGuard() {
        StockCheckOrder order = pendingOrder();
        order.setStatus("CHECKING");
        when(orderMapper.selectById(1L)).thenReturn(order);

        assertThatThrownBy(() -> service.audit(1L, new CheckAuditRequest(true, null, null), AUDITOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅待审核的盘点单可审核");
    }

    // ---------- 驳回（FR-INV-09） ----------

    @Test
    @DisplayName("驳回：意见必填；驳回成功退回盘点中且不产生任何库存变动（§2.2.4）")
    void auditReject_remarkRequired_andBackToChecking() {
        StockCheckOrder order = pendingOrder();
        when(orderMapper.selectById(1L)).thenReturn(order);

        assertThatThrownBy(() -> service.audit(1L, new CheckAuditRequest(false, " ", null), AUDITOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("驳回意见不能为空");

        when(orderMapper.update(eq(null), any())).thenReturn(1);
        when(itemMapper.selectCount(any())).thenReturn(0L, 0L);
        CheckOrderVO vo = service.audit(1L, new CheckAuditRequest(false, "差异原因不实，请复核", null), AUDITOR);

        assertThat(vo.status()).isEqualTo("CHECKING");
        assertThat(vo.auditRemark()).isEqualTo("差异原因不实，请复核");
        verify(inventoryRecordMapper, never()).insert(any(InventoryRecord.class));
        verify(inventoryMapper, never()).updateQuantityAndCost(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("审核：条件更新失败（并发状态变化）返回冲突")
    void audit_concurrentConflict() {
        StockCheckOrder order = pendingOrder();
        when(orderMapper.selectById(1L)).thenReturn(order);
        when(itemMapper.countDuringChanges(eq(1L), any(), any())).thenReturn(0L);
        when(itemMapper.selectList(any())).thenReturn(List.of(item(9L, "10", "12", "2.5", "x")));
        when(inventoryMapper.selectByGoodsIdForUpdate(9L, 1L)).thenReturn(inv("10", "2.5"));
        when(orderMapper.update(eq(null), any())).thenReturn(0);

        assertThatThrownBy(() -> service.audit(1L, new CheckAuditRequest(true, null, null), AUDITOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请刷新后重试");
    }
}

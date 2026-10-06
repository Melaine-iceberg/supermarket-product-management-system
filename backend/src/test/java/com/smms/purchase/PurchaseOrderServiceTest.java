package com.smms.purchase;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.goods.entity.Goods;
import com.smms.goods.entity.Unit;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.goods.mapper.UnitMapper;
import com.smms.purchase.dto.PurchaseAuditRequest;
import com.smms.purchase.dto.PurchaseOrderDetailVO;
import com.smms.purchase.dto.PurchaseReasonRequest;
import com.smms.purchase.dto.PurchaseSaveRequest;
import com.smms.purchase.dto.PurchaseSettleRequest;
import com.smms.purchase.entity.PurchaseOrder;
import com.smms.purchase.entity.PurchaseOrderItem;
import com.smms.purchase.mapper.PurchaseOrderItemMapper;
import com.smms.purchase.mapper.PurchaseOrderMapper;
import com.smms.purchase.service.PurchaseOrderService;
import com.smms.supplier.entity.Supplier;
import com.smms.supplier.mapper.SupplierMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采购单服务单元测试：覆盖 BR-PUR-01（PO 单号规则）、BR-PUR-02（状态机约束）、
 * BR-PUR-03（明细校验与金额自动计算）、BR-PUR-06（乐观锁冲突提示）、
 * FR-PUR-01/03/04/05/09（草稿保存、查询、提交审核、作废终止、结算登记）。
 */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceTest {

    private static final String TODAY = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

    @Mock private PurchaseOrderMapper orderMapper;
    @Mock private PurchaseOrderItemMapper itemMapper;
    @Mock private SupplierMapper supplierMapper;
    @Mock private GoodsMapper goodsMapper;
    @Mock private UnitMapper unitMapper;

    private PurchaseOrderService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PurchaseOrder.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PurchaseOrderItem.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Goods.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Unit.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Supplier.class);
        service = new PurchaseOrderService(orderMapper, itemMapper, supplierMapper, goodsMapper, unitMapper);
    }

    // ==================== 创建草稿（FR-PUR-01 / BR-PUR-01 / BR-PUR-03） ====================

    @Test
    @DisplayName("创建草稿：首单单号 PO+日期+0001，状态草稿，总额自动计算（BR-PUR-01/03）")
    void createFirstGeneratesOrderNoAndTotal() {
        PurchaseSaveRequest req = saveRequest();
        AtomicReference<PurchaseOrder> saved = new AtomicReference<>();

        when(supplierMapper.selectById(1L)).thenReturn(activeSupplier());
        when(goodsMapper.selectBatchIds(any())).thenReturn(List.of(activeGoods(101L, 11L)));
        when(orderMapper.selectLastOrderNoForUpdate(any())).thenReturn(null);
        when(orderMapper.insert(any(PurchaseOrder.class))).thenAnswer(inv -> {
            PurchaseOrder order = inv.getArgument(0, PurchaseOrder.class);
            order.setId(100L);
            saved.set(order);
            return 1;
        });
        when(itemMapper.insert(any(PurchaseOrderItem.class))).thenReturn(1);
        // detail 回查
        lenient().when(orderMapper.selectById(100L)).thenAnswer(inv -> saved.get());
        lenient().when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(savedItem()));
        lenient().when(unitMapper.selectBatchIds(any())).thenReturn(List.of(unit()));

        PurchaseOrderDetailVO vo = service.create(req, "李采购");

        assertThat(vo.orderNo()).isEqualTo("PO" + TODAY + "0001");
        assertThat(vo.status()).isEqualTo(PurchaseOrder.STATUS_DRAFT);
        assertThat(vo.totalAmount()).isEqualByComparingTo("25.00");
        assertThat(vo.items()).hasSize(1);
        assertThat(vo.items().get(0).pendingQty()).isEqualByComparingTo("10");
        assertThat(saved.get().getCreateBy()).isEqualTo("李采购");
        assertThat(saved.get().getVersion()).isZero();
    }

    @Test
    @DisplayName("创建草稿：单号从当日最大流水 +1（BR-PUR-01）")
    void createIncrementsOrderNo() {
        when(supplierMapper.selectById(1L)).thenReturn(activeSupplier());
        when(goodsMapper.selectBatchIds(any())).thenReturn(List.of(activeGoods(101L, 11L)));
        when(orderMapper.selectLastOrderNoForUpdate(any())).thenReturn("PO" + TODAY + "0042");
        AtomicReference<PurchaseOrder> saved = new AtomicReference<>();
        when(orderMapper.insert(any(PurchaseOrder.class))).thenAnswer(inv -> {
            PurchaseOrder order = inv.getArgument(0, PurchaseOrder.class);
            order.setId(101L);
            saved.set(order);
            return 1;
        });
        when(itemMapper.insert(any(PurchaseOrderItem.class))).thenReturn(1);
        lenient().when(orderMapper.selectById(101L)).thenAnswer(inv -> saved.get());
        lenient().when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(savedItem()));
        lenient().when(unitMapper.selectBatchIds(any())).thenReturn(List.of(unit()));

        PurchaseOrderDetailVO vo = service.create(saveRequest(), "李采购");

        assertThat(vo.orderNo()).isEqualTo("PO" + TODAY + "0043");
    }

    @Test
    @DisplayName("创建草稿：停用供应商被拒（FR-SUP-03）")
    void createRejectsDisabledSupplier() {
        Supplier disabled = activeSupplier();
        disabled.setStatus(0);
        when(supplierMapper.selectById(1L)).thenReturn(disabled);

        assertThatThrownBy(() -> service.create(saveRequest(), "李采购"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("创建草稿：同商品重复行被拒（BR-PUR-03）")
    void createRejectsDuplicatedGoodsLine() {
        PurchaseSaveRequest req = saveRequest();
        PurchaseSaveRequest.ItemRequest dup = new PurchaseSaveRequest.ItemRequest();
        dup.setGoodsId(101L);
        dup.setQuantity(BigDecimal.ONE);
        dup.setPrice(BigDecimal.TEN);
        req.getItems().add(dup);
        when(supplierMapper.selectById(1L)).thenReturn(activeSupplier());

        assertThatThrownBy(() -> service.create(req, "李采购"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PUR_LINE_DUPLICATED.getCode());
    }

    @Test
    @DisplayName("创建草稿：数量 ≤ 0 被拒（BR-PUR-03）")
    void createRejectsNonPositiveQty() {
        PurchaseSaveRequest req = saveRequest();
        req.getItems().get(0).setQuantity(BigDecimal.ZERO);
        when(supplierMapper.selectById(1L)).thenReturn(activeSupplier());

        assertThatThrownBy(() -> service.create(req, "李采购"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PUR_LINE_INVALID.getCode());
    }

    @Test
    @DisplayName("创建草稿：停用商品被拒（BR-PUR-03）")
    void createRejectsDisabledGoods() {
        Goods disabled = activeGoods(101L, 11L);
        disabled.setStatus(0);
        when(supplierMapper.selectById(1L)).thenReturn(activeSupplier());
        when(goodsMapper.selectBatchIds(any())).thenReturn(List.of(disabled));

        assertThatThrownBy(() -> service.create(saveRequest(), "李采购"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PUR_LINE_INVALID.getCode());
    }

    // ==================== 提交与审核（FR-PUR-04 / BR-PUR-02） ====================

    @Test
    @DisplayName("提交审核：草稿 → 待审核（FR-PUR-04）")
    void submitMovesDraftToSubmitted() {
        when(orderMapper.selectById(100L)).thenReturn(order(PurchaseOrder.STATUS_DRAFT));
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        service.submit(100L, "李采购");
        verify(orderMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("撤回：仅创建人可撤回待审核单据（FR-PUR-04）")
    void withdrawRejectsNonOwner() {
        PurchaseOrder order = order(PurchaseOrder.STATUS_SUBMITTED);
        order.setCreateBy("李采购");
        when(orderMapper.selectById(100L)).thenReturn(order);

        assertThatThrownBy(() -> service.withdraw(100L, "王店长"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PUR_WITHDRAW_NOT_OWNER.getCode());
    }

    @Test
    @DisplayName("审核：驳回时必须填写意见（FR-PUR-04）")
    void auditRejectRequiresRemark() {
        when(orderMapper.selectById(100L)).thenReturn(order(PurchaseOrder.STATUS_SUBMITTED));

        assertThatThrownBy(() -> service.audit(100L, new PurchaseAuditRequest(false, "  ", null), "王店长"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("审核：通过后更新为已审核（FR-PUR-04）")
    void auditApproveUpdatesStatus() {
        when(orderMapper.selectById(100L)).thenReturn(order(PurchaseOrder.STATUS_SUBMITTED));
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        service.audit(100L, new PurchaseAuditRequest(true, null, null), "王店长");
        verify(orderMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("审核：乐观锁冲突提示刷新重试（BR-PUR-06）")
    void submitConflictThrowsConcurrentConflict() {
        when(orderMapper.selectById(100L)).thenReturn(order(PurchaseOrder.STATUS_DRAFT));
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.submit(100L, "李采购"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.CONCURRENT_CONFLICT.getCode());
    }

    // ==================== 作废/终止/关闭（FR-PUR-05） ====================

    @Test
    @DisplayName("终止：仅已审核单据可终止，草稿被状态机拒绝（BR-PUR-02）")
    void terminateRejectsDraft() {
        when(orderMapper.selectById(100L)).thenReturn(order(PurchaseOrder.STATUS_DRAFT));

        assertThatThrownBy(() -> service.terminate(100L, new PurchaseReasonRequest("供应商断供"), "王店长"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PUR_STATUS_INVALID.getCode());
    }

    @Test
    @DisplayName("作废：草稿 → 已作废，须填原因（FR-PUR-05）")
    void cancelMovesDraftToCancelled() {
        when(orderMapper.selectById(100L)).thenReturn(order(PurchaseOrder.STATUS_DRAFT));
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        service.cancel(100L, new PurchaseReasonRequest("录入错误"), "李采购");
        verify(orderMapper).update(isNull(), any(Wrapper.class));
    }

    // ==================== 结算登记（FR-PUR-09） ====================

    @Test
    @DisplayName("结算：仅已入库各状态可登记，草稿被拒（FR-PUR-09）")
    void settleRejectsDraftStatus() {
        when(orderMapper.selectById(100L)).thenReturn(order(PurchaseOrder.STATUS_DRAFT));

        assertThatThrownBy(() -> service.settle(100L,
                new PurchaseSettleRequest(PurchaseOrder.SETTLE_SETTLED, new BigDecimal("100"), "CASH", LocalDate.now()), "王店长"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PUR_SETTLE_INVALID.getCode());
    }

    @Test
    @DisplayName("结算：已完成单据登记结算金额与方式（FR-PUR-09）")
    void settleRegistersAmountAndMethod() {
        when(orderMapper.selectById(100L)).thenReturn(order(PurchaseOrder.STATUS_COMPLETED));
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        service.settle(100L, new PurchaseSettleRequest(PurchaseOrder.SETTLE_SETTLED,
                new BigDecimal("100"), "CASH", LocalDate.now()), "王店长");
        verify(orderMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("结算：非法结算状态被拒（FR-PUR-09）")
    void settleRejectsInvalidSettleStatus() {
        when(orderMapper.selectById(100L)).thenReturn(order(PurchaseOrder.STATUS_COMPLETED));

        assertThatThrownBy(() -> service.settle(100L,
                new PurchaseSettleRequest("INVALID", new BigDecimal("100"), "CASH", LocalDate.now()), "王店长"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PUR_SETTLE_INVALID.getCode());
    }

    // ==================== 测试数据 ====================

    private Supplier activeSupplier() {
        Supplier supplier = new Supplier();
        supplier.setId(1L);
        supplier.setName("华东饮品");
        supplier.setStatus(1);
        return supplier;
    }

    private Goods activeGoods(Long id, Long unitId) {
        Goods goods = new Goods();
        goods.setId(id);
        goods.setGoodsCode("SP0001");
        goods.setName("可乐 330ml");
        goods.setSpec("330ml");
        goods.setUnitId(unitId);
        goods.setStatus(1);
        return goods;
    }

    private Unit unit() {
        Unit unit = new Unit();
        unit.setId(11L);
        unit.setName("罐");
        return unit;
    }

    private PurchaseOrder order(String status) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(100L);
        order.setOrderNo("PO" + TODAY + "0001");
        order.setSupplierId(1L);
        order.setStatus(status);
        order.setCreateBy("李采购");
        order.setVersion(0);
        return order;
    }

    private PurchaseOrderItem savedItem() {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(200L);
        item.setOrderId(100L);
        item.setGoodsId(101L);
        item.setQuantity(new BigDecimal("10"));
        item.setPrice(new BigDecimal("2.50"));
        item.setAmount(new BigDecimal("25.00"));
        item.setReceivedQty(BigDecimal.ZERO);
        item.setReturnedQty(BigDecimal.ZERO);
        return item;
    }

    private PurchaseSaveRequest saveRequest() {
        PurchaseSaveRequest req = new PurchaseSaveRequest();
        req.setSupplierId(1L);
        req.setExpectDate(LocalDate.now().plusDays(3));
        PurchaseSaveRequest.ItemRequest item = new PurchaseSaveRequest.ItemRequest();
        item.setGoodsId(101L);
        item.setQuantity(new BigDecimal("10"));
        item.setPrice(new BigDecimal("2.50"));
        req.setItems(new ArrayList<>(List.of(item)));
        return req;
    }
}

package com.smms.purchase.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.goods.entity.Goods;
import com.smms.goods.entity.Unit;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.goods.mapper.UnitMapper;
import com.smms.inv.dto.StockChange;
import com.smms.inv.service.InventoryService;
import com.smms.purchase.dto.PurchaseAuditRequest;
import com.smms.purchase.dto.PurchaseReturnCreateRequest;
import com.smms.purchase.dto.PurchaseReturnVO;
import com.smms.purchase.entity.PurchaseOrder;
import com.smms.purchase.entity.PurchaseOrderItem;
import com.smms.purchase.entity.PurchaseReturnItem;
import com.smms.purchase.entity.PurchaseReturnOrder;
import com.smms.purchase.mapper.PurchaseOrderItemMapper;
import com.smms.purchase.mapper.PurchaseOrderMapper;
import com.smms.purchase.mapper.PurchaseReturnItemMapper;
import com.smms.purchase.mapper.PurchaseReturnOrderMapper;
import com.smms.purchase.mapper.ReceiptOrderItemMapper;
import com.smms.purchase.service.PurchaseOrderService;
import com.smms.purchase.service.PurchaseReturnService;
import com.smms.supplier.entity.Supplier;
import com.smms.supplier.mapper.SupplierMapper;
import com.smms.sys.service.BusinessDayService;
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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采购退货服务单元测试：覆盖 FR-PUR-08（PT 单号、可退数量校验、退货单价取入库成本、
 * 审核通过事务执行、驳回须意见）、BR-PUR-02（退货状态机：全部退货/部分退货）、
 * BR-PUR-06（乐观锁）、DR-C-04（供应商累计采购额冲减）。
 */
@ExtendWith(MockitoExtension.class)
class PurchaseReturnServiceTest {

    private static final String TODAY = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

    @Mock private PurchaseOrderMapper orderMapper;
    @Mock private PurchaseOrderItemMapper orderItemMapper;
    @Mock private PurchaseOrderService purchaseOrderService;
    @Mock private PurchaseReturnOrderMapper returnMapper;
    @Mock private PurchaseReturnItemMapper returnItemMapper;
    @Mock private ReceiptOrderItemMapper receiptItemMapper;
    @Mock private SupplierMapper supplierMapper;
    @Mock private GoodsMapper goodsMapper;
    @Mock private UnitMapper unitMapper;
    @Mock private InventoryService inventoryService;
    @Mock private SysConfigService configService;

    private PurchaseReturnService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PurchaseOrder.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PurchaseOrderItem.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PurchaseReturnOrder.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PurchaseReturnItem.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Goods.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Unit.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Supplier.class);
        service = new PurchaseReturnService(orderMapper, orderItemMapper, purchaseOrderService,
                returnMapper, returnItemMapper, receiptItemMapper, supplierMapper,
                goodsMapper, unitMapper, inventoryService, new BusinessDayService(configService));
    }

    // ==================== 创建退货单（FR-PUR-08） ====================

    @Test
    @DisplayName("创建退货单：PT 单号，退货单价取入库加权成本（FR-PUR-08）")
    void createGeneratesPtOrderWithReceiptCostPrice() {
        PurchaseOrder order = order(PurchaseOrder.STATUS_COMPLETED);
        PurchaseOrderItem item = poItem("10", "9.80", "10", "0");
        when(purchaseOrderService.requireOrder(100L)).thenReturn(order);
        when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
        when(receiptItemMapper.selectAvgReceiptPrice(200L)).thenReturn(new BigDecimal("9.50"));

        AtomicReference<PurchaseReturnOrder> saved = new AtomicReference<>();
        when(returnMapper.selectLastOrderNoForUpdate(any())).thenReturn(null);
        when(returnMapper.insert(any(PurchaseReturnOrder.class))).thenAnswer(inv -> {
            PurchaseReturnOrder returnOrder = inv.getArgument(0, PurchaseReturnOrder.class);
            returnOrder.setId(400L);
            saved.set(returnOrder);
            return 1;
        });
        when(returnItemMapper.insert(any(PurchaseReturnItem.class))).thenAnswer(inv -> {
            inv.getArgument(0, PurchaseReturnItem.class).setId(500L);
            return 1;
        });
        // detail 回查
        lenient().when(returnMapper.selectById(400L)).thenAnswer(inv -> saved.get());
        lenient().when(orderMapper.selectById(100L)).thenReturn(order);
        lenient().when(supplierMapper.selectById(1L)).thenReturn(supplier());
        lenient().when(returnItemMapper.selectList(any(Wrapper.class))).thenAnswer(inv -> List.of(returnItem("5", "9.50")));
        lenient().when(goodsMapper.selectBatchIds(any())).thenReturn(List.of(goods()));
        lenient().when(unitMapper.selectBatchIds(any())).thenReturn(List.of(unit()));

        PurchaseReturnVO vo = service.create(request("5"), "李采购");

        assertThat(vo.orderNo()).isEqualTo("PT" + TODAY + "0001");
        assertThat(vo.status()).isEqualTo(PurchaseReturnOrder.STATUS_SUBMITTED);
        assertThat(vo.totalAmount()).isEqualByComparingTo("47.50"); // 5 × 9.50（入库成本，非采购价 9.80）
        assertThat(vo.items()).hasSize(1);
        assertThat(vo.items().get(0).returnPrice()).isEqualByComparingTo("9.50");
        // 创建阶段不动库存，待店长审核
        verify(inventoryService, never()).stockOut(any());
        // 营业日（BR-RPT-02/GAP-P1-05）：创建落库即写入
        assertThat(saved.get().getBusinessDate()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("创建退货单：退货数量超过可退（已入库−已退）被拒（FR-PUR-08）")
    void createRejectsReturnQtyOverReturnable() {
        when(purchaseOrderService.requireOrder(100L)).thenReturn(order(PurchaseOrder.STATUS_COMPLETED));
        when(orderItemMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(poItem("10", "9.80", "10", "5"))); // 可退 = 10−5 = 5
        when(goodsMapper.selectById(101L)).thenReturn(goods());

        assertThatThrownBy(() -> service.create(request("6"), "李采购"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PUR_RETURN_LINE_INVALID.getCode());
    }

    @Test
    @DisplayName("创建退货单：待审核状态不允许退货（BR-PUR-02）")
    void createRejectsNotReceivedOrder() {
        when(purchaseOrderService.requireOrder(100L)).thenReturn(order(PurchaseOrder.STATUS_SUBMITTED));

        assertThatThrownBy(() -> service.create(request("5"), "李采购"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PUR_STATUS_INVALID.getCode());
    }

    // ==================== 审核（FR-PUR-08） ====================

    @Test
    @DisplayName("审核通过：全部退完转全部退货，库存负数流水并冲减供应商额度（FR-PUR-08）")
    void auditApproveExecutesReturnTransaction() {
        PurchaseOrder order = order(PurchaseOrder.STATUS_COMPLETED);
        PurchaseOrderItem item = poItem("10", "9.80", "10", "0");
        PurchaseReturnOrder returnOrder = returnOrder(new BigDecimal("95.00"));
        when(returnMapper.selectById(400L)).thenReturn(returnOrder);
        when(purchaseOrderService.requireOrder(100L)).thenReturn(order);
        when(returnItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(returnItem("10", "9.50")));
        lenient().when(returnMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        // 已退数量原子回写：同步更新内存对象供全退判定读取
        when(orderMapper.addReturnedQtyIfEnough(eq(200L), any())).thenAnswer(inv -> {
            item.setReturnedQty(item.getReturnedQty().add(inv.getArgument(1, BigDecimal.class)));
            return 1;
        });
        when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
        when(orderMapper.updateReturnState(eq(100L), any(), any(), any(), any())).thenReturn(1);

        service.audit(400L, new PurchaseAuditRequest(true, null, null), "王店长");

        // 库存退货负数流水（PURCHASE_RETURN）
        ArgumentCaptor<StockChange> stockCaptor = ArgumentCaptor.forClass(StockChange.class);
        verify(inventoryService).stockOut(stockCaptor.capture());
        assertThat(stockCaptor.getValue().recordType()).isEqualTo("PURCHASE_RETURN");
        assertThat(stockCaptor.getValue().qty()).isEqualByComparingTo("10");
        assertThat(stockCaptor.getValue().unitCost()).isEqualByComparingTo("9.50");
        // 全部退完 → ALL_RETURNED（§2.2.1）
        verify(orderMapper).updateReturnState(eq(100L), eq(new BigDecimal("95.00")),
                eq(PurchaseOrder.STATUS_ALL_RETURNED), eq("王店长"), eq(0));
        // 供应商累计采购额冲减（DR-C-04）
        verify(supplierMapper).addTotalPurchaseAmount(eq(1L), eq(new BigDecimal("-95.00")));
    }

    @Test
    @DisplayName("审核通过：部分退货后已完成单据转部分退货（§2.2.1）")
    void auditApprovePartialReturnKeepsPartialReturned() {
        PurchaseOrder order = order(PurchaseOrder.STATUS_COMPLETED);
        // 已入 10 已退 0，本次退 4：仍有余量 → PARTIAL_RETURNED
        PurchaseOrderItem item = poItem("10", "9.80", "10", "0");
        when(returnMapper.selectById(400L)).thenReturn(returnOrder(new BigDecimal("38.00")));
        when(purchaseOrderService.requireOrder(100L)).thenReturn(order);
        when(returnItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(returnItem("4", "9.50")));
        lenient().when(returnMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(orderMapper.addReturnedQtyIfEnough(eq(200L), any())).thenAnswer(inv -> {
            item.setReturnedQty(item.getReturnedQty().add(inv.getArgument(1, BigDecimal.class)));
            return 1;
        });
        when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
        when(orderMapper.updateReturnState(eq(100L), any(), any(), any(), any())).thenReturn(1);

        service.audit(400L, new PurchaseAuditRequest(true, null, null), "王店长");

        verify(orderMapper).updateReturnState(eq(100L), any(),
                eq(PurchaseOrder.STATUS_PARTIAL_RETURNED), eq("王店长"), eq(0));
    }

    @Test
    @DisplayName("审核通过：部分入库单退完已入库数量后仍保持部分入库（剩余待入库量可继续收货，GAP-P0-04）")
    void auditApprovePartialReceivedOrderKeepsPartialStatus() {
        PurchaseOrder order = order(PurchaseOrder.STATUS_PARTIAL);
        // 订购 100、已入库 40、已退 0；本次退 40 → 仍有 60 待入库。
        // 修复前 allMatch(returned >= received) 成立会误转 ALL_RETURNED，导致剩余 60 既不能入库也不能关闭（卡死）。
        PurchaseOrderItem item = poItem("100", "9.80", "40", "0");
        when(returnMapper.selectById(400L)).thenReturn(returnOrder(new BigDecimal("380.00")));
        when(purchaseOrderService.requireOrder(100L)).thenReturn(order);
        when(returnItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(returnItem("40", "9.50")));
        lenient().when(returnMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(orderMapper.addReturnedQtyIfEnough(eq(200L), any())).thenAnswer(inv -> {
            item.setReturnedQty(item.getReturnedQty().add(inv.getArgument(1, BigDecimal.class)));
            return 1;
        });
        when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
        when(orderMapper.updateReturnState(eq(100L), any(), any(), any(), any())).thenReturn(1);

        service.audit(400L, new PurchaseAuditRequest(true, null, null), "王店长");

        verify(orderMapper).updateReturnState(eq(100L), eq(new BigDecimal("380.00")),
                eq(PurchaseOrder.STATUS_PARTIAL), eq("王店长"), eq(0));
    }

    @Test
    @DisplayName("审核驳回：必须填写意见（FR-PUR-08）")
    void auditRejectRequiresRemark() {
        when(returnMapper.selectById(400L)).thenReturn(returnOrder(BigDecimal.TEN));

        assertThatThrownBy(() -> service.audit(400L, new PurchaseAuditRequest(false, " ", null), "王店长"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("审核通过：可退数量不足时整体回滚（防超退，FR-PUR-08）")
    void auditApproveFailsWhenReturnableNotEnough() {
        when(returnMapper.selectById(400L)).thenReturn(returnOrder(BigDecimal.TEN));
        when(purchaseOrderService.requireOrder(100L)).thenReturn(order(PurchaseOrder.STATUS_COMPLETED));
        when(returnItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(returnItem("10", "9.50")));
        lenient().when(returnMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        // 并发退货导致可退不足 → 原子更新返回 0
        when(orderMapper.addReturnedQtyIfEnough(eq(200L), any())).thenReturn(0);

        assertThatThrownBy(() -> service.audit(400L, new PurchaseAuditRequest(true, null, null), "王店长"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PUR_RETURN_LINE_INVALID.getCode());
        verify(inventoryService, never()).stockOut(any());
    }

    @Test
    @DisplayName("审核：非待审核状态的退货单不可审核（FR-PUR-08）")
    void auditRejectsNonSubmittedStatus() {
        PurchaseReturnOrder approved = returnOrder(BigDecimal.TEN);
        approved.setStatus(PurchaseReturnOrder.STATUS_APPROVED);
        when(returnMapper.selectById(400L)).thenReturn(approved);

        assertThatThrownBy(() -> service.audit(400L, new PurchaseAuditRequest(true, null, null), "王店长"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PUR_RETURN_STATUS_INVALID.getCode());
    }

    // ==================== 测试数据 ====================

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

    /** 采购明细：数量/单价/已入库/已退货 */
    private PurchaseOrderItem poItem(String quantity, String price, String receivedQty, String returnedQty) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(200L);
        item.setOrderId(100L);
        item.setGoodsId(101L);
        item.setQuantity(new BigDecimal(quantity));
        item.setPrice(new BigDecimal(price));
        item.setReceivedQty(new BigDecimal(receivedQty));
        item.setReturnedQty(new BigDecimal(returnedQty));
        return item;
    }

    private PurchaseReturnOrder returnOrder(BigDecimal totalAmount) {
        PurchaseReturnOrder returnOrder = new PurchaseReturnOrder();
        returnOrder.setId(400L);
        returnOrder.setOrderNo("PT" + TODAY + "0001");
        returnOrder.setPurchaseOrderId(100L);
        returnOrder.setSupplierId(1L);
        returnOrder.setStatus(PurchaseReturnOrder.STATUS_SUBMITTED);
        returnOrder.setTotalAmount(totalAmount);
        returnOrder.setCreateBy("李采购");
        return returnOrder;
    }

    private PurchaseReturnItem returnItem(String qty, String price) {
        PurchaseReturnItem item = new PurchaseReturnItem();
        item.setId(500L);
        item.setReturnId(400L);
        item.setPurchaseItemId(200L);
        item.setGoodsId(101L);
        item.setReturnQty(new BigDecimal(qty));
        item.setReturnPrice(new BigDecimal(price));
        item.setAmount(new BigDecimal(qty).multiply(new BigDecimal(price)));
        return item;
    }

    private PurchaseReturnCreateRequest request(String returnQty) {
        PurchaseReturnCreateRequest req = new PurchaseReturnCreateRequest();
        req.setPurchaseOrderId(100L);
        req.setReturnReason("临期质量问题");
        PurchaseReturnCreateRequest.ItemRequest item = new PurchaseReturnCreateRequest.ItemRequest();
        item.setPurchaseItemId(200L);
        item.setReturnQty(new BigDecimal(returnQty));
        req.setItems(List.of(item));
        return req;
    }

    private Supplier supplier() {
        Supplier supplier = new Supplier();
        supplier.setId(1L);
        supplier.setName("华东饮品");
        supplier.setStatus(1);
        return supplier;
    }

    private Goods goods() {
        Goods goods = new Goods();
        goods.setId(101L);
        goods.setGoodsCode("SP0001");
        goods.setName("可乐 330ml");
        goods.setSpec("330ml");
        goods.setUnitId(11L);
        goods.setStatus(1);
        return goods;
    }

    private Unit unit() {
        Unit unit = new Unit();
        unit.setId(11L);
        unit.setName("罐");
        return unit;
    }
}

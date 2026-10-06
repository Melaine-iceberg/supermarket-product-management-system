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
import com.smms.purchase.dto.ReceiptCreateRequest;
import com.smms.purchase.dto.ReceiptOrderVO;
import com.smms.purchase.dto.ReceiptPreCheckVO;
import com.smms.purchase.entity.PurchaseOrder;
import com.smms.purchase.entity.PurchaseOrderItem;
import com.smms.purchase.entity.ReceiptOrder;
import com.smms.purchase.entity.ReceiptOrderItem;
import com.smms.purchase.mapper.PurchaseOrderItemMapper;
import com.smms.purchase.mapper.PurchaseOrderMapper;
import com.smms.purchase.mapper.ReceiptOrderItemMapper;
import com.smms.purchase.mapper.ReceiptOrderMapper;
import com.smms.purchase.service.PurchaseOrderService;
import com.smms.purchase.service.ReceiptService;
import com.smms.security.LoginUser;
import com.smms.supplier.entity.Supplier;
import com.smms.supplier.mapper.SupplierMapper;
import com.smms.sys.service.OperationLogService;
import com.smms.sys.service.SensitiveAuthService;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采购入库验收服务单元测试：覆盖 BR-PUR-04（超收容差 5%）、BR-PUR-05（价差授权与黄色提示）、
 * BR-PUR-06（乐观锁）、BR-PUR-07（以实收单价入库）、BR-SYS-01（职责分离）、
 * FR-PUR-06（RK 单号与单事务落库）、FR-PUR-07（入库单详情）。
 */
@ExtendWith(MockitoExtension.class)
class ReceiptServiceTest {

    private static final String TODAY = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

    @Mock private PurchaseOrderMapper orderMapper;
    @Mock private PurchaseOrderItemMapper orderItemMapper;
    @Mock private PurchaseOrderService purchaseOrderService;
    @Mock private ReceiptOrderMapper receiptMapper;
    @Mock private ReceiptOrderItemMapper receiptItemMapper;
    @Mock private SupplierMapper supplierMapper;
    @Mock private GoodsMapper goodsMapper;
    @Mock private UnitMapper unitMapper;
    @Mock private InventoryService inventoryService;
    @Mock private SysConfigService configService;
    @Mock private OperationLogService operationLogService;
    @Mock private SensitiveAuthService sensitiveAuthService;

    private ReceiptService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PurchaseOrder.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PurchaseOrderItem.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReceiptOrder.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReceiptOrderItem.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Goods.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Unit.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Supplier.class);
        service = new ReceiptService(orderMapper, orderItemMapper, purchaseOrderService,
                receiptMapper, receiptItemMapper, supplierMapper, goodsMapper, unitMapper,
                inventoryService, configService, operationLogService, sensitiveAuthService);
    }

    // ==================== 入库验收（FR-PUR-06） ====================

    @Test
    @DisplayName("入库验收：全部入库后采购单转已完成，库存按实收单价入库（BR-PUR-07）")
    void createReceivesAllToCompleted() {
        PurchaseOrder order = order(PurchaseOrder.STATUS_APPROVED);
        PurchaseOrderItem item = poItem("10", "10", "0");
        stubFullReceiptFlow(order, item, "10", "9.80", null);

        ReceiptOrderVO vo = service.create(request("10", "9.80", null), operator("验收员"));

        assertThat(vo.orderNo()).isEqualTo("RK" + TODAY + "0001");
        assertThat(vo.authorizerName()).isNull();
        // 库存流水：PURCHASE_IN，单价取实收单价 9.80（BR-PUR-07）
        ArgumentCaptor<StockChange> stockCaptor = ArgumentCaptor.forClass(StockChange.class);
        verify(inventoryService).stockIn(stockCaptor.capture());
        assertThat(stockCaptor.getValue().recordType()).isEqualTo("PURCHASE_IN");
        assertThat(stockCaptor.getValue().qty()).isEqualByComparingTo("10");
        assertThat(stockCaptor.getValue().unitCost()).isEqualByComparingTo("9.80");
        assertThat(stockCaptor.getValue().bizType()).isEqualTo("RK");
        // 全部入库 → COMPLETED，实收金额 = 10×9.80 = 98.00
        verify(orderMapper).updateReceiptState(eq(100L), eq(new BigDecimal("98.00")),
                eq(PurchaseOrder.STATUS_COMPLETED), eq("验收员"), eq(0));
        // 供应商累计采购额增加（DR-C-04）
        verify(supplierMapper).addTotalPurchaseAmount(eq(1L), eq(new BigDecimal("98.00")));
        verify(orderMapper).addReceivedQty(200L, new BigDecimal("10"));
    }

    @Test
    @DisplayName("入库验收：部分入库采购单转部分入库状态")
    void createPartialKeepsPartialStatus() {
        PurchaseOrder order = order(PurchaseOrder.STATUS_APPROVED);
        PurchaseOrderItem item = poItem("10", "10", "0");
        stubFullReceiptFlow(order, item, "4", "10", null);

        service.create(request("4", "10", null), operator("验收员"));

        verify(orderMapper).updateReceiptState(eq(100L), eq(new BigDecimal("40.00")),
                eq(PurchaseOrder.STATUS_PARTIAL), eq("验收员"), eq(0));
    }

    @Test
    @DisplayName("超收容差：超出待入库×(1+5%) 且无授权被拒（BR-PUR-04）")
    void createRejectsOverToleranceWithoutAuthorizer() {
        when(purchaseOrderService.requireOrder(100L)).thenReturn(order(PurchaseOrder.STATUS_APPROVED));
        when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(poItem("10", "10", "0")));
        stubToleranceConfig();

        ReceiptCreateRequest req = request("11", "10", null); // 11 > 10×1.05
        assertThatThrownBy(() -> service.create(req, operator("验收员")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PUR_RECEIPT_TOLERANCE_EXCEED.getCode());
        verify(inventoryService, never()).stockIn(any());
    }

    @Test
    @DisplayName("超收容差：店长账号密码授权后放行并记录授权人（BR-PUR-04）")
    void createAllowsOverToleranceWithAuthorizer() {
        PurchaseOrder order = order(PurchaseOrder.STATUS_APPROVED);
        PurchaseOrderItem item = poItem("10", "10", "0");
        stubFullReceiptFlow(order, item, "11", "10", "manager");
        when(sensitiveAuthService.authorize("manager", "pwd"))
                .thenReturn(new SensitiveAuthService.Authorizer(2L, "manager", "王店长"));

        ReceiptOrderVO vo = service.create(request("11", "10", "manager"), operator("验收员"));

        assertThat(vo.authorizerName()).isEqualTo("王店长");
        verify(inventoryService).stockIn(any(StockChange.class));
    }

    @Test
    @DisplayName("价差授权：实收单价偏离采购单价超 ±5% 且无授权被拒（BR-PUR-05）")
    void createRejectsPriceDiffWithoutAuthorizer() {
        when(purchaseOrderService.requireOrder(100L)).thenReturn(order(PurchaseOrder.STATUS_APPROVED));
        when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(poItem("10", "10", "0")));
        stubToleranceConfig();

        ReceiptCreateRequest req = request("10", "11.60", null); // +16% > 5%
        assertThatThrownBy(() -> service.create(req, operator("验收员")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PUR_RECEIPT_PRICE_AUTH.getCode());
    }

    @Test
    @DisplayName("状态校验：草稿单据不允许入库（BR-PUR-02）")
    void createRejectsDraftOrder() {
        when(purchaseOrderService.requireOrder(100L)).thenReturn(order(PurchaseOrder.STATUS_DRAFT));

        assertThatThrownBy(() -> service.create(request("10", "10", null), operator("验收员")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PUR_STATUS_INVALID.getCode());
    }

    @Test
    @DisplayName("职责分离：严格模式下验收人=创建人被拒（BR-SYS-01）")
    void createRejectsSameCreatorInStrictMode() {
        when(purchaseOrderService.requireOrder(100L)).thenReturn(order(PurchaseOrder.STATUS_APPROVED));
        when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(poItem("10", "10", "0")));
        stubToleranceConfig();
        when(configService.getBool(eq("duty_separation_strict"), eq(false))).thenReturn(true);

        // 验收人与采购单创建人同为“李采购”
        assertThatThrownBy(() -> service.create(request("10", "10", null), operator("李采购")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PUR_RECEIPT_DUTY_SEPARATION.getCode());
    }

    @Test
    @DisplayName("职责分离：非严格模式放行并记录异常日志（BR-SYS-01）")
    void createLogsDutySeparationWhenLenient() {
        PurchaseOrder order = order(PurchaseOrder.STATUS_APPROVED);
        PurchaseOrderItem item = poItem("10", "10", "0");
        stubFullReceiptFlow(order, item, "10", "10", null);
        lenient().when(configService.getBool(eq("duty_separation_strict"), eq(false))).thenReturn(false);

        service.create(request("10", "10", null), operator("李采购"));

        verify(operationLogService).recordAsync(any());
        verify(orderMapper).updateReceiptState(eq(100L), any(), any(), eq("李采购"), eq(0));
    }

    // ==================== 入库预检（FR-PUR-06 前置） ====================

    @Test
    @DisplayName("预检：返回容差上限、价差率与近 3 次均价偏离黄色提示（BR-PUR-04/05）")
    void preCheckComputesToleranceAndWarnings() {
        when(purchaseOrderService.requireOrder(100L)).thenReturn(order(PurchaseOrder.STATUS_APPROVED));
        when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(poItem("10", "10", "0")));
        stubToleranceConfig();
        when(goodsMapper.selectById(101L)).thenReturn(goods());
        when(receiptItemMapper.selectRecentAvgPrice(101L)).thenReturn(new BigDecimal("10"));

        ReceiptPreCheckVO vo = service.preCheck(request("11", "12.50", null));

        assertThat(vo.getToleranceRate()).isEqualByComparingTo("5");
        assertThat(vo.getItems()).hasSize(1);
        ReceiptPreCheckVO.ItemCheckVO line = vo.getItems().get(0);
        assertThat(line.getPendingQty()).isEqualByComparingTo("10");
        assertThat(line.getToleranceMax()).isEqualByComparingTo("10.5"); // 10×(1+5%)
        assertThat(line.isOverTolerance()).isTrue();                    // 11 > 10.5
        assertThat(line.getPriceDiffPercent()).isEqualByComparingTo("25"); // (12.5−10)/10
        assertThat(line.isPriceAuthRequired()).isTrue();                  // 25% > 5%
        assertThat(line.getRecentAvgPrice()).isEqualByComparingTo("10");
        assertThat(line.isPriceWarning()).isTrue();                       // 25% > 20% 黄色提示
        assertThat(line.isNeedAuth()).isTrue();
    }

    // ==================== 完整流程 mock（detail 回查用） ====================

    /**
     * 打通入库全链路 mock：明细加载 → 数量回写（同步更新内存对象）→ RK 单号 →
     * 明细落库 → 状态更新 → 供应商额度 → 详情回查。
     */
    private void stubFullReceiptFlow(PurchaseOrder order, PurchaseOrderItem item,
                                     String receivedQty, String receivedPrice, String authorizer) {
        when(purchaseOrderService.requireOrder(100L)).thenReturn(order);
        // loadItems 与全部入库判定共用同一列表；addReceivedQty 同步更新内存对象
        when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
        stubToleranceConfig();
        when(orderMapper.addReceivedQty(eq(200L), any())).thenAnswer(inv -> {
            item.setReceivedQty(item.getReceivedQty().add(inv.getArgument(1, BigDecimal.class)));
            return 1;
        });

        AtomicReference<ReceiptOrder> saved = new AtomicReference<>();
        when(receiptMapper.selectLastOrderNoForUpdate(any())).thenReturn(null);
        when(receiptMapper.insert(any(ReceiptOrder.class))).thenAnswer(inv -> {
            ReceiptOrder receipt = inv.getArgument(0, ReceiptOrder.class);
            receipt.setId(300L);
            saved.set(receipt);
            return 1;
        });
        when(receiptItemMapper.insert(any(ReceiptOrderItem.class))).thenAnswer(inv -> {
            ReceiptOrderItem inserted = inv.getArgument(0, ReceiptOrderItem.class);
            inserted.setId(400L);
            return 1;
        });
        when(orderMapper.updateReceiptState(eq(100L), any(), any(), anyString(), any()))
                .thenReturn(1);
        lenient().when(supplierMapper.addTotalPurchaseAmount(any(), any())).thenReturn(1);

        // detail 回查
        lenient().when(receiptMapper.selectById(300L)).thenAnswer(inv -> saved.get());
        lenient().when(orderMapper.selectById(100L)).thenReturn(order);
        lenient().when(supplierMapper.selectById(1L)).thenReturn(supplier());
        lenient().when(receiptItemMapper.selectList(any(Wrapper.class))).thenAnswer(inv -> {
            ReceiptOrderItem det = new ReceiptOrderItem();
            det.setId(400L);
            det.setReceiptId(300L);
            det.setPurchaseItemId(200L);
            det.setGoodsId(101L);
            det.setReceivedQty(new BigDecimal(receivedQty));
            det.setReceivedPrice(new BigDecimal(receivedPrice));
            det.setAmount(new BigDecimal(receivedQty).multiply(new BigDecimal(receivedPrice)));
            return List.of(det);
        });
        lenient().when(goodsMapper.selectBatchIds(any())).thenReturn(List.of(goods()));
        lenient().when(unitMapper.selectBatchIds(any())).thenReturn(List.of(unit()));
    }

    private void stubToleranceConfig() {
        lenient().when(configService.getStr(eq("receiving_tolerance_rate"), anyString())).thenReturn("5");
        lenient().when(configService.getStr(eq("price_diff_tolerance_rate"), anyString())).thenReturn("5");
        lenient().when(configService.getStr(eq("price_diff_warn_rate"), anyString())).thenReturn("20");
    }

    // ==================== 测试数据 ====================

    private LoginUser operator(String realName) {
        return new LoginUser(9L, "operator", realName, List.of("PURCHASER"), List.of("采购员"),
                Set.of(), "fp", false);
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

    /** 采购明细：数量/单价/已入库数量 */
    private PurchaseOrderItem poItem(String quantity, String price, String receivedQty) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(200L);
        item.setOrderId(100L);
        item.setGoodsId(101L);
        item.setQuantity(new BigDecimal(quantity));
        item.setPrice(new BigDecimal(price));
        item.setAmount(new BigDecimal(quantity).multiply(new BigDecimal(price)));
        item.setReceivedQty(new BigDecimal(receivedQty));
        item.setReturnedQty(BigDecimal.ZERO);
        return item;
    }

    private ReceiptCreateRequest request(String receivedQty, String receivedPrice, String authorizer) {
        ReceiptCreateRequest req = new ReceiptCreateRequest();
        req.setPurchaseOrderId(100L);
        req.setAuthorizerUsername(authorizer);
        req.setAuthorizerPassword(authorizer == null ? null : "pwd");
        ReceiptCreateRequest.ItemRequest item = new ReceiptCreateRequest.ItemRequest();
        item.setPurchaseItemId(200L);
        item.setReceivedQty(new BigDecimal(receivedQty));
        item.setReceivedPrice(new BigDecimal(receivedPrice));
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

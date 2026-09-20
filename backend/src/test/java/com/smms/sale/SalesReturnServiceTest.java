package com.smms.sale;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.goods.entity.Goods;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.inv.dto.StockChange;
import com.smms.inv.service.InventoryService;
import com.smms.member.entity.Member;
import com.smms.member.mapper.MemberMapper;
import com.smms.sale.dto.ReturnCreateRequest;
import com.smms.sale.dto.ReturnOrderDetailVO;
import com.smms.sale.entity.CashierShift;
import com.smms.sale.entity.ReturnOrder;
import com.smms.sale.entity.ReturnOrderItem;
import com.smms.sale.entity.SalesOrder;
import com.smms.sale.entity.SalesOrderItem;
import com.smms.sale.mapper.CashierShiftMapper;
import com.smms.sale.mapper.ReturnOrderItemMapper;
import com.smms.sale.mapper.ReturnOrderMapper;
import com.smms.sale.mapper.SalesOrderItemMapper;
import com.smms.sale.mapper.SalesOrderMapper;
import com.smms.sale.service.SalesReturnService;
import com.smms.security.LoginUser;
import com.smms.sys.service.SysConfigService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 销售退货单元测试：覆盖 FR-SALE-10（按原成交单价退款/回补库存/成本快照回冲/
 * 原单状态回写/会员消费冲减）、FR-SALE-11（超阈值与误操作授权、授权人≠发起人）、
 * BR-SALE-05（退货有效期店长特批）、BR-SALE-06（退款口径）、BR-SALE-07（允许退货标志）、
 * BR-SALE-09（收银员仅本人单据）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesReturnServiceTest {

    private static final long ORDER_ID = 100L;
    private static final long ITEM_ID = 1001L;
    private static final long GOODS_ID = 1L;
    private static final long RETURN_ID = 200L;
    private static final String KEY_AUTH_AMOUNT = "return_auth_amount";
    private static final String KEY_VALID_DAYS = "return_valid_days";
    private static final String KEY_DAY_START = "business_day_start";

    private static final LoginUser CASHIER = new LoginUser(5L, "cashier", "陈收银",
            List.of("CASHIER"), List.of("收银员"), Set.of("sys:ret:create"), null, false);
    private static final LoginUser OTHER_CASHIER = new LoginUser(6L, "cashier2", "李收银",
            List.of("CASHIER"), List.of("收银员"), Set.of("sys:ret:create"), null, false);
    private static final LoginUser MANAGER = new LoginUser(2L, "manager", "王店长",
            List.of("MANAGER"), List.of("店长"), Set.of(), null, false);

    @Mock private ReturnOrderMapper returnOrderMapper;
    @Mock private ReturnOrderItemMapper returnOrderItemMapper;
    @Mock private SalesOrderMapper salesOrderMapper;
    @Mock private SalesOrderItemMapper salesOrderItemMapper;
    @Mock private GoodsMapper goodsMapper;
    @Mock private MemberMapper memberMapper;
    @Mock private CashierShiftMapper shiftMapper;
    @Mock private InventoryService inventoryService;
    @Mock private SysConfigService configService;

    private SalesReturnService service;
    private final List<ReturnOrder> insertedReturns = new ArrayList<>();
    private final List<ReturnOrderItem> insertedItems = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SalesOrderItem.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReturnOrderItem.class);
        service = new SalesReturnService(returnOrderMapper, returnOrderItemMapper, salesOrderMapper,
                salesOrderItemMapper, goodsMapper, memberMapper, shiftMapper, inventoryService, configService);

        doAnswer(inv -> {
            ReturnOrder ro = inv.getArgument(0);
            ro.setId(RETURN_ID);
            insertedReturns.add(ro);
            return 1;
        }).when(returnOrderMapper).insert(any(ReturnOrder.class));
        when(returnOrderMapper.selectById(any())).thenAnswer(inv ->
                insertedReturns.stream().filter(r -> r.getId().equals(inv.getArgument(0))).findFirst().orElse(null));
        doAnswer(inv -> {
            insertedItems.add(inv.getArgument(0));
            return 1;
        }).when(returnOrderItemMapper).insert(any(ReturnOrderItem.class));
        when(returnOrderItemMapper.selectList(any())).thenAnswer(inv -> insertedItems);
        when(returnOrderMapper.selectLastReturnNoForUpdate(any())).thenReturn(null);
        when(configService.getInt(KEY_VALID_DAYS, 7)).thenReturn(7);
        when(configService.getStr(KEY_AUTH_AMOUNT, "200")).thenReturn("200");
        when(configService.getStr(KEY_DAY_START, "00:00")).thenReturn("00:00");
    }

    // ---------- 造数 ----------

    private SalesOrder order(Long memberId, String returnStatus, LocalDateTime saleTime) {
        SalesOrder order = new SalesOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("XS20260920010001");
        order.setCounterNo(1);
        order.setCashierId(CASHIER.userId());
        order.setCashierName(CASHIER.realName());
        order.setMemberId(memberId);
        order.setSaleTime(saleTime == null ? LocalDateTime.now() : saleTime);
        order.setReturnStatus(returnStatus);
        return order;
    }

    private SalesOrderItem item(String paidAmount, String quantity, String returnedQty) {
        SalesOrderItem item = new SalesOrderItem();
        item.setId(ITEM_ID);
        item.setOrderId(ORDER_ID);
        item.setGoodsId(GOODS_ID);
        item.setGoodsName("可乐 330ml");
        item.setUnitPrice(new BigDecimal("10.00"));
        item.setQuantity(new BigDecimal(quantity));
        item.setPaidAmount(new BigDecimal(paidAmount));
        item.setCostPrice(new BigDecimal("5.00"));
        item.setReturnedQty(new BigDecimal(returnedQty));
        item.setLineType("NORMAL");
        return item;
    }

    private Goods goods(int allowReturn) {
        Goods g = new Goods();
        g.setId(GOODS_ID);
        g.setName("可乐 330ml");
        g.setBarcode("B1");
        g.setAllowReturn(allowReturn);
        return g;
    }

    private Member member(String totalConsume) {
        Member m = new Member();
        m.setId(10L);
        m.setName("张三");
        m.setPhone("13800000001");
        m.setTotalConsume(new BigDecimal(totalConsume));
        m.setConsumeTimes(3);
        m.setStatus(1);
        return m;
    }

    private ReturnCreateRequest request(String refundType, String reason, String authorizer,
                                        String returnQty) {
        return new ReturnCreateRequest(ORDER_ID, refundType, reason, null, authorizer,
                List.of(new ReturnCreateRequest.ReturnItemRequest(ITEM_ID, new BigDecimal(returnQty), null)));
    }

    /** 标准场景：原单 1 行（数量 3、实付 30、成本 5），退货数量由请求决定 */
    private void mockStandard(Long memberId, String paidAmount, String quantity, String returnedQty) {
        SalesOrder order = order(memberId, "NONE", LocalDateTime.now());
        when(salesOrderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(salesOrderMapper.selectById(RETURN_ID)).thenAnswer(inv -> order);
        when(salesOrderItemMapper.selectList(any())).thenReturn(List.of(item(paidAmount, quantity, returnedQty)));
        when(goodsMapper.selectById(GOODS_ID)).thenReturn(goods(1));
        when(goodsMapper.selectBatchIds(any())).thenReturn(List.of(goods(1)));
        when(memberMapper.selectById(10L)).thenReturn(member("100.00"));
    }

    // ---------- 退货成功主流程（FR-SALE-10） ----------

    @Test
    @DisplayName("部分退货：按原成交单价比例退款、成本快照回冲、原单转部分退货、会员消费冲减")
    void createReturn_partial() {
        mockStandard(10L, "30.00", "3", "0");

        ReturnOrderDetailVO vo = service.createReturn(request("CASH", "QUALITY", null, "2"), CASHIER);

        // 退款 = 原行实付 30 × 2/3 = 20；退款单价 = 30/3 = 10（BR-SALE-06）
        ReturnOrder returnOrder = insertedReturns.get(0);
        assertThat(returnOrder.getOrderNo()).startsWith("XT").endsWith("0001");
        assertThat(returnOrder.getRefundAmount()).isEqualByComparingTo("20.00");
        assertThat(returnOrder.getAuthorizerName()).isNull();
        ReturnOrderItem ri = insertedItems.get(0);
        assertThat(ri.getRefundAmount()).isEqualByComparingTo("20.00");
        assertThat(ri.getRefundPrice()).isEqualByComparingTo("10.00");
        assertThat(ri.getCostPrice()).isEqualByComparingTo("5.00");
        assertThat(ri.getCostAmount()).isEqualByComparingTo("10.00");
        assertThat(vo.refundAmount()).isEqualByComparingTo("20.00");

        // 回补库存：SALE_RETURN + 成本快照回冲（BR-INV-06）
        verify(inventoryService).stockIn(argThat(c ->
                c.goodsId() == GOODS_ID && "SALE_RETURN".equals(c.recordType())
                        && c.qty().compareTo(new BigDecimal("2")) == 0
                        && c.unitCost().compareTo(new BigDecimal("5")) == 0
                        && "XT".equals(c.bizType())));
        // 原单明细已退数量回写 + 原单状态 PARTIAL（FR-SALE-10）
        ArgumentCaptor<SalesOrderItem> itemCaptor = ArgumentCaptor.forClass(SalesOrderItem.class);
        verify(salesOrderItemMapper).updateById(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getReturnedQty()).isEqualByComparingTo("2");
        ArgumentCaptor<SalesOrder> orderCaptor = ArgumentCaptor.forClass(SalesOrder.class);
        verify(salesOrderMapper).updateById(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getReturnStatus()).isEqualTo("PARTIAL");
        // 会员消费冲减：100 − 20 = 80（BR-MEM-06）
        ArgumentCaptor<Member> memberCaptor = ArgumentCaptor.forClass(Member.class);
        verify(memberMapper).updateById(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getTotalConsume()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("整行退完：退款=原行实付，原单退货状态转 ALL（FR-SALE-10）")
    void createReturn_fullLine() {
        mockStandard(null, "30.00", "3", "0");

        service.createReturn(request("MOBILE", "QUALITY", null, "3"), CASHIER);

        assertThat(insertedReturns.get(0).getRefundAmount()).isEqualByComparingTo("30.00");
        ArgumentCaptor<SalesOrder> orderCaptor = ArgumentCaptor.forClass(SalesOrder.class);
        verify(salesOrderMapper).updateById(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getReturnStatus()).isEqualTo("ALL");
        verify(memberMapper, never()).updateById(any(Member.class)); // 散客单不动会员
    }

    // ---------- 授权校验（FR-SALE-11） ----------

    @Test
    @DisplayName("退款超阈值：无授权人拒绝；授权人=发起人拒绝；他人授权通过并落库授权信息")
    void createReturn_authThreshold() {
        mockStandard(null, "300.00", "3", "0"); // 退 3 件全额 300 > 200

        assertThatThrownBy(() -> service.createReturn(request("CASH", "QUALITY", null, "3"), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("授权");

        assertThatThrownBy(() -> service.createReturn(request("CASH", "QUALITY", "陈收银", "3"), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("发起人");

        service.createReturn(request("CASH", "QUALITY", "王店长", "3"), CASHIER);
        ReturnOrder returnOrder = insertedReturns.get(0);
        assertThat(returnOrder.getAuthorizerName()).isEqualTo("王店长");
        assertThat(returnOrder.getAuthorizeTime()).isNotNull();
    }

    @Test
    @DisplayName("误操作退货：即使金额低于阈值也须店长授权（FR-SALE-11）")
    void createReturn_mistakeReason_requiresAuth() {
        mockStandard(null, "30.00", "3", "0");

        assertThatThrownBy(() -> service.createReturn(request("CASH", "MISTAKE", null, "1"), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("授权");
    }

    // ---------- 有效期与数据范围（BR-SALE-05/09） ----------

    @Test
    @DisplayName("超退货有效期：收银员拒绝、店长特批通过且授权人为经办店长（BR-SALE-05）")
    void createReturn_expired() {
        LocalDateTime tenDaysAgo = LocalDateTime.now().minusDays(10);
        mockStandard(null, "30.00", "3", "0");
        when(salesOrderMapper.selectById(ORDER_ID)).thenReturn(order(null, "NONE", tenDaysAgo));

        assertThatThrownBy(() -> service.createReturn(request("CASH", "QUALITY", null, "1"), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("有效期");

        ReturnOrderDetailVO vo = service.createReturn(request("CASH", "QUALITY", null, "1"), MANAGER);
        assertThat(vo.authorizerName()).isEqualTo("王店长");
    }

    @Test
    @DisplayName("收银员仅可办理本人单据退货，店长可跨收银员（BR-SALE-09）")
    void createReturn_scope() {
        mockStandard(null, "30.00", "3", "0");

        assertThatThrownBy(() -> service.createReturn(request("CASH", "QUALITY", null, "1"), OTHER_CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("本人");

        service.createReturn(request("CASH", "QUALITY", null, "1"), MANAGER);
        assertThat(insertedReturns).hasSize(1);
    }

    // ---------- 明细校验（BR-SALE-06/07） ----------

    @Test
    @DisplayName("不允许退货商品拒绝（BR-SALE-07）；超可退数量拒绝")
    void createReturn_lineValidation() {
        mockStandard(null, "30.00", "3", "0");
        when(goodsMapper.selectById(GOODS_ID)).thenReturn(goods(0));

        assertThatThrownBy(() -> service.createReturn(request("CASH", "QUALITY", null, "1"), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许退货");

        when(goodsMapper.selectById(GOODS_ID)).thenReturn(goods(1));
        assertThatThrownBy(() -> service.createReturn(request("CASH", "QUALITY", null, "4"), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("可退数量");

        // 已退 2 后仅可退 1
        when(salesOrderItemMapper.selectList(any())).thenReturn(List.of(item("30.00", "3", "2")));
        assertThatThrownBy(() -> service.createReturn(request("CASH", "QUALITY", null, "2"), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("可退数量");
    }

    @Test
    @DisplayName("原单已全部退货、退款方式与原因不合法均拒绝")
    void createReturn_miscValidation() {
        mockStandard(null, "30.00", "3", "0");
        when(salesOrderMapper.selectById(ORDER_ID)).thenReturn(order(null, "ALL", LocalDateTime.now()));
        assertThatThrownBy(() -> service.createReturn(request("CASH", "QUALITY", null, "1"), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.SALE_RETURN_FULLY_RETURNED.getMessage());

        when(salesOrderMapper.selectById(ORDER_ID)).thenReturn(order(null, "NONE", LocalDateTime.now()));
        assertThatThrownBy(() -> service.createReturn(request("BALANCE", "QUALITY", null, "1"), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("储值");
        assertThatThrownBy(() -> service.createReturn(request("ALIPAY", "QUALITY", null, "1"), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("退款方式");
        assertThatThrownBy(() -> service.createReturn(request("CASH", "WHATEVER", null, "1"), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("退货原因");

        verify(returnOrderMapper, never()).insert(any(ReturnOrder.class));
    }

    // ---------- 单号（BR-SALE-02） ----------

    @Test
    @DisplayName("退货单号 XT 前缀独立续号：当日已有 0007 则生成 0008")
    void createReturn_orderNoSequence() {
        mockStandard(null, "30.00", "3", "0");
        String prefix = "XT" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "01";
        when(returnOrderMapper.selectLastReturnNoForUpdate(prefix)).thenReturn(prefix + "0007");

        service.createReturn(request("CASH", "QUALITY", null, "1"), CASHIER);

        assertThat(insertedReturns.get(0).getOrderNo()).isEqualTo(prefix + "0008");
    }

    // ---------- 班次联动（FR-SALE-12 / BR-SALE-09） ----------

    @Test
    @DisplayName("已交班班次的单据：收银员退货被拒（SHIFT_RETURN_FORBIDDEN），店长仍可办理")
    void createReturn_closedShift() {
        mockStandard(null, "30.00", "3", "0");
        SalesOrder order = order(null, "NONE", LocalDateTime.now());
        order.setShiftId(88L);
        when(salesOrderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(salesOrderMapper.selectById(RETURN_ID)).thenAnswer(inv -> order);

        CashierShift closed = new CashierShift();
        closed.setId(88L);
        closed.setStatus(CashierShift.STATUS_CLOSED);
        closed.setEndTime(LocalDateTime.now().minusHours(1));
        when(shiftMapper.selectById(88L)).thenReturn(closed);

        // 收银员（本人单据）被拒：交班后该班次单据的退货须店长处理
        assertThatThrownBy(() -> service.createReturn(request("CASH", "QUALITY", null, "1"), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("交班");
        verify(returnOrderMapper, never()).insert(any(ReturnOrder.class));

        // 店长（全局销售角色）仍可办理
        service.createReturn(request("CASH", "QUALITY", null, "1"), MANAGER);
        assertThat(insertedReturns).hasSize(1);
        assertThat(insertedReturns.get(0).getRefundAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("在班班次的单据：收银员本人可正常退货（班次未交班不拦截）")
    void createReturn_workingShift_cashierAllowed() {
        mockStandard(null, "30.00", "3", "0");
        SalesOrder order = order(null, "NONE", LocalDateTime.now());
        order.setShiftId(88L);
        when(salesOrderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(salesOrderMapper.selectById(RETURN_ID)).thenAnswer(inv -> order);

        CashierShift working = new CashierShift();
        working.setId(88L);
        working.setStatus(CashierShift.STATUS_WORKING);
        when(shiftMapper.selectById(88L)).thenReturn(working);

        service.createReturn(request("CASH", "QUALITY", null, "1"), CASHIER);

        assertThat(insertedReturns).hasSize(1);
        assertThat(insertedReturns.get(0).getRefundAmount()).isEqualByComparingTo("10.00");
    }
}

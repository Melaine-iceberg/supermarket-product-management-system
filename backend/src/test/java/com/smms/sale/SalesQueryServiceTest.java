package com.smms.sale;

import com.smms.goods.entity.Goods;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.member.entity.Member;
import com.smms.member.mapper.MemberMapper;
import com.smms.sale.dto.SalesOrderDetailVO;
import com.smms.sale.entity.PaymentRecord;
import com.smms.sale.entity.ReturnOrder;
import com.smms.sale.entity.SalesOrder;
import com.smms.sale.entity.SalesOrderItem;
import com.smms.sale.mapper.PaymentRecordMapper;
import com.smms.sale.mapper.ReturnOrderMapper;
import com.smms.sale.mapper.SalesOrderItemMapper;
import com.smms.sale.mapper.SalesOrderMapper;
import com.smms.sale.service.SalesQueryService;
import com.smms.security.LoginUser;
import com.smms.sys.entity.OperationLog;
import com.smms.sys.service.OperationLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 销售单详情测试（FR-SALE-09/GAP-P2-03/04）：
 * 明细返回商品档案"是否允许退货"、详情返回按单号追溯的操作日志。
 */
class SalesQueryServiceTest {

    private static final Long ORDER_ID = 1L;
    private static final Long GOODS_ID = 11L;

    private final SalesOrderMapper salesOrderMapper = mock(SalesOrderMapper.class);
    private final SalesOrderItemMapper salesOrderItemMapper = mock(SalesOrderItemMapper.class);
    private final PaymentRecordMapper paymentRecordMapper = mock(PaymentRecordMapper.class);
    private final ReturnOrderMapper returnOrderMapper = mock(ReturnOrderMapper.class);
    private final MemberMapper memberMapper = mock(MemberMapper.class);
    private final GoodsMapper goodsMapper = mock(GoodsMapper.class);
    private final OperationLogService operationLogService = mock(OperationLogService.class);

    private final SalesQueryService service = new SalesQueryService(salesOrderMapper,
            salesOrderItemMapper, paymentRecordMapper, returnOrderMapper, memberMapper,
            goodsMapper, operationLogService);

    private static final LoginUser MANAGER = new LoginUser(9L, "manager", "店长",
            List.of("MANAGER"), List.of("店长"), Set.of(), "fp", false);

    @BeforeEach
    void setUp() {
        // 完整手机号可见角色（BR-MEM-08）
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(MANAGER, null, List.of()));
        lenient().when(paymentRecordMapper.selectList(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("详情：明细带商品档案是否允许退货（P2-04 退货界面禁用勾选依据）")
    void detail_includesAllowReturn() {
        SalesOrder order = order();
        when(salesOrderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(salesOrderItemMapper.selectList(any())).thenReturn(List.of(item(GOODS_ID, null)));
        when(returnOrderMapper.selectList(any())).thenReturn(List.of());
        when(memberMapper.selectById(any())).thenReturn(null);
        Goods allow = new Goods();
        allow.setId(GOODS_ID);
        allow.setAllowReturn(0);
        when(goodsMapper.selectBatchIds(anyCollection())).thenReturn(List.of(allow));

        SalesOrderDetailVO detail = service.detail(ORDER_ID, MANAGER);

        assertThat(detail.items()).hasSize(1);
        assertThat(detail.items().get(0).allowReturn()).isEqualTo(0);
    }

    @Test
    @DisplayName("详情：非店长角色手机号仅显示后 4 位（BR-MEM-08 收银台口径，GAP-P2-19）")
    void detail_masksMemberPhoneTail4ForCashier() {
        LoginUser cashier = new LoginUser(9L, "cashier", "陈收银",
                List.of("CASHIER"), List.of("收银员"), Set.of(), null, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(cashier, null, List.of()));

        SalesOrder order = order();
        order.setMemberId(88L);
        when(salesOrderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(salesOrderItemMapper.selectList(any())).thenReturn(List.of());
        when(returnOrderMapper.selectList(any())).thenReturn(List.of());
        when(goodsMapper.selectBatchIds(anyCollection())).thenReturn(List.of());
        Member member = new Member();
        member.setId(88L);
        member.setName("张会员");
        member.setPhone("13800005678");
        when(memberMapper.selectById(88L)).thenReturn(member);

        SalesOrderDetailVO detail = service.detail(ORDER_ID, cashier);

        // 收银员仅见后 4 位，不泄露完整号码（此前误用 138****5678 通用口径）
        assertThat(detail.memberPhone()).isEqualTo("****5678");
    }

    @Test
    @DisplayName("详情：商品档案已删除时 allowReturn 为空，按允许退货处理")
    void detail_deletedGoodsAllowReturnNull() {
        when(salesOrderMapper.selectById(ORDER_ID)).thenReturn(order());
        when(salesOrderItemMapper.selectList(any())).thenReturn(List.of(item(GOODS_ID, null)));
        when(returnOrderMapper.selectList(any())).thenReturn(List.of());
        when(memberMapper.selectById(any())).thenReturn(null);
        when(goodsMapper.selectBatchIds(anyCollection())).thenReturn(List.of());

        SalesOrderDetailVO detail = service.detail(ORDER_ID, MANAGER);

        assertThat(detail.items().get(0).allowReturn()).isNull();
    }

    @Test
    @DisplayName("详情：操作日志按销售单号与退货单号追溯（GAP-P2-03）")
    void detail_includesOperationLogs() {
        SalesOrder order = order();
        ReturnOrder returned = new ReturnOrder();
        returned.setId(5L);
        returned.setOrderNo("XT20261013001");
        returned.setRefundAmount(new BigDecimal("9.90"));
        returned.setReturnReason("OTHER");
        returned.setRefundType("CASH");
        returned.setCashierName("收银员");
        returned.setReturnTime(LocalDateTime.now());
        when(salesOrderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(salesOrderItemMapper.selectList(any())).thenReturn(List.of(item(GOODS_ID, null)));
        when(returnOrderMapper.selectList(any())).thenReturn(List.of(returned));
        when(memberMapper.selectById(any())).thenReturn(null);
        when(goodsMapper.selectBatchIds(anyCollection())).thenReturn(List.of());
        OperationLog checkoutLog = new OperationLog();
        checkoutLog.setId(100L);
        checkoutLog.setAction("收银结算");
        checkoutLog.setUsername("cashier");
        checkoutLog.setRealName("收银员");
        checkoutLog.setResult(1);
        checkoutLog.setOperateTime(LocalDateTime.now());
        when(operationLogService.listByTargets(anyCollection()))
                .thenReturn(List.of(checkoutLog));

        SalesOrderDetailVO detail = service.detail(ORDER_ID, MANAGER);

        assertThat(detail.operationLogs()).hasSize(1);
        assertThat(detail.operationLogs().get(0).action()).isEqualTo("收银结算");
        assertThat(detail.operationLogs().get(0).result()).isEqualTo(1);
    }

    private SalesOrder order() {
        SalesOrder order = new SalesOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("XS20261013001");
        order.setCounterNo(1);
        order.setCashierId(9L);
        order.setCashierName("收银员");
        order.setSaleTime(LocalDateTime.now());
        order.setItemCount(1);
        order.setGoodsAmount(new BigDecimal("10.00"));
        order.setPayableAmount(new BigDecimal("10.00"));
        order.setPaidAmount(new BigDecimal("10.00"));
        order.setChangeAmount(BigDecimal.ZERO);
        order.setReturnStatus("NONE");
        return order;
    }

    private SalesOrderItem item(Long goodsId, String lineType) {
        SalesOrderItem item = new SalesOrderItem();
        item.setId(21L);
        item.setOrderId(ORDER_ID);
        item.setGoodsId(goodsId);
        item.setGoodsName("可乐");
        item.setUnitPrice(new BigDecimal("10.00"));
        item.setQuantity(new BigDecimal("1"));
        item.setAmount(new BigDecimal("10.00"));
        item.setDiscountRate(BigDecimal.ONE);
        item.setDiscountAmount(BigDecimal.ZERO);
        item.setPaidAmount(new BigDecimal("10.00"));
        item.setCostPrice(new BigDecimal("6.00"));
        item.setCostAmount(new BigDecimal("6.00"));
        item.setProfitAmount(new BigDecimal("4.00"));
        item.setReturnedQty(BigDecimal.ZERO);
        item.setLineType(lineType);
        return item;
    }
}

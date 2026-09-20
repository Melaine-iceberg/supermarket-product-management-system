package com.smms.sale;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.sale.dto.ShiftQuery;
import com.smms.sale.dto.ShiftVO;
import com.smms.sale.entity.CashierShift;
import com.smms.sale.entity.PaymentRecord;
import com.smms.sale.entity.ReturnOrder;
import com.smms.sale.mapper.CashierShiftMapper;
import com.smms.sale.mapper.PaymentRecordMapper;
import com.smms.sale.mapper.ReturnOrderMapper;
import com.smms.sale.service.CashierShiftService;
import com.smms.security.LoginUser;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收银班次单元测试（FR-SALE-12）：开班登记、交班统计口径
 * （净销售 = Σ应收 − 退货额；应收现金 = 现金收款 − 现金退款）、
 * 数据范围（收银员仅本人班次，BR-SALE-09 同口径）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CashierShiftServiceTest {

    private static final long SHIFT_ID = 88L;
    private static final long ORDER_A = 101L;
    private static final long ORDER_B = 102L;

    private static final LoginUser CASHIER = new LoginUser(5L, "cashier", "陈收银",
            List.of("CASHIER"), List.of("收银员"), Set.of("sys:shift:open", "sys:shift:close"), null, false);
    private static final LoginUser MANAGER = new LoginUser(2L, "manager", "王店长",
            List.of("MANAGER"), List.of("店长"), Set.of(), null, false);

    @Mock private CashierShiftMapper shiftMapper;
    @Mock private PaymentRecordMapper paymentRecordMapper;
    @Mock private ReturnOrderMapper returnOrderMapper;

    private CashierShiftService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), CashierShift.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PaymentRecord.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReturnOrder.class);
        service = new CashierShiftService(shiftMapper, paymentRecordMapper, returnOrderMapper);
        doAnswer(inv -> {
            CashierShift s = inv.getArgument(0);
            s.setId(SHIFT_ID);
            return 1;
        }).when(shiftMapper).insert(any(CashierShift.class));
    }

    // ---------- 造数 ----------

    private ShiftVO.OrderLine orderLine(long id, String payable) {
        return new ShiftVO.OrderLine(id, "XS" + id, LocalDateTime.now(), 3,
                new BigDecimal(payable), new BigDecimal(payable), BigDecimal.ZERO, "NONE", BigDecimal.ZERO);
    }

    private PaymentRecord pay(long orderId, String payType, String amount) {
        PaymentRecord p = new PaymentRecord();
        p.setOrderId(orderId);
        p.setPayType(payType);
        p.setAmount(new BigDecimal(amount));
        return p;
    }

    private ReturnOrder ret(String refundType, String amount) {
        ReturnOrder r = new ReturnOrder();
        r.setSalesOrderId(ORDER_A);
        r.setRefundType(refundType);
        r.setRefundAmount(new BigDecimal(amount));
        return r;
    }

    private CashierShift workingShift() {
        CashierShift s = new CashierShift();
        s.setId(SHIFT_ID);
        s.setCashierId(CASHIER.userId());
        s.setCashierName(CASHIER.realName());
        s.setCounterNo(1);
        s.setStartTime(LocalDateTime.now().minusHours(3));
        s.setOrderCount(0);
        s.setSalesAmount(BigDecimal.ZERO);
        s.setCashAmount(BigDecimal.ZERO);
        s.setBankAmount(BigDecimal.ZERO);
        s.setMobileAmount(BigDecimal.ZERO);
        s.setBalanceAmount(BigDecimal.ZERO);
        s.setReturnAmount(BigDecimal.ZERO);
        s.setExpectedCash(BigDecimal.ZERO);
        s.setStatus(CashierShift.STATUS_WORKING);
        return s;
    }

    /** 标准场景：班次 2 单（应收 47.75 / 30.00），收款 CASH 50 + BANK 30 + CASH 47.75 = 127.75，退货现金 10 */
    private void mockShiftBusiness() {
        when(shiftMapper.selectWorking(CASHIER.userId())).thenReturn(workingShift());
        when(shiftMapper.selectById(SHIFT_ID)).thenReturn(workingShift());
        when(shiftMapper.selectShiftOrders(SHIFT_ID)).thenReturn(List.of(
                orderLine(ORDER_A, "47.75"), orderLine(ORDER_B, "30.00")));
        when(paymentRecordMapper.selectList(any())).thenReturn(List.of(
                pay(ORDER_A, "CASH", "50.00"), pay(ORDER_A, "BANK", "30.00"),
                pay(ORDER_B, "CASH", "47.75")));
        when(returnOrderMapper.selectList(any())).thenReturn(List.of(ret("CASH", "10.00")));
    }

    // ---------- 开班（FR-SALE-12） ----------

    @Test
    @DisplayName("开班：落库 WORKING 班次，统计字段归零；查询当前在班班次可取到")
    void openShift_success() {
        when(shiftMapper.selectWorking(CASHIER.userId())).thenReturn(null);

        ShiftVO vo = service.openShift(3, CASHIER);

        ArgumentCaptor<CashierShift> captor = ArgumentCaptor.forClass(CashierShift.class);
        verify(shiftMapper).insert(captor.capture());
        CashierShift saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(CashierShift.STATUS_WORKING);
        assertThat(saved.getCashierId()).isEqualTo(CASHIER.userId());
        assertThat(saved.getCounterNo()).isEqualTo(3);
        assertThat(saved.getExpectedCash()).isEqualByComparingTo("0");
        assertThat(vo.id()).isEqualTo(SHIFT_ID);
        assertThat(vo.status()).isEqualTo("WORKING");

        // current 返回在班班次
        when(shiftMapper.selectWorking(CASHIER.userId())).thenReturn(saved);
        assertThat(service.current(CASHIER).id()).isEqualTo(SHIFT_ID);
    }

    @Test
    @DisplayName("重复开班拒绝；台号越界拒绝")
    void openShift_invalid() {
        CashierShift working = workingShift();
        when(shiftMapper.selectWorking(CASHIER.userId())).thenReturn(working);

        assertThatThrownBy(() -> service.openShift(2, CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先交班");
        assertThatThrownBy(() -> service.openShift(0, CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1~99");
        verify(shiftMapper, never()).insert(any(CashierShift.class));
    }

    // ---------- 交班统计（FR-SALE-12） ----------

    @Test
    @DisplayName("交班：统计回写并返回交班单——单数/净销售/各方式收款/退货额/应收现金")
    void closeShift_statistics() {
        mockShiftBusiness();

        ShiftVO.Detail detail = service.closeShift(CASHIER);

        ArgumentCaptor<CashierShift> captor = ArgumentCaptor.forClass(CashierShift.class);
        verify(shiftMapper).updateById(captor.capture());
        CashierShift saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(CashierShift.STATUS_CLOSED);
        assertThat(saved.getEndTime()).isNotNull();
        assertThat(saved.getOrderCount()).isEqualTo(2);
        // 净销售 = Σ应收(77.75) − 退货(10) = 67.75
        assertThat(saved.getSalesAmount()).isEqualByComparingTo("67.75");
        // 收款：现金 97.75 / 银行 30 / 移动 0 / 储值 0
        assertThat(saved.getCashAmount()).isEqualByComparingTo("97.75");
        assertThat(saved.getBankAmount()).isEqualByComparingTo("30.00");
        assertThat(saved.getMobileAmount()).isEqualByComparingTo("0");
        assertThat(saved.getBalanceAmount()).isEqualByComparingTo("0");
        assertThat(saved.getReturnAmount()).isEqualByComparingTo("10.00");
        // 应收现金 = 现金收款 97.75 − 现金退款 10 = 87.75
        assertThat(saved.getExpectedCash()).isEqualByComparingTo("87.75");

        assertThat(detail.orders()).hasSize(2);
        assertThat(detail.shift().status()).isEqualTo("CLOSED");
        assertThat(detail.shift().expectedCash()).isEqualByComparingTo("87.75");
    }

    @Test
    @DisplayName("空班次交班：零单零金额正常关闭")
    void closeShift_empty() {
        CashierShift working = workingShift();
        when(shiftMapper.selectWorking(CASHIER.userId())).thenReturn(working);
        when(shiftMapper.selectShiftOrders(SHIFT_ID)).thenReturn(List.of());

        ShiftVO.Detail detail = service.closeShift(CASHIER);

        assertThat(detail.shift().orderCount()).isZero();
        assertThat(detail.shift().salesAmount()).isEqualByComparingTo("0");
        assertThat(detail.shift().expectedCash()).isEqualByComparingTo("0");
        assertThat(detail.orders()).isEmpty();
    }

    @Test
    @DisplayName("未开班交班拒绝（SHIFT_NOT_OPEN）")
    void closeShift_notOpen() {
        when(shiftMapper.selectWorking(CASHIER.userId())).thenReturn(null);

        assertThatThrownBy(() -> service.closeShift(CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有在班班次");
        verify(shiftMapper, never()).updateById(any(CashierShift.class));
    }

    // ---------- 查询（FR-SALE-12） ----------

    @Test
    @DisplayName("在班班次详情：统计实时计算展示（交班前可随时核对）")
    void detail_working_realtimeStats() {
        mockShiftBusiness();

        ShiftVO.Detail detail = service.detail(SHIFT_ID);

        assertThat(detail.shift().status()).isEqualTo("WORKING");
        assertThat(detail.shift().orderCount()).isEqualTo(2);
        assertThat(detail.shift().cashAmount()).isEqualByComparingTo("97.75");
        assertThat(detail.shift().expectedCash()).isEqualByComparingTo("87.75");
    }

    @Test
    @DisplayName("班次不存在拒绝；收银员查询自动限定本人班次（BR-SALE-09 同口径）")
    void page_scopeAndNotFound() {
        assertThatThrownBy(() -> service.detail(999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("班次不存在");

        ShiftQuery q = new ShiftQuery(null, null, null, null, null, null, 1, 20);
        Page<ShiftVO> emptyPage = new Page<>(1, 20);
        when(shiftMapper.selectShiftPage(any(Page.class), any(ShiftQuery.class))).thenReturn(emptyPage);
        service.page(q, CASHIER);
        ArgumentCaptor<ShiftQuery> captor = ArgumentCaptor.forClass(ShiftQuery.class);
        verify(shiftMapper).selectShiftPage(any(Page.class), captor.capture());
        assertThat(captor.getValue().cashierId()).isEqualTo(CASHIER.userId());

        service.page(q, MANAGER);
        ArgumentCaptor<ShiftQuery> managerCaptor = ArgumentCaptor.forClass(ShiftQuery.class);
        verify(shiftMapper, org.mockito.Mockito.times(2))
                .selectShiftPage(any(Page.class), managerCaptor.capture());
        assertThat(managerCaptor.getAllValues().get(1).cashierId()).isNull();
    }
}

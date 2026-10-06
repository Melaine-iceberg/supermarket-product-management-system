package com.smms.sale;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.goods.entity.Goods;
import com.smms.goods.entity.Promotion;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.goods.mapper.PromotionMapper;
import com.smms.inv.dto.StockChange;
import com.smms.inv.entity.Inventory;
import com.smms.inv.mapper.InventoryMapper;
import com.smms.inv.service.InventoryService;
import com.smms.member.entity.Member;
import com.smms.member.entity.MemberLevel;
import com.smms.member.mapper.MemberLevelMapper;
import com.smms.member.mapper.MemberMapper;
import com.smms.member.service.MemberBusinessService;
import com.smms.sale.dto.CheckoutCommand;
import com.smms.sale.dto.CheckoutVO;
import com.smms.sale.dto.MemberSearchVO;
import com.smms.sale.entity.CashierShift;
import com.smms.sale.entity.PaymentRecord;
import com.smms.sale.entity.SalesOrder;
import com.smms.sale.entity.SalesOrderItem;
import com.smms.sale.mapper.CashierShiftMapper;
import com.smms.sale.mapper.PaymentRecordMapper;
import com.smms.sale.mapper.SalesOrderMapper;
import com.smms.sale.mapper.SalesOrderItemMapper;
import com.smms.sale.service.SalesCheckoutService;
import com.smms.security.LoginUser;
import com.smms.sys.service.SensitiveAuthService;
import com.smms.sys.service.SysConfigService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收银结算核心单元测试：覆盖 BR-SALE-01（一行一商品/数量精度）、BR-SALE-02（单号）、
 * BR-SALE-03（金额分摊与尾差）、BR-SALE-04（快照）、BR-GOODS-04（取价优先级）、
 * FR-SALE-05（会员折扣/停用拒绝）、FR-SALE-06（收款校验/原子扣减/会员统计）、
 * FR-SALE-07（整单折扣授权）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesCheckoutServiceTest {

    private static final long GOODS_A = 1L;
    private static final long GOODS_B = 2L;
    private static final long GOODS_C = 3L;
    private static final long MEMBER_ID = 10L;
    private static final long ORDER_ID = 100L;
    private static final String KEY_MIN_DISCOUNT = "min_discount_rate";
    private static final String KEY_DAY_START = "business_day_start";
    private static final LoginUser CASHIER = new LoginUser(5L, "cashier", "陈收银",
            List.of("CASHIER"), List.of("收银员"), Set.of("sys:cashier:enter"), null, false);

    @Mock private SalesOrderMapper salesOrderMapper;
    @Mock private SalesOrderItemMapper salesOrderItemMapper;
    @Mock private PaymentRecordMapper paymentRecordMapper;
    @Mock private GoodsMapper goodsMapper;
    @Mock private PromotionMapper promotionMapper;
    @Mock private InventoryMapper inventoryMapper;
    @Mock private MemberMapper memberMapper;
    @Mock private MemberLevelMapper memberLevelMapper;
    @Mock private CashierShiftMapper shiftMapper;
    @Mock private InventoryService inventoryService;
    @Mock private SysConfigService configService;
    @Mock private MemberBusinessService memberBusinessService;
    @Mock private SensitiveAuthService sensitiveAuthService;

    private SalesCheckoutService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Goods.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Promotion.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Member.class);
        service = new SalesCheckoutService(salesOrderMapper, salesOrderItemMapper, paymentRecordMapper,
                goodsMapper, promotionMapper, inventoryMapper, memberMapper, memberLevelMapper,
                shiftMapper, inventoryService, configService, memberBusinessService, sensitiveAuthService);
    }

    // ---------- 造数 ----------

    private Goods goods(long id, String name, String retail, String memberPrice, int weighed) {
        Goods g = new Goods();
        g.setId(id);
        g.setName(name);
        g.setBarcode("B" + id);
        g.setRetailPrice(new BigDecimal(retail));
        g.setMemberPrice(memberPrice == null ? null : new BigDecimal(memberPrice));
        g.setIsWeighed(weighed);
        g.setStatus(1);
        return g;
    }

    private Inventory inv(long goodsId, String cost) {
        Inventory i = new Inventory();
        i.setGoodsId(goodsId);
        i.setQuantity(new BigDecimal("100"));
        i.setCostPrice(new BigDecimal(cost));
        return i;
    }

    private Promotion promo(long goodsId, String price) {
        Promotion p = new Promotion();
        p.setGoodsId(goodsId);
        p.setPromoPrice(new BigDecimal(price));
        p.setStatus("ACTIVE");
        p.setStartTime(LocalDateTime.now().minusHours(1));
        p.setEndTime(LocalDateTime.now().plusHours(1));
        return p;
    }

    private Member member(String totalConsume, int times, int status) {
        Member m = new Member();
        m.setId(MEMBER_ID);
        m.setMemberNo("M00000001");
        m.setName("张三");
        m.setPhone("13800000001");
        m.setLevelId(1L);
        m.setTotalConsume(new BigDecimal(totalConsume));
        m.setConsumeTimes(times);
        m.setStatus(status);
        return m;
    }

    private MemberLevel level(String rate) {
        MemberLevel l = new MemberLevel();
        l.setId(1L);
        l.setName("银卡会员");
        l.setDiscountRate(new BigDecimal(rate));
        l.setStatus(1);
        return l;
    }

    private CheckoutCommand.CheckoutItem item(long goodsId, String qty) {
        return new CheckoutCommand.CheckoutItem(goodsId, new BigDecimal(qty), null, null);
    }

    private CheckoutCommand.CheckoutPayment pay(String type, String amount) {
        return new CheckoutCommand.CheckoutPayment(type, new BigDecimal(amount));
    }

    /** 标准三行单：A×2（零售10/会员9）、B×1（促销15，零售20）、C×1.5kg（称重8.5） */
    private CheckoutCommand command(Long memberId, BigDecimal orderRate, String authorizer,
                                    List<CheckoutCommand.CheckoutPayment> payments) {
        return new CheckoutCommand(1, memberId, orderRate, authorizer,
                authorizer == null ? null : "pwd", null,
                List.of(item(GOODS_A, "2"), item(GOODS_B, "1"), item(GOODS_C, "1.5")),
                payments);
    }

    private void mockGoodsAndPromoAndStock() {
        when(goodsMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                goods(GOODS_A, "可乐 330ml", "10.00", "9.00", 0),
                goods(GOODS_B, "洗衣液 2L", "20.00", null, 0),
                goods(GOODS_C, "苹果", "8.50", null, 1)));
        when(promotionMapper.selectList(any())).thenReturn(List.of(promo(GOODS_B, "15.00")));
        when(inventoryMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                inv(GOODS_A, "6.00"), inv(GOODS_B, "9.00"), inv(GOODS_C, "4.00")));
    }

    private void mockOrderInsertSuccess() {
        when(configService.getStr(KEY_MIN_DISCOUNT, "0.7")).thenReturn("0.7");
        when(configService.getStr(KEY_DAY_START, "00:00")).thenReturn("00:00");
        when(salesOrderMapper.selectLastOrderNoForUpdate(any())).thenReturn(null);
        doAnswer(inv -> {
            SalesOrder order = inv.getArgument(0);
            order.setId(ORDER_ID);
            return 1;
        }).when(salesOrderMapper).insert(any(SalesOrder.class));
    }

    private SalesOrder capturedOrder() {
        ArgumentCaptor<SalesOrder> captor = ArgumentCaptor.forClass(SalesOrder.class);
        verify(salesOrderMapper, atLeastOnce()).insert(captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }

    private List<SalesOrderItem> capturedItems() {
        ArgumentCaptor<SalesOrderItem> captor = ArgumentCaptor.forClass(SalesOrderItem.class);
        verify(salesOrderItemMapper, atLeastOnce()).insert(captor.capture());
        return captor.getAllValues();
    }

    // ---------- 结算主流程：散客单（BR-GOODS-04/BR-SALE-04/FR-SALE-06） ----------

    @Test
    @DisplayName("散客结算：促销价/零售价取价、快照落库、原子扣减、收款与找零、单号 XS 前缀")
    void checkout_guest_success() {
        mockGoodsAndPromoAndStock();
        mockOrderInsertSuccess();

        CheckoutVO vo = service.checkout(command(null, null, null, List.of(pay("CASH", "50.00"))), CASHIER);

        // 取价：A 散客按零售价 10；B 有生效促销按 15；C 称重单价 8.5
        SalesOrder order = capturedOrder();
        assertThat(vo.orderNo()).startsWith("XS").contains(LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)).endsWith("0001");
        assertThat(order.getItemCount()).isEqualTo(4);
        assertThat(order.getGoodsAmount()).isEqualByComparingTo("47.75"); // 20 + 15 + 12.75
        assertThat(order.getDiscountAmount()).isEqualByComparingTo("0");
        assertThat(order.getPayableAmount()).isEqualByComparingTo("47.75");
        assertThat(order.getPaidAmount()).isEqualByComparingTo("50.00");
        assertThat(order.getChangeAmount()).isEqualByComparingTo("2.25");
        assertThat(order.getReturnStatus()).isEqualTo("NONE");
        assertThat(order.getBusinessDate()).isEqualTo(LocalDate.now());

        List<SalesOrderItem> items = capturedItems();
        SalesOrderItem a = items.get(0);
        assertThat(a.getUnitPrice()).isEqualByComparingTo("10.00"); // 散客不享受会员价
        assertThat(a.getAmount()).isEqualByComparingTo("20.00");
        assertThat(a.getCostPrice()).isEqualByComparingTo("6.00");  // 成本快照（BR-SALE-04）
        assertThat(a.getCostAmount()).isEqualByComparingTo("12.00");
        assertThat(a.getProfitAmount()).isEqualByComparingTo("8.00");
        SalesOrderItem b = items.get(1);
        assertThat(b.getUnitPrice()).isEqualByComparingTo("15.00"); // 促销价优先
        SalesOrderItem c = items.get(2);
        assertThat(c.getIsWeighed()).isEqualTo(1);
        assertThat(c.getNetWeight()).isEqualByComparingTo("1.5");
        assertThat(c.getAmount()).isEqualByComparingTo("12.75");

        // 出库：按 goodsId 升序逐行原子扣减（BR-INV-03）
        InOrder orderCall = inOrder(inventoryService);
        orderCall.verify(inventoryService).stockOut(argThat(stockChangeOf(GOODS_A, "2")));
        orderCall.verify(inventoryService).stockOut(argThat(stockChangeOf(GOODS_B, "1")));
        orderCall.verify(inventoryService).stockOut(argThat(stockChangeOf(GOODS_C, "1.5")));

        verify(paymentRecordMapper).insert(any(PaymentRecord.class));
        verify(memberMapper, never()).updateById(any(Member.class));
        assertThat(vo.payableAmount()).isEqualByComparingTo("47.75");
        assertThat(vo.changeAmount()).isEqualByComparingTo("2.25");
    }

    private static org.mockito.ArgumentMatcher<StockChange> stockChangeOf(long goodsId, String qty) {
        return c -> c.goodsId() == goodsId && c.qty().compareTo(new BigDecimal(qty)) == 0
                && "SALE_OUT".equals(c.recordType()) && "XS".equals(c.bizType())
                && c.bizNo() != null && c.bizNo().startsWith("XS");
    }

    // ---------- 会员折扣 + 店长整单折扣 + 金额分摊（BR-SALE-03/10，FR-SALE-05/07） ----------

    @Test
    @DisplayName("会员单+整单折扣：0.98×0.9=0.882 分摊到行，尾差入最后一行，Σ行实付=应收")
    void checkout_memberAndOrderDiscount_allocation() {
        mockGoodsAndPromoAndStock();
        mockOrderInsertSuccess();
        when(memberMapper.selectById(MEMBER_ID)).thenReturn(member("100.00", 3, 1));
        when(memberLevelMapper.selectById(1L)).thenReturn(level("0.98"));
        when(sensitiveAuthService.authorize("manager1", "pwd"))
                .thenReturn(new SensitiveAuthService.Authorizer(9L, "manager1", "王店长"));

        CheckoutVO vo = service.checkout(
                command(MEMBER_ID, new BigDecimal("0.9"), "manager1", List.of(pay("MOBILE", "40.35"))), CASHIER);

        // 商品总额 45.75 = 9×2（会员价） + 15（促销价优先于会员价） + 8.5×1.5
        SalesOrder order = capturedOrder();
        assertThat(order.getGoodsAmount()).isEqualByComparingTo("45.75");
        // 整单优惠 45.75×0.118 = 5.40；会员优惠拆分 45.75×0.02 = 0.92
        assertThat(order.getDiscountAmount()).isEqualByComparingTo("5.40");
        assertThat(order.getMemberDiscountAmount()).isEqualByComparingTo("0.92");
        assertThat(order.getOrderDiscountAmount()).isEqualByComparingTo("4.48");
        assertThat(order.getPayableAmount()).isEqualByComparingTo("40.35");
        assertThat(order.getAuthorizerName()).isEqualTo("王店长");
        assertThat(order.getOrderDiscountRate()).isEqualByComparingTo("0.9");

        // 分摊：A 18×0.118=2.12；B 15×0.118=1.77；C 尾差 5.40-2.12-1.77=1.51
        List<SalesOrderItem> items = capturedItems();
        assertThat(items.get(0).getUnitPrice()).isEqualByComparingTo("9.00"); // 会员价
        assertThat(items.get(0).getDiscountAmount()).isEqualByComparingTo("2.12");
        assertThat(items.get(0).getPaidAmount()).isEqualByComparingTo("15.88");
        assertThat(items.get(1).getDiscountAmount()).isEqualByComparingTo("1.77");
        assertThat(items.get(1).getPaidAmount()).isEqualByComparingTo("13.23");
        assertThat(items.get(2).getDiscountAmount()).isEqualByComparingTo("1.51"); // 尾差入最后一行
        assertThat(items.get(2).getPaidAmount()).isEqualByComparingTo("11.24");
        // Σ行实付 = 应收（BR-SALE-03 对账不变量）
        BigDecimal sumPaid = items.stream().map(SalesOrderItem::getPaidAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumPaid).isEqualByComparingTo(order.getPayableAmount());

        // 会员结算联动（BR-MEM-05/06/07）：累计消费/积分/等级交由 MemberBusinessService
        verify(memberBusinessService).settleEffects(eq(MEMBER_ID),
                argThat(p -> p.compareTo(new BigDecimal("40.35")) == 0),
                argThat(b -> b.compareTo(BigDecimal.ZERO) == 0),
                argThat(n -> n != null && n.startsWith("XS")),
                eq("陈收银"), any());
        verify(memberMapper, never()).updateById(any(Member.class));
        assertThat(vo.payableAmount()).isEqualByComparingTo("40.35");
        assertThat(vo.changeAmount()).isEqualByComparingTo("0.00");
    }

    // ---------- 取价优先级（BR-GOODS-04） ----------

    @Test
    @DisplayName("会员单取价：无促销时用会员价；无会员价时退回零售价")
    void checkout_memberPricing() {
        when(goodsMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                goods(GOODS_A, "可乐", "10.00", "9.00", 0),
                goods(GOODS_B, "洗衣液", "20.00", null, 0)));
        when(promotionMapper.selectList(any())).thenReturn(List.of());
        when(inventoryMapper.selectBatchIds(anyCollection())).thenReturn(List.of(inv(GOODS_A, "6"), inv(GOODS_B, "9")));
        mockOrderInsertSuccess();
        when(memberMapper.selectById(MEMBER_ID)).thenReturn(member("0", 0, 1));
        when(memberLevelMapper.selectById(1L)).thenReturn(level("1"));

        service.checkout(new CheckoutCommand(1, MEMBER_ID, null, null, null, null,
                List.of(item(GOODS_A, "1"), item(GOODS_B, "1")),
                List.of(pay("CASH", "30"))), CASHIER);

        List<SalesOrderItem> items = capturedItems();
        assertThat(items.get(0).getUnitPrice()).isEqualByComparingTo("9.00");  // 会员价
        assertThat(items.get(1).getUnitPrice()).isEqualByComparingTo("20.00"); // 无会员价回退零售价
    }

    // ---------- 明细校验（BR-SALE-01） ----------

    @Test
    @DisplayName("同一商品重复成行拒绝（BR-SALE-01）")
    void checkout_duplicateGoods_rejected() {
        CheckoutCommand cmd = new CheckoutCommand(1, null, null, null, null, null,
                List.of(item(GOODS_A, "1"), item(GOODS_A, "2")), List.of(pay("CASH", "30")));

        assertThatThrownBy(() -> service.checkout(cmd, CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("合并为一行");
        verify(salesOrderMapper, never()).insert(any(SalesOrder.class));
    }

    @Test
    @DisplayName("普通商品数量必须为正整数，称重商品净重最多 3 位小数（BR-SALE-01）")
    void checkout_quantityRules() {
        mockGoodsAndPromoAndStock();

        CheckoutCommand decimalQty = new CheckoutCommand(1, null, null, null, null, null,
                List.of(item(GOODS_A, "1.5")), List.of(pay("CASH", "15")));
        assertThatThrownBy(() -> service.checkout(decimalQty, CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("正整数");

        CheckoutCommand tooFine = new CheckoutCommand(1, null, null, null, null, null,
                List.of(item(GOODS_C, "1.2345")), List.of(pay("CASH", "15")));
        assertThatThrownBy(() -> service.checkout(tooFine, CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("3 位小数");
    }

    @Test
    @DisplayName("停用/不存在商品不可销售（FR-GOODS-05）")
    void checkout_goodsDisabled_rejected() {
        Goods disabled = goods(GOODS_A, "可乐", "10", null, 0);
        disabled.setStatus(0);
        when(goodsMapper.selectBatchIds(anyCollection())).thenReturn(List.of(disabled));

        CheckoutCommand cmd = new CheckoutCommand(1, null, null, null, null, null,
                List.of(item(GOODS_A, "1")), List.of(pay("CASH", "10")));
        assertThatThrownBy(() -> service.checkout(cmd, CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已停用");
    }

    // ---------- 收款校验（FR-SALE-06） ----------

    @Test
    @DisplayName("实收不足应收拒绝；储值与非法方式拒绝")
    void checkout_paymentValidation() {
        mockGoodsAndPromoAndStock();
        mockOrderInsertSuccess();

        assertThatThrownBy(() -> service.checkout(command(null, null, null, List.of(pay("CASH", "30"))), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不足应收");

        assertThatThrownBy(() -> service.checkout(command(null, null, null, List.of(pay("BALANCE", "40"))), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("储值");

        assertThatThrownBy(() -> service.checkout(command(null, null, null, List.of(pay("BITCOIN", "40"))), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("收款方式不合法");

        verify(salesOrderMapper, never()).insert(any(SalesOrder.class));
    }

    @Test
    @DisplayName("多方式组合收款：现金+移动支付各一笔，实收为合计（FR-SALE-06）")
    void checkout_combinedPayments() {
        mockGoodsAndPromoAndStock();
        mockOrderInsertSuccess();

        CheckoutVO vo = service.checkout(command(null, null, null,
                List.of(pay("CASH", "20.00"), pay("MOBILE", "27.75"))), CASHIER);

        assertThat(vo.paidAmount()).isEqualByComparingTo("47.75");
        assertThat(vo.changeAmount()).isEqualByComparingTo("0.00");
        verify(paymentRecordMapper, times(2)).insert(any(PaymentRecord.class));
    }

    // ---------- 储值支付（FR-MEM-08，BR-MEM-07） ----------

    @Test
    @DisplayName("储值支付：会员单 BALANCE+现金组合联动收到储值额；超应收与余额不足拒绝")
    void checkout_balancePayment() {
        mockGoodsAndPromoAndStock();
        mockOrderInsertSuccess();
        Member m = member("100.00", 3, 1);
        m.setBalance(new BigDecimal("30.00"));
        when(memberMapper.selectById(MEMBER_ID)).thenReturn(m);
        when(memberLevelMapper.selectById(1L)).thenReturn(level("1"));

        // 会员单总额：9×2 + 15 + 8.5×1.5 = 45.75；储值 20 + 现金 25.75
        CheckoutVO vo = service.checkout(command(MEMBER_ID, null, null,
                List.of(pay("BALANCE", "20.00"), pay("CASH", "25.75"))), CASHIER);

        assertThat(vo.payableAmount()).isEqualByComparingTo("45.75");
        assertThat(vo.memberLevelUpgraded()).isNull();
        verify(memberBusinessService).settleEffects(eq(MEMBER_ID),
                argThat(p -> p.compareTo(new BigDecimal("45.75")) == 0),
                argThat(b -> b.compareTo(new BigDecimal("20.00")) == 0),
                argThat(n -> n != null && n.startsWith("XS")),
                eq("陈收银"), any());

        // 储值支付额超过应收拒绝（储值不参与找零）
        assertThatThrownBy(() -> service.checkout(command(MEMBER_ID, null, null,
                List.of(pay("BALANCE", "50.00"))), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不得超过应收");

        // 储值余额不足拒绝（BR-MEM-07 余额不可为负）
        assertThatThrownBy(() -> service.checkout(command(MEMBER_ID, null, null,
                List.of(pay("BALANCE", "35.00"), pay("CASH", "20"))), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("储值余额不足");
    }

    // ---------- 整单折扣授权（FR-SALE-07，BR-SALE-05/10） ----------

    @Test
    @DisplayName("整单折扣低于下限拒绝；折扣未填授权人拒绝")
    void checkout_orderDiscountValidation() {
        mockGoodsAndPromoAndStock();

        when(configService.getStr(KEY_MIN_DISCOUNT, "0.7")).thenReturn("0.7");
        assertThatThrownBy(() -> service.checkout(command(null, new BigDecimal("0.6"), "王店长",
                List.of(pay("CASH", "25"))), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不得低于");

        assertThatThrownBy(() -> service.checkout(command(null, new BigDecimal("0.9"), "  ",
                List.of(pay("CASH", "32"))), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("授权");
    }

    @Test
    @DisplayName("整单折扣授权人不得为当前收银员本人（FR-SALE-07）")
    void checkout_orderDiscountAuthorizerSelf_rejected() {
        mockGoodsAndPromoAndStock();
        when(configService.getStr(KEY_MIN_DISCOUNT, "0.7")).thenReturn("0.7");
        when(sensitiveAuthService.authorize("cashier", "pwd"))
                .thenReturn(new SensitiveAuthService.Authorizer(CASHIER.userId(), "cashier", "陈收银"));

        assertThatThrownBy(() -> service.checkout(command(null, new BigDecimal("0.9"), "cashier",
                List.of(pay("CASH", "32"))), CASHIER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SALE_DISCOUNT_AUTH_SELF.getCode());
        verify(salesOrderMapper, never()).insert(any(SalesOrder.class));
    }

    // ---------- 会员校验（FR-SALE-05，BR-MEM-03） ----------

    @Test
    @DisplayName("会员不存在/已停用拒绝结算")
    void checkout_memberValidation() {
        mockGoodsAndPromoAndStock();
        mockOrderInsertSuccess();
        when(memberMapper.selectById(MEMBER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.checkout(command(MEMBER_ID, null, null,
                List.of(pay("CASH", "50"))), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.SALE_MEMBER_NOT_FOUND.getMessage());

        when(memberMapper.selectById(MEMBER_ID)).thenReturn(member("0", 0, 0));
        assertThatThrownBy(() -> service.checkout(command(MEMBER_ID, null, null,
                List.of(pay("CASH", "50"))), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("停用");
    }

    // ---------- 单号生成（BR-SALE-02） ----------

    @Test
    @DisplayName("单号续号：当日已有 0007 则生成 0008（BR-SALE-02）")
    void checkout_orderNoSequence() {
        mockGoodsAndPromoAndStock();
        when(configService.getStr(KEY_MIN_DISCOUNT, "0.7")).thenReturn("0.7");
        when(configService.getStr(KEY_DAY_START, "00:00")).thenReturn("00:00");
        String prefix = "XS" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "01";
        when(salesOrderMapper.selectLastOrderNoForUpdate(prefix)).thenReturn(prefix + "0007");
        doAnswer(inv -> {
            SalesOrder order = inv.getArgument(0);
            order.setId(ORDER_ID);
            return 1;
        }).when(salesOrderMapper).insert(any(SalesOrder.class));

        service.checkout(command(null, null, null, List.of(pay("CASH", "50"))), CASHIER);

        assertThat(capturedOrder().getOrderNo()).isEqualTo(prefix + "0008");
    }

    @Test
    @DisplayName("单号并发冲突：唯一键兜底换单号重试后成功（BR-SALE-02）")
    void checkout_orderNoConflictRetry() {
        mockGoodsAndPromoAndStock();
        when(configService.getStr(KEY_MIN_DISCOUNT, "0.7")).thenReturn("0.7");
        when(configService.getStr(KEY_DAY_START, "00:00")).thenReturn("00:00");
        String prefix = "XS" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "01";
        // 第二次读到了并发事务已提交的 0001（行锁释放后）
        when(salesOrderMapper.selectLastOrderNoForUpdate(prefix)).thenReturn(null, prefix + "0001");
        doThrow(new DuplicateKeyException("dup"))
                .doAnswer(inv -> {
                    SalesOrder order = inv.getArgument(0);
                    order.setId(ORDER_ID);
                    return 1;
                }).when(salesOrderMapper).insert(any(SalesOrder.class));

        service.checkout(command(null, null, null, List.of(pay("CASH", "50"))), CASHIER);

        verify(salesOrderMapper, times(2)).insert(any(SalesOrder.class));
        assertThat(capturedOrder().getOrderNo()).isEqualTo(prefix + "0002");
    }

    // ---------- 库存不足冒烟：扣减异常时事务回滚由 @Transactional 保证 ----------

    @Test
    @DisplayName("库存不足时结算失败：库存服务抛出异常即终止（BR-INV-02/03）")
    void checkout_stockNotEnough_propagates() {
        mockGoodsAndPromoAndStock();
        mockOrderInsertSuccess();
        doThrow(new BusinessException(ErrorCode.STOCK_NOT_ENOUGH, "库存不足"))
                .when(inventoryService).stockOut(any(StockChange.class));

        assertThatThrownBy(() -> service.checkout(command(null, null, null,
                List.of(pay("CASH", "50"))), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存不足");
    }

    // ---------- 无码商品临时登记（FR-SALE-13，Could） ----------

    @Test
    @DisplayName("无码商品：名称金额直接入单、lineType=TEMP、成本为0、不扣库存")
    void checkout_tempLine_success() {
        when(goodsMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                goods(GOODS_A, "可乐 330ml", "10.00", null, 0)));
        when(promotionMapper.selectList(any())).thenReturn(List.of());
        when(inventoryMapper.selectBatchIds(anyCollection())).thenReturn(List.of(inv(GOODS_A, "6.00")));
        mockOrderInsertSuccess();

        CheckoutCommand.CheckoutItem temp = new CheckoutCommand.CheckoutItem(
                null, "手工蛋糕", new BigDecimal("12.50"), BigDecimal.ONE, null, null);
        CheckoutCommand cmd = new CheckoutCommand(1, null, null, null, null, null,
                List.of(item(GOODS_A, "1"), temp), List.of(pay("CASH", "22.50")));

        CheckoutVO vo = service.checkout(cmd, CASHIER);

        // 商品总额 = 10 + 12.50；件数 = 1 + 1
        assertThat(vo.goodsAmount()).isEqualByComparingTo("22.50");
        SalesOrder order = capturedOrder();
        assertThat(order.getItemCount()).isEqualTo(2);
        assertThat(order.getPayableAmount()).isEqualByComparingTo("22.50");

        List<SalesOrderItem> items = capturedItems();
        SalesOrderItem tempItem = items.get(1);
        assertThat(tempItem.getGoodsId()).isNull();
        assertThat(tempItem.getGoodsName()).isEqualTo("手工蛋糕");
        assertThat(tempItem.getBarcode()).isNull();
        assertThat(tempItem.getLineType()).isEqualTo("TEMP");
        assertThat(tempItem.getQuantity()).isEqualByComparingTo("1");
        assertThat(tempItem.getAmount()).isEqualByComparingTo("12.50");
        assertThat(tempItem.getPaidAmount()).isEqualByComparingTo("12.50");
        assertThat(tempItem.getCostPrice()).isEqualByComparingTo("0");
        assertThat(tempItem.getCostAmount()).isEqualByComparingTo("0");
        assertThat(tempItem.getProfitAmount()).isEqualByComparingTo("12.50"); // 无成本档案，毛利=实付

        // 库存仅对档案商品扣减（无码商品不扣库存，FR-SALE-13）
        verify(inventoryService, times(1)).stockOut(any(StockChange.class));
        verify(inventoryService).stockOut(argThat(stockChangeOf(GOODS_A, "1")));
    }

    @Test
    @DisplayName("纯无码商品单：不查商品/促销/库存档案，结算成功")
    void checkout_tempOnly_success() {
        mockOrderInsertSuccess();

        CheckoutCommand cmd = new CheckoutCommand(1, null, null, null, null, null,
                List.of(new CheckoutCommand.CheckoutItem(null, "散装点心", new BigDecimal("8.00"), BigDecimal.ONE, null, null)),
                List.of(pay("MOBILE", "8.00")));

        CheckoutVO vo = service.checkout(cmd, CASHIER);

        assertThat(vo.goodsAmount()).isEqualByComparingTo("8.00");
        assertThat(vo.payableAmount()).isEqualByComparingTo("8.00");
        assertThat(capturedItems().get(0).getLineType()).isEqualTo("TEMP");
        verify(inventoryService, never()).stockOut(any(StockChange.class));
        verify(goodsMapper, never()).selectBatchIds(anyCollection());
    }

    @Test
    @DisplayName("会员单含无码商品：整单优惠按比例分摊到含无码的全部行（BR-SALE-03）")
    void checkout_tempLine_memberDiscountAllocation() {
        when(goodsMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                goods(GOODS_A, "可乐", "10.00", null, 0)));
        when(promotionMapper.selectList(any())).thenReturn(List.of());
        when(inventoryMapper.selectBatchIds(anyCollection())).thenReturn(List.of(inv(GOODS_A, "6.00")));
        mockOrderInsertSuccess();
        when(memberMapper.selectById(MEMBER_ID)).thenReturn(member("100.00", 3, 1));
        when(memberLevelMapper.selectById(1L)).thenReturn(level("0.9"));

        CheckoutCommand cmd = new CheckoutCommand(1, MEMBER_ID, null, null, null, null,
                List.of(item(GOODS_A, "1"),
                        new CheckoutCommand.CheckoutItem(null, "代售报刊", new BigDecimal("10.00"), BigDecimal.ONE, null, null)),
                List.of(pay("CASH", "18")));

        service.checkout(cmd, CASHIER);

        // 总额 20 × 0.9 = 18；行优惠 1.00 + 1.00，Σ行实付 = 应收
        List<SalesOrderItem> items = capturedItems();
        assertThat(items.get(0).getDiscountAmount()).isEqualByComparingTo("1.00");
        assertThat(items.get(0).getPaidAmount()).isEqualByComparingTo("9.00");
        assertThat(items.get(1).getDiscountAmount()).isEqualByComparingTo("1.00");
        assertThat(items.get(1).getPaidAmount()).isEqualByComparingTo("9.00");
        BigDecimal sumPaid = items.stream().map(SalesOrderItem::getPaidAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumPaid).isEqualByComparingTo(capturedOrder().getPayableAmount());
    }

    @Test
    @DisplayName("无码商品：名称缺失/金额非法拒绝结算（FR-SALE-13）")
    void checkout_tempLine_validation() {
        CheckoutCommand blankName = new CheckoutCommand(1, null, null, null, null, null,
                List.of(new CheckoutCommand.CheckoutItem(null, "  ", new BigDecimal("5"), BigDecimal.ONE, null, null)),
                List.of(pay("CASH", "5")));
        assertThatThrownBy(() -> service.checkout(blankName, CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("名称不能为空");

        CheckoutCommand zeroAmount = new CheckoutCommand(1, null, null, null, null, null,
                List.of(new CheckoutCommand.CheckoutItem(null, "散装糖果", new BigDecimal("0"), BigDecimal.ONE, null, null)),
                List.of(pay("CASH", "5")));
        assertThatThrownBy(() -> service.checkout(zeroAmount, CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("金额必须大于 0");

        CheckoutCommand fineScale = new CheckoutCommand(1, null, null, null, null, null,
                List.of(new CheckoutCommand.CheckoutItem(null, "散装糖果", new BigDecimal("1.234"), BigDecimal.ONE, null, null)),
                List.of(pay("CASH", "5")));
        assertThatThrownBy(() -> service.checkout(fineScale, CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2 位小数");

        verify(salesOrderMapper, never()).insert(any(SalesOrder.class));
    }

    // ---------- 会员检索（FR-SALE-05） ----------

    @Test
    @DisplayName("会员检索：手机号/编号/姓名命中并带出等级折扣率，停用会员折扣率为 1（BR-MEM-03）")
    void searchMembers_mapsAndFiltersDisabled() {
        Member normal = member("0", 0, 1);
        normal.setBalance(new BigDecimal("88.50"));
        Member disabled = member("500.00", 5, 0);
        disabled.setId(11L);
        when(memberMapper.selectList(any())).thenReturn(List.of(disabled, normal));
        when(memberLevelMapper.selectBatchIds(anyCollection())).thenReturn(List.of(level("0.98")));

        List<MemberSearchVO> result = service.searchMembers("138");

        assertThat(result).hasSize(2);
        MemberSearchVO normalVO = result.get(1);
        assertThat(normalVO.memberNo()).isEqualTo("M00000001");
        assertThat(normalVO.levelName()).isEqualTo("银卡会员");
        assertThat(normalVO.discountRate()).isEqualByComparingTo("0.98");
        // 手机号脱敏（BR-MEM-08：收银台仅显示后 4 位）与储值余额回显（BR-MEM-07）
        assertThat(normalVO.phone()).isEqualTo("138****0001");
        assertThat(normalVO.balance()).isEqualByComparingTo("88.50");
        MemberSearchVO disabledVO = result.get(0);
        assertThat(disabledVO.status()).isZero();
        assertThat(disabledVO.discountRate()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("会员检索：空关键字返回空列表")
    void searchMembers_blankKeyword() {
        assertThat(service.searchMembers(" ")).isEmpty();
        verify(memberMapper, never()).selectList(any());
    }

    // ---------- 班次关联（FR-SALE-12） ----------

    @Test
    @DisplayName("结算关联在班班次：开班后单据写入 shiftId；未开班 shiftId 为空")
    void checkout_bindsWorkingShift() {
        mockGoodsAndPromoAndStock();
        mockOrderInsertSuccess();

        // 未开班：shiftId 为空
        when(shiftMapper.selectWorking(CASHIER.userId())).thenReturn(null);
        service.checkout(command(null, null, null, List.of(pay("CASH", "50.00"))), CASHIER);
        assertThat(capturedOrder().getShiftId()).isNull();

        // 在班：写入班次ID
        CashierShift working = new CashierShift();
        working.setId(88L);
        working.setCashierId(CASHIER.userId());
        working.setStatus(CashierShift.STATUS_WORKING);
        when(shiftMapper.selectWorking(CASHIER.userId())).thenReturn(working);
        service.checkout(command(null, null, null, List.of(pay("CASH", "50.00"))), CASHIER);
        assertThat(capturedOrder().getShiftId()).isEqualTo(88L);
    }
}

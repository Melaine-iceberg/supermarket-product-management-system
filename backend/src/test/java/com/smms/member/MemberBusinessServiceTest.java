package com.smms.member;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.member.dto.MemberConsumeVO;
import com.smms.member.dto.RechargeRequest;
import com.smms.member.entity.BalanceRecord;
import com.smms.member.entity.Member;
import com.smms.member.entity.MemberLevel;
import com.smms.member.entity.PointRecord;
import com.smms.member.mapper.BalanceRecordMapper;
import com.smms.member.mapper.MemberLevelMapper;
import com.smms.member.mapper.MemberMapper;
import com.smms.member.mapper.PointRecordMapper;
import com.smms.member.service.MemberBusinessService;
import com.smms.sale.entity.ReturnOrder;
import com.smms.sale.entity.SalesOrder;
import com.smms.sale.mapper.ReturnOrderMapper;
import com.smms.sale.mapper.SalesOrderMapper;
import com.smms.security.LoginUser;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会员业务联动单元测试：覆盖 BR-MEM-05（积分累计与退货等比例扣回）、
 * BR-MEM-06（累计消费与等级自动升降）、BR-MEM-07（储值扣减不可为负、
 * 储值消费默认不计积分基数）、FR-MEM-08（充值店长授权、退款回储值）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemberBusinessServiceTest {

    private static final long MEMBER_ID = 10L;
    private static final LoginUser CASHIER = new LoginUser(5L, "cashier", "陈收银",
            List.of("CASHIER"), List.of("收银员"), Set.of("sys:cashier:enter"), null, false);

    @Mock private MemberMapper memberMapper;
    @Mock private MemberLevelMapper memberLevelMapper;
    @Mock private PointRecordMapper pointRecordMapper;
    @Mock private BalanceRecordMapper balanceRecordMapper;
    @Mock private SalesOrderMapper salesOrderMapper;
    @Mock private ReturnOrderMapper returnOrderMapper;
    @Mock private SysConfigService configService;
    @Mock private SensitiveAuthService sensitiveAuthService;

    private MemberBusinessService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Member.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MemberLevel.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PointRecord.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), BalanceRecord.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SalesOrder.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReturnOrder.class);
        service = new MemberBusinessService(memberMapper, memberLevelMapper, pointRecordMapper,
                balanceRecordMapper, salesOrderMapper, returnOrderMapper, configService, sensitiveAuthService);

        // 默认参数与等级（普通 0 元 / 银卡 1000 元，倍率 1 / 2）
        when(configService.getBool(eq("point_include_recharge"), eq(false))).thenReturn(false);
        when(configService.getInt(eq("point_per_yuan"), eq(1))).thenReturn(1);
        when(configService.getStr(eq("member_level_downgrade"), eq("ONLY_UP"))).thenReturn("ONLY_UP");
        when(memberLevelMapper.selectList(any())).thenReturn(List.of(
                level(1L, "普通会员", "0", "1"),
                level(2L, "银卡会员", "1000", "2")));
        when(memberLevelMapper.selectById(1L)).thenReturn(level(1L, "普通会员", "0", "1"));
        when(memberLevelMapper.selectById(2L)).thenReturn(level(2L, "银卡会员", "1000", "2"));
    }

    // ---------- 造数 ----------

    private MemberLevel level(long id, String name, String threshold, String pointRate) {
        MemberLevel l = new MemberLevel();
        l.setId(id);
        l.setName(name);
        l.setThreshold(new BigDecimal(threshold));
        l.setPointRate(new BigDecimal(pointRate));
        l.setStatus(1);
        return l;
    }

    private Member member(String totalConsume, int points, String balance, long levelId) {
        Member m = new Member();
        m.setId(MEMBER_ID);
        m.setMemberNo("M00000001");
        m.setName("张三");
        m.setPhone("13800000001");
        m.setLevelId(levelId);
        m.setTotalConsume(new BigDecimal(totalConsume));
        m.setConsumeTimes(3);
        m.setPoints(points);
        m.setBalance(balance == null ? null : new BigDecimal(balance));
        m.setStatus(1);
        return m;
    }

    private RechargeRequest rechargeReq(String amount, String payType, String authorizerUsername) {
        return rechargeReq(amount, payType, authorizerUsername, "pwd");
    }

    private RechargeRequest rechargeReq(String amount, String payType, String authorizerUsername,
                                        String authorizerPassword) {
        RechargeRequest req = new RechargeRequest();
        req.setAmount(new BigDecimal(amount));
        req.setPayType(payType);
        req.setAuthorizerUsername(authorizerUsername);
        req.setAuthorizerPassword(authorizerPassword);
        return req;
    }

    private PointRecord consumePointRecord(String bizNo, int changeValue) {
        PointRecord r = new PointRecord();
        r.setMemberId(MEMBER_ID);
        r.setChangeType("CONSUME");
        r.setChangeValue(changeValue);
        r.setBizNo(bizNo);
        return r;
    }

    // ---------- 结算联动 settleEffects（BR-MEM-05/06/07） ----------

    @Test
    @DisplayName("结算联动：累计消费+实付、次数+1、积分=floor(实付×每元×倍率)、无升级返回 null")
    void settleEffects_basic() {
        Member m = member("500.00", 50, "100.00", 1L);
        when(memberMapper.selectByIdForUpdate(MEMBER_ID)).thenReturn(m);
        LocalDateTime now = LocalDateTime.now();

        String upgraded = service.settleEffects(MEMBER_ID, new BigDecimal("100.00"),
                null, "XS20260920010001", "陈收银", now);

        assertThat(upgraded).isNull(); // 600 < 1000 未达银卡
        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberMapper).updateById(captor.capture());
        Member saved = captor.getValue();
        assertThat(saved.getTotalConsume()).isEqualByComparingTo("600.00");
        assertThat(saved.getConsumeTimes()).isEqualTo(4);
        assertThat(saved.getLastConsumeTime()).isEqualTo(now);
        assertThat(saved.getPoints()).isEqualTo(150); // 50 + floor(100×1×1)
        assertThat(saved.getBalance()).isEqualByComparingTo("100.00"); // 未用储值

        ArgumentCaptor<PointRecord> pointCaptor = ArgumentCaptor.forClass(PointRecord.class);
        verify(pointRecordMapper).insert(pointCaptor.capture());
        assertThat(pointCaptor.getValue().getChangeValue()).isEqualTo(100);
        assertThat(pointCaptor.getValue().getAfterValue()).isEqualTo(150);
        assertThat(pointCaptor.getValue().getChangeType()).isEqualTo("CONSUME");
        assertThat(pointCaptor.getValue().getBizNo()).isEqualTo("XS20260920010001");
        verify(balanceRecordMapper, never()).insert(any(BalanceRecord.class));
    }

    @Test
    @DisplayName("储值支付扣余额写 CONSUME 流水；储值部分默认不计积分基数（BR-MEM-07）；跨门槛升级返回等级名")
    void settleEffects_balancePaid_andUpgrade() {
        Member m = member("990.00", 0, "100.00", 1L);
        when(memberMapper.selectByIdForUpdate(MEMBER_ID)).thenReturn(m);

        String upgraded = service.settleEffects(MEMBER_ID, new BigDecimal("50.00"),
                new BigDecimal("50.00"), "XS20260920010002", "陈收银", LocalDateTime.now());

        assertThat(upgraded).isEqualTo("银卡会员"); // 1040 ≥ 1000 升级（FR-MEM-06 收银台提示）
        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberMapper).updateById(captor.capture());
        Member saved = captor.getValue();
        assertThat(saved.getBalance()).isEqualByComparingTo("50.00");
        assertThat(saved.getLevelId()).isEqualTo(2L);
        assertThat(saved.getTotalConsume()).isEqualByComparingTo("1040.00");
        assertThat(saved.getPoints()).isZero(); // 基数 = 50 − 50 = 0，不积分

        ArgumentCaptor<BalanceRecord> balCaptor = ArgumentCaptor.forClass(BalanceRecord.class);
        verify(balanceRecordMapper).insert(balCaptor.capture());
        assertThat(balCaptor.getValue().getChangeType()).isEqualTo("CONSUME");
        assertThat(balCaptor.getValue().getChangeAmount()).isEqualByComparingTo("-50.00");
        assertThat(balCaptor.getValue().getAfterBalance()).isEqualByComparingTo("50.00");
        verify(pointRecordMapper, never()).insert(any(PointRecord.class));
    }

    @Test
    @DisplayName("积分倍率与向下取整：floor(33.34×2)=66（BR-MEM-05）")
    void settleEffects_pointRateFloor() {
        Member m = member("0", 0, null, 2L); // 银卡倍率 2
        when(memberMapper.selectByIdForUpdate(MEMBER_ID)).thenReturn(m);

        service.settleEffects(MEMBER_ID, new BigDecimal("33.34"), null, "XS001", "陈收银", LocalDateTime.now());

        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberMapper).updateById(captor.capture());
        assertThat(captor.getValue().getPoints()).isEqualTo(66); // floor(66.68)
    }

    @Test
    @DisplayName("point_include_recharge=true 时储值支付也计积分基数（BR-MEM-07 参数）")
    void settleEffects_pointIncludeRecharge() {
        Member m = member("0", 0, "100.00", 1L);
        when(memberMapper.selectByIdForUpdate(MEMBER_ID)).thenReturn(m);
        when(configService.getBool(eq("point_include_recharge"), eq(false))).thenReturn(true);

        service.settleEffects(MEMBER_ID, new BigDecimal("100.00"), new BigDecimal("40.00"),
                "XS001", "陈收银", LocalDateTime.now());

        ArgumentCaptor<PointRecord> pointCaptor = ArgumentCaptor.forClass(PointRecord.class);
        verify(pointRecordMapper).insert(pointCaptor.capture());
        assertThat(pointCaptor.getValue().getChangeValue()).isEqualTo(100);
    }

    @Test
    @DisplayName("储值余额不足：行锁兜底拦截（BR-MEM-07 余额不可为负）")
    void settleEffects_balanceNotEnough() {
        Member m = member("0", 0, "10.00", 1L);
        when(memberMapper.selectByIdForUpdate(MEMBER_ID)).thenReturn(m);

        assertThatThrownBy(() -> service.settleEffects(MEMBER_ID, new BigDecimal("50.00"),
                new BigDecimal("20.00"), "XS001", "陈收银", LocalDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.MEMBER_BALANCE_NOT_ENOUGH.getMessage());
        verify(memberMapper, never()).updateById(any(Member.class));
    }

    // ---------- 退货回退 returnEffects（BR-MEM-05/06，FR-MEM-08） ----------

    @Test
    @DisplayName("退货回退：累计消费−退款、积分按原单等比例扣回、现金退款不动余额、ONLY_UP 不降级")
    void returnEffects_proportionalDeduct() {
        Member m = member("200.00", 60, "20.00", 2L); // 银卡
        when(memberMapper.selectByIdForUpdate(MEMBER_ID)).thenReturn(m);
        when(pointRecordMapper.selectOne(any())).thenReturn(consumePointRecord("XS001", 100));

        service.returnEffects(MEMBER_ID, "XS001", new BigDecimal("100.00"), new BigDecimal("30.00"),
                "CASH", "XT20260920010001", "陈收银", LocalDateTime.now());

        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberMapper).updateById(captor.capture());
        Member saved = captor.getValue();
        assertThat(saved.getTotalConsume()).isEqualByComparingTo("170.00");
        assertThat(saved.getPoints()).isEqualTo(30); // 60 − floor(100×0.3)
        assertThat(saved.getLevelId()).isEqualTo(2L); // 累计 170 < 1000，ONLY_UP 只升不降
        assertThat(saved.getBalance()).isEqualByComparingTo("20.00");

        ArgumentCaptor<PointRecord> pointCaptor = ArgumentCaptor.forClass(PointRecord.class);
        verify(pointRecordMapper).insert(pointCaptor.capture());
        assertThat(pointCaptor.getValue().getChangeValue()).isEqualTo(-30);
        assertThat(pointCaptor.getValue().getChangeType()).isEqualTo("RETURN");
        assertThat(pointCaptor.getValue().getBizNo()).isEqualTo("XT20260920010001");
        verify(balanceRecordMapper, never()).insert(any(BalanceRecord.class));
    }

    @Test
    @DisplayName("积分不足时扣至 0 为止：差额记备注不阻断退货（BR-MEM-05）")
    void returnEffects_deductAtMostZero() {
        Member m = member("200.00", 10, null, 1L);
        when(memberMapper.selectByIdForUpdate(MEMBER_ID)).thenReturn(m);
        when(pointRecordMapper.selectOne(any())).thenReturn(consumePointRecord("XS001", 100));

        service.returnEffects(MEMBER_ID, "XS001", new BigDecimal("100.00"), new BigDecimal("50.00"),
                "CASH", "XT001", "陈收银", LocalDateTime.now());

        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberMapper).updateById(captor.capture());
        assertThat(captor.getValue().getPoints()).isZero();
        ArgumentCaptor<PointRecord> pointCaptor = ArgumentCaptor.forClass(PointRecord.class);
        verify(pointRecordMapper).insert(pointCaptor.capture());
        assertThat(pointCaptor.getValue().getChangeValue()).isEqualTo(-10);
        assertThat(pointCaptor.getValue().getRemark()).contains("应扣 50");
    }

    @Test
    @DisplayName("BALANCE 退款回充储值余额并写 REFUND 流水（FR-MEM-08）")
    void returnEffects_balanceRefund() {
        Member m = member("200.00", 60, "20.00", 1L);
        when(memberMapper.selectByIdForUpdate(MEMBER_ID)).thenReturn(m);
        when(pointRecordMapper.selectOne(any())).thenReturn(consumePointRecord("XS001", 100));

        service.returnEffects(MEMBER_ID, "XS001", new BigDecimal("100.00"), new BigDecimal("30.00"),
                "BALANCE", "XT001", "陈收银", LocalDateTime.now());

        ArgumentCaptor<BalanceRecord> balCaptor = ArgumentCaptor.forClass(BalanceRecord.class);
        verify(balanceRecordMapper).insert(balCaptor.capture());
        assertThat(balCaptor.getValue().getChangeType()).isEqualTo("REFUND");
        assertThat(balCaptor.getValue().getChangeAmount()).isEqualByComparingTo("30.00");
        assertThat(balCaptor.getValue().getAfterBalance()).isEqualByComparingTo("50.00");
        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberMapper).updateById(captor.capture());
        assertThat(captor.getValue().getBalance()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("YEARLY 降级策略允许按累计消费额降级（BR-MEM-06 参数 member_level_downgrade）")
    void returnEffects_yearlyDowngrade() {
        Member m = member("200.00", 60, null, 2L); // 银卡，回退后累计 170
        when(memberMapper.selectByIdForUpdate(MEMBER_ID)).thenReturn(m);
        when(configService.getStr(eq("member_level_downgrade"), eq("ONLY_UP"))).thenReturn("YEARLY");

        service.returnEffects(MEMBER_ID, "XS001", new BigDecimal("100.00"), new BigDecimal("30.00"),
                "CASH", "XT001", "陈收银", LocalDateTime.now());

        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberMapper).updateById(captor.capture());
        assertThat(captor.getValue().getLevelId()).isEqualTo(1L); // 降回普通会员
    }

    @Test
    @DisplayName("历史单据无积分流水：按 BR-MEM-05 公式重算近似原单积分")
    void returnEffects_originalPointsFallback() {
        Member m = member("200.00", 60, null, 1L);
        when(memberMapper.selectByIdForUpdate(MEMBER_ID)).thenReturn(m);
        when(pointRecordMapper.selectOne(any())).thenReturn(null); // 无流水

        service.returnEffects(MEMBER_ID, "XS001", new BigDecimal("100.00"), new BigDecimal("100.00"),
                "CASH", "XT001", "陈收银", LocalDateTime.now());

        // 原单积分近似 = floor(100×1) = 100 → 全额退扣回 100，积分 60 不足扣至 0
        ArgumentCaptor<PointRecord> pointCaptor = ArgumentCaptor.forClass(PointRecord.class);
        verify(pointRecordMapper).insert(pointCaptor.capture());
        assertThat(pointCaptor.getValue().getChangeValue()).isEqualTo(-60);
    }

    // ---------- 储值充值 recharge（FR-MEM-08） ----------

    @Test
    @DisplayName("充值：店长授权（授权账号+密码核验、授权人≠发起人）、余额累加并写 RECHARGE 流水、返回新余额")
    void recharge_success() {
        Member m = member("100.00", 0, "50.00", 1L);
        when(memberMapper.selectByIdForUpdate(MEMBER_ID)).thenReturn(m);
        when(sensitiveAuthService.authorize("manager1", "pwd"))
                .thenReturn(new SensitiveAuthService.Authorizer(9L, "manager1", "王店长"));

        BigDecimal balance = service.recharge(MEMBER_ID,
                rechargeReq("200.00", "CASH", "manager1"), CASHIER);

        assertThat(balance).isEqualByComparingTo("250.00");
        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberMapper).updateById(captor.capture());
        assertThat(captor.getValue().getBalance()).isEqualByComparingTo("250.00");

        ArgumentCaptor<BalanceRecord> balCaptor = ArgumentCaptor.forClass(BalanceRecord.class);
        verify(balanceRecordMapper).insert(balCaptor.capture());
        BalanceRecord record = balCaptor.getValue();
        assertThat(record.getChangeType()).isEqualTo("RECHARGE");
        assertThat(record.getChangeAmount()).isEqualByComparingTo("200.00");
        assertThat(record.getAfterBalance()).isEqualByComparingTo("250.00");
        assertThat(record.getPayType()).isEqualTo("CASH");
        assertThat(record.getOperatorName()).isEqualTo("陈收银");
        assertThat(record.getAuthorizerName()).isEqualTo("王店长"); // 落库真实授权人姓名（GAP-P0-07）
    }

    @Test
    @DisplayName("充值授权人不得为发起人本人（FR-MEM-08，按授权账号核验）")
    void recharge_authSelf_rejected() {
        when(sensitiveAuthService.authorize("cashier", "pwd"))
                .thenReturn(new SensitiveAuthService.Authorizer(CASHIER.userId(), "cashier", "陈收银"));

        assertThatThrownBy(() -> service.recharge(MEMBER_ID,
                rechargeReq("200.00", "CASH", "cashier"), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.MEMBER_RECHARGE_AUTH_SELF.getMessage());
        verify(memberMapper, never()).updateById(any(Member.class));
    }

    @Test
    @DisplayName("GAP-P0-07：授权账号不存在/密码错误/非店长一律拒绝（不可再手填姓名伪造授权）")
    void recharge_authorizerAuthFailed_rejected() {
        when(sensitiveAuthService.authorize("someone", "wrong"))
                .thenThrow(new BusinessException(ErrorCode.AUTHORIZER_AUTH_FAILED));

        assertThatThrownBy(() -> service.recharge(MEMBER_ID,
                rechargeReq("200.00", "CASH", "someone", "wrong"), CASHIER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.AUTHORIZER_AUTH_FAILED.getCode());
        verify(memberMapper, never()).updateById(any(Member.class));
    }

    @Test
    @DisplayName("停用会员不可充值（BR-MEM-03）")
    void recharge_disabledMember_rejected() {
        Member m = member("100.00", 0, "50.00", 1L);
        m.setStatus(0);
        when(memberMapper.selectByIdForUpdate(MEMBER_ID)).thenReturn(m);
        when(sensitiveAuthService.authorize("manager1", "pwd"))
                .thenReturn(new SensitiveAuthService.Authorizer(9L, "manager1", "王店长"));

        assertThatThrownBy(() -> service.recharge(MEMBER_ID,
                rechargeReq("200.00", "CASH", "manager1"), CASHIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("停用");
        verify(memberMapper, never()).updateById(any(Member.class));
    }

    // ---------- 消费记录查询（FR-MEM-04） ----------

    @Test
    @DisplayName("消费记录：销售+退货分页合并，统计总额=Σ实付−Σ退款、客单价=总额/频次")
    void consumeRecords_stat() {
        when(memberMapper.selectById(MEMBER_ID)).thenReturn(member("100.00", 0, null, 1L));

        Page<MemberConsumeVO> page = new Page<>(1, 20);
        MemberConsumeVO sale = new MemberConsumeVO();
        sale.setType("SALE");
        sale.setOrderNo("XS001");
        page.setRecords(List.of(sale));
        page.setTotal(1);
        when(memberMapper.selectConsumePage(any(), eq(MEMBER_ID), any(), any())).thenReturn(page);

        SalesOrder o1 = new SalesOrder();
        o1.setPayableAmount(new BigDecimal("100.00"));
        SalesOrder o2 = new SalesOrder();
        o2.setPayableAmount(new BigDecimal("50.00"));
        when(salesOrderMapper.selectList(any())).thenReturn(List.of(o1, o2));
        ReturnOrder r1 = new ReturnOrder();
        r1.setRefundAmount(new BigDecimal("30.00"));
        when(returnOrderMapper.selectList(any())).thenReturn(List.of(r1));
        when(pointRecordMapper.selectList(any())).thenReturn(
                List.of(consumePointRecord("XS001", 100)));

        MemberBusinessService.ConsumeResult result = service.consumeRecords(
                MEMBER_ID, null, null, 1, 20);

        assertThat(result.records().total()).isEqualTo(1);
        assertThat(result.records().list()).hasSize(1);
        assertThat(result.records().list().get(0).getPointsChange()).isEqualTo(100);
        assertThat(result.stat().totalAmount()).isEqualByComparingTo("120.00"); // 150 − 30
        assertThat(result.stat().orderCount()).isEqualTo(2);
        assertThat(result.stat().avgAmount()).isEqualByComparingTo("60.00");
    }
}

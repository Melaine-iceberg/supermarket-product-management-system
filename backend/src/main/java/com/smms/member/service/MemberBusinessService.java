package com.smms.member.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
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
import com.smms.sale.entity.ReturnOrder;
import com.smms.sale.entity.SalesOrder;
import com.smms.sale.mapper.ReturnOrderMapper;
import com.smms.sale.mapper.SalesOrderMapper;
import com.smms.security.LoginUser;
import com.smms.sys.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 会员业务联动服务（FR-MEM-04/06/07/08）：
 * 结算联动（积分累计 BR-MEM-05、累计消费与等级自动升降 BR-MEM-06、储值消费扣余额 BR-MEM-07）、
 * 退货回退（积分等比例扣回、退款回储值）、储值充值（店长授权）、消费记录查询。
 * 所有余额/积分变动均写不可删改流水（DR-24/25）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberBusinessService {

    /** 每元积分数（BR-MEM-05） */
    private static final String CONFIG_POINT_PER_YUAN = "point_per_yuan";
    /** 储值消费是否计积分基数（BR-MEM-07） */
    private static final String CONFIG_POINT_INCLUDE_RECHARGE = "point_include_recharge";
    /** 会员降级策略：ONLY_UP 只升不降 / YEARLY 允许降级（BR-MEM-06） */
    private static final String CONFIG_LEVEL_DOWNGRADE = "member_level_downgrade";

    private final MemberMapper memberMapper;
    private final MemberLevelMapper memberLevelMapper;
    private final PointRecordMapper pointRecordMapper;
    private final BalanceRecordMapper balanceRecordMapper;
    private final SalesOrderMapper salesOrderMapper;
    private final ReturnOrderMapper returnOrderMapper;
    private final SysConfigService configService;

    // ==================== 结算联动（BR-MEM-05/06/07） ====================

    /**
     * 销售结算后的会员联动（由 SalesCheckoutService 在结算事务内调用）：
     * 累计消费 += 实付、消费次数 +1、积分 = floor(积分基数 × 每元积分数 × 等级倍率)，
     * 储值支付扣减余额并写流水；按累计消费额重算等级（默认只升不降）。
     *
     * @return 升级后的等级名（未升级返回 null，收银台提示用）
     */
    @Transactional(rollbackFor = Exception.class)
    public String settleEffects(Long memberId, BigDecimal payable, BigDecimal balancePaid,
                                String orderNo, String operatorName, LocalDateTime now) {
        Member member = lockMember(memberId);

        // 累计消费额 += 实付（BR-MEM-06：Σ实付 − Σ退款）
        BigDecimal totalConsume = nvl(member.getTotalConsume()).add(payable);
        member.setTotalConsume(totalConsume);
        member.setConsumeTimes((member.getConsumeTimes() == null ? 0 : member.getConsumeTimes()) + 1);
        member.setLastConsumeTime(now);

        // 积分累计（BR-MEM-05）：floor(积分基数 × 每元积分数 × 等级倍率)；
        // 积分基数默认扣除储值支付（BR-MEM-07 避免充值与消费二次积分）
        MemberLevel level = memberLevelMapper.selectById(member.getLevelId());
        BigDecimal pointRate = level != null && level.getPointRate() != null
                ? level.getPointRate() : BigDecimal.ONE;
        boolean includeRecharge = configService.getBool(CONFIG_POINT_INCLUDE_RECHARGE, false);
        BigDecimal base = includeRecharge ? payable : payable.subtract(nvl(balancePaid));
        if (base.compareTo(BigDecimal.ZERO) < 0) {
            base = BigDecimal.ZERO;
        }
        int perYuan = configService.getInt(CONFIG_POINT_PER_YUAN, 1);
        int earned = base.multiply(BigDecimal.valueOf(perYuan)).multiply(pointRate)
                .setScale(0, RoundingMode.FLOOR).intValueExact();
        if (earned > 0) {
            int after = (member.getPoints() == null ? 0 : member.getPoints()) + earned;
            member.setPoints(after);
            pointRecordMapper.insert(pointRecord(member.getId(), "CONSUME", earned, after,
                    orderNo, operatorName, now, null));
        }

        // 储值支付扣减余额并写流水（余额不可为负在结算收款校验前置拦截，行锁兜底）
        if (balancePaid != null && balancePaid.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal balance = nvl(member.getBalance()).subtract(balancePaid);
            if (balance.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException(ErrorCode.MEMBER_BALANCE_NOT_ENOUGH);
            }
            member.setBalance(balance);
            balanceRecordMapper.insert(balanceRecord(member.getId(), "CONSUME",
                    balancePaid.negate(), balance, null, orderNo, operatorName, null, now, null));
        }

        // 等级自动升降（BR-MEM-06）：按累计消费额取满足门槛的最高启用等级
        String upgraded = refreshLevel(member, totalConsume);

        memberMapper.updateById(member);
        return upgraded;
    }

    // ==================== 退货回退（BR-MEM-05/06，FR-MEM-08） ====================

    /**
     * 退货后的会员联动（由 SalesReturnService 在退货事务内调用）：
     * 累计消费额 −= 退款；积分按原单所得等比例扣回、扣至 0 为止（差额记备注，不阻断）；
     * 退款方式为 BALANCE 时退回储值余额。
     */
    @Transactional(rollbackFor = Exception.class)
    public void returnEffects(Long memberId, String originalOrderNo, BigDecimal originalPayable,
                              BigDecimal totalRefund, String refundType, String returnOrderNo,
                              String operatorName, LocalDateTime now) {
        Member member = lockMember(memberId);

        // 累计消费额 −= 退款（BR-MEM-06）
        member.setTotalConsume(nvl(member.getTotalConsume()).subtract(totalRefund));

        // 积分等比例扣回（BR-MEM-05）：扣回 = floor(原单积分 × 退款比例)，扣至 0 为止
        int originalEarned = originalPoints(originalOrderNo, originalPayable);
        BigDecimal ratio = originalPayable.compareTo(BigDecimal.ZERO) > 0
                ? totalRefund.divide(originalPayable, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        int shouldDeduct = BigDecimal.valueOf(originalEarned).multiply(ratio)
                .setScale(0, RoundingMode.FLOOR).intValueExact();
        int points = member.getPoints() == null ? 0 : member.getPoints();
        int deduct = Math.min(shouldDeduct, points);
        if (deduct > 0) {
            member.setPoints(points - deduct);
            String remark = shouldDeduct > deduct
                    ? "积分不足仅扣回 " + deduct + "（应扣 " + shouldDeduct + "，差额不阻断退货）" : null;
            pointRecordMapper.insert(pointRecord(member.getId(), "RETURN", -deduct,
                    points - deduct, returnOrderNo, operatorName, now, remark));
        }

        // 退款回储值（FR-MEM-08：销售退款可退回储值余额）
        if ("BALANCE".equals(refundType)) {
            BigDecimal balance = nvl(member.getBalance()).add(totalRefund);
            member.setBalance(balance);
            balanceRecordMapper.insert(balanceRecord(member.getId(), "REFUND",
                    totalRefund, balance, null, returnOrderNo, operatorName, null, now, null));
        }

        // 降级策略允许降级时按累计消费额重算（默认 ONLY_UP 不降，BR-MEM-06）
        refreshLevel(member, member.getTotalConsume());

        memberMapper.updateById(member);
    }

    // ==================== 储值充值（FR-MEM-08） ====================

    /**
     * 储值充值：金额 > 0、收款方式合法、须店长授权（授权人 ≠ 发起人）；
     * 余额 += 金额并写 RECHARGE 流水（充值不计积分）。
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal recharge(Long memberId, RechargeRequest req, LoginUser operator) {
        if (req.getAuthorizerName().trim().equals(operator.realName())) {
            throw new BusinessException(ErrorCode.MEMBER_RECHARGE_AUTH_SELF,
                    "储值充值的授权人不得为发起人本人（FR-MEM-08）");
        }
        Member member = lockMember(memberId);
        if (member.getStatus() == null || member.getStatus() != 1) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND, "会员已停用，不可充值");
        }
        BigDecimal amount = req.getAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal balance = nvl(member.getBalance()).add(amount);
        member.setBalance(balance);
        memberMapper.updateById(member);
        balanceRecordMapper.insert(balanceRecord(member.getId(), "RECHARGE", amount, balance,
                req.getPayType(), null, operator.realName(), req.getAuthorizerName().trim(),
                LocalDateTime.now(), req.getRemark()));
        return balance;
    }

    // ==================== 查询（FR-MEM-04/07/08） ====================

    /** 积分明细（FR-MEM-07）：近期变动倒序分页 */
    public PageVO<PointRecord> pointRecords(Long memberId, int page, int size) {
        Page<PointRecord> result = pointRecordMapper.selectPage(new Page<>(page, size),
                Wrappers.<PointRecord>lambdaQuery()
                        .eq(PointRecord::getMemberId, memberId)
                        .orderByDesc(PointRecord::getId));
        return new PageVO<>(result.getRecords(), result.getTotal());
    }

    /** 储值流水（FR-MEM-08）：倒序分页 */
    public PageVO<BalanceRecord> balanceRecords(Long memberId, int page, int size) {
        Page<BalanceRecord> result = balanceRecordMapper.selectPage(new Page<>(page, size),
                Wrappers.<BalanceRecord>lambdaQuery()
                        .eq(BalanceRecord::getMemberId, memberId)
                        .orderByDesc(BalanceRecord::getId));
        return new PageVO<>(result.getRecords(), result.getTotal());
    }

    /**
     * 消费记录（FR-MEM-04）：销售单+退货单时间倒序分页，积分变动按单号回填；
     * 统计（按同区间）：消费总额 = Σ销售实付 − Σ退款、频次 = 销售单数、客单价 = 总额/频次。
     */
    public ConsumeResult consumeRecords(Long memberId, String beginDate, String endDate,
                                         int page, int size) {
        requireMember(memberId);
        String begin = blankToNull(beginDate);
        String end = blankToNull(endDate);
        Page<MemberConsumeVO> result = memberMapper.selectConsumePage(
                new Page<>(Math.max(page, 1), size <= 0 ? 20 : Math.min(size, 100)),
                memberId, begin, end);
        fillPointsChange(result.getRecords());

        // 时间区间统计（BR-MEM-06 口径）
        List<SalesOrder> orders = salesOrderMapper.selectList(
                Wrappers.<SalesOrder>lambdaQuery()
                        .select(SalesOrder::getPayableAmount)
                        .eq(SalesOrder::getMemberId, memberId)
                        .ge(begin != null, SalesOrder::getSaleTime, begin)
                        .le(end != null, SalesOrder::getSaleTime, end + " 23:59:59"));
        BigDecimal paid = orders.stream().map(o -> nvl(o.getPayableAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ReturnOrder> returns = returnOrderMapper.selectList(
                Wrappers.<ReturnOrder>lambdaQuery()
                        .select(ReturnOrder::getRefundAmount)
                        .eq(ReturnOrder::getMemberId, memberId)
                        .ge(begin != null, ReturnOrder::getReturnTime, begin)
                        .le(end != null, ReturnOrder::getReturnTime, end + " 23:59:59"));
        BigDecimal refunded = returns.stream().map(r -> nvl(r.getRefundAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = paid.subtract(refunded);
        int count = orders.size();
        BigDecimal avg = count == 0 ? BigDecimal.ZERO
                .setScale(2) : total.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
        return new ConsumeResult(new PageVO<>(result.getRecords(), result.getTotal()),
                new ConsumeStat(total, count, avg));
    }

    /** 消费记录结果：分页明细 + 区间统计（FR-MEM-04） */
    public record ConsumeResult(PageVO<MemberConsumeVO> records, ConsumeStat stat) {
    }

    /** 区间统计：消费总额、消费频次、客单价 */
    public record ConsumeStat(BigDecimal totalAmount, int orderCount, BigDecimal avgAmount) {
    }

    // ==================== 内部方法 ====================

    private Member lockMember(Long memberId) {
        Member member = memberMapper.selectByIdForUpdate(memberId);
        if (member == null) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        }
        return member;
    }

    private void requireMember(Long memberId) {
        if (memberMapper.selectById(memberId) == null) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        }
    }

    /**
     * 等级自动升降（BR-MEM-06）：取门槛 ≤ 累计消费额的最高启用等级；
     * ONLY_UP（默认）只升不降；YEARLY 策略允许降级重算。
     *
     * @return 升级后的等级名（未升级 null）
     */
    private String refreshLevel(Member member, BigDecimal totalConsume) {
        List<MemberLevel> enabled = memberLevelMapper.selectList(
                Wrappers.<MemberLevel>lambdaQuery().eq(MemberLevel::getStatus, 1));
        MemberLevel target = null;
        for (MemberLevel l : enabled) {
            if (totalConsume.compareTo(nvl(l.getThreshold())) >= 0
                    && (target == null || nvl(l.getThreshold()).compareTo(nvl(target.getThreshold())) > 0)) {
                target = l;
            }
        }
        if (target == null) {
            return null; // 无启用等级（理论不发生）：保持现状
        }
        boolean onlyUp = !"YEARLY".equalsIgnoreCase(configService.getStr(CONFIG_LEVEL_DOWNGRADE, "ONLY_UP"));
        if (Objects.equals(target.getId(), member.getLevelId())) {
            return null;
        }
        MemberLevel current = memberLevelMapper.selectById(member.getLevelId());
        if (current != null && nvl(target.getThreshold()).compareTo(nvl(current.getThreshold())) < 0) {
            if (onlyUp) {
                return null; // 目标门槛低于当前：只升不降
            }
            member.setLevelId(target.getId());
            return null; // 降级不提示
        }
        member.setLevelId(target.getId());
        return target.getName(); // 升级提示（FR-MEM-06 收银台提示）
    }

    /** 原单所得积分：优先取积分流水；无流水（历史单据）按 BR-MEM-05 公式重算近似值 */
    private int originalPoints(String orderNo, BigDecimal payable) {
        PointRecord record = pointRecordMapper.selectOne(Wrappers.<PointRecord>lambdaQuery()
                .eq(PointRecord::getBizNo, orderNo)
                .eq(PointRecord::getChangeType, "CONSUME")
                .last("LIMIT 1"));
        if (record != null) {
            return Math.max(record.getChangeValue(), 0);
        }
        int perYuan = configService.getInt(CONFIG_POINT_PER_YUAN, 1);
        return nvl(payable).multiply(BigDecimal.valueOf(perYuan))
                .setScale(0, RoundingMode.FLOOR).intValueExact();
    }

    /** 积分变动回填（FR-MEM-04：来源单据号 → 变动值） */
    private void fillPointsChange(List<MemberConsumeVO> records) {
        if (records.isEmpty()) {
            return;
        }
        List<String> orderNos = records.stream().map(MemberConsumeVO::getOrderNo).toList();
        Map<String, Integer> changes = pointRecordMapper.selectList(
                        Wrappers.<PointRecord>lambdaQuery().in(PointRecord::getBizNo, orderNos))
                .stream()
                .collect(Collectors.toMap(PointRecord::getBizNo, PointRecord::getChangeValue, (a, b) -> a));
        records.forEach(r -> r.setPointsChange(changes.get(r.getOrderNo())));
    }

    private PointRecord pointRecord(Long memberId, String type, int change, int after,
                                    String bizNo, String operator, LocalDateTime now, String remark) {
        PointRecord record = new PointRecord();
        record.setMemberId(memberId);
        record.setChangeType(type);
        record.setChangeValue(change);
        record.setAfterValue(after);
        record.setBizNo(bizNo);
        record.setOperatorName(operator);
        record.setCreateTime(now);
        record.setRemark(remark);
        return record;
    }

    private BalanceRecord balanceRecord(Long memberId, String type, BigDecimal change, BigDecimal after,
                                        String payType, String bizNo, String operator,
                                        String authorizer, LocalDateTime now, String remark) {
        BalanceRecord record = new BalanceRecord();
        record.setMemberId(memberId);
        record.setChangeType(type);
        record.setChangeAmount(change);
        record.setAfterBalance(after);
        record.setPayType(payType);
        record.setBizNo(bizNo);
        record.setOperatorName(operator);
        record.setAuthorizerName(authorizer);
        record.setCreateTime(now);
        record.setRemark(remark);
        return record;
    }

    private BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

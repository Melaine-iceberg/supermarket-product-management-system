package com.smms.sale.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.sale.dto.ShiftQuery;
import com.smms.sale.dto.ShiftVO;
import com.smms.sale.entity.CashierShift;
import com.smms.sale.entity.PaymentRecord;
import com.smms.sale.entity.ReturnOrder;
import com.smms.sale.mapper.CashierShiftMapper;
import com.smms.sale.mapper.PaymentRecordMapper;
import com.smms.sale.mapper.ReturnOrderMapper;
import com.smms.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 收银班次服务（FR-SALE-12，DR-37）：收银员开班/交班登记。
 * 交班时按班次统计销售单数、销售总额（净销售 = Σ应收 − 退货额）、
 * 各收款方式金额、退货额与应收现金（= 现金收款 − 现金退款），回写班次并生成交班单。
 * 统计口径：收款按班次内销售单的收款记录；退货按原销售单归属班次
 * （交班后该班次单据的退货由店长处理，仍计入原班次，BR-SALE-09）。
 */
@Service
@RequiredArgsConstructor
public class CashierShiftService {

    private final CashierShiftMapper shiftMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final ReturnOrderMapper returnOrderMapper;

    // ==================== 开班（FR-SALE-12） ====================

    /** 开班登记：同一收银员同一时刻仅一个在班班次 */
    @Transactional
    public ShiftVO openShift(int counterNo, LoginUser cashier) {
        if (counterNo < 1 || counterNo > 99) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "收银台号须为 1~99");
        }
        CashierShift working = shiftMapper.selectWorking(cashier.userId());
        if (working != null) {
            throw new BusinessException(ErrorCode.SHIFT_ALREADY_OPEN,
                    "已在 " + working.getStartTime() + " 于 " + working.getCounterNo() + " 号台开班，请先交班");
        }
        CashierShift shift = new CashierShift();
        shift.setCashierId(cashier.userId());
        shift.setCashierName(cashier.realName());
        shift.setCounterNo(counterNo);
        shift.setStartTime(LocalDateTime.now());
        shift.setOrderCount(0);
        shift.setSalesAmount(BigDecimal.ZERO);
        shift.setCashAmount(BigDecimal.ZERO);
        shift.setBankAmount(BigDecimal.ZERO);
        shift.setMobileAmount(BigDecimal.ZERO);
        shift.setBalanceAmount(BigDecimal.ZERO);
        shift.setReturnAmount(BigDecimal.ZERO);
        shift.setExpectedCash(BigDecimal.ZERO);
        shift.setStatus(CashierShift.STATUS_WORKING);
        // 审计字段（DR-C-08/GAP-P2-28）：开班经办收银员
        shift.setCreateBy(cashier.username());
        shift.setCreateTime(shift.getStartTime());
        shiftMapper.insert(shift);
        return toVO(shift);
    }

    // ==================== 交班（FR-SALE-12） ====================

    /** 交班对账：统计回写班次并返回交班单（含班次内销售单列表，供打印核对 FR-RPT-09） */
    @Transactional
    public ShiftVO.Detail closeShift(LoginUser cashier) {
        CashierShift shift = shiftMapper.selectWorking(cashier.userId());
        if (shift == null) {
            throw new BusinessException(ErrorCode.SHIFT_NOT_OPEN);
        }
        List<ShiftVO.OrderLine> orders = shiftMapper.selectShiftOrders(shift.getId());
        ShiftStats stats = computeStats(orders);

        shift.setEndTime(LocalDateTime.now());
        shift.setStatus(CashierShift.STATUS_CLOSED);
        applyStats(shift, stats);
        shift.setUpdateBy(cashier.username());
        shift.setUpdateTime(shift.getEndTime());
        shiftMapper.updateById(shift);
        return new ShiftVO.Detail(toVO(shift), orders);
    }

    // ==================== 查询（FR-SALE-12） ====================

    /** 收银员当前在班班次（收银台展示；无在班班次返回 null） */
    public ShiftVO current(LoginUser cashier) {
        CashierShift working = shiftMapper.selectWorking(cashier.userId());
        return working == null ? null : toVO(working);
    }

    /** 班次分页：收银员仅可查询本人班次，店长/超管全局（BR-SALE-09 同口径） */
    public PageVO<ShiftVO> page(ShiftQuery q, LoginUser user) {
        int pageNo = Math.max(q.page(), 1);
        int size = q.size() <= 0 ? 20 : Math.min(q.size(), 100);
        ShiftQuery scoped = SalesQueryService.isGlobalSaleRole(user) ? q
                : new ShiftQuery(q.cashierName(), q.counterNo(), q.status(),
                        q.beginDate(), q.endDate(), user.userId(), q.page(), q.size());
        IPage<ShiftVO> result = shiftMapper.selectShiftPage(new Page<>(pageNo, size), scoped);
        return PageVO.of(result);
    }

    /** 班次详情（交班单视图）：统计实时计算——已交班班次的后续退货仍计入原班次 */
    public ShiftVO.Detail detail(Long id) {
        CashierShift shift = shiftMapper.selectById(id);
        if (shift == null) {
            throw new BusinessException(ErrorCode.SHIFT_NOT_FOUND);
        }
        List<ShiftVO.OrderLine> orders = shiftMapper.selectShiftOrders(id);
        ShiftVO vo = toVO(shift);
        if (CashierShift.STATUS_WORKING.equals(shift.getStatus())) {
            // 在班班次：统计字段尚未回写，实时计算展示
            ShiftStats stats = computeStats(orders);
            vo = new ShiftVO(vo.id(), vo.cashierId(), vo.cashierName(), vo.counterNo(),
                    vo.startTime(), vo.endTime(), stats.orderCount(), stats.netSales(),
                    stats.cash(), stats.bank(), stats.mobile(), stats.balance(),
                    stats.returnTotal(), stats.expectedCash(), vo.status());
        }
        return new ShiftVO.Detail(vo, orders);
    }

    // ==================== 内部统计 ====================

    /** 班次统计中间结果 */
    private record ShiftStats(int orderCount, BigDecimal payableTotal,
                              BigDecimal cash, BigDecimal bank, BigDecimal mobile, BigDecimal balance,
                              BigDecimal returnTotal, BigDecimal cashReturn,
                              BigDecimal netSales, BigDecimal expectedCash) {
    }

    /** 按班次内销售单实时统计：收款按收款记录、退货按原单归属（含交班后店长办理的退货） */
    private ShiftStats computeStats(List<ShiftVO.OrderLine> orders) {
        int orderCount = orders.size();
        BigDecimal payableTotal = orders.stream()
                .map(o -> nvl(o.payableAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal cash = BigDecimal.ZERO;
        BigDecimal bank = BigDecimal.ZERO;
        BigDecimal mobile = BigDecimal.ZERO;
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal returnTotal = BigDecimal.ZERO;
        BigDecimal cashReturn = BigDecimal.ZERO;

        if (!orders.isEmpty()) {
            List<Long> orderIds = orders.stream().map(ShiftVO.OrderLine::id).toList();
            for (PaymentRecord pay : paymentRecordMapper.selectList(
                    Wrappers.<PaymentRecord>lambdaQuery().in(PaymentRecord::getOrderId, orderIds))) {
                switch (pay.getPayType() == null ? "" : pay.getPayType()) {
                    case "CASH" -> cash = cash.add(nvl(pay.getAmount()));
                    case "BANK" -> bank = bank.add(nvl(pay.getAmount()));
                    case "MOBILE" -> mobile = mobile.add(nvl(pay.getAmount()));
                    case "BALANCE" -> balance = balance.add(nvl(pay.getAmount()));
                    default -> { /* 未知方式忽略 */
                    }
                }
            }
            for (ReturnOrder ro : returnOrderMapper.selectList(
                    Wrappers.<ReturnOrder>lambdaQuery().in(ReturnOrder::getSalesOrderId, orderIds))) {
                BigDecimal refund = nvl(ro.getRefundAmount());
                returnTotal = returnTotal.add(refund);
                if ("CASH".equals(ro.getRefundType())) {
                    cashReturn = cashReturn.add(refund);
                }
            }
        }
        // 净销售 = Σ应收 − 退货额；应收现金 = 现金收款 − 现金退款
        return new ShiftStats(orderCount, payableTotal, cash, bank, mobile, balance,
                returnTotal, cashReturn,
                payableTotal.subtract(returnTotal), cash.subtract(cashReturn));
    }

    private void applyStats(CashierShift shift, ShiftStats stats) {
        shift.setOrderCount(stats.orderCount());
        shift.setSalesAmount(stats.netSales());
        shift.setCashAmount(stats.cash());
        shift.setBankAmount(stats.bank());
        shift.setMobileAmount(stats.mobile());
        shift.setBalanceAmount(stats.balance());
        shift.setReturnAmount(stats.returnTotal());
        shift.setExpectedCash(stats.expectedCash());
    }

    private ShiftVO toVO(CashierShift s) {
        return new ShiftVO(s.getId(), s.getCashierId(), s.getCashierName(), s.getCounterNo(),
                s.getStartTime(), s.getEndTime(), s.getOrderCount(), s.getSalesAmount(),
                s.getCashAmount(), s.getBankAmount(), s.getMobileAmount(), s.getBalanceAmount(),
                s.getReturnAmount(), s.getExpectedCash(), s.getStatus());
    }

    private BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}

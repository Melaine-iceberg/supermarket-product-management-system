package com.smms.sale.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.sale.dto.HoldCreateCommand;
import com.smms.sale.dto.HoldDetailVO;
import com.smms.sale.dto.HoldOrderVO;
import com.smms.sale.entity.HoldOrder;
import com.smms.sale.mapper.HoldOrderMapper;
import com.smms.security.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 收银挂单服务（FR-SALE-08）：挂单仅暂存明细快照供稍后取单，不扣库存、不计入报表（BR-RPT-03）；
 * 挂单归属收银员本人（列表/取单/删除均按登录人过滤），取单/删除用状态条件更新防并发重复操作；
 * 超 24 小时由定时任务清理（IR-SW-06）。件数与金额摘要由后端按明细计算，前端不传。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HoldOrderService {

    /** 挂单行类型：NORMAL档案商品 / TEMP无码商品（FR-SALE-13） */
    private static final String LINE_NORMAL = "NORMAL";
    private static final String LINE_TEMP = "TEMP";

    /** 单收银员未取挂单上限（临时表防膨胀；超限提示先取单或删除） */
    private static final int MAX_ACTIVE_PER_CASHIER = 50;

    /** 挂单保留时长（小时），超时定时清理（IR-SW-06） */
    private static final long KEEP_HOURS = 24;

    private final HoldOrderMapper holdMapper;
    private final ObjectMapper objectMapper;

    // ==================== 挂单（FR-SALE-08） ====================

    /** 挂单：校验明细快照并落库，返回摘要；件数/金额由后端计算 */
    @Transactional
    public HoldOrderVO create(HoldCreateCommand cmd, LoginUser cashier) {
        Long active = holdMapper.selectCount(Wrappers.<HoldOrder>lambdaQuery()
                .eq(HoldOrder::getCashierId, cashier.userId())
                .eq(HoldOrder::getStatus, HoldOrder.STATUS_ACTIVE));
        if (active != null && active >= MAX_ACTIVE_PER_CASHIER) {
            throw new BusinessException(ErrorCode.HOLD_LIMIT_EXCEED,
                    "未取挂单已有 " + active + " 张，请先取单或删除");
        }
        List<HoldDetailVO.Item> items = new ArrayList<>();
        int itemCount = 0;
        BigDecimal amount = BigDecimal.ZERO;
        for (HoldCreateCommand.HoldItem item : cmd.items()) {
            items.add(toSnapshot(item));
            itemCount += item.isWeighed() == 1 ? 1 : item.quantity().intValue();
            BigDecimal unit = item.overridePrice() != null ? item.overridePrice() : item.retailPrice();
            amount = amount.add(unit.multiply(item.quantity()));
        }
        HoldOrder hold = new HoldOrder();
        hold.setCounterNo(cmd.counterNo());
        hold.setCashierId(cashier.userId());
        hold.setCashierName(cashier.realName());
        hold.setMemberId(cmd.memberId());
        hold.setMemberName(trimToNull(cmd.memberName()));
        hold.setItemsJson(objectMapper.writeValueAsString(items));
        hold.setItemCount(itemCount);
        hold.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        hold.setOrderRate(cmd.orderRate());
        hold.setDiscountReason(trimToNull(cmd.discountReason()));
        hold.setAuthorizerUsername(trimToNull(cmd.authorizerUsername()));
        hold.setRemark(trimToNull(cmd.remark()));
        hold.setHoldTime(LocalDateTime.now());
        hold.setStatus(HoldOrder.STATUS_ACTIVE);
        // 审计字段（DR-C-08/GAP-P2-28）：挂单创建人=收银员
        hold.setCreateBy(cashier.username());
        hold.setCreateTime(hold.getHoldTime());
        holdMapper.insert(hold);
        return toVO(hold);
    }

    /** 本人未取挂单列表（挂单时间倒序，最早挂单最先清理） */
    public List<HoldOrderVO> list(LoginUser cashier) {
        return holdMapper.selectList(Wrappers.<HoldOrder>lambdaQuery()
                        .eq(HoldOrder::getCashierId, cashier.userId())
                        .eq(HoldOrder::getStatus, HoldOrder.STATUS_ACTIVE)
                        .orderByDesc(HoldOrder::getHoldTime))
                .stream().map(this::toVO).toList();
    }

    // ==================== 取单 / 删除（FR-SALE-08） ====================

    /** 取单：ACTIVE→TAKEN 条件更新（并发重复取单仅一次成功），返回明细快照供恢复购物车 */
    @Transactional
    public HoldDetailVO take(Long id, LoginUser cashier) {
        int updated = holdMapper.update(null, Wrappers.<HoldOrder>lambdaUpdate()
                .eq(HoldOrder::getId, id)
                .eq(HoldOrder::getCashierId, cashier.userId())
                .eq(HoldOrder::getStatus, HoldOrder.STATUS_ACTIVE)
                .set(HoldOrder::getStatus, HoldOrder.STATUS_TAKEN)
                .set(HoldOrder::getTakenTime, LocalDateTime.now()));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.HOLD_NOT_FOUND, "挂单不存在、已被取单或已删除");
        }
        return toDetail(holdMapper.selectById(id));
    }

    /** 删除挂单：ACTIVE→DELETED 条件更新（与取单互斥，防并发） */
    @Transactional
    public void delete(Long id, LoginUser cashier) {
        int updated = holdMapper.update(null, Wrappers.<HoldOrder>lambdaUpdate()
                .eq(HoldOrder::getId, id)
                .eq(HoldOrder::getCashierId, cashier.userId())
                .eq(HoldOrder::getStatus, HoldOrder.STATUS_ACTIVE)
                .set(HoldOrder::getStatus, HoldOrder.STATUS_DELETED)
                .set(HoldOrder::getTakenTime, LocalDateTime.now()));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.HOLD_NOT_FOUND, "挂单不存在、已被取单或已删除");
        }
    }

    // ==================== 超时清理（IR-SW-06） ====================

    /**
     * 超时清理（IR-SW-06）：删除 24 小时前的挂单（临时数据，含已取/已删终态，不保留历史），返回删除条数。
     * 幂等：按挂单时间条件删除，重复执行不产生副作用；调度由 DynamicTaskScheduler 统一注册（GAP-P1-03）。
     */
    public int cleanupExpired() {
        int removed = holdMapper.delete(Wrappers.<HoldOrder>lambdaQuery()
                .lt(HoldOrder::getHoldTime, LocalDateTime.now().minusHours(KEEP_HOURS)));
        if (removed > 0) {
            log.info("挂单超时清理：删除 {} 条超过 {} 小时的挂单", removed, KEEP_HOURS);
        }
        return removed;
    }

    // ==================== 内部 ====================

    /** 明细快照校验与清洗（改价仅限档案商品且原因必填，与结算校验口径一致 FR-SALE-07） */
    private HoldDetailVO.Item toSnapshot(HoldCreateCommand.HoldItem item) {
        boolean temp = LINE_TEMP.equals(item.lineType());
        if (!temp && !LINE_NORMAL.equals(item.lineType())) {
            throw new BusinessException(ErrorCode.HOLD_ITEM_INVALID, "行类型仅支持 NORMAL/TEMP");
        }
        if (!temp && item.goodsId() == null) {
            throw new BusinessException(ErrorCode.HOLD_ITEM_INVALID, "档案商品必须携带商品ID");
        }
        if (temp && item.overridePrice() != null) {
            throw new BusinessException(ErrorCode.HOLD_ITEM_INVALID, "无码商品以录入金额成交，不支持改价");
        }
        if (temp && item.retailPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.HOLD_ITEM_INVALID, "无码商品金额须大于 0");
        }
        if (item.overridePrice() != null && item.overridePrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.HOLD_ITEM_INVALID, "改价单价必须大于 0");
        }
        if (item.overridePrice() != null && (item.priceReason() == null || item.priceReason().isBlank())) {
            throw new BusinessException(ErrorCode.HOLD_ITEM_INVALID, "改价必须填写改价原因（FR-SALE-07）");
        }
        return new HoldDetailVO.Item(
                item.lineType(),
                item.goodsId(),
                item.name().trim(),
                trimToNull(item.spec()),
                trimToNull(item.barcode()),
                item.unitName().trim(),
                item.retailPrice(),
                item.isWeighed(),
                item.quantity(),
                trimToNull(item.remark()),
                item.overridePrice(),
                item.overridePrice() == null ? null : item.priceReason().trim());
    }

    private HoldOrderVO toVO(HoldOrder hold) {
        return new HoldOrderVO(hold.getId(), hold.getCounterNo(), hold.getCashierName(),
                hold.getMemberId(), hold.getMemberName(), hold.getItemCount(), hold.getAmount(),
                hold.getRemark(), hold.getHoldTime());
    }

    private HoldDetailVO toDetail(HoldOrder hold) {
        List<HoldDetailVO.Item> items = hold.getItemsJson() == null || hold.getItemsJson().isBlank()
                ? List.of()
                : objectMapper.readValue(hold.getItemsJson(), new TypeReference<List<HoldDetailVO.Item>>() {
                });
        return new HoldDetailVO(hold.getId(), hold.getCounterNo(), hold.getCashierName(),
                hold.getMemberId(), hold.getMemberName(), hold.getItemCount(), hold.getAmount(),
                hold.getRemark(), hold.getHoldTime(), hold.getOrderRate(), hold.getDiscountReason(),
                hold.getAuthorizerUsername(), items);
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}

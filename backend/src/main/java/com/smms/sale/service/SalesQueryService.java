package com.smms.sale.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.common.util.MaskUtils;
import com.smms.member.entity.Member;
import com.smms.member.mapper.MemberMapper;
import com.smms.sale.dto.SalesOrderDetailVO;
import com.smms.sale.dto.SalesOrderQuery;
import com.smms.sale.dto.SalesOrderVO;
import com.smms.sale.entity.PaymentRecord;
import com.smms.sale.entity.ReturnOrder;
import com.smms.sale.entity.SalesOrder;
import com.smms.sale.entity.SalesOrderItem;
import com.smms.sale.mapper.PaymentRecordMapper;
import com.smms.sale.mapper.ReturnOrderMapper;
import com.smms.sale.mapper.SalesOrderItemMapper;
import com.smms.sale.mapper.SalesOrderMapper;
import com.smms.security.LoginUser;
import com.smms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 销售单查询服务（FR-SALE-09）：多条件分页与单据详情
 * （明细快照、收款信息、关联退货记录）。
 * 数据范围（BR-SALE-09）：收银员仅可查询本人经手的单据，店长/超管可查全部。
 */
@Service
@RequiredArgsConstructor
public class SalesQueryService {

    /** 可跨收银员查询/办理退货的角色（BR-SALE-09） */
    private static final Set<String> GLOBAL_SALE_ROLES = Set.of("SUPER_ADMIN", "MANAGER");

    private final SalesOrderMapper salesOrderMapper;
    private final SalesOrderItemMapper salesOrderItemMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final ReturnOrderMapper returnOrderMapper;
    private final MemberMapper memberMapper;

    /** 分页查询（FR-SALE-09）：收银员自动限定本人单据（BR-SALE-09） */
    public PageVO<SalesOrderVO> page(SalesOrderQuery q, LoginUser operator) {
        int pageNo = Math.max(q.page(), 1);
        int size = q.size() <= 0 ? 20 : Math.min(q.size(), 100);
        SalesOrderQuery scoped = scopeQuery(q, operator);
        LocalDateTime beginTime = scoped.beginDate() == null ? null : scoped.beginDate().atStartOfDay();
        LocalDateTime endTime = scoped.endDate() == null ? null : scoped.endDate().plusDays(1).atStartOfDay();
        IPage<SalesOrderVO> result =
                salesOrderMapper.selectOrderPage(new Page<>(pageNo, size), scoped, beginTime, endTime);
        // 会员手机号按角色脱敏（BR-MEM-08：完整手机号仅店长及以上可见）
        if (!SecurityUtils.canViewMemberPhone()) {
            List<SalesOrderVO> masked = result.getRecords().stream()
                    .map(o -> o.withMemberPhone(MaskUtils.maskPhone(o.memberPhone())))
                    .toList();
            result.setRecords(masked);
        }
        return PageVO.of(result);
    }

    /** 单据详情（FR-SALE-09）：单头 + 明细快照 + 收款 + 关联退货；收银员仅可看本人单据 */
    public SalesOrderDetailVO detail(Long id, LoginUser operator) {
        SalesOrder order = requireOrderInScope(id, operator);
        List<SalesOrderItem> items = salesOrderItemMapper.selectList(
                Wrappers.<SalesOrderItem>lambdaQuery().eq(SalesOrderItem::getOrderId, id));
        List<PaymentRecord> payments = paymentRecordMapper.selectList(
                Wrappers.<PaymentRecord>lambdaQuery().eq(PaymentRecord::getOrderId, id));
        List<ReturnOrder> returns = returnOrderMapper.selectList(
                Wrappers.<ReturnOrder>lambdaQuery().eq(ReturnOrder::getSalesOrderId, id)
                        .orderByDesc(ReturnOrder::getReturnTime));

        Member member = order.getMemberId() == null ? null : memberMapper.selectById(order.getMemberId());
        String memberName = member == null ? null : member.getName();
        // 手机号按角色脱敏（BR-MEM-08）
        String memberPhone = member == null ? null
                : SecurityUtils.canViewMemberPhone() ? member.getPhone() : MaskUtils.maskPhone(member.getPhone());

        return new SalesOrderDetailVO(
                order.getId(), order.getOrderNo(), order.getCounterNo(), order.getCashierName(),
                order.getMemberId(), memberName, memberPhone,
                order.getSaleTime(), order.getBusinessDate(), order.getItemCount(),
                order.getGoodsAmount(), order.getMemberDiscountAmount(), order.getOrderDiscountAmount(),
                order.getDiscountAmount(), order.getPayableAmount(), order.getPaidAmount(),
                order.getChangeAmount(), order.getOrderDiscountRate(), order.getCostAmount(),
                order.getProfitAmount(), order.getReturnStatus(), order.getAuthorizerName(), order.getRemark(),
                items.stream().map(SalesQueryService::toItemVO).toList(),
                payments.stream().map(p -> new SalesOrderDetailVO.PaymentVO(
                        p.getPayType(), p.getAmount(), p.getPayTime(), p.getOperatorName())).toList(),
                returns.stream().map(r -> new SalesOrderDetailVO.LinkedReturnVO(
                        r.getId(), r.getOrderNo(), r.getRefundAmount(), r.getReturnReason(),
                        r.getRefundType(), r.getCashierName(), r.getAuthorizerName(), r.getReturnTime())).toList());
    }

    static boolean isGlobalSaleRole(LoginUser operator) {
        return operator.roleCodes().stream().anyMatch(GLOBAL_SALE_ROLES::contains);
    }

    /** BR-SALE-09：收银员仅本人单据（cashierId 服务端注入，忽略前端传参） */
    private SalesOrderQuery scopeQuery(SalesOrderQuery q, LoginUser operator) {
        if (isGlobalSaleRole(operator)) {
            return q;
        }
        return new SalesOrderQuery(q.orderNo(), null, q.counterNo(), operator.userId(),
                q.memberKeyword(), q.payType(), q.beginDate(), q.endDate(),
                q.amountMin(), q.amountMax(), q.returnStatus(), q.memberOnly(), q.page(), q.size());
    }

    private SalesOrder requireOrderInScope(Long id, LoginUser operator) {
        SalesOrder order = salesOrderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "销售单不存在");
        }
        if (!isGlobalSaleRole(operator) && !operator.userId().equals(order.getCashierId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "收银员仅可查询本人经手的销售单（BR-SALE-09）");
        }
        return order;
    }

    private static SalesOrderDetailVO.ItemVO toItemVO(SalesOrderItem item) {
        return new SalesOrderDetailVO.ItemVO(
                item.getId(), item.getGoodsId(), item.getGoodsName(), item.getBarcode(),
                item.getUnitPrice(), item.getQuantity(), item.getIsWeighed(), item.getNetWeight(),
                item.getAmount(), item.getDiscountRate(), item.getDiscountAmount(), item.getPaidAmount(),
                item.getCostPrice(), item.getCostAmount(), item.getProfitAmount(),
                item.getReturnedQty(), item.getLineType(), item.getRemark());
    }
}

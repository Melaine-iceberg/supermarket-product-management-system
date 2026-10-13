package com.smms.sale.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.goods.entity.Goods;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.inv.dto.StockChange;
import com.smms.inv.service.InventoryService;
import com.smms.member.entity.Member;
import com.smms.member.mapper.MemberMapper;
import com.smms.member.service.MemberBusinessService;
import com.smms.sale.dto.ReturnCreateRequest;
import com.smms.sale.dto.ReturnOrderDetailVO;
import com.smms.sale.dto.ReturnOrderVO;
import com.smms.sale.dto.ReturnQuery;
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
import com.smms.common.util.MaskUtils;
import com.smms.security.LoginUser;
import com.smms.security.SecurityUtils;
import com.smms.sys.service.BusinessDayService;
import com.smms.sys.service.SensitiveAuthService;
import com.smms.sys.service.SysConfigService;
import com.smms.sys.service.SysDictService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 销售退货服务（FR-SALE-10/11）。
 * 单事务内：退货单落库（XT 单号，BR-SALE-02）→ 按原成交单价退款（BR-SALE-06）
 * → 逐行回补库存并按成本快照回冲（SALE_RETURN，BR-INV-06）
 * → 更新原单退货状态 → 会员退货回退（BR-MEM-05/06：累计消费、积分、BALANCE 储值退回）。
 * 授权（FR-SALE-11）：退款超阈值或原因为"误操作"须店长授权且授权人 ≠ 发起人；
 * 超过退货有效期须店长特批（BR-SALE-05）；收银员仅可办理本人单据退货（BR-SALE-09）。
 */
@Service
@RequiredArgsConstructor
public class SalesReturnService {

    /** 退货授权金额阈值参数（BR-SALE-05，默认 200 元） */
    private static final String CONFIG_RETURN_AUTH_AMOUNT = "return_auth_amount";
    /** 退货有效期参数（BR-SALE-05，默认 7 天） */
    private static final String CONFIG_RETURN_VALID_DAYS = "return_valid_days";
    /** 收款方式字典（FR-SYS-07）：退款方式与之同域，停用项不可被新单据选择 */
    private static final String DICT_PAY_TYPE = "pay_type";
    /** 退货原因字典（FR-SYS-07） */
    private static final String DICT_RETURN_REASON = "return_reason";
    /** 内置退款方式兜底（字典缺失/为空时使用；退款方式与收款方式同域，FR-MEM-08） */
    private static final Set<String> REFUND_TYPES = Set.of("CASH", "BANK", "MOBILE", "BALANCE");
    /** 内置退货原因兜底（字典缺失/为空时使用） */
    private static final Set<String> RETURN_REASONS = Set.of("QUALITY", "WRONG_ITEM", "MISTAKE", "OTHER");
    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_RETRY = 3;

    private final ReturnOrderMapper returnOrderMapper;
    private final ReturnOrderItemMapper returnOrderItemMapper;
    private final SalesOrderMapper salesOrderMapper;
    private final SalesOrderItemMapper salesOrderItemMapper;
    private final GoodsMapper goodsMapper;
    private final MemberMapper memberMapper;
    private final CashierShiftMapper shiftMapper;
    private final InventoryService inventoryService;
    private final SysConfigService configService;
    private final BusinessDayService businessDayService;
    private final MemberBusinessService memberBusinessService;
    private final SensitiveAuthService sensitiveAuthService;
    private final SysDictService dictService;

    // ==================== 办理退货（FR-SALE-10） ====================

    @Transactional
    public ReturnOrderDetailVO createReturn(ReturnCreateRequest req, LoginUser operator) {
        LocalDateTime now = LocalDateTime.now();

        // 0. 请求令牌防重（NFR-REL-02）：同令牌重放（网络重发/重复点击）直接返回既有退货单，
        //    不重复退款、不重复回补库存
        String requestToken = normalizeToken(req.requestToken());
        if (requestToken != null) {
            ReturnOrder replayed = findReturnByToken(requestToken);
            if (replayed != null) {
                return detail(replayed.getId());
            }
        }

        // 1. 原单校验：存在 + 本人单据或店长权限（BR-SALE-09）+ 未全部退货
        SalesOrder order = salesOrderMapper.selectById(req.salesOrderId());
        if (order == null) {
            throw new BusinessException(ErrorCode.SALE_RETURN_ORDER_NOT_FOUND);
        }
        if (!SalesQueryService.isGlobalSaleRole(operator)
                && !operator.userId().equals(order.getCashierId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "收银员仅可办理本人经手的销售单退货（BR-SALE-09）");
        }
        if ("ALL".equals(order.getReturnStatus())) {
            throw new BusinessException(ErrorCode.SALE_RETURN_FULLY_RETURNED);
        }
        // 交班后该班次单据的退货须店长处理（FR-SALE-12 / BR-SALE-09）
        if (!SalesQueryService.isGlobalSaleRole(operator) && order.getShiftId() != null) {
            CashierShift shift = shiftMapper.selectById(order.getShiftId());
            if (shift != null && CashierShift.STATUS_CLOSED.equals(shift.getStatus())) {
                throw new BusinessException(ErrorCode.SHIFT_RETURN_FORBIDDEN,
                        "该销售单所属班次已于 " + shift.getEndTime() + " 交班，退货须店长处理");
            }
        }

        // 2. 基础项校验：退款方式（BALANCE 仅限会员单退回储值，FR-MEM-08）/ 退货原因；
        //    取值以数据字典为准（FR-SYS-07：停用项不可选），字典缺失/为空时按内置枚举兜底
        if ("BALANCE".equals(req.refundType()) && order.getMemberId() == null) {
            throw new BusinessException(ErrorCode.SALE_BALANCE_UNAVAILABLE,
                    "储值退款仅限会员单（退款将退回会员储值余额）");
        }
        Set<String> enabledRefundTypes = dictService.enabledValues(DICT_PAY_TYPE);
        Set<String> allowedRefundTypes = enabledRefundTypes.isEmpty() ? REFUND_TYPES : enabledRefundTypes;
        if (!allowedRefundTypes.contains(req.refundType())) {
            throw new BusinessException(ErrorCode.SALE_REFUND_TYPE_INVALID, "退款方式不合法：" + req.refundType());
        }
        Set<String> enabledReasons = dictService.enabledValues(DICT_RETURN_REASON);
        Set<String> allowedReasons = enabledReasons.isEmpty() ? RETURN_REASONS : enabledReasons;
        if (!allowedReasons.contains(req.returnReason())) {
            throw new BusinessException(ErrorCode.SALE_RETURN_REASON_INVALID, "退货原因不合法：" + req.returnReason());
        }

        // 3. 退货有效期（BR-SALE-05）：超期须店长特批
        int validDays = configService.getInt(CONFIG_RETURN_VALID_DAYS, 7);
        boolean expired = order.getSaleTime().plusDays(validDays).isBefore(now);
        if (expired && !SalesQueryService.isGlobalSaleRole(operator)) {
            throw new BusinessException(ErrorCode.SALE_RETURN_EXPIRED,
                    "已超过 " + validDays + " 天退货有效期，须店长特批");
        }

        // 4. 明细校验与退款计算（BR-SALE-06/07）：按原成交单价（含折扣）比例退款
        Map<Long, SalesOrderItem> itemMap = loadOriginalItems(order.getId());
        List<CalculatedReturn> lines = calculateReturnLines(req.items(), itemMap);
        BigDecimal totalRefund = lines.stream().map(CalculatedReturn::refundAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 5. 授权校验（FR-SALE-11）：超阈值或误操作须店长账号+密码授权，授权人 ≠ 发起人；
        //    超期特批（BR-SALE-05）：经办店长本人即授权人，或由其他店长账号授权
        BigDecimal authThreshold = new BigDecimal(
                configService.getStr(CONFIG_RETURN_AUTH_AMOUNT, "200"));
        boolean needAuth = totalRefund.compareTo(authThreshold) > 0
                || "MISTAKE".equals(req.returnReason());
        String authUsername = req.authorizerUsername();
        boolean hasAuthInput = authUsername != null && !authUsername.isBlank();
        String authorizer = null;
        if (needAuth) {
            if (!hasAuthInput) {
                throw new BusinessException(ErrorCode.SALE_RETURN_AUTH_REQUIRED,
                        "MISTAKE".equals(req.returnReason())
                                ? "误操作退货须店长账号授权（FR-SALE-11）"
                                : "退款金额 " + totalRefund + " 元超过阈值 "
                                        + authThreshold.stripTrailingZeros().toPlainString() + " 元，须店长账号授权");
            }
            authorizer = verifyAuthorizer(authUsername, req.authorizerPassword(), operator, true);
        } else if (expired) {
            authorizer = hasAuthInput
                    ? verifyAuthorizer(authUsername, req.authorizerPassword(), operator, false)
                    : operator.realName(); // 超期店长特批：经办店长即授权人
        }

        // 6. 退货单落库（XT 单号，BR-SALE-02；uk_rt_no 唯一键兜底并发冲突，
        //    uk_rt_token 兜底并发同令牌重放，NFR-REL-02）
        ReturnOrder returnOrder;
        try {
            returnOrder = insertReturnOrder(req, order, operator, now, totalRefund, authorizer, requestToken);
        } catch (ReturnReplaySignal signal) {
            return detail(signal.returnOrder.getId());
        }

        // 7. 明细落库 + 原单已退数量回写
        for (CalculatedReturn line : lines) {
            ReturnOrderItem item = new ReturnOrderItem();
            item.setReturnId(returnOrder.getId());
            item.setSalesItemId(line.item().getId());
            item.setGoodsId(line.item().getGoodsId());
            item.setReturnQty(line.returnQty());
            item.setRefundPrice(line.refundPrice());
            item.setRefundAmount(line.refundAmount());
            item.setCostPrice(line.item().getCostPrice());
            item.setCostAmount(line.costAmount());
            item.setRemark(line.remark());
            returnOrderItemMapper.insert(item);

            SalesOrderItem original = line.item();
            original.setReturnedQty(original.getReturnedQty().add(line.returnQty()));
            salesOrderItemMapper.updateById(original);
        }

        // 8. 逐行回补库存并按成本快照回冲（SALE_RETURN 不重算移动平均，BR-INV-06）
        for (CalculatedReturn line : lines.stream()
                .sorted((a, b) -> a.item().getGoodsId().compareTo(b.item().getGoodsId())).toList()) {
            inventoryService.stockIn(new StockChange(
                    line.item().getGoodsId(), "SALE_RETURN", line.returnQty(),
                    line.item().getCostPrice(), "XT", returnOrder.getOrderNo(),
                    operator.realName(), line.remark()));
        }

        // 9. 原单退货状态回写（FR-SALE-10）：全部行退完 → ALL，否则 PARTIAL
        boolean allReturned = itemMap.values().stream()
                .allMatch(i -> i.getReturnedQty().compareTo(i.getQuantity()) >= 0);
        order.setReturnStatus(allReturned ? "ALL" : "PARTIAL");
        salesOrderMapper.updateById(order);

        // 10. 会员退货回退（BR-MEM-05/06，FR-MEM-08）：
        // 累计消费额 −= 退款、积分等比例扣回（扣至 0 为止）、BALANCE 退款退回储值余额
        if (order.getMemberId() != null) {
            memberBusinessService.returnEffects(order.getMemberId(), order.getOrderNo(),
                    order.getPayableAmount(), totalRefund, req.refundType(),
                    returnOrder.getOrderNo(), operator.realName(), now);
        }

        return detail(returnOrder.getId());
    }

    // ==================== 退货单查询（FR-SALE-10） ====================

    /** 分页：退货单号/原销售单号/会员/退款方式/原因/时间区间；收银员仅见本人经手（BR-SALE-09） */
    public PageVO<ReturnOrderVO> page(ReturnQuery q, LoginUser operator) {
        int pageNo = Math.max(q.page(), 1);
        int size = q.size() <= 0 ? 20 : Math.min(q.size(), 100);
        ReturnQuery scoped = SalesQueryService.isGlobalSaleRole(operator) ? q
                : new ReturnQuery(q.orderNo(), q.salesOrderNo(), q.memberKeyword(), q.refundType(),
                        q.returnReason(), q.beginDate(), q.endDate(), operator.realName(), q.page(), q.size());
        IPage<ReturnOrderVO> result =
                returnOrderMapper.selectReturnPage(new Page<>(pageNo, size), scoped);
        // 会员手机号按角色脱敏（BR-MEM-08：完整手机号仅店长及以上可见）
        if (!SecurityUtils.canViewMemberPhone()) {
            List<ReturnOrderVO> masked = result.getRecords().stream()
                    .map(r -> r.withMemberPhone(MaskUtils.maskPhone(r.memberPhone())))
                    .toList();
            result.setRecords(masked);
        }
        return PageVO.of(result);
    }

    /** 退货单详情（FR-SALE-10）：关联原单信息与明细 */
    public ReturnOrderDetailVO detail(Long id) {
        ReturnOrder returnOrder = returnOrderMapper.selectById(id);
        if (returnOrder == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "退货单不存在");
        }
        SalesOrder order = salesOrderMapper.selectById(returnOrder.getSalesOrderId());
        Member member = returnOrder.getMemberId() == null
                ? null : memberMapper.selectById(returnOrder.getMemberId());
        List<ReturnOrderItem> items = returnOrderItemMapper.selectList(
                Wrappers.<ReturnOrderItem>lambdaQuery().eq(ReturnOrderItem::getReturnId, id));

        Map<Long, Goods> goodsMap = goodsMapper.selectBatchIds(
                        items.stream().map(ReturnOrderItem::getGoodsId)
                                .filter(java.util.Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(Goods::getId, Function.identity()));

        return new ReturnOrderDetailVO(
                returnOrder.getId(), returnOrder.getOrderNo(),
                returnOrder.getSalesOrderId(), order == null ? null : order.getOrderNo(),
                order == null ? null : order.getSaleTime(),
                member == null ? null : member.getName(),
                // 手机号按角色脱敏（BR-MEM-08）
                member == null ? null
                        : SecurityUtils.canViewMemberPhone() ? member.getPhone() : MaskUtils.maskPhone(member.getPhone()),
                returnOrder.getReturnReason(), returnOrder.getReasonRemark(),
                returnOrder.getRefundAmount(), returnOrder.getRefundType(),
                returnOrder.getCashierName(), returnOrder.getAuthorizerName(),
                returnOrder.getAuthorizeTime(), returnOrder.getReturnTime(),
                returnOrder.getBusinessDate(),
                items.stream().map(i -> new ReturnOrderDetailVO.ItemVO(
                        i.getId(), i.getSalesItemId(), i.getGoodsId(),
                        goodsName(goodsMap, i), goodsBarcode(goodsMap, i),
                        i.getReturnQty(), i.getRefundPrice(), i.getRefundAmount(),
                        i.getCostPrice(), i.getCostAmount(), i.getRemark())).toList());
    }

    // ==================== 内部计算与校验 ====================

    private Map<Long, SalesOrderItem> loadOriginalItems(Long orderId) {
        return salesOrderItemMapper.selectList(Wrappers.<SalesOrderItem>lambdaQuery()
                        .eq(SalesOrderItem::getOrderId, orderId))
                .stream().collect(Collectors.toMap(SalesOrderItem::getId, Function.identity()));
    }

    /**
     * 退货明细校验与退款计算（BR-SALE-06/07）：
     * 行退款 = 原行实付 × 退货数量 / 原数量（四舍五入到分，整行退时与原实付严格一致）；
     * 退款单价 = 原行实付 / 原数量（展示用原实际成交单价，含折扣分摊）；
     * 商品"允许退货"标志拦截（BR-SALE-07）；数量 ≤ 原数量 − 已退。
     */
    private List<CalculatedReturn> calculateReturnLines(List<ReturnCreateRequest.ReturnItemRequest> requests,
                                                        Map<Long, SalesOrderItem> itemMap) {
        List<CalculatedReturn> lines = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (ReturnCreateRequest.ReturnItemRequest request : requests) {
            if (!seen.add(request.salesItemId())) {
                throw new BusinessException(ErrorCode.SALE_RETURN_LINE_INVALID, "退货明细重复：" + request.salesItemId());
            }
            SalesOrderItem item = itemMap.get(request.salesItemId());
            if (item == null) {
                throw new BusinessException(ErrorCode.SALE_RETURN_LINE_INVALID,
                        "退货明细 " + request.salesItemId() + " 不属于原销售单");
            }
            if (item.getGoodsId() == null || !"NORMAL".equals(item.getLineType())) {
                throw new BusinessException(ErrorCode.SALE_RETURN_LINE_INVALID,
                        "无码商品行不支持退货（FR-SALE-13）");
            }
            Goods goods = goodsMapper.selectById(item.getGoodsId());
            if (goods != null && (goods.getAllowReturn() == null || goods.getAllowReturn() != 1)) {
                throw new BusinessException(ErrorCode.SALE_RETURN_NOT_ALLOWED,
                        "商品【" + item.getGoodsName() + "】不允许退货（BR-SALE-07）");
            }
            BigDecimal qty = request.returnQty();
            if (qty.scale() > 3) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "退货数量最多 3 位小数");
            }
            BigDecimal available = item.getQuantity().subtract(item.getReturnedQty());
            if (qty.compareTo(available) > 0) {
                throw new BusinessException(ErrorCode.SALE_RETURN_LINE_INVALID,
                        "商品【" + item.getGoodsName() + "】可退数量为 "
                                + available.stripTrailingZeros().toPlainString());
            }
            BigDecimal refund = item.getPaidAmount().multiply(qty)
                    .divide(item.getQuantity(), 2, RoundingMode.HALF_UP);
            BigDecimal refundPrice = item.getPaidAmount()
                    .divide(item.getQuantity(), 2, RoundingMode.HALF_UP);
            BigDecimal costAmount = item.getCostPrice().multiply(qty)
                    .setScale(2, RoundingMode.HALF_UP);
            lines.add(new CalculatedReturn(item, qty, refund, refundPrice, costAmount, request.remark()));
        }
        return lines;
    }

    /**
     * 授权人身份核验（FR-SALE-11）：店长账号+密码校验通过后返回授权人姓名；
     * rejectSelf=true 时授权人不得为退货发起人本人。
     */
    private String verifyAuthorizer(String username, String password, LoginUser operator, boolean rejectSelf) {
        SensitiveAuthService.Authorizer authorizer = sensitiveAuthService.authorize(username, password);
        if (rejectSelf && authorizer.userId().equals(operator.userId())) {
            throw new BusinessException(ErrorCode.SALE_RETURN_AUTH_SELF);
        }
        return authorizer.realName();
    }

    /** XT 单号生成（BR-SALE-02）：XT+yyyyMMdd+收银台2位+4位当日流水（独立于 XS 流水） */
    private String nextReturnNo(int counterNo) {
        String prefix = "XT" + LocalDate.now().format(DATE_PART) + "%02d".formatted(counterNo);
        String last = returnOrderMapper.selectLastReturnNoForUpdate(prefix);
        int seq = last == null ? 1 : Integer.parseInt(last.substring(prefix.length())) + 1;
        if (seq > 9999) {
            throw new BusinessException(ErrorCode.SALE_DAILY_NO_EXCEED, "该收银台当日退货流水号已用尽");
        }
        return prefix + "%04d".formatted(seq);
    }

    private ReturnOrder insertReturnOrder(ReturnCreateRequest req, SalesOrder order, LoginUser operator,
                                          LocalDateTime now, BigDecimal totalRefund, String authorizer,
                                          String requestToken) {
        ReturnOrder returnOrder = new ReturnOrder();
        returnOrder.setStoreId(1L);
        returnOrder.setSalesOrderId(order.getId());
        returnOrder.setMemberId(order.getMemberId());
        returnOrder.setReturnReason(req.returnReason());
        returnOrder.setReasonRemark(req.reasonRemark());
        returnOrder.setRefundAmount(totalRefund);
        returnOrder.setRefundType(req.refundType());
        returnOrder.setCashierName(operator.realName());
        returnOrder.setReturnTime(now);
        returnOrder.setBusinessDate(businessDayService.businessDate(now));
        returnOrder.setRequestToken(requestToken);
        if (authorizer != null && !authorizer.isBlank()) {
            returnOrder.setAuthorizerName(authorizer);
            returnOrder.setAuthorizeTime(now);
        }

        DuplicateKeyException conflict = null;
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            returnOrder.setOrderNo(nextReturnNo(order.getCounterNo()));
            try {
                returnOrderMapper.insert(returnOrder);
                return returnOrder;
            } catch (DuplicateKeyException e) {
                // 并发同令牌：另一请求已成功退单 → 回查后按重放返回（NFR-REL-02）
                if (requestToken != null) {
                    ReturnOrder existing = findReturnByToken(requestToken);
                    if (existing != null) {
                        throw new ReturnReplaySignal(existing);
                    }
                }
                conflict = e;
            }
        }
        throw new BusinessException(ErrorCode.SALE_ORDER_NO_RETRY,
                "退货单号生成冲突，请重试（" + (conflict == null ? "" : conflict.getMessage()) + "）");
    }

    /** 请求令牌去空白并归一：空串视为未携带（历史客户端兼容） */
    private String normalizeToken(String token) {
        return token == null || token.isBlank() ? null : token.trim();
    }

    private ReturnOrder findReturnByToken(String requestToken) {
        return returnOrderMapper.selectOne(
                Wrappers.<ReturnOrder>lambdaQuery().eq(ReturnOrder::getRequestToken, requestToken));
    }

    /** 并发同令牌重放信号：携带另一请求已落库的退货单，由 createReturn 捕获并按重放返回 */
    private static class ReturnReplaySignal extends RuntimeException {

        private final transient ReturnOrder returnOrder;

        ReturnReplaySignal(ReturnOrder returnOrder) {
            this.returnOrder = returnOrder;
        }
    }

    private String goodsName(Map<Long, Goods> goodsMap, ReturnOrderItem item) {
        Goods goods = item.getGoodsId() == null ? null : goodsMap.get(item.getGoodsId());
        return goods == null ? "未知商品" : goods.getName();
    }

    private String goodsBarcode(Map<Long, Goods> goodsMap, ReturnOrderItem item) {
        Goods goods = item.getGoodsId() == null ? null : goodsMap.get(item.getGoodsId());
        return goods == null ? null : goods.getBarcode();
    }

    /** 退货行计算结果 */
    private record CalculatedReturn(SalesOrderItem item, BigDecimal returnQty,
                                    BigDecimal refundAmount, BigDecimal refundPrice,
                                    BigDecimal costAmount, String remark) {
    }
}

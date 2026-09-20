package com.smms.sale.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import com.smms.sale.dto.CheckoutCommand;
import com.smms.sale.dto.CheckoutVO;
import com.smms.sale.dto.MemberSearchVO;
import com.smms.sale.entity.PaymentRecord;
import com.smms.sale.entity.SalesOrder;
import com.smms.sale.entity.SalesOrderItem;
import com.smms.sale.mapper.PaymentRecordMapper;
import com.smms.sale.mapper.SalesOrderMapper;
import com.smms.sale.mapper.SalesOrderItemMapper;
import com.smms.security.LoginUser;
import com.smms.sys.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 收银结算核心服务（FR-SALE-06）。
 * 单事务内完成：销售单与明细落库（快照，BR-SALE-04）→ 逐行原子扣减库存并写出库流水
 * （BR-INV-03，按 goodsId 升序防死锁）→ 收款记录 → 会员消费统计。
 * 金额计算顺序与分摊遵循 BR-SALE-03；取价遵循 BR-GOODS-04（生效促销价 &gt; 会员价（仅会员单）&gt; 零售价）。
 * 销售单一经结算不可修改删除（BR-SALE-08），错误只能通过退货冲正。
 */
@Service
@RequiredArgsConstructor
public class SalesCheckoutService {

    private static final long STORE_ID = 1L;
    /** 最低整单折扣率参数（BR-SALE-05/10） */
    private static final String CONFIG_MIN_DISCOUNT = "min_discount_rate";
    /** 营业日起始时刻（BR-RPT-02 跨零点归属） */
    private static final String CONFIG_BUSINESS_DAY_START = "business_day_start";
    /** 收银台可用收款方式（BALANCE 储值支付待会员模块开放） */
    private static final Set<String> PAY_TYPES = Set.of("CASH", "BANK", "MOBILE");
    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_RETRY = 3;

    private final SalesOrderMapper salesOrderMapper;
    private final SalesOrderItemMapper salesOrderItemMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final GoodsMapper goodsMapper;
    private final PromotionMapper promotionMapper;
    private final InventoryMapper inventoryMapper;
    private final MemberMapper memberMapper;
    private final MemberLevelMapper memberLevelMapper;
    private final InventoryService inventoryService;
    private final SysConfigService configService;

    // ==================== 结算（FR-SALE-06） ====================

    @Transactional
    public CheckoutVO checkout(CheckoutCommand cmd, LoginUser cashier) {
        LocalDateTime now = LocalDateTime.now();

        // 1. 明细基础校验（BR-SALE-01：同商品一行、数量精度）
        validateItems(cmd.items());

        // 2. 商品与价格档案（停用商品不可销售，FR-GOODS-05）
        Map<Long, Goods> goodsMap = loadGoods(cmd.items());
        Map<Long, Promotion> promoMap = loadEffectivePromotions(goodsMap.keySet(), now);

        // 3. 会员识别与等级折扣（FR-SALE-05；散客不享受会员价与折扣）
        MemberContext member = resolveMember(cmd.memberId());

        // 4. 整单折扣授权校验（FR-SALE-07）
        BigDecimal orderRate = resolveOrderRate(cmd);

        // 5. 逐行取价与金额计算（BR-GOODS-04 取价 → BR-SALE-03 分摊，尾差入最后一行）
        BigDecimal memberRate = member.discountRate();
        CalculatedLines lines = calculateLines(cmd.items(), goodsMap, promoMap, member.hasMember(), memberRate, orderRate);

        // 6. 收款校验：实收 ≥ 应收，找零 = 实收 − 应收（FR-SALE-06）
        BigDecimal paid = validatePayments(cmd.payments());
        if (paid.compareTo(lines.payable()) < 0) {
            throw new BusinessException(ErrorCode.SALE_PAID_NOT_ENOUGH,
                    "实收 " + paid + " 元不足应收 " + lines.payable() + " 元");
        }
        BigDecimal change = paid.subtract(lines.payable());

        // 7. 单号生成（BR-SALE-02）+ 销售单落库（uk_so_no 唯一键兜底并发冲突）
        SalesOrder order = insertOrder(cmd, cashier, now, lines, paid, change);

        // 8. 明细落库（快照，BR-SALE-04）
        for (SalesOrderItem item : buildItems(order.getId(), lines)) {
            salesOrderItemMapper.insert(item);
        }

        // 9. 逐行原子扣减库存并写出库流水（按 goodsId 升序防死锁，BR-INV-03）
        for (CalculatedLine line : lines.sortedByGoodsId()) {
            inventoryService.stockOut(new StockChange(
                    line.goodsId(), "SALE_OUT", line.quantity(), null,
                    "XS", order.getOrderNo(), cashier.realName(), null));
        }

        // 10. 收款记录（FR-SALE-06 一单多方式）
        List<CheckoutVO.PaymentLineVO> paymentLines = new ArrayList<>();
        for (CheckoutCommand.CheckoutPayment pay : cmd.payments()) {
            paymentRecordMapper.insert(paymentRecord(order.getId(), pay, now, cashier.realName()));
            paymentLines.add(new CheckoutVO.PaymentLineVO(pay.payType(), pay.amount()));
        }

        // 11. 会员消费统计（BR-MEM-06 口径：累计消费额 = Σ实付；积分累计待会员模块）
        if (member.hasMember()) {
            accumulateMember(member.member(), lines.payable(), now);
        }

        return new CheckoutVO(order.getId(), order.getOrderNo(), order.getItemCount(),
                lines.goodsAmount(), order.getMemberDiscountAmount(), order.getOrderDiscountAmount(),
                order.getDiscountAmount(), lines.payable(), paid, change, paymentLines,
                order.getSaleTime());
    }

    // ==================== 收银台会员检索（FR-SALE-05） ====================

    /** 手机号/会员编号精确 + 姓名模糊检索，最多 10 条；停用会员返回且折扣率置 1（BR-MEM-03） */
    public List<MemberSearchVO> searchMembers(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String kw = keyword.trim();
        List<Member> members = memberMapper.selectList(Wrappers.<Member>lambdaQuery()
                .and(w -> w.eq(Member::getPhone, kw)
                        .or().eq(Member::getMemberNo, kw)
                        .or().like(Member::getName, kw))
                .orderByDesc(Member::getId)
                .last("LIMIT 10"));
        if (members.isEmpty()) {
            return List.of();
        }
        Map<Long, MemberLevel> levels = memberLevelMapper.selectBatchIds(
                        members.stream().map(Member::getLevelId).distinct().toList())
                .stream().collect(Collectors.toMap(MemberLevel::getId, Function.identity()));
        return members.stream().map(m -> {
            MemberLevel level = levels.get(m.getLevelId());
            boolean enabled = m.getStatus() != null && m.getStatus() == 1;
            BigDecimal rate = enabled && level != null && level.getStatus() != null && level.getStatus() == 1
                    ? level.getDiscountRate() : BigDecimal.ONE;
            return new MemberSearchVO(m.getId(), m.getMemberNo(), m.getName(), m.getPhone(),
                    level == null ? "普通会员" : level.getName(), rate, m.getPoints(), m.getStatus());
        }).toList();
    }

    // ==================== 内部计算与校验 ====================

    /** 明细校验：非空、同商品不重复成行、数量精度（BR-SALE-01） */
    private void validateItems(List<CheckoutCommand.CheckoutItem> items) {
        Set<Long> goodsIds = new HashSet<>();
        for (CheckoutCommand.CheckoutItem item : items) {
            if (!goodsIds.add(item.goodsId())) {
                throw new BusinessException(ErrorCode.SALE_GOODS_DUPLICATED,
                        "商品【" + goodsName(item.goodsId()) + "】重复录入，请合并为一行");
            }
            BigDecimal qty = item.quantity();
            if (qty.scale() > 3) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "数量最多 3 位小数");
            }
        }
    }

    /** 商品档案加载：不存在或已停用即拒绝（FR-GOODS-05 停用不可销售） */
    private Map<Long, Goods> loadGoods(List<CheckoutCommand.CheckoutItem> items) {
        List<Long> ids = items.stream().map(CheckoutCommand.CheckoutItem::goodsId).toList();
        Map<Long, Goods> map = goodsMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Goods::getId, Function.identity()));
        for (CheckoutCommand.CheckoutItem item : items) {
            Goods goods = map.get(item.goodsId());
            if (goods == null || goods.getStatus() == null || goods.getStatus() != 1) {
                throw new BusinessException(ErrorCode.SALE_GOODS_INVALID,
                        "商品【" + (goods == null ? item.goodsId() : goods.getName()) + "】不存在或已停用，不可销售");
            }
        }
        return map;
    }

    /** 结算时刻生效中的促销（BR-GOODS-04：取价时刻的生效促销价优先） */
    private Map<Long, Promotion> loadEffectivePromotions(Set<Long> goodsIds, LocalDateTime now) {
        if (goodsIds.isEmpty()) {
            return Map.of();
        }
        return promotionMapper.selectList(Wrappers.<Promotion>lambdaQuery()
                        .in(Promotion::getGoodsId, goodsIds)
                        .eq(Promotion::getStatus, "ACTIVE")
                        .le(Promotion::getStartTime, now)
                        .ge(Promotion::getEndTime, now))
                .stream()
                .collect(Collectors.toMap(Promotion::getGoodsId, Function.identity(), (a, b) -> a));
    }

    /** 会员上下文：不存在/停用即拒绝（BR-MEM-03：停用会员提示后按散客结算） */
    private MemberContext resolveMember(Long memberId) {
        if (memberId == null) {
            return new MemberContext(null, BigDecimal.ONE);
        }
        Member member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BusinessException(ErrorCode.SALE_MEMBER_NOT_FOUND);
        }
        if (member.getStatus() == null || member.getStatus() != 1) {
            throw new BusinessException(ErrorCode.SALE_MEMBER_DISABLED);
        }
        MemberLevel level = memberLevelMapper.selectById(member.getLevelId());
        BigDecimal rate = level != null && level.getStatus() != null && level.getStatus() == 1
                && level.getDiscountRate() != null
                ? level.getDiscountRate() : BigDecimal.ONE;
        return new MemberContext(member, rate);
    }

    /** 整单折扣率校验（FR-SALE-07/BR-SALE-05/10）：不低于系统下限；打折须店长授权 */
    private BigDecimal resolveOrderRate(CheckoutCommand cmd) {
        if (cmd.orderDiscountRate() == null) {
            return BigDecimal.ONE;
        }
        BigDecimal rate = cmd.orderDiscountRate();
        BigDecimal min = new BigDecimal(configService.getStr(CONFIG_MIN_DISCOUNT, "0.7"));
        if (rate.compareTo(min) < 0) {
            throw new BusinessException(ErrorCode.SALE_DISCOUNT_BELOW_MIN,
                    "整单折扣率不得低于 " + min.stripTrailingZeros().toPlainString());
        }
        if (rate.compareTo(BigDecimal.ONE) < 0
                && (cmd.authorizerName() == null || cmd.authorizerName().isBlank())) {
            throw new BusinessException(ErrorCode.SALE_DISCOUNT_AUTH_REQUIRED);
        }
        return rate;
    }

    /**
     * 逐行金额计算（BR-SALE-03）：
     * 行金额 = 取价 × 数量（四舍五入到分）→ 整单优惠率 = 会员等级折扣 × 店长整单折扣（BR-SALE-10）
     * → 行优惠额按比例四舍五入到分，尾差计入最后一行，保证 Σ行实付 = 应收金额。
     * 成本按当前移动加权成本快照（BR-INV-06/BR-SALE-04）。
     */
    private CalculatedLines calculateLines(List<CheckoutCommand.CheckoutItem> items,
                                           Map<Long, Goods> goodsMap,
                                           Map<Long, Promotion> promoMap,
                                           boolean hasMember, BigDecimal memberRate, BigDecimal orderRate) {
        Map<Long, Inventory> inventoryMap = inventoryMapper.selectBatchIds(
                        items.stream().map(CheckoutCommand.CheckoutItem::goodsId).toList())
                .stream().collect(Collectors.toMap(Inventory::getGoodsId, Function.identity()));

        List<CalculatedLine> lines = new ArrayList<>();
        for (CheckoutCommand.CheckoutItem item : items) {
            Goods goods = goodsMap.get(item.goodsId());
            boolean weighed = goods.getIsWeighed() != null && goods.getIsWeighed() == 1;
            BigDecimal qty = item.quantity();
            if (weighed) {
                // 称重商品：数量即净重（≤3 位小数），金额 = 单价 × 净重四舍五入到分（BR-GOODS-05）
                if (qty.stripTrailingZeros().scale() > 3) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID,
                            "称重商品【" + goods.getName() + "】净重最多 3 位小数");
                }
            } else if (qty.stripTrailingZeros().scale() > 0) {
                throw new BusinessException(ErrorCode.SALE_QTY_INVALID,
                        "商品【" + goods.getName() + "】数量必须为正整数");
            }
            // 取价：生效促销价 > 会员价（仅会员单）> 零售价（BR-GOODS-04）
            BigDecimal unitPrice = resolvePrice(goods, promoMap.get(goods.getId()), hasMember);
            BigDecimal amount = unitPrice.multiply(qty).setScale(2, RoundingMode.HALF_UP);
            BigDecimal costPrice = inventoryMap.containsKey(goods.getId())
                    ? inventoryMap.get(goods.getId()).getCostPrice() : BigDecimal.ZERO;
            if (costPrice == null) {
                costPrice = BigDecimal.ZERO;
            }
            lines.add(new CalculatedLine(
                    goods.getId(), goods.getName(), goods.getBarcode(), unitPrice, qty, amount,
                    weighed ? qty : null, costPrice, null, null, null, null, item.remark()));
        }

        // 整单优惠分摊（BR-SALE-03）
        BigDecimal goodsAmount = lines.stream().map(CalculatedLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalRate = memberRate.multiply(orderRate);
        BigDecimal targetDiscount = goodsAmount.multiply(BigDecimal.ONE.subtract(totalRate))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < lines.size() - 1; i++) {
            CalculatedLine line = lines.get(i);
            BigDecimal discount = line.amount().multiply(BigDecimal.ONE.subtract(totalRate))
                    .setScale(2, RoundingMode.HALF_UP);
            allocated = allocated.add(discount);
            lines.set(i, line.withDiscount(discount));
        }
        // 尾差计入最后一行：Σ行优惠 = 整单优惠，Σ行实付 = 应收
        CalculatedLine last = lines.get(lines.size() - 1);
        BigDecimal lastDiscount = targetDiscount.subtract(allocated);
        lines.set(lines.size() - 1, last.withDiscount(lastDiscount));

        // 会员优惠与店长优惠的展示拆分（口径仍以优惠合计为准）
        BigDecimal memberDiscount = hasMember
                ? goodsAmount.multiply(BigDecimal.ONE.subtract(memberRate)).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal payable = goodsAmount.subtract(targetDiscount);
        return new CalculatedLines(lines, goodsAmount, targetDiscount, memberDiscount,
                targetDiscount.subtract(memberDiscount), payable, totalRate);
    }

    /** 取价（BR-GOODS-04）：生效促销价 &gt; 会员价（仅会员单）&gt; 零售价 */
    private BigDecimal resolvePrice(Goods goods, Promotion promo, boolean hasMember) {
        if (promo != null && promo.getPromoPrice() != null) {
            return promo.getPromoPrice().setScale(2, RoundingMode.HALF_UP);
        }
        if (hasMember && goods.getMemberPrice() != null && goods.getMemberPrice().compareTo(BigDecimal.ZERO) > 0) {
            return goods.getMemberPrice().setScale(2, RoundingMode.HALF_UP);
        }
        return goods.getRetailPrice().setScale(2, RoundingMode.HALF_UP);
    }

    /** 收款校验：方式合法（储值待会员模块开放）、金额为正、实收 ≥ 应收 */
    private BigDecimal validatePayments(List<CheckoutCommand.CheckoutPayment> payments) {
        BigDecimal paid = BigDecimal.ZERO;
        for (CheckoutCommand.CheckoutPayment pay : payments) {
            if ("BALANCE".equals(pay.payType())) {
                throw new BusinessException(ErrorCode.SALE_BALANCE_UNAVAILABLE);
            }
            if (!PAY_TYPES.contains(pay.payType())) {
                throw new BusinessException(ErrorCode.SALE_PAY_TYPE_INVALID,
                        "收款方式不合法：" + pay.payType() + "（当前支持 CASH/BANK/MOBILE）");
            }
            if (pay.amount() == null || pay.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(ErrorCode.SALE_PAY_AMOUNT_INVALID);
            }
            paid = paid.add(pay.amount());
        }
        return paid.setScale(2, RoundingMode.HALF_UP);
    }

    /** 单号生成（BR-SALE-02）：XS+yyyyMMdd+收银台2位+4位当日流水；前缀范围行锁串行取号 */
    private String nextOrderNo(int counterNo) {
        String prefix = "XS" + LocalDate.now().format(DATE_PART) + "%02d".formatted(counterNo);
        String last = salesOrderMapper.selectLastOrderNoForUpdate(prefix);
        int seq = last == null ? 1 : Integer.parseInt(last.substring(prefix.length())) + 1;
        if (seq > 9999) {
            throw new BusinessException(ErrorCode.SALE_DAILY_NO_EXCEED,
                    "收银台 " + counterNo + " 当日流水号已用尽");
        }
        return prefix + "%04d".formatted(seq);
    }

    /** 销售单落库：唯一键冲突（并发出单）自动换单号重试（BR-SALE-02 全局唯一） */
    private SalesOrder insertOrder(CheckoutCommand cmd, LoginUser cashier, LocalDateTime now,
                                   CalculatedLines lines, BigDecimal paid, BigDecimal change) {
        int itemCount = cmd.items().stream()
                .mapToInt(i -> i.quantity().stripTrailingZeros().scale() <= 0
                        ? i.quantity().intValue() : 1)
                .sum();
        SalesOrder order = new SalesOrder();
        order.setStoreId(STORE_ID);
        order.setCounterNo(cmd.counterNo());
        order.setCashierId(cashier.userId());
        order.setCashierName(cashier.realName());
        order.setMemberId(cmd.memberId());
        order.setSaleTime(now);
        order.setBusinessDate(businessDate(now));
        order.setItemCount(itemCount);
        order.setGoodsAmount(lines.goodsAmount());
        order.setMemberDiscountAmount(lines.memberDiscount());
        order.setOrderDiscountAmount(lines.orderDiscount());
        order.setDiscountAmount(lines.totalDiscount());
        order.setPayableAmount(lines.payable());
        order.setPaidAmount(paid);
        order.setChangeAmount(change);
        order.setOrderDiscountRate(cmd.orderDiscountRate());
        order.setCostAmount(BigDecimal.ZERO);
        order.setProfitAmount(BigDecimal.ZERO);
        order.setReturnStatus("NONE");
        order.setAuthorizerName(cmd.authorizerName());
        order.setRemark(cmd.remark());

        DuplicateKeyException conflict = null;
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            order.setOrderNo(nextOrderNo(cmd.counterNo()));
            try {
                salesOrderMapper.insert(order);
                return order;
            } catch (DuplicateKeyException e) {
                conflict = e;
            }
        }
        throw new BusinessException(ErrorCode.SALE_ORDER_NO_RETRY,
                "单号生成冲突，请重试结算（" + (conflict == null ? "" : conflict.getMessage()) + "）");
    }

    /** 明细实体（全部快照字段，BR-SALE-04）：行实付 = 行金额 − 行优惠；成本额/毛利额快照 */
    private List<SalesOrderItem> buildItems(Long orderId, CalculatedLines lines) {
        List<SalesOrderItem> result = new ArrayList<>();
        for (CalculatedLine line : lines.lines()) {
            SalesOrderItem entity = new SalesOrderItem();
            entity.setOrderId(orderId);
            entity.setGoodsId(line.goodsId());
            entity.setGoodsName(line.goodsName());
            entity.setBarcode(line.barcode());
            entity.setUnitPrice(line.unitPrice());
            entity.setQuantity(line.quantity());
            entity.setIsWeighed(line.netWeight() != null ? 1 : 0);
            entity.setNetWeight(line.netWeight());
            entity.setAmount(line.amount());
            entity.setDiscountRate(lines.totalRate());
            entity.setDiscountAmount(line.discount());
            BigDecimal paidAmount = line.amount().subtract(line.discount());
            entity.setPaidAmount(paidAmount);
            BigDecimal costAmount = line.costPrice().multiply(line.quantity())
                    .setScale(2, RoundingMode.HALF_UP);
            entity.setCostPrice(line.costPrice().setScale(4, RoundingMode.HALF_UP));
            entity.setCostAmount(costAmount);
            entity.setProfitAmount(paidAmount.subtract(costAmount));
            entity.setReturnedQty(BigDecimal.ZERO);
            entity.setLineType("NORMAL");
            entity.setRemark(line.remark());
            result.add(entity);
        }
        return result;
    }

    private PaymentRecord paymentRecord(Long orderId, CheckoutCommand.CheckoutPayment pay,
                                        LocalDateTime now, String cashierName) {
        PaymentRecord record = new PaymentRecord();
        record.setOrderId(orderId);
        record.setPayType(pay.payType());
        record.setAmount(pay.amount().setScale(2, RoundingMode.HALF_UP));
        record.setPayTime(now);
        record.setOperatorName(cashierName);
        return record;
    }

    /** 会员消费统计（BR-MEM-06）：累计消费额 += 应收、消费次数 +1、刷新最近消费时间 */
    private void accumulateMember(Member member, BigDecimal payable, LocalDateTime now) {
        member.setTotalConsume((member.getTotalConsume() == null ? BigDecimal.ZERO : member.getTotalConsume())
                .add(payable));
        member.setConsumeTimes((member.getConsumeTimes() == null ? 0 : member.getConsumeTimes()) + 1);
        member.setLastConsumeTime(now);
        memberMapper.updateById(member);
    }

    /** 营业日（BR-RPT-02）：结算时刻早于营业日起始时刻则归属前一日 */
    private LocalDate businessDate(LocalDateTime now) {
        LocalTime dayStart = LocalTime.parse(configService.getStr(CONFIG_BUSINESS_DAY_START, "00:00"));
        return now.toLocalTime().isBefore(dayStart) ? now.toLocalDate().minusDays(1) : now.toLocalDate();
    }

    private String goodsName(Long goodsId) {
        Goods goods = goodsMapper.selectById(goodsId);
        return goods == null ? String.valueOf(goodsId) : goods.getName();
    }

    /** 会员上下文（散客折扣率恒为 1） */
    private record MemberContext(Member member, BigDecimal discountRate) {
        boolean hasMember() {
            return member != null;
        }
    }

    /** 单行计算结果（不可变，withDiscount 用于回填分摊优惠） */
    private record CalculatedLine(Long goodsId, String goodsName, String barcode,
                                  BigDecimal unitPrice, BigDecimal quantity, BigDecimal amount,
                                  BigDecimal netWeight, BigDecimal costPrice, BigDecimal discount,
                                  BigDecimal paidAmount, BigDecimal costAmount, BigDecimal profitAmount,
                                  String remark) {

        CalculatedLine withDiscount(BigDecimal discount) {
            return new CalculatedLine(goodsId, goodsName, barcode, unitPrice, quantity, amount,
                    netWeight, costPrice, discount, amount.subtract(discount), costAmount, profitAmount, remark);
        }
    }

    /** 整单金额计算结果：应收 = 商品总额 − 优惠合计（BR-SALE-03） */
    private record CalculatedLines(List<CalculatedLine> lines, BigDecimal goodsAmount,
                                   BigDecimal totalDiscount, BigDecimal memberDiscount,
                                   BigDecimal orderDiscount, BigDecimal payable, BigDecimal totalRate) {

        List<CalculatedLine> sortedByGoodsId() {
            return lines.stream().sorted((a, b) -> a.goodsId().compareTo(b.goodsId())).toList();
        }
    }
}

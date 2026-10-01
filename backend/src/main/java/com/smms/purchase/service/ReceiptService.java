package com.smms.purchase.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.goods.entity.Goods;
import com.smms.goods.entity.Unit;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.goods.mapper.UnitMapper;
import com.smms.inv.dto.StockChange;
import com.smms.inv.service.InventoryService;
import com.smms.purchase.dto.ReceiptCreateRequest;
import com.smms.purchase.dto.ReceiptOrderVO;
import com.smms.purchase.dto.ReceiptPreCheckVO;
import com.smms.purchase.dto.ReceiptQuery;
import com.smms.purchase.entity.PurchaseOrder;
import com.smms.purchase.entity.PurchaseOrderItem;
import com.smms.purchase.entity.ReceiptOrder;
import com.smms.purchase.entity.ReceiptOrderItem;
import com.smms.purchase.mapper.PurchaseOrderItemMapper;
import com.smms.purchase.mapper.PurchaseOrderMapper;
import com.smms.purchase.mapper.ReceiptOrderItemMapper;
import com.smms.purchase.mapper.ReceiptOrderMapper;
import com.smms.security.LoginUser;
import com.smms.supplier.entity.Supplier;
import com.smms.supplier.mapper.SupplierMapper;
import com.smms.sys.entity.OperationLog;
import com.smms.sys.service.OperationLogService;
import com.smms.sys.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 采购入库验收服务（FR-PUR-06/07）。
 * 单事务内完成：入库单生成（RK 单号）→ 库存流水写入（PURCHASE_IN）→ 结存更新与
 * 移动加权平均成本重算（BR-INV-06，由 InventoryService.stockIn 统一处理）→
 * 采购单状态与金额更新（乐观锁 BR-PUR-06）→ 供应商累计采购额更新（DR-C-04）。
 * 超收容差（BR-PUR-04）与价差授权（BR-PUR-05）须店长授权并记录；
 * 职责分离（BR-SYS-01）：验收人 ≠ 采购单创建人，违反时按参数拒绝或放行并记异常日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptService {

    private static final long STORE_ID = 1L;
    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_RETRY = 3;
    /** 超收容差比例（BR-PUR-04，默认 5%） */
    private static final String CFG_RECEIVING_TOLERANCE = "receiving_tolerance_rate";
    /** 实收单价与采购单价偏离容差（BR-PUR-05，默认 ±5%） */
    private static final String CFG_PRICE_DIFF_TOLERANCE = "price_diff_tolerance_rate";
    /** 与近 3 次均价偏离黄色提示阈值（BR-PUR-05，默认 ±20%） */
    private static final String CFG_PRICE_DIFF_WARN = "price_diff_warn_rate";
    /** 职责分离是否强制拒绝（BR-SYS-01，默认 false 放行但记异常日志） */
    private static final String CFG_DUTY_SEPARATION_STRICT = "duty_separation_strict";

    private final PurchaseOrderMapper orderMapper;
    private final PurchaseOrderItemMapper orderItemMapper;
    private final PurchaseOrderService purchaseOrderService;
    private final ReceiptOrderMapper receiptMapper;
    private final ReceiptOrderItemMapper receiptItemMapper;
    private final SupplierMapper supplierMapper;
    private final GoodsMapper goodsMapper;
    private final UnitMapper unitMapper;
    private final InventoryService inventoryService;
    private final SysConfigService configService;
    private final OperationLogService operationLogService;

    // ==================== 入库预检（BR-PUR-04/05） ====================

    /**
     * 录入实收前的预检：返回每行待入库数量、容差上限、价差率、最近均价与黄色提示；
     * needAuth=true 的行提交时必须携带店长授权人。
     */
    public ReceiptPreCheckVO preCheck(ReceiptCreateRequest req) {
        PurchaseOrder order = purchaseOrderService.requireOrder(req.getPurchaseOrderId());
        requireReceivable(order);
        Map<Long, PurchaseOrderItem> itemMap = loadItems(order.getId());

        ReceiptPreCheckVO vo = new ReceiptPreCheckVO();
        vo.setPurchaseOrderId(order.getId());
        vo.setPurchaseOrderNo(order.getOrderNo());
        vo.setToleranceRate(toleranceRate());
        vo.setPriceDiffRate(priceDiffRate());
        vo.setPriceWarnRate(priceWarnRate());
        vo.setItems(req.getItems().stream()
                .map(item -> checkLine(itemMap, item, vo)).toList());
        return vo;
    }

    private ReceiptPreCheckVO.ItemCheckVO checkLine(Map<Long, PurchaseOrderItem> itemMap,
                                                    ReceiptCreateRequest.ItemRequest request,
                                                    ReceiptPreCheckVO vo) {
        PurchaseOrderItem poItem = requireItem(itemMap, request);
        validateQtyPrice(request);

        ReceiptPreCheckVO.ItemCheckVO line = new ReceiptPreCheckVO.ItemCheckVO();
        line.setPurchaseItemId(poItem.getId());
        line.setGoodsId(poItem.getGoodsId());
        Goods goods = goodsMapper.selectById(poItem.getGoodsId());
        line.setGoodsName(goods == null ? String.valueOf(poItem.getGoodsId()) : goods.getName());
        line.setPendingQty(poItem.getQuantity().subtract(poItem.getReceivedQty()));
        line.setToleranceMax(line.getPendingQty()
                .multiply(BigDecimal.ONE.add(vo.getToleranceRate().divide(HUNDRED, 4, RoundingMode.HALF_UP)))
                .setScale(3, RoundingMode.UP));
        line.setReceivedQty(request.getReceivedQty());
        line.setReceivedPrice(request.getReceivedPrice());
        line.setOrderPrice(poItem.getPrice());

        // 价差率（与本次采购单价，BR-PUR-05）
        line.setPriceDiffPercent(diffPercent(request.getReceivedPrice(), poItem.getPrice()));
        if (needPriceAuth(request.getReceivedPrice(), poItem.getPrice(), vo.getPriceDiffRate())) {
            line.setPriceAuthRequired(true);
        }
        // 与最近 3 次平均采购价偏离 → 黄色提示（不阻断）
        BigDecimal avg = receiptItemMapper.selectRecentAvgPrice(poItem.getGoodsId());
        line.setRecentAvgPrice(avg);
        if (avg != null && avg.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal warnPercent = diffPercent(request.getReceivedPrice(), avg);
            if (warnPercent.abs().compareTo(vo.getPriceWarnRate()) > 0) {
                line.setPriceWarning(true);
            }
        }
        // 超收容差（BR-PUR-04）
        if (request.getReceivedQty().compareTo(line.getToleranceMax()) > 0) {
            line.setOverTolerance(true);
        }
        line.setNeedAuth(line.isOverTolerance() || line.isPriceAuthRequired());
        return line;
    }

    // ==================== 入库验收（FR-PUR-06） ====================

    /** 入库验收主流程：校验（容差/价差/职责分离）→ 单事务落库与库存联动 */
    @Transactional
    public ReceiptOrderVO create(ReceiptCreateRequest req, LoginUser operator) {
        PurchaseOrder order = purchaseOrderService.requireOrder(req.getPurchaseOrderId());
        requireReceivable(order);
        Map<Long, PurchaseOrderItem> itemMap = loadItems(order.getId());

        // 基础校验并构建行
        List<Line> lines = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (ReceiptCreateRequest.ItemRequest request : req.getItems()) {
            if (!seen.add(request.getPurchaseItemId())) {
                throw new BusinessException(ErrorCode.PUR_RECEIPT_LINE_INVALID,
                        "入库明细重复：采购明细 " + request.getPurchaseItemId());
            }
            PurchaseOrderItem poItem = requireItem(itemMap, request);
            validateQtyPrice(request);
            lines.add(new Line(poItem, request.getReceivedQty(), request.getReceivedPrice(),
                    request.getReceivedQty().multiply(request.getReceivedPrice())
                            .setScale(2, RoundingMode.HALF_UP),
                    request.getRemark()));
        }

        // 容差与价差授权校验（BR-PUR-04/05）：超出须店长授权
        BigDecimal tolerance = toleranceRate();
        BigDecimal priceTolerance = priceDiffRate();
        boolean hasAuthorizer = !isBlank(req.getAuthorizerName());
        for (Line line : lines) {
            BigDecimal pending = line.item().getQuantity().subtract(line.item().getReceivedQty());
            BigDecimal toleranceMax = pending.multiply(
                    BigDecimal.ONE.add(tolerance.divide(HUNDRED, 4, RoundingMode.HALF_UP)));
            if (line.qty().compareTo(toleranceMax) > 0 && !hasAuthorizer) {
                throw new BusinessException(ErrorCode.PUR_RECEIPT_TOLERANCE_EXCEED,
                        "商品实收 " + line.qty().stripTrailingZeros().toPlainString()
                                + " 超出待入库 " + pending.stripTrailingZeros().toPlainString()
                                + " ×(1+" + tolerance.stripTrailingZeros().toPlainString() + "%)，须店长授权");
            }
            if (needPriceAuth(line.price(), line.item().getPrice(), priceTolerance) && !hasAuthorizer) {
                throw new BusinessException(ErrorCode.PUR_RECEIPT_PRICE_AUTH,
                        "实收单价 " + line.price() + " 与采购单价 " + line.item().getPrice()
                                + " 偏离超过 ±" + priceTolerance.stripTrailingZeros().toPlainString()
                                + "%，须店长授权");
            }
        }

        // 职责分离（BR-SYS-01）：验收人 ≠ 采购单创建人
        checkDutySeparation(order, operator);

        // 1. 入库单落库（RK 单号，BR-PUR-01）
        ReceiptOrder receipt = insertWithOrderNo(req, order, operator, lines);

        // 2. 明细落库 + 采购明细已入库数量回写
        for (Line line : lines) {
            ReceiptOrderItem item = new ReceiptOrderItem();
            item.setReceiptId(receipt.getId());
            item.setPurchaseItemId(line.item().getId());
            item.setGoodsId(line.item().getGoodsId());
            item.setReceivedQty(line.qty());
            item.setReceivedPrice(line.price());
            item.setAmount(line.amount());
            item.setRemark(line.remark());
            receiptItemMapper.insert(item);
            orderMapper.addReceivedQty(line.item().getId(), line.qty());
        }

        // 3. 库存入库 + 移动加权成本重算（BR-INV-06/BR-PUR-07：以实收单价为准）；
        //    按 goodsId 升序逐行处理，避免并发交叉死锁
        List<Line> sorted = lines.stream()
                .sorted((a, b) -> a.item().getGoodsId().compareTo(b.item().getGoodsId())).toList();
        for (Line line : sorted) {
            inventoryService.stockIn(new StockChange(
                    line.item().getGoodsId(), "PURCHASE_IN", line.qty(), line.price(),
                    "RK", receipt.getOrderNo(), operator.realName(), line.remark()));
        }

        // 4. 采购单状态与金额更新（乐观锁，BR-PUR-06）：全部行入库完成 → COMPLETED，否则 PARTIAL
        boolean allReceived = orderItemMapper.selectList(Wrappers.<PurchaseOrderItem>lambdaQuery()
                        .eq(PurchaseOrderItem::getOrderId, order.getId()))
                .stream().allMatch(i -> i.getReceivedQty().compareTo(i.getQuantity()) >= 0);
        BigDecimal total = lines.stream().map(Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        String newStatus = allReceived ? PurchaseOrder.STATUS_COMPLETED : PurchaseOrder.STATUS_PARTIAL;
        if (orderMapper.updateReceiptState(order.getId(), total, newStatus,
                operator.realName(), order.getVersion()) == 0) {
            throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT,
                    "采购单 " + order.getOrderNo() + " 正在被其他人入库，请刷新后重试（BR-PUR-06）");
        }

        // 5. 供应商累计采购额更新（DR-C-04 派生数据一致性）
        supplierMapper.addTotalPurchaseAmount(order.getSupplierId(), total);

        return detail(receipt.getId());
    }

    // ==================== 入库单查询（FR-PUR-07） ====================

    /** 分页：入库单号/采购单号/供应商/日期区间/操作人 */
    public PageVO<ReceiptOrderVO> page(ReceiptQuery q) {
        int pageNo = Math.max(q.page(), 1);
        int size = q.size() <= 0 ? 20 : Math.min(q.size(), 100);
        LocalDateTime beginTime = q.beginDate() == null ? null : q.beginDate().atStartOfDay();
        LocalDateTime endTime = q.endDate() == null ? null : q.endDate().plusDays(1).atStartOfDay();
        IPage<ReceiptOrderVO> result =
                receiptItemMapper.selectReceiptPage(new Page<>(pageNo, size), q, beginTime, endTime);
        return PageVO.of(result);
    }

    /** 详情：逐行实收数量、实收单价、金额、验收备注（A4 打印视图数据源） */
    public ReceiptOrderVO detail(Long id) {
        ReceiptOrder receipt = receiptMapper.selectById(id);
        if (receipt == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "入库单不存在");
        }
        PurchaseOrder order = orderMapper.selectById(receipt.getPurchaseOrderId());
        Supplier supplier = supplierMapper.selectById(receipt.getSupplierId());
        List<ReceiptOrderItem> items = receiptItemMapper.selectList(
                Wrappers.<ReceiptOrderItem>lambdaQuery()
                        .eq(ReceiptOrderItem::getReceiptId, id)
                        .orderByAsc(ReceiptOrderItem::getId));

        Map<Long, Goods> goodsMap = goodsMapper.selectBatchIds(
                        items.stream().map(ReceiptOrderItem::getGoodsId).distinct().toList())
                .stream().collect(Collectors.toMap(Goods::getId, Function.identity()));
        Map<Long, Unit> unitMap = unitMapper.selectBatchIds(
                        goodsMap.values().stream().map(Goods::getUnitId).distinct().toList())
                .stream().collect(Collectors.toMap(Unit::getId, Function.identity()));

        return new ReceiptOrderVO(
                receipt.getId(), receipt.getOrderNo(),
                receipt.getPurchaseOrderId(), order == null ? null : order.getOrderNo(),
                receipt.getSupplierId(), supplier == null ? null : supplier.getName(),
                receipt.getReceiptTime(), receipt.getOperatorName(), receipt.getAuthorizerName(),
                receipt.getDeliveryOrderNo(), receipt.getTotalAmount(), receipt.getRemark(),
                items.stream().map(i -> {
                    Goods goods = goodsMap.get(i.getGoodsId());
                    Unit unit = goods == null ? null : unitMap.get(goods.getUnitId());
                    return new ReceiptOrderVO.ItemVO(
                            i.getId(), i.getPurchaseItemId(), i.getGoodsId(),
                            goods == null ? null : goods.getGoodsCode(),
                            goods == null ? null : goods.getName(),
                            goods == null ? null : goods.getSpec(),
                            unit == null ? null : unit.getName(),
                            i.getReceivedQty(), i.getReceivedPrice(), i.getAmount(), i.getRemark());
                }).toList());
    }

    // ==================== 内部校验与工具 ====================

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** 可入库状态：已审核待入库 / 部分入库（状态—操作约束矩阵） */
    private void requireReceivable(PurchaseOrder order) {
        if (!PurchaseOrder.STATUS_APPROVED.equals(order.getStatus())
                && !PurchaseOrder.STATUS_PARTIAL.equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.PUR_STATUS_INVALID,
                    "采购单 " + order.getOrderNo() + " 当前状态为 " + order.getStatus()
                            + "，不允许入库（要求已审核待入库/部分入库）");
        }
    }

    private Map<Long, PurchaseOrderItem> loadItems(Long orderId) {
        return orderItemMapper.selectList(Wrappers.<PurchaseOrderItem>lambdaQuery()
                        .eq(PurchaseOrderItem::getOrderId, orderId))
                .stream().collect(Collectors.toMap(PurchaseOrderItem::getId, Function.identity()));
    }

    private PurchaseOrderItem requireItem(Map<Long, PurchaseOrderItem> itemMap,
                                          ReceiptCreateRequest.ItemRequest request) {
        PurchaseOrderItem poItem = itemMap.get(request.getPurchaseItemId());
        if (poItem == null) {
            throw new BusinessException(ErrorCode.PUR_RECEIPT_LINE_INVALID,
                    "入库明细 " + request.getPurchaseItemId() + " 不属于该采购单");
        }
        return poItem;
    }

    private void validateQtyPrice(ReceiptCreateRequest.ItemRequest request) {
        if (request.getReceivedQty() == null || request.getReceivedQty().compareTo(BigDecimal.ZERO) <= 0
                || request.getReceivedQty().scale() > 3) {
            throw new BusinessException(ErrorCode.PUR_RECEIPT_LINE_INVALID,
                    "实收数量必须大于 0 且最多 3 位小数");
        }
        if (request.getReceivedPrice() == null || request.getReceivedPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ErrorCode.PUR_RECEIPT_LINE_INVALID, "实收单价不能为负");
        }
    }

    /** 价差授权判定（BR-PUR-05）：与采购单价偏离超容差；采购单价为 0 而实收为正同样须授权 */
    private boolean needPriceAuth(BigDecimal receivedPrice, BigDecimal orderPrice, BigDecimal toleranceRate) {
        if (orderPrice == null || orderPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return receivedPrice != null && receivedPrice.compareTo(BigDecimal.ZERO) > 0;
        }
        return diffPercent(receivedPrice, orderPrice).abs().compareTo(toleranceRate) > 0;
    }

    /** 偏离百分比 =（实收单价 − 基准单价）/ 基准单价 × 100 */
    private BigDecimal diffPercent(BigDecimal price, BigDecimal base) {
        if (base == null || base.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return price.subtract(base).divide(base, 4, RoundingMode.HALF_UP)
                .multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }

    /** 职责分离（BR-SYS-01）：验收人 = 创建人时按参数拒绝或放行并记异常日志 */
    private void checkDutySeparation(PurchaseOrder order, LoginUser operator) {
        if (!operator.realName().equals(order.getCreateBy())) {
            return;
        }
        if (configService.getBool(CFG_DUTY_SEPARATION_STRICT, false)) {
            throw new BusinessException(ErrorCode.PUR_RECEIPT_DUTY_SEPARATION);
        }
        // LOG 模式：放行但记录异常日志（BR-SYS-01）
        OperationLog dutyLog = new OperationLog();
        dutyLog.setUserId(operator.userId());
        dutyLog.setUsername(operator.username());
        dutyLog.setRoleNames(String.join(",", operator.roleNames()));
        dutyLog.setModule("采购管理");
        dutyLog.setAction("职责未分离告警");
        dutyLog.setTarget("采购单 " + order.getOrderNo());
        dutyLog.setParamSummary("入库验收人与采购单创建人同为 " + operator.realName() + "（BR-SYS-01）");
        dutyLog.setResult(1);
        dutyLog.setOperateTime(LocalDateTime.now());
        operationLogService.recordAsync(dutyLog);
        log.warn("职责未分离：采购单 {} 验收人与创建人同为 {}（BR-SYS-01）", order.getOrderNo(), operator.realName());
    }

    /** RK 单号生成（BR-PUR-01）：RK+yyyyMMdd+4位流水，行锁取号 + 唯一键兜底重试 */
    private ReceiptOrder insertWithOrderNo(ReceiptCreateRequest req, PurchaseOrder order,
                                           LoginUser operator, List<Line> lines) {
        BigDecimal total = lines.stream().map(Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        ReceiptOrder receipt = new ReceiptOrder();
        receipt.setStoreId(STORE_ID);
        receipt.setPurchaseOrderId(order.getId());
        receipt.setSupplierId(order.getSupplierId());
        receipt.setReceiptTime(LocalDateTime.now());
        receipt.setOperatorName(operator.realName());
        receipt.setAuthorizerName(isBlank(req.getAuthorizerName()) ? null : req.getAuthorizerName());
        receipt.setDeliveryOrderNo(req.getDeliveryOrderNo());
        receipt.setTotalAmount(total);
        receipt.setRemark(req.getRemark());

        DuplicateKeyException conflict = null;
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            receipt.setOrderNo(nextOrderNo());
            try {
                receiptMapper.insert(receipt);
                return receipt;
            } catch (DuplicateKeyException e) {
                conflict = e;
            }
        }
        throw new BusinessException(ErrorCode.PUR_NO_EXCEED, "入库单号生成冲突，请重试（" + conflict + "）");
    }

    private String nextOrderNo() {
        String prefix = "RK" + LocalDate.now().format(DATE_PART);
        String last = receiptMapper.selectLastOrderNoForUpdate(prefix);
        int seq = last == null ? 1 : Integer.parseInt(last.substring(prefix.length())) + 1;
        if (seq > 9999) {
            throw new BusinessException(ErrorCode.PUR_NO_EXCEED, "当日入库单流水号已用尽");
        }
        return prefix + "%04d".formatted(seq);
    }

    private BigDecimal toleranceRate() {
        return new BigDecimal(configService.getStr(CFG_RECEIVING_TOLERANCE, "5"));
    }

    private BigDecimal priceDiffRate() {
        return new BigDecimal(configService.getStr(CFG_PRICE_DIFF_TOLERANCE, "5"));
    }

    private BigDecimal priceWarnRate() {
        return new BigDecimal(configService.getStr(CFG_PRICE_DIFF_WARN, "20"));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 验收行计算结果 */
    private record Line(PurchaseOrderItem item, BigDecimal qty, BigDecimal price,
                        BigDecimal amount, String remark) {
    }
}

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
import com.smms.purchase.dto.PurchaseAuditRequest;
import com.smms.purchase.dto.PurchaseReturnCreateRequest;
import com.smms.purchase.dto.PurchaseReturnQuery;
import com.smms.purchase.dto.PurchaseReturnVO;
import com.smms.purchase.entity.PurchaseOrder;
import com.smms.purchase.entity.PurchaseOrderItem;
import com.smms.purchase.entity.PurchaseReturnItem;
import com.smms.purchase.entity.PurchaseReturnOrder;
import com.smms.purchase.mapper.PurchaseOrderItemMapper;
import com.smms.purchase.mapper.PurchaseOrderMapper;
import com.smms.purchase.mapper.PurchaseReturnItemMapper;
import com.smms.purchase.mapper.PurchaseReturnOrderMapper;
import com.smms.purchase.mapper.ReceiptOrderItemMapper;
import com.smms.supplier.entity.Supplier;
import com.smms.supplier.mapper.SupplierMapper;
import com.smms.sys.service.BusinessDayService;
import com.smms.common.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 采购退货服务（FR-PUR-08）：针对质量问题等将已入库商品退回供应商。
 * 创建（PT 单号，待审核）→ 店长审核通过后单事务内：
 * 生成"采购退货"负数流水（PURCHASE_RETURN）、扣减结存、
 * 按入库时成本单价冲减库存成本、更新采购单退货数量与应付金额、冲减供应商累计采购额。
 * 状态机（§2.2.1）：已完成 → 部分退货 → 全部退货；部分入库单据亦可退货（状态—操作矩阵）。
 */
@Service
@RequiredArgsConstructor
public class PurchaseReturnService {

    private static final long STORE_ID = 1L;
    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_RETRY = 3;
    /** 允许发起退货的采购单状态：部分入库（矩阵允许）/ 已完成 / 部分退货 */
    private static final Set<String> RETURNABLE_STATUSES = Set.of(
            PurchaseOrder.STATUS_PARTIAL, PurchaseOrder.STATUS_COMPLETED,
            PurchaseOrder.STATUS_PARTIAL_RETURNED);

    private final PurchaseOrderMapper orderMapper;
    private final PurchaseOrderItemMapper orderItemMapper;
    private final PurchaseOrderService purchaseOrderService;
    private final PurchaseReturnOrderMapper returnMapper;
    private final PurchaseReturnItemMapper returnItemMapper;
    private final ReceiptOrderItemMapper receiptItemMapper;
    private final SupplierMapper supplierMapper;
    private final GoodsMapper goodsMapper;
    private final UnitMapper unitMapper;
    private final InventoryService inventoryService;
    private final BusinessDayService businessDayService;

    // ==================== 创建退货单（FR-PUR-08） ====================

    /** 创建待审核退货单：校验可退数量（≤ 已入库 − 已退），退货单价取入库时成本单价 */
    @Transactional
    public PurchaseReturnVO create(PurchaseReturnCreateRequest req, String operator) {
        PurchaseOrder order = purchaseOrderService.requireOrder(req.getPurchaseOrderId());
        if (!RETURNABLE_STATUSES.contains(order.getStatus())) {
            throw new BusinessException(ErrorCode.PUR_STATUS_INVALID,
                    "采购单 " + order.getOrderNo() + " 当前状态为 " + order.getStatus()
                            + "，不允许退货（要求部分入库/已完成/部分退货）");
        }
        Map<Long, PurchaseOrderItem> itemMap = orderItemMapper.selectList(
                        Wrappers.<PurchaseOrderItem>lambdaQuery()
                                .eq(PurchaseOrderItem::getOrderId, order.getId()))
                .stream().collect(Collectors.toMap(PurchaseOrderItem::getId, Function.identity()));

        Set<Long> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        PurchaseReturnOrder returnOrder = new PurchaseReturnOrder();
        returnOrder.setStoreId(STORE_ID);
        returnOrder.setPurchaseOrderId(order.getId());
        returnOrder.setSupplierId(order.getSupplierId());
        returnOrder.setStatus(PurchaseReturnOrder.STATUS_SUBMITTED);
        returnOrder.setReturnReason(req.getReturnReason());
        returnOrder.setCreateBy(operator);
        // 营业日（BR-RPT-02/GAP-P1-05）：采购汇总的退货侧按该字段聚合
        returnOrder.setBusinessDate(businessDayService.businessDate(LocalDateTime.now()));
        returnOrder.setRemark(TextSanitizer.sanitize(req.getRemark()));

        List<PurchaseReturnItem> items = new java.util.ArrayList<>();
        for (PurchaseReturnCreateRequest.ItemRequest request : req.getItems()) {
            if (!seen.add(request.getPurchaseItemId())) {
                throw new BusinessException(ErrorCode.PUR_RETURN_LINE_INVALID,
                        "退货明细重复：采购明细 " + request.getPurchaseItemId());
            }
            PurchaseOrderItem poItem = itemMap.get(request.getPurchaseItemId());
            if (poItem == null) {
                throw new BusinessException(ErrorCode.PUR_RETURN_LINE_INVALID,
                        "退货明细 " + request.getPurchaseItemId() + " 不属于该采购单");
            }
            BigDecimal qty = request.getReturnQty();
            if (qty.compareTo(BigDecimal.ZERO) <= 0 || qty.scale() > 3) {
                throw new BusinessException(ErrorCode.PUR_RETURN_LINE_INVALID,
                        "退货数量必须大于 0 且最多 3 位小数");
            }
            BigDecimal returnable = poItem.getReceivedQty().subtract(poItem.getReturnedQty());
            if (qty.compareTo(returnable) > 0) {
                Goods goods = goodsMapper.selectById(poItem.getGoodsId());
                throw new BusinessException(ErrorCode.PUR_RETURN_LINE_INVALID,
                        "商品【" + (goods == null ? poItem.getGoodsId() : goods.getName())
                                + "】可退数量为 " + returnable.stripTrailingZeros().toPlainString());
            }
            // 退货单价 = 入库时成本单价（按该明细入库记录加权，FR-PUR-08）
            BigDecimal returnPrice = receiptItemMapper.selectAvgReceiptPrice(poItem.getId());
            if (returnPrice == null) {
                returnPrice = poItem.getPrice();
            }
            PurchaseReturnItem item = new PurchaseReturnItem();
            item.setPurchaseItemId(poItem.getId());
            item.setGoodsId(poItem.getGoodsId());
            item.setReturnQty(qty);
            item.setReturnPrice(returnPrice);
            item.setAmount(qty.multiply(returnPrice).setScale(2, RoundingMode.HALF_UP));
            item.setRemark(TextSanitizer.sanitize(request.getRemark()));
            items.add(item);
            total = total.add(item.getAmount());
        }
        returnOrder.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        returnOrder.setCreateTime(LocalDateTime.now());
        insertWithOrderNo(returnOrder);
        for (PurchaseReturnItem item : items) {
            item.setReturnId(returnOrder.getId());
            // 审计字段（DR-C-08/GAP-P2-28）：明细创建人=经办人
            item.setCreateBy(operator);
            item.setCreateTime(LocalDateTime.now());
            returnItemMapper.insert(item);
        }
        return detail(returnOrder.getId());
    }

    // ==================== 审核（FR-PUR-08） ====================

    /**
     * 店长审核：通过后事务内执行退货（负数流水、扣结存、按入库成本冲减、
     * 更新采购单退货数量与应付金额、冲减供应商累计采购额）；驳回须填意见。
     */
    @Transactional
    public void audit(Long id, PurchaseAuditRequest req, String auditor) {
        PurchaseReturnOrder returnOrder = requireReturn(id);
        if (!PurchaseReturnOrder.STATUS_SUBMITTED.equals(returnOrder.getStatus())) {
            throw new BusinessException(ErrorCode.PUR_RETURN_STATUS_INVALID);
        }
        if (!Boolean.TRUE.equals(req.approved())) {
            if (req.remark() == null || req.remark().isBlank()) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "驳回时必须填写审核意见");
            }
            returnMapper.update(null, Wrappers.<PurchaseReturnOrder>lambdaUpdate()
                    .eq(PurchaseReturnOrder::getId, id)
                    .set(PurchaseReturnOrder::getStatus, PurchaseReturnOrder.STATUS_REJECTED)
                    .set(PurchaseReturnOrder::getAuditBy, auditor)
                    .set(PurchaseReturnOrder::getAuditTime, LocalDateTime.now())
                    .set(PurchaseReturnOrder::getAuditRemark, req.remark()));
            return;
        }

        PurchaseOrder order = purchaseOrderService.requireOrder(returnOrder.getPurchaseOrderId());
        List<PurchaseReturnItem> items = returnItemMapper.selectList(
                Wrappers.<PurchaseReturnItem>lambdaQuery().eq(PurchaseReturnItem::getReturnId, id));

        // 1. 退货单转已审核
        returnMapper.update(null, Wrappers.<PurchaseReturnOrder>lambdaUpdate()
                .eq(PurchaseReturnOrder::getId, id)
                .set(PurchaseReturnOrder::getStatus, PurchaseReturnOrder.STATUS_APPROVED)
                .set(PurchaseReturnOrder::getAuditBy, auditor)
                .set(PurchaseReturnOrder::getAuditTime, LocalDateTime.now())
                .set(PurchaseReturnOrder::getAuditRemark, req.remark()));

        // 2. 采购明细已退数量回写（原子防超退：并发退货时可退不足则整体回滚）
        for (PurchaseReturnItem item : items) {
            if (orderMapper.addReturnedQtyIfEnough(item.getPurchaseItemId(), item.getReturnQty()) == 0) {
                throw new BusinessException(ErrorCode.PUR_RETURN_LINE_INVALID,
                        "商品可退数量不足（已入库 − 已退），请刷新后重试");
            }
        }

        // 3. 采购退货负数流水 + 结存扣减（PURCHASE_RETURN；goodsId 升序防死锁）
        List<PurchaseReturnItem> sorted = items.stream()
                .sorted((a, b) -> a.getGoodsId().compareTo(b.getGoodsId())).toList();
        for (PurchaseReturnItem item : sorted) {
            inventoryService.stockOut(new StockChange(
                    item.getGoodsId(), "PURCHASE_RETURN", item.getReturnQty(), item.getReturnPrice(),
                    "PT", returnOrder.getOrderNo(), auditor, item.getRemark()));
        }

        // 4. 采购单退货金额与状态更新（乐观锁，BR-PUR-06）：
        //    仅“全部明细均已全量入库且已退完”才转 ALL_RETURNED（GAP-P0-04：部分入库单退完已入库量时，
        //    剩余未入库量仍待收货，若误转 ALL_RETURNED 将导致剩余数量无法继续入库/关闭而卡死）；
        //    部分入库单据仍待入库 → 保持 PARTIAL；
        //    已完成/部分退货后仍有余量 → PARTIAL_RETURNED
        List<PurchaseOrderItem> poItems = orderItemMapper.selectList(
                Wrappers.<PurchaseOrderItem>lambdaQuery().eq(PurchaseOrderItem::getOrderId, order.getId()));
        boolean fullyReceived = poItems.stream()
                .allMatch(i -> i.getReceivedQty().compareTo(i.getQuantity()) >= 0);
        boolean allReturned = fullyReceived && poItems.stream()
                .allMatch(i -> i.getReturnedQty().compareTo(i.getReceivedQty()) >= 0);
        String newStatus;
        if (allReturned) {
            newStatus = PurchaseOrder.STATUS_ALL_RETURNED;
        } else if (PurchaseOrder.STATUS_PARTIAL.equals(order.getStatus())) {
            newStatus = PurchaseOrder.STATUS_PARTIAL;
        } else {
            newStatus = PurchaseOrder.STATUS_PARTIAL_RETURNED;
        }
        if (orderMapper.updateReturnState(order.getId(), returnOrder.getTotalAmount(), newStatus,
                auditor, order.getVersion()) == 0) {
            throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT,
                    "采购单 " + order.getOrderNo() + " 正在被其他人处理，请刷新后重试（BR-PUR-06）");
        }

        // 5. 供应商累计采购额冲减（DR-C-04）
        supplierMapper.addTotalPurchaseAmount(order.getSupplierId(), returnOrder.getTotalAmount().negate());
    }

    // ==================== 查询（FR-PUR-08） ====================

    /** 分页：退货单号/采购单号/供应商/状态/日期区间 */
    public PageVO<PurchaseReturnVO> page(PurchaseReturnQuery q) {
        int pageNo = Math.max(q.page(), 1);
        int size = q.size() <= 0 ? 20 : Math.min(q.size(), 100);
        LocalDateTime beginTime = q.beginDate() == null ? null : q.beginDate().atStartOfDay();
        LocalDateTime endTime = q.endDate() == null ? null : q.endDate().plusDays(1).atStartOfDay();
        IPage<PurchaseReturnVO> result =
                returnMapper.selectReturnPage(new Page<>(pageNo, size), q, beginTime, endTime);
        return PageVO.of(result);
    }

    /** 详情：逐行退货数量、退货单价（入库时成本单价）、金额 */
    public PurchaseReturnVO detail(Long id) {
        PurchaseReturnOrder returnOrder = requireReturn(id);
        PurchaseOrder order = orderMapper.selectById(returnOrder.getPurchaseOrderId());
        Supplier supplier = supplierMapper.selectById(returnOrder.getSupplierId());
        List<PurchaseReturnItem> items = returnItemMapper.selectList(
                Wrappers.<PurchaseReturnItem>lambdaQuery()
                        .eq(PurchaseReturnItem::getReturnId, id)
                        .orderByAsc(PurchaseReturnItem::getId));

        Map<Long, Goods> goodsMap = goodsMapper.selectBatchIds(
                        items.stream().map(PurchaseReturnItem::getGoodsId).distinct().toList())
                .stream().collect(Collectors.toMap(Goods::getId, Function.identity()));
        Map<Long, Unit> unitMap = unitMapper.selectBatchIds(
                        goodsMap.values().stream().map(Goods::getUnitId).distinct().toList())
                .stream().collect(Collectors.toMap(Unit::getId, Function.identity()));

        return new PurchaseReturnVO(
                returnOrder.getId(), returnOrder.getOrderNo(),
                returnOrder.getPurchaseOrderId(), order == null ? null : order.getOrderNo(),
                returnOrder.getSupplierId(), supplier == null ? null : supplier.getName(),
                returnOrder.getStatus(), returnOrder.getReturnReason(), returnOrder.getTotalAmount(),
                returnOrder.getCreateBy(), returnOrder.getCreateTime(),
                returnOrder.getAuditBy(), returnOrder.getAuditTime(), returnOrder.getAuditRemark(),
                returnOrder.getRemark(),
                items.stream().map(i -> {
                    Goods goods = goodsMap.get(i.getGoodsId());
                    Unit unit = goods == null ? null : unitMap.get(goods.getUnitId());
                    return new PurchaseReturnVO.ItemVO(
                            i.getId(), i.getPurchaseItemId(), i.getGoodsId(),
                            goods == null ? null : goods.getGoodsCode(),
                            goods == null ? null : goods.getName(),
                            goods == null ? null : goods.getSpec(),
                            unit == null ? null : unit.getName(),
                            i.getReturnQty(), i.getReturnPrice(), i.getAmount(), i.getRemark());
                }).toList());
    }

    // ==================== 内部工具 ====================

    private PurchaseReturnOrder requireReturn(Long id) {
        PurchaseReturnOrder returnOrder = returnMapper.selectById(id);
        if (returnOrder == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "采购退货单不存在");
        }
        return returnOrder;
    }

    /** PT 单号生成（BR-PUR-01）：PT+yyyyMMdd+4位流水，行锁取号 + 唯一键兜底重试 */
    private void insertWithOrderNo(PurchaseReturnOrder returnOrder) {
        DuplicateKeyException conflict = null;
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            returnOrder.setOrderNo(nextOrderNo());
            try {
                returnMapper.insert(returnOrder);
                return;
            } catch (DuplicateKeyException e) {
                conflict = e;
            }
        }
        throw new BusinessException(ErrorCode.PUR_NO_EXCEED, "退货单号生成冲突，请重试（" + conflict + "）");
    }

    private String nextOrderNo() {
        String prefix = "PT" + LocalDate.now().format(DATE_PART);
        String last = returnMapper.selectLastOrderNoForUpdate(prefix);
        int seq = last == null ? 1 : Integer.parseInt(last.substring(prefix.length())) + 1;
        if (seq > 9999) {
            throw new BusinessException(ErrorCode.PUR_NO_EXCEED, "当日退货单流水号已用尽");
        }
        return prefix + "%04d".formatted(seq);
    }
}

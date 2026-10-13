package com.smms.purchase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.smms.purchase.dto.PurchaseAuditRequest;
import com.smms.purchase.dto.PurchaseOrderDetailVO;
import com.smms.purchase.dto.PurchaseOrderQuery;
import com.smms.purchase.dto.PurchaseOrderVO;
import com.smms.purchase.dto.PurchaseReasonRequest;
import com.smms.purchase.dto.PurchaseSaveRequest;
import com.smms.purchase.dto.PurchaseSettleRequest;
import com.smms.purchase.entity.PurchaseOrder;
import com.smms.purchase.entity.PurchaseOrderItem;
import com.smms.purchase.mapper.PurchaseOrderItemMapper;
import com.smms.purchase.mapper.PurchaseOrderMapper;
import com.smms.supplier.entity.Supplier;
import com.smms.supplier.mapper.SupplierMapper;
import com.smms.sys.service.BusinessDayService;
import com.smms.sys.service.SysDictService;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 采购单服务（FR-PUR-01/03/04/05/09）。
 * 状态机严格遵循 §2.2.1（BR-PUR-02）：草稿可反复编辑/删除/作废，提交后由店长审核，
 * 驳回退回草稿并须填意见；创建人可在审核前撤回；已审核单据明细不可编辑，
 * 终止/关闭须填原因；部分入库单据只可"关闭剩余数量"转已完成。
 * PO 单号 = PO+yyyyMMdd+4位流水（BR-PUR-01）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PurchaseOrderService {

    private static final long STORE_ID = 1L;
    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_RETRY = 3;
    private static final int MAX_ITEMS = 200;
    private static final Set<String> SETTLE_STATUSES = Set.of(
            PurchaseOrder.SETTLE_UNSETTLED, PurchaseOrder.SETTLE_PARTIAL, PurchaseOrder.SETTLE_SETTLED);
    /** 允许结算登记的单据状态：已发生入库后的各状态（FR-PUR-09） */
    private static final Set<String> SETTLABLE_STATUSES = Set.of(
            PurchaseOrder.STATUS_PARTIAL, PurchaseOrder.STATUS_COMPLETED,
            PurchaseOrder.STATUS_PARTIAL_RETURNED, PurchaseOrder.STATUS_ALL_RETURNED);
    /** 结算方式字典（FR-SYS-07/GAP-P1-09）：停用项不可被选择，字典缺失时回退内置枚举 */
    private static final String DICT_SETTLE_TYPE = "settle_type";
    private static final Set<String> SETTLE_TYPES = Set.of("CASH", "MONTHLY", "CREDIT");

    private final PurchaseOrderMapper orderMapper;
    private final PurchaseOrderItemMapper itemMapper;
    private final SupplierMapper supplierMapper;
    private final GoodsMapper goodsMapper;
    private final UnitMapper unitMapper;
    private final SysDictService dictService;
    private final BusinessDayService businessDayService;

    // ==================== 创建草稿（FR-PUR-01） ====================

    /** 保存草稿：明细金额与总额自动计算（BR-PUR-03），可反复编辑与删除 */
    @Transactional
    public PurchaseOrderDetailVO create(PurchaseSaveRequest req, String operator) {
        Supplier supplier = requireActiveSupplier(req.getSupplierId());
        List<PurchaseOrderItem> items = buildItems(req.getItems());

        PurchaseOrder order = new PurchaseOrder();
        order.setStoreId(STORE_ID);
        order.setSupplierId(supplier.getId());
        order.setStatus(PurchaseOrder.STATUS_DRAFT);
        order.setCreateBy(operator);
        // 营业日（BR-RPT-02/GAP-P1-05）：采购报表按该字段聚合，不再运行时按 create_time 计算
        order.setBusinessDate(businessDayService.businessDate(LocalDateTime.now()));
        order.setExpectDate(req.getExpectDate());
        order.setTotalAmount(totalOf(items));
        order.setReceivedAmount(BigDecimal.ZERO);
        order.setReturnAmount(BigDecimal.ZERO);
        order.setSettleStatus(PurchaseOrder.SETTLE_UNSETTLED);
        order.setVersion(0);
        order.setRemark(req.getRemark());
        insertWithOrderNo(order);

        for (PurchaseOrderItem item : items) {
            item.setOrderId(order.getId());
            itemMapper.insert(item);
        }
        log.info("采购单创建: orderNo={}, 供应商={}, 金额={}, 操作人={}",
                order.getOrderNo(), supplier.getName(), order.getTotalAmount(), operator);
        return detail(order.getId());
    }

    /** 编辑草稿：仅 DRAFT 态可编辑（BR-PUR-02 已审核后不可修改） */
    @Transactional
    public void update(Long id, PurchaseSaveRequest req, String operator) {
        PurchaseOrder order = requireOrder(id);
        requireStatus(order, PurchaseOrder.STATUS_DRAFT);
        Supplier supplier = requireActiveSupplier(req.getSupplierId());
        List<PurchaseOrderItem> items = buildItems(req.getItems());

        order.setSupplierId(supplier.getId());
        order.setExpectDate(req.getExpectDate());
        order.setTotalAmount(totalOf(items));
        order.setRemark(req.getRemark());
        order.setUpdateBy(operator);
        casUpdateById(order);

        itemMapper.delete(Wrappers.<PurchaseOrderItem>lambdaQuery()
                .eq(PurchaseOrderItem::getOrderId, id));
        for (PurchaseOrderItem item : items) {
            item.setOrderId(id);
            itemMapper.insert(item);
        }
    }

    /** 删除草稿：仅 DRAFT 态（状态—操作约束矩阵） */
    @Transactional
    public void delete(Long id) {
        PurchaseOrder order = requireOrder(id);
        requireStatus(order, PurchaseOrder.STATUS_DRAFT);
        itemMapper.delete(Wrappers.<PurchaseOrderItem>lambdaQuery()
                .eq(PurchaseOrderItem::getOrderId, id));
        orderMapper.deleteById(id);
    }

    // ==================== 提交与审核（FR-PUR-04） ====================

    /** 提交审核：DRAFT → SUBMITTED */
    @Transactional
    public void submit(Long id, String operator) {
        PurchaseOrder order = requireOrder(id);
        requireStatus(order, PurchaseOrder.STATUS_DRAFT);
        casUpdate(order, PurchaseOrder.STATUS_SUBMITTED, updater -> updater
                .set(PurchaseOrder::getSubmitTime, LocalDateTime.now())
                .set(PurchaseOrder::getUpdateBy, operator));
    }

    /** 创建人撤回：SUBMITTED → DRAFT（审核前，FR-PUR-04） */
    @Transactional
    public void withdraw(Long id, String operator) {
        PurchaseOrder order = requireOrder(id);
        requireStatus(order, PurchaseOrder.STATUS_SUBMITTED);
        if (!order.getCreateBy().equals(operator)) {
            throw new BusinessException(ErrorCode.PUR_WITHDRAW_NOT_OWNER);
        }
        casUpdate(order, PurchaseOrder.STATUS_DRAFT, updater -> updater
                .set(PurchaseOrder::getSubmitTime, null)
                .set(PurchaseOrder::getUpdateBy, operator));
    }

    /** 店长审核：通过 → APPROVED；驳回须填意见并退回 DRAFT（FR-PUR-04） */
    @Transactional
    public void audit(Long id, PurchaseAuditRequest req, String auditor) {
        PurchaseOrder order = requireOrder(id);
        requireStatus(order, PurchaseOrder.STATUS_SUBMITTED);
        if (!Boolean.TRUE.equals(req.approved()) && isBlank(req.remark())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "驳回时必须填写审核意见（FR-PUR-04）");
        }
        String target = Boolean.TRUE.equals(req.approved())
                ? PurchaseOrder.STATUS_APPROVED : PurchaseOrder.STATUS_DRAFT;
        casUpdate(order, target, updater -> updater
                .set(PurchaseOrder::getAuditBy, auditor)
                .set(PurchaseOrder::getAuditTime, LocalDateTime.now())
                .set(PurchaseOrder::getAuditRemark, req.remark())
                .set(!Boolean.TRUE.equals(req.approved()), PurchaseOrder::getSubmitTime, null)
                .set(PurchaseOrder::getUpdateBy, auditor));
    }

    // ==================== 作废/终止/关闭（FR-PUR-05） ====================

    /** 作废：仅 DRAFT 态，须填原因 */
    @Transactional
    public void cancel(Long id, PurchaseReasonRequest req, String operator) {
        PurchaseOrder order = requireOrder(id);
        requireStatus(order, PurchaseOrder.STATUS_DRAFT);
        casUpdate(order, PurchaseOrder.STATUS_CANCELLED, updater -> updater
                .set(PurchaseOrder::getTerminateReason, req.reason())
                .set(PurchaseOrder::getUpdateBy, operator));
    }

    /** 终止：仅 APPROVED（已审核未入库）可由店长终止，须填原因（BR-PUR-08 在途量同步扣除） */
    @Transactional
    public void terminate(Long id, PurchaseReasonRequest req, String operator) {
        PurchaseOrder order = requireOrder(id);
        requireStatus(order, PurchaseOrder.STATUS_APPROVED);
        casUpdate(order, PurchaseOrder.STATUS_TERMINATED, updater -> updater
                .set(PurchaseOrder::getTerminateReason, req.reason())
                .set(PurchaseOrder::getUpdateBy, operator));
    }

    /** 关闭短少：仅 PARTIAL 态，剩余数量不再入库，转已完成（FR-PUR-05） */
    @Transactional
    public void closeRemaining(Long id, PurchaseReasonRequest req, String operator) {
        PurchaseOrder order = requireOrder(id);
        requireStatus(order, PurchaseOrder.STATUS_PARTIAL);
        casUpdate(order, PurchaseOrder.STATUS_COMPLETED, updater -> updater
                .set(PurchaseOrder::getTerminateReason, "关闭短少：" + req.reason())
                .set(PurchaseOrder::getUpdateBy, operator));
    }

    // ==================== 结算登记（FR-PUR-09） ====================

    /** 标记结算状态并登记金额/方式/日期/经办人（不做财务核销，X-05）；结算方式取值以字典为准（GAP-P1-09） */
    @Transactional
    public void settle(Long id, PurchaseSettleRequest req, String operator) {
        PurchaseOrder order = requireOrder(id);
        if (!SETTLABLE_STATUSES.contains(order.getStatus())) {
            throw new BusinessException(ErrorCode.PUR_SETTLE_INVALID,
                    "仅已入库的采购单可登记结算（当前状态：" + order.getStatus() + "）");
        }
        if (!SETTLE_STATUSES.contains(req.settleStatus())) {
            throw new BusinessException(ErrorCode.PUR_SETTLE_INVALID, "结算状态不合法：" + req.settleStatus());
        }
        boolean unsettled = PurchaseOrder.SETTLE_UNSETTLED.equals(req.settleStatus());
        String settleMethod = unsettled ? null : requireSettleMethod(req.settleMethod());
        casUpdate(order, order.getStatus(), updater -> updater
                .set(PurchaseOrder::getSettleStatus, req.settleStatus())
                .set(PurchaseOrder::getSettleAmount,
                        unsettled ? BigDecimal.ZERO : req.settleAmount())
                .set(PurchaseOrder::getSettleMethod, settleMethod)
                .set(PurchaseOrder::getSettleDate, unsettled ? null : req.settleDate())
                .set(PurchaseOrder::getSettleBy, unsettled ? null : operator)
                .set(PurchaseOrder::getUpdateBy, operator));
    }

    /** 结算方式校验（FR-PUR-09、FR-SYS-07/GAP-P1-09）：非未结算状态下必填且须为启用中的字典项 */
    private String requireSettleMethod(String settleMethod) {
        String value = settleMethod == null ? "" : settleMethod.trim();
        if (value.isEmpty()) {
            throw new BusinessException(ErrorCode.PUR_SETTLE_INVALID, "请选择结算方式（数据字典 settle_type）");
        }
        Set<String> enabled = dictService.enabledValues(DICT_SETTLE_TYPE);
        Set<String> allowed = enabled.isEmpty() ? SETTLE_TYPES : enabled;
        if (!allowed.contains(value)) {
            throw new BusinessException(ErrorCode.PUR_SETTLE_INVALID,
                    "结算方式不合法或已被停用（数据字典 settle_type）：" + value);
        }
        return value;
    }

    // ==================== 查询（FR-PUR-03） ====================

    /** 分页 + 多维筛选；列表含总额、已入库数量、入库进度、结算状态 */
    public PageVO<PurchaseOrderVO> page(PurchaseOrderQuery q) {
        int pageNo = Math.max(q.page(), 1);
        int size = q.size() <= 0 ? 20 : Math.min(q.size(), 100);
        LocalDateTime beginTime = q.createDateBegin() == null ? null : q.createDateBegin().atStartOfDay();
        LocalDateTime endTime = q.createDateEnd() == null ? null : q.createDateEnd().plusDays(1).atStartOfDay();
        IPage<PurchaseOrderVO> result =
                orderMapper.selectOrderPage(new Page<>(pageNo, size), q, beginTime, endTime, null);
        return PageVO.of(result);
    }

    /** 待入库采购单分页（GAP-P0-05）：已审核待入库/部分入库，供库管员验收选单（只读，鉴权 sys:rcv:list） */
    public PageVO<PurchaseOrderVO> pickable(String orderNo, int page, int size) {
        int pageNo = Math.max(page, 1);
        int pageSize = size <= 0 ? 20 : Math.min(size, 50);
        PurchaseOrderQuery q = new PurchaseOrderQuery(blankToNull(orderNo), null, null, null,
                null, null, null, null, null, pageNo, pageSize);
        IPage<PurchaseOrderVO> result =
                orderMapper.selectOrderPage(new Page<>(pageNo, pageSize), q, null, null,
                        List.of(PurchaseOrder.STATUS_APPROVED, PurchaseOrder.STATUS_PARTIAL));
        return PageVO.of(result);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 单据详情：单头 + 明细（含商品信息与已入库/已退货/待入库数量） */
    public PurchaseOrderDetailVO detail(Long id) {
        PurchaseOrder order = requireOrder(id);
        Supplier supplier = supplierMapper.selectById(order.getSupplierId());
        List<PurchaseOrderItem> items = itemMapper.selectList(
                Wrappers.<PurchaseOrderItem>lambdaQuery()
                        .eq(PurchaseOrderItem::getOrderId, id)
                        .orderByAsc(PurchaseOrderItem::getId));

        Map<Long, Goods> goodsMap = goodsMapper.selectBatchIds(
                        items.stream().map(PurchaseOrderItem::getGoodsId).distinct().toList())
                .stream().collect(Collectors.toMap(Goods::getId, Function.identity()));
        Map<Long, Unit> unitMap = unitMapper.selectBatchIds(
                        goodsMap.values().stream().map(Goods::getUnitId).distinct().toList())
                .stream().collect(Collectors.toMap(Unit::getId, Function.identity()));

        return new PurchaseOrderDetailVO(
                order.getId(), order.getOrderNo(), order.getSupplierId(),
                supplier == null ? null : supplier.getName(),
                order.getStatus(), order.getCreateBy(), order.getCreateTime(),
                order.getSubmitTime(), order.getAuditBy(), order.getAuditTime(), order.getAuditRemark(),
                order.getExpectDate(), order.getTotalAmount(), order.getReceivedAmount(),
                order.getReturnAmount(), order.getSettleStatus(), order.getSettleAmount(),
                order.getSettleMethod(), order.getSettleDate(), order.getSettleBy(),
                order.getTerminateReason(), order.getVersion(), order.getRemark(),
                items.stream().map(i -> {
                    Goods goods = goodsMap.get(i.getGoodsId());
                    Unit unit = goods == null ? null : unitMap.get(goods.getUnitId());
                    return new PurchaseOrderDetailVO.ItemVO(
                            i.getId(), i.getGoodsId(),
                            goods == null ? null : goods.getGoodsCode(),
                            goods == null ? null : goods.getName(),
                            goods == null ? null : goods.getSpec(),
                            unit == null ? null : unit.getName(),
                            i.getQuantity(), i.getPrice(), i.getAmount(),
                            i.getReceivedQty(), i.getReturnedQty(),
                            i.getQuantity().subtract(i.getReceivedQty()), i.getRemark());
                }).toList());
    }

    /** 加载并校验采购单存在 */
    PurchaseOrder requireOrder(Long id) {
        PurchaseOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ErrorCode.PUR_ORDER_NOT_FOUND);
        }
        return order;
    }

    /** 状态机校验（BR-PUR-02） */
    void requireStatus(PurchaseOrder order, String expected) {
        if (!expected.equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.PUR_STATUS_INVALID,
                    "采购单 " + order.getOrderNo() + " 当前状态为 " + order.getStatus()
                            + "，不允许该操作（要求 " + expected + "）");
        }
    }

    /** 供应商有效且启用（停用供应商不可被新采购单选择，FR-SUP-03） */
    private Supplier requireActiveSupplier(Long supplierId) {
        Supplier supplier = supplierMapper.selectById(supplierId);
        if (supplier == null) {
            throw new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND);
        }
        if (supplier.getStatus() == null || supplier.getStatus() != 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "供应商【" + supplier.getName() + "】已停用，不可创建采购单（FR-SUP-03）");
        }
        return supplier;
    }

    /** 明细构建与校验（BR-PUR-03）：数量>0、单价≥0、行数≤200、商品有效且启用、同商品仅一行 */
    private List<PurchaseOrderItem> buildItems(List<PurchaseSaveRequest.ItemRequest> requests) {
        if (requests == null || requests.isEmpty() || requests.size() > MAX_ITEMS) {
            throw new BusinessException(ErrorCode.PUR_LINE_INVALID,
                    "采购明细须为 1~200 行（BR-PUR-03）");
        }
        Set<Long> seen = new HashSet<>();
        for (PurchaseSaveRequest.ItemRequest request : requests) {
            if (!seen.add(request.getGoodsId())) {
                throw new BusinessException(ErrorCode.PUR_LINE_DUPLICATED);
            }
            if (request.getQuantity() == null || request.getQuantity().compareTo(BigDecimal.ZERO) <= 0
                    || request.getQuantity().scale() > 3) {
                throw new BusinessException(ErrorCode.PUR_LINE_INVALID,
                        "采购数量必须大于 0 且最多 3 位小数（BR-PUR-03）");
            }
            if (request.getPrice() == null || request.getPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException(ErrorCode.PUR_LINE_INVALID, "采购单价不能为负（BR-PUR-03）");
            }
        }
        Map<Long, Goods> goodsMap = goodsMapper.selectBatchIds(
                        requests.stream().map(PurchaseSaveRequest.ItemRequest::getGoodsId).toList())
                .stream().collect(Collectors.toMap(Goods::getId, Function.identity()));
        return requests.stream().map(request -> {
            Goods goods = goodsMap.get(request.getGoodsId());
            if (goods == null || goods.getStatus() == null || goods.getStatus() != 1) {
                throw new BusinessException(ErrorCode.PUR_LINE_INVALID,
                        "商品 " + request.getGoodsId() + " 不存在或已停用，不可采购");
            }
            PurchaseOrderItem item = new PurchaseOrderItem();
            item.setGoodsId(request.getGoodsId());
            item.setQuantity(request.getQuantity());
            item.setPrice(request.getPrice());
            item.setAmount(request.getQuantity().multiply(request.getPrice())
                    .setScale(2, RoundingMode.HALF_UP));
            item.setReceivedQty(BigDecimal.ZERO);
            item.setReturnedQty(BigDecimal.ZERO);
            item.setRemark(request.getRemark());
            return item;
        }).toList();
    }

    /** 单据总额 = Σ(数量×单价)，2 位小数（BR-PUR-03） */
    private BigDecimal totalOf(List<PurchaseOrderItem> items) {
        return items.stream().map(PurchaseOrderItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    /** PO 单号生成（BR-PUR-01）：PO+yyyyMMdd+4位流水，行锁取号 + 唯一键兜底重试 */
    private void insertWithOrderNo(PurchaseOrder order) {
        DuplicateKeyException conflict = null;
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            order.setOrderNo(nextOrderNo());
            try {
                orderMapper.insert(order);
                return;
            } catch (DuplicateKeyException e) {
                conflict = e;
            }
        }
        throw new BusinessException(ErrorCode.PUR_NO_EXCEED, "采购单号生成冲突，请重试（" + conflict + "）");
    }

    private String nextOrderNo() {
        String prefix = "PO" + LocalDate.now().format(DATE_PART);
        String last = orderMapper.selectLastOrderNoForUpdate(prefix);
        int seq = last == null ? 1 : Integer.parseInt(last.substring(prefix.length())) + 1;
        if (seq > 9999) {
            throw new BusinessException(ErrorCode.PUR_NO_EXCEED);
        }
        return prefix + "%04d".formatted(seq);
    }

    /** CAS 更新（BR-PUR-06）：带版本号条件，冲突即提示刷新重试 */
    void casUpdate(PurchaseOrder order, String newStatus,
                   java.util.function.Consumer<
                           com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PurchaseOrder>>
                           customizer) {
        var updater = Wrappers.<PurchaseOrder>lambdaUpdate()
                .eq(PurchaseOrder::getId, order.getId())
                .eq(PurchaseOrder::getVersion, order.getVersion())
                .set(PurchaseOrder::getStatus, newStatus)
                .setSql("version = version + 1");
        customizer.accept(updater);
        if (orderMapper.update(null, updater) == 0) {
            throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT,
                    "采购单 " + order.getOrderNo() + " 已被其他人操作，请刷新后重试（BR-PUR-06）");
        }
    }

    /** 常规字段更新（编辑草稿），同样带版本条件 */
    private void casUpdateById(PurchaseOrder order) {
        var updater = Wrappers.<PurchaseOrder>lambdaUpdate()
                .eq(PurchaseOrder::getId, order.getId())
                .eq(PurchaseOrder::getVersion, order.getVersion())
                .set(PurchaseOrder::getSupplierId, order.getSupplierId())
                .set(PurchaseOrder::getExpectDate, order.getExpectDate())
                .set(PurchaseOrder::getTotalAmount, order.getTotalAmount())
                .set(PurchaseOrder::getRemark, order.getRemark())
                .set(PurchaseOrder::getUpdateBy, order.getUpdateBy())
                .setSql("version = version + 1");
        if (orderMapper.update(null, updater) == 0) {
            throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT,
                    "采购单 " + order.getOrderNo() + " 已被其他人操作，请刷新后重试（BR-PUR-06）");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 供报表/建议采购等模块复用的在途查询条件（BR-PUR-08） */
    public static List<String> inTransitStatuses() {
        return List.of(PurchaseOrder.STATUS_APPROVED, PurchaseOrder.STATUS_PARTIAL);
    }

    /** 工具：过滤非空 id */
    static List<Long> nonNullIds(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
    }
}

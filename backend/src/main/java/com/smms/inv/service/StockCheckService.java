package com.smms.inv.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.goods.entity.Category;
import com.smms.goods.entity.Goods;
import com.smms.goods.mapper.CategoryMapper;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.inv.dto.CheckAuditRequest;
import com.smms.inv.dto.CheckCreateRequest;
import com.smms.inv.dto.CheckEntryRequest;
import com.smms.inv.dto.CheckItemVO;
import com.smms.inv.dto.CheckOrderDetailVO;
import com.smms.inv.dto.CheckOrderVO;
import com.smms.inv.dto.CheckQuery;
import com.smms.inv.dto.CheckScopeGoods;
import com.smms.inv.dto.CheckSummaryVO;
import com.smms.inv.entity.Inventory;
import com.smms.inv.entity.InventoryRecord;
import com.smms.inv.entity.StockCheckItem;
import com.smms.inv.entity.StockCheckOrder;
import com.smms.inv.mapper.InventoryMapper;
import com.smms.inv.mapper.InventoryRecordMapper;
import com.smms.inv.mapper.StockCheckItemMapper;
import com.smms.inv.mapper.StockCheckOrderMapper;
import com.smms.security.LoginUser;
import com.smms.sys.service.BusinessDayService;
import com.smms.sys.service.DutySeparationService;
import com.smms.sys.service.SysDictService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 盘点管理（FR-INV-07~09，§2.1.3 流程 / §2.2.4 状态机）。
 * 创建时快照账面数量（BR-INV-10 基准时间）；同范围不可重复创建未审核单（BR-INV-09）；
 * 审核时以实盘数覆盖结存并生成盘盈/盘亏流水（FR-INV-09，见审核方法）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StockCheckService {

    private static final long STORE_ID = 1L;
    /** 单号流水位数：PD+yyyyMMdd+4位 */
    private static final int SERIAL_WIDTH = 4;
    /** 明细批量插入分片大小 */
    private static final int INSERT_BATCH = 500;
    /** 未审核完成状态（BR-INV-09 防重复盘点的排除对象） */
    private static final List<String> UNAUDITED_STATUSES = List.of("CHECKING", "PENDING_AUDIT");
    /** 盘点差异原因字典（FR-SYS-07，差异行原因存字典代码） */
    private static final String DICT_CHECK_DIFF_REASON = "check_diff_reason";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StockCheckOrderMapper orderMapper;
    private final StockCheckItemMapper itemMapper;
    private final InventoryMapper inventoryMapper;
    private final InventoryRecordMapper inventoryRecordMapper;
    private final CategoryMapper categoryMapper;
    private final GoodsMapper goodsMapper;
    private final SysDictService dictService;
    private final DutySeparationService dutySeparationService;
    private final BusinessDayService businessDayService;

    // ==================== 盘点单创建（FR-INV-07） ====================

    /**
     * 创建盘点单：选择范围 → 快照账面数量与成本单价 → 状态盘点中。
     * 同一商品同一时间只允许一张未审核盘点单（BR-INV-09），重叠时拒绝并提示已有单号。
     */
    @Transactional
    public CheckOrderVO create(CheckCreateRequest req, String checkerName) {
        Scope scope = resolveScope(req);
        List<CheckScopeGoods> goods = itemMapper.selectScopeGoods(
                scope.type(), scope.categoryIds(), scope.goodsIds());
        if (goods.isEmpty()) {
            throw new BusinessException(ErrorCode.CHECK_SCOPE_EMPTY,
                    "盘点范围【" + scope.desc() + "】内没有可盘商品");
        }
        assertNoOverlap(scope, goods);

        StockCheckOrder order = new StockCheckOrder();
        order.setOrderNo(generateOrderNo());
        order.setStoreId(STORE_ID);
        order.setScopeType(scope.type());
        order.setScopeDesc(scope.desc());
        order.setStatus("CHECKING");
        order.setCheckerName(checkerName);
        LocalDateTime now = LocalDateTime.now();
        order.setStartTime(now);
        order.setBaseTime(now);
        // 营业日（BR-RPT-02/GAP-P1-05）：创建时按创建时刻归属，审核通过后改写为审核时刻所属营业日
        order.setBusinessDate(businessDayService.businessDate(now));
        order.setItemCount(goods.size());
        order.setSurplusAmount(java.math.BigDecimal.ZERO);
        order.setShortageAmount(java.math.BigDecimal.ZERO);
        order.setDuringChangeCount(0);
        orderMapper.insert(order);

        List<StockCheckItem> items = new ArrayList<>(goods.size());
        for (CheckScopeGoods g : goods) {
            StockCheckItem item = new StockCheckItem();
            item.setOrderId(order.getId());
            item.setGoodsId(g.goodsId());
            item.setBookQty(g.bookQty());
            item.setCostPrice(g.costPrice());
            // 审计字段（DR-C-08/GAP-P2-28）：明细创建人=盘点人
            item.setCreateBy(checkerName);
            item.setCreateTime(now);
            items.add(item);
        }
        for (int i = 0; i < items.size(); i += INSERT_BATCH) {
            itemMapper.insertBatch(items.subList(i, Math.min(i + INSERT_BATCH, items.size())));
        }
        return toVO(order, 0, 0);
    }

    /** BR-INV-09：新范围商品与任一未审核盘点单的明细商品有交集即拒绝（全店范围与任何单必交） */
    private void assertNoOverlap(Scope scope, List<CheckScopeGoods> goods) {
        List<StockCheckOrder> unaudited = orderMapper.selectList(Wrappers.<StockCheckOrder>lambdaQuery()
                .select(StockCheckOrder::getId, StockCheckOrder::getOrderNo, StockCheckOrder::getScopeDesc)
                .in(StockCheckOrder::getStatus, UNAUDITED_STATUSES));
        if (unaudited.isEmpty()) {
            return;
        }
        Set<Long> target = new HashSet<>();
        goods.forEach(g -> target.add(g.goodsId()));
        for (StockCheckOrder existing : unaudited) {
            List<Long> occupied = itemMapper.selectGoodsIdsOfOrders(List.of(existing.getId()));
            if (occupied.stream().anyMatch(target::contains)) {
                throw new BusinessException(ErrorCode.CHECK_OVERLAP,
                        "范围内存在未审核盘点单【" + existing.getOrderNo() + "】（"
                                + existing.getScopeDesc() + "），不可重复创建");
            }
        }
    }

    /** 单号：PD + yyyyMMdd + 4位流水（取当日同前缀最大流水 +1） */
    private String generateOrderNo() {
        String prefix = "PD" + LocalDateTime.now().format(DATE_FMT);
        List<StockCheckOrder> todays = orderMapper.selectList(Wrappers.<StockCheckOrder>lambdaQuery()
                .select(StockCheckOrder::getOrderNo)
                .likeRight(StockCheckOrder::getOrderNo, prefix));
        long max = todays.stream().map(StockCheckOrder::getOrderNo)
                .filter(no -> no.length() == prefix.length() + SERIAL_WIDTH)
                .map(no -> no.substring(prefix.length()))
                .filter(s -> s.chars().allMatch(Character::isDigit))
                .mapToLong(Long::parseLong)
                .max().orElse(0L);
        if (max >= 9999L) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "当日盘点单流水号已用尽");
        }
        return prefix + String.format("%0" + SERIAL_WIDTH + "d", max + 1);
    }

    /** 范围解析（FR-INV-07）：ALL全店 / CATEGORY分类（含子树） / GOODS指定商品 / WARNING预警商品 */
    private Scope resolveScope(CheckCreateRequest req) {
        String type = req.scopeType() == null ? "" : req.scopeType().trim();
        String remark = req.remark() == null || req.remark().isBlank() ? "" : "：" + req.remark().trim();
        switch (type) {
            case "ALL" -> {
                return new Scope("ALL", null, null, "全店盘点" + remark);
            }
            case "CATEGORY" -> {
                if (req.categoryId() == null) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "分类盘点必须指定分类");
                }
                Category category = categoryMapper.selectById(req.categoryId());
                if (category == null) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "所选分类不存在");
                }
                return new Scope("CATEGORY", resolveCategoryTree(category.getId()),
                        null, "分类：" + category.getName() + "（含子分类）" + remark);
            }
            case "GOODS" -> {
                List<Long> goodsIds = req.goodsIds();
                if (goodsIds == null || goodsIds.isEmpty()) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "指定商品盘点必须提供商品清单");
                }
                List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(goodsIds));
                List<Goods> found = goodsMapper.selectBatchIds(distinct);
                if (found.size() != distinct.size()) {
                    Set<Long> foundIds = new HashSet<>();
                    found.forEach(g -> foundIds.add(g.getId()));
                    List<Long> missing = distinct.stream().filter(id -> !foundIds.contains(id)).toList();
                    throw new BusinessException(ErrorCode.PARAM_INVALID,
                            "商品不存在：" + missing.stream().map(String::valueOf).limit(10).toList());
                }
                return new Scope("GOODS", null, distinct, "指定商品 " + distinct.size() + " 个" + remark);
            }
            case "WARNING" -> {
                return new Scope("WARNING", null, null, "预警商品（缺货/预警/积压）" + remark);
            }
            default -> throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "不支持的盘点范围类型：" + req.scopeType());
        }
    }

    // ==================== 查询（FR-INV-07） ====================

    /** 盘点单分页：单号/状态/盘点人/审核人/时间区间，创建时间倒序 */
    public PageVO<CheckOrderVO> page(CheckQuery q) {
        int pageNo = Math.max(q.page(), 1);
        int size = q.size() <= 0 ? 20 : Math.min(q.size(), 100);
        LocalDateTime beginTime = q.beginDate() == null ? null : q.beginDate().atStartOfDay();
        LocalDateTime endTime = q.endDate() == null ? null : q.endDate().plusDays(1).atStartOfDay();
        IPage<CheckOrderVO> result = orderMapper.selectCheckPage(new Page<>(pageNo, size), q, beginTime, endTime);
        return PageVO.of(result);
    }

    /** 盘点单详情：单头 + 明细分页（keyword 定位行，onlyDiff 仅看差异行） */
    public CheckOrderDetailVO detail(Long id, int page, int size, boolean onlyDiff, String keyword) {
        StockCheckOrder order = requireOrder(id);
        int pageNo = Math.max(page, 1);
        int pageSize = size <= 0 ? 20 : Math.min(size, 100);
        IPage<CheckItemVO> items = itemMapper.selectItemPage(new Page<>(pageNo, pageSize), id, onlyDiff, keyword);
        return new CheckOrderDetailVO(toVO(order), PageVO.of(items));
    }

    // ==================== 作废（FR-INV-07，§2.2.4：仅盘点中可作废） ====================

    /** 作废盘点单：仅 CHECKING 状态、提交审核前；须填写作废原因 */
    public void cancel(Long id, String reason) {
        StockCheckOrder order = requireOrder(id);
        if (!"CHECKING".equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.CHECK_STATUS_INVALID,
                    "仅盘点中的单据可作废，当前状态：" + statusLabel(order.getStatus()));
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.CHECK_REASON_REQUIRED, "作废原因不能为空");
        }
        orderMapper.update(null, Wrappers.<StockCheckOrder>lambdaUpdate()
                .set(StockCheckOrder::getStatus, "CANCELLED")
                .set(StockCheckOrder::getCancelReason, reason.trim())
                .eq(StockCheckOrder::getId, id));
    }

    // ==================== 实盘数量录入（FR-INV-08） ====================

    /**
     * 录入实盘数量（FR-INV-08）：仅盘点中状态；差异由服务端按 实盘-账面 计算，
     * 差异金额 = 差异数量 × 快照成本单价（2位，HALF_UP）；差异≠0 的行必须填写原因。
     * 录入后实时汇总盘盈/盘亏总额回写单头并返回。
     */
    @Transactional
    public CheckSummaryVO enter(Long orderId, CheckEntryRequest req) {
        StockCheckOrder order = requireOrder(orderId);
        if (!"CHECKING".equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.CHECK_STATUS_INVALID,
                    "仅盘点中的单据可录入实盘数，当前状态：" + statusLabel(order.getStatus()));
        }
        List<CheckEntryRequest.Item> lines = req == null || req.items() == null ? List.of() : req.items();
        if (lines.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "录入明细不能为空");
        }
        Map<Long, StockCheckItem> byGoods = itemMapper.selectList(Wrappers.<StockCheckItem>lambdaQuery()
                        .eq(StockCheckItem::getOrderId, orderId))
                .stream().collect(java.util.stream.Collectors.toMap(StockCheckItem::getGoodsId, i -> i));
        Map<Long, String> names = goodsMapper.selectBatchIds(
                        lines.stream().map(CheckEntryRequest.Item::goodsId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(Goods::getId, Goods::getName));
        Set<Long> seen = new HashSet<>();
        for (CheckEntryRequest.Item line : lines) {
            if (!seen.add(line.goodsId())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "商品【" + names.getOrDefault(line.goodsId(), String.valueOf(line.goodsId()))
                                + "】重复录入，请合并为一行");
            }
            StockCheckItem item = byGoods.get(line.goodsId());
            if (item == null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "商品【" + names.getOrDefault(line.goodsId(), String.valueOf(line.goodsId()))
                                + "】不在本次盘点范围内");
            }
            if (line.actualQty().scale() > 3) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "商品【" + names.get(line.goodsId()) + "】实盘数量最多 3 位小数");
            }
            BigDecimal actual = line.actualQty().setScale(3, java.math.RoundingMode.HALF_UP);
            BigDecimal diff = actual.subtract(item.getBookQty());
            if (diff.signum() != 0 && isBlank(line.diffReason())) {
                throw new BusinessException(ErrorCode.CHECK_REASON_REQUIRED,
                        "商品【" + names.get(line.goodsId()) + "】实盘 " + actual.stripTrailingZeros().toPlainString()
                                + " 与账面 " + item.getBookQty().stripTrailingZeros().toPlainString()
                                + " 存在差异，必须填写差异原因");
            }
            String diffReason = requireDiffReason(names.get(line.goodsId()), line.diffReason());
            BigDecimal diffAmount = diff.multiply(item.getCostPrice()).setScale(2, java.math.RoundingMode.HALF_UP);
            itemMapper.update(null, Wrappers.<StockCheckItem>lambdaUpdate()
                    .set(StockCheckItem::getActualQty, actual)
                    .set(StockCheckItem::getDiffQty, diff)
                    .set(StockCheckItem::getDiffAmount, diffAmount)
                    .set(StockCheckItem::getDiffReason, diffReason)
                    .set(StockCheckItem::getRemark, isBlank(line.remark()) ? null : line.remark().trim())
                    .eq(StockCheckItem::getId, item.getId()));
        }
        return refreshTotals(order);
    }

    // ==================== 提交审核（FR-INV-08，§2.2.4：盘点中→待审核） ====================

    /**
     * 提交审核：未盘行按账面确认（实盘=账面、差异清零）；差异行原因复核；
     * 汇总盘盈/盘亏金额后状态置为待审核（条件更新防并发重复提交）。
     */
    @Transactional
    public CheckSummaryVO submit(Long orderId) {
        StockCheckOrder order = requireOrder(orderId);
        if (!"CHECKING".equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.CHECK_STATUS_INVALID,
                    "仅盘点中的单据可提交审核，当前状态：" + statusLabel(order.getStatus()));
        }
        itemMapper.confirmUnenteredAsBook(orderId);
        Long noReason = itemMapper.selectCount(Wrappers.<StockCheckItem>lambdaQuery()
                .eq(StockCheckItem::getOrderId, orderId)
                .isNotNull(StockCheckItem::getDiffQty)
                .ne(StockCheckItem::getDiffQty, BigDecimal.ZERO)
                .and(w -> w.isNull(StockCheckItem::getDiffReason).or().eq(StockCheckItem::getDiffReason, "")));
        if (noReason != null && noReason > 0) {
            throw new BusinessException(ErrorCode.CHECK_REASON_REQUIRED,
                    "存在 " + noReason + " 行差异未填写原因，请补录后提交");
        }
        CheckSummaryVO summary = refreshTotals(order);
        int affected = orderMapper.update(null, Wrappers.<StockCheckOrder>lambdaUpdate()
                .set(StockCheckOrder::getStatus, "PENDING_AUDIT")
                .eq(StockCheckOrder::getId, orderId)
                .eq(StockCheckOrder::getStatus, "CHECKING"));
        if (affected == 0) {
            throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT, "盘点单状态已变化，请刷新后重试");
        }
        return summary;
    }

    /** 重算单头汇总（FR-INV-08 实时口径）：已录入行数、差异行数、盘盈/盘亏总额并回写 */
    private CheckSummaryVO refreshTotals(StockCheckOrder order) {
        List<StockCheckItem> all = itemMapper.selectList(Wrappers.<StockCheckItem>lambdaQuery()
                .eq(StockCheckItem::getOrderId, order.getId()));
        int entered = 0;
        int diffCount = 0;
        BigDecimal surplus = BigDecimal.ZERO;
        BigDecimal shortage = BigDecimal.ZERO;
        for (StockCheckItem item : all) {
            if (item.getActualQty() != null) {
                entered++;
            }
            if (item.getDiffQty() != null && item.getDiffQty().signum() != 0) {
                diffCount++;
                BigDecimal amount = item.getDiffAmount() == null ? BigDecimal.ZERO : item.getDiffAmount();
                if (item.getDiffQty().signum() > 0) {
                    surplus = surplus.add(amount);
                } else {
                    shortage = shortage.add(amount.abs());
                }
            }
        }
        BigDecimal surplusAmount = surplus.setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal shortageAmount = shortage.setScale(2, java.math.RoundingMode.HALF_UP);
        orderMapper.update(null, Wrappers.<StockCheckOrder>lambdaUpdate()
                .set(StockCheckOrder::getSurplusAmount, surplusAmount)
                .set(StockCheckOrder::getShortageAmount, shortageAmount)
                .eq(StockCheckOrder::getId, order.getId()));
        return new CheckSummaryVO(entered, diffCount, surplusAmount, shortageAmount);
    }

    // ==================== 盘点审核与库存调整（FR-INV-09） ====================

    /**
     * 盘点审核（FR-INV-09）：通过 → 单事务内生成盘盈/盘亏流水并将结存覆盖为实盘数；
     * 驳回 → 须填意见并退回盘点中（§2.2.4）。
     * 职责分离（BR-SYS-01，GAP-P1-08 统一策略）：审核人 ≠ 盘点人；违反时按参数
     * duty_separation_strict 拒绝或放行并记异常日志，超级管理员可代审（同样留痕）。
     */
    @Transactional
    public CheckOrderVO audit(Long orderId, CheckAuditRequest req, LoginUser auditor) {
        StockCheckOrder order = requireOrder(orderId);
        if (!"PENDING_AUDIT".equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.CHECK_STATUS_INVALID,
                    "仅待审核的盘点单可审核，当前状态：" + statusLabel(order.getStatus()));
        }
        if (auditor.username().equals(order.getCheckerName())) {
            dutySeparationService.enforce("库存管理", "盘点审核职责未分离",
                    "盘点单 " + order.getOrderNo() + " 盘点人与审核人同为 " + auditor.username(),
                    ErrorCode.CHECK_AUDIT_SELF, auditor);
        }
        CheckOrderVO vo = Boolean.TRUE.equals(req.approved())
                ? auditPass(order, req.remark(), auditor.username())
                : auditReject(order, req.remark(), auditor.username());
        log.info("盘点单审核: checkNo={}, 结果={}, 审核人={}", order.getOrderNo(),
                Boolean.TRUE.equals(req.approved()) ? "通过" : "驳回", auditor.username());
        return vo;
    }
    /**
     * 审核通过（FR-INV-09，AC-12）：按 goodsId 升序逐行处理（与库存服务锁序一致，避免交叉死锁）——
     * 行锁读当前结存，实盘≠当前结存时写 CHECK_SURPLUS/CHECK_SHORTAGE 流水（§8.2 按当前成本单价入账/结转），
     * 并将结存覆盖为实盘数（成本单价不变，BR-INV-06 仅采购/报溢入库重算）。
     * 流水差异 = 实盘 − 当前结存（盘点期间发生的出入库已计入结存，BR-INV-10），
     * 保证不变式 结存 = 期初 + Σ流水（BR-INV-08）在任何盘点场景下成立。
     */
    private CheckOrderVO auditPass(StockCheckOrder order, String remark, String auditorName) {
        LocalDateTime now = LocalDateTime.now();
        // BR-INV-10：先统计盘点期间出入库笔数（须在本单调整流水写入前），审核人据此复核差异原因
        long during = itemMapper.countDuringChanges(order.getId(), order.getBaseTime(), now);
        List<StockCheckItem> items = new ArrayList<>(itemMapper.selectList(Wrappers.<StockCheckItem>lambdaQuery()
                .eq(StockCheckItem::getOrderId, order.getId())));
        items.sort(java.util.Comparator.comparing(StockCheckItem::getGoodsId));
        for (StockCheckItem item : items) {
            BigDecimal actual = item.getActualQty() == null ? item.getBookQty() : item.getActualQty();
            Inventory inv = lockInventory(item.getGoodsId());
            BigDecimal current = inv.getQuantity();
            BigDecimal costPrice = inv.getCostPrice() == null ? BigDecimal.ZERO : inv.getCostPrice();
            // GAP-P1-07：审核定稿本行差异金额（差异 = 实盘 − 账面快照，成本 = 审核时当前成本单价，FR-INV-08），
            // 使损耗报表（读 diff_amount）与单头盘盈/盘亏汇总，与流水结转所用成本同口径
            BigDecimal diff = actual.subtract(item.getBookQty());
            itemMapper.update(null, Wrappers.<StockCheckItem>lambdaUpdate()
                    .set(StockCheckItem::getCostPrice,
                            costPrice.setScale(4, java.math.RoundingMode.HALF_UP))
                    .set(StockCheckItem::getDiffAmount,
                            diff.multiply(costPrice).setScale(2, java.math.RoundingMode.HALF_UP))
                    .eq(StockCheckItem::getId, item.getId()));
            if (actual.compareTo(current) == 0) {
                continue; // 期间变动恰好抵补，结存已与实盘一致，无需调整
            }
            boolean surplus = actual.compareTo(current) > 0;
            InventoryRecord record = new InventoryRecord();
            record.setGoodsId(item.getGoodsId());
            record.setStoreId(STORE_ID);
            record.setRecordType(surplus ? "CHECK_SURPLUS" : "CHECK_SHORTAGE");
            record.setChangeQty(actual.subtract(current));
            record.setBeforeQty(current);
            record.setAfterQty(actual);
            record.setUnitCost(costPrice.setScale(4, java.math.RoundingMode.HALF_UP));
            record.setBizType("PD");
            record.setBizNo(order.getOrderNo());
            record.setOperatorName(auditorName);
            record.setOperateTime(now);
            record.setBusinessDate(businessDayService.businessDate(now));
            record.setRemark("盘点审核调整" + (isBlank(item.getDiffReason()) ? "" : "：" + item.getDiffReason()));
            inventoryRecordMapper.insert(record);
            inventoryMapper.updateQuantityAndCost(item.getGoodsId(), STORE_ID, actual, costPrice,
                    actual.multiply(costPrice).setScale(2, java.math.RoundingMode.HALF_UP));
        }
        // GAP-P1-07：明细成本/差异金额定稿后刷新单头盘盈/盘亏汇总（FR-INV-08：单头 = Σ明细），
        // 并同步内存对象，保证审核返回 VO 与落库一致
        CheckSummaryVO summary = refreshTotals(order);
        order.setSurplusAmount(summary.surplusAmount());
        order.setShortageAmount(summary.shortageAmount());

        String cleanRemark = isBlank(remark) ? null : remark.trim();
        int affected = orderMapper.update(null, Wrappers.<StockCheckOrder>lambdaUpdate()
                .set(StockCheckOrder::getStatus, "AUDITED")
                .set(StockCheckOrder::getAuditorName, auditorName)
                .set(StockCheckOrder::getAuditTime, now)
                .set(StockCheckOrder::getBusinessDate, businessDayService.businessDate(now))
                .set(StockCheckOrder::getAuditRemark, cleanRemark)
                .set(StockCheckOrder::getDuringChangeCount, (int) during)
                .eq(StockCheckOrder::getId, order.getId())
                .eq(StockCheckOrder::getStatus, "PENDING_AUDIT"));
        if (affected == 0) {
            throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT, "盘点单状态已变化，请刷新后重试");
        }
        order.setStatus("AUDITED");
        order.setAuditorName(auditorName);
        order.setAuditTime(now);
        order.setAuditRemark(cleanRemark);
        order.setDuringChangeCount((int) during);
        return toVO(order);
    }

    /** 行锁读取结存（事务内保持至提交）；行缺失（从未发生变动的商品）先补 0 行 */
    private Inventory lockInventory(Long goodsId) {
        Inventory inv = inventoryMapper.selectByGoodsIdForUpdate(goodsId, STORE_ID);
        if (inv == null) {
            inventoryMapper.ensureRow(goodsId, STORE_ID);
            inv = inventoryMapper.selectByGoodsIdForUpdate(goodsId, STORE_ID);
        }
        return inv;
    }

    /** 驳回（FR-INV-09）：意见必填，退回盘点中（§2.2.4），录入数据保留供修改后重新提交 */
    private CheckOrderVO auditReject(StockCheckOrder order, String remark, String auditorName) {
        if (isBlank(remark)) {
            throw new BusinessException(ErrorCode.CHECK_REASON_REQUIRED, "驳回意见不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        String cleanRemark = remark.trim();
        int affected = orderMapper.update(null, Wrappers.<StockCheckOrder>lambdaUpdate()
                .set(StockCheckOrder::getStatus, "CHECKING")
                .set(StockCheckOrder::getAuditorName, auditorName)
                .set(StockCheckOrder::getAuditTime, now)
                .set(StockCheckOrder::getAuditRemark, cleanRemark)
                .eq(StockCheckOrder::getId, order.getId())
                .eq(StockCheckOrder::getStatus, "PENDING_AUDIT"));
        if (affected == 0) {
            throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT, "盘点单状态已变化，请刷新后重试");
        }
        order.setStatus("CHECKING");
        order.setAuditorName(auditorName);
        order.setAuditTime(now);
        order.setAuditRemark(cleanRemark);
        return toVO(order);
    }

    // ==================== 内部工具 ====================

    private StockCheckOrder requireOrder(Long id) {
        StockCheckOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "盘点单不存在");
        }
        return order;
    }

    /** 单头 VO：明细统计（已录入行数 / 差异行数），详情页复用 */
    private CheckOrderVO toVO(StockCheckOrder order) {
        Long entered = itemMapper.selectCount(Wrappers.<StockCheckItem>lambdaQuery()
                .eq(StockCheckItem::getOrderId, order.getId())
                .isNotNull(StockCheckItem::getActualQty));
        Long diff = itemMapper.selectCount(Wrappers.<StockCheckItem>lambdaQuery()
                .eq(StockCheckItem::getOrderId, order.getId())
                .isNotNull(StockCheckItem::getDiffQty)
                .ne(StockCheckItem::getDiffQty, java.math.BigDecimal.ZERO));
        return toVO(order, entered.intValue(), diff.intValue());
    }

    private CheckOrderVO toVO(StockCheckOrder order, int enteredCount, int diffCount) {
        return new CheckOrderVO(order.getId(), order.getOrderNo(), order.getScopeType(), order.getScopeDesc(),
                order.getStatus(), order.getCheckerName(), order.getStartTime(), order.getBaseTime(),
                order.getItemCount(), enteredCount, diffCount, order.getSurplusAmount(), order.getShortageAmount(),
                order.getDuringChangeCount(), order.getAuditorName(), order.getAuditTime(), order.getAuditRemark(),
                order.getCancelReason(), order.getCreateTime());
    }

    private String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "CHECKING" -> "盘点中";
            case "PENDING_AUDIT" -> "待审核";
            case "AUDITED" -> "已审核";
            case "CANCELLED" -> "已作废";
            default -> status;
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 差异原因校验（FR-SYS-07、GAP-P1-09）：须为启用中的 check_diff_reason 字典项代码，
     * 停用项不可被新单据选择；空白返回 null（无差异时允许不填）；字典未初始化时报系统错误。
     */
    private String requireDiffReason(String goodsName, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        Set<String> enabled = dictService.enabledValues(DICT_CHECK_DIFF_REASON);
        if (enabled.isEmpty()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "盘点差异原因字典未初始化（check_diff_reason）");
        }
        if (!enabled.contains(value)) {
            throw new BusinessException(ErrorCode.CHECK_REASON_INVALID,
                    "商品【" + goodsName + "】差异原因不合法或已被停用（数据字典 check_diff_reason）");
        }
        return value;
    }

    /** 分类子树 id 集合（含自身）：按物化路径前缀匹配（与库存查询同口径） */
    private List<Long> resolveCategoryTree(Long categoryId) {
        Category category = categoryMapper.selectById(categoryId);
        String prefix = category.getPath() + "/";
        List<Long> ids = new ArrayList<>();
        ids.add(categoryId);
        categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                        .select(Category::getId, Category::getPath))
                .stream()
                .filter(c -> c.getPath().startsWith(prefix))
                .forEach(c -> ids.add(c.getId()));
        return ids;
    }

    /** 盘点范围（FR-INV-07） */
    private record Scope(String type, List<Long> categoryIds, List<Long> goodsIds, String desc) {
    }
}

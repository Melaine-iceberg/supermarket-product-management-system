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
import com.smms.inv.dto.CheckCreateRequest;
import com.smms.inv.dto.CheckEntryRequest;
import com.smms.inv.dto.CheckItemVO;
import com.smms.inv.dto.CheckOrderDetailVO;
import com.smms.inv.dto.CheckOrderVO;
import com.smms.inv.dto.CheckQuery;
import com.smms.inv.dto.CheckScopeGoods;
import com.smms.inv.dto.CheckSummaryVO;
import com.smms.inv.entity.StockCheckItem;
import com.smms.inv.entity.StockCheckOrder;
import com.smms.inv.mapper.StockCheckItemMapper;
import com.smms.inv.mapper.StockCheckOrderMapper;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class StockCheckService {

    private static final long STORE_ID = 1L;
    /** 单号流水位数：PD+yyyyMMdd+4位 */
    private static final int SERIAL_WIDTH = 4;
    /** 明细批量插入分片大小 */
    private static final int INSERT_BATCH = 500;
    /** 未审核完成状态（BR-INV-09 防重复盘点的排除对象） */
    private static final List<String> UNAUDITED_STATUSES = List.of("CHECKING", "PENDING_AUDIT");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StockCheckOrderMapper orderMapper;
    private final StockCheckItemMapper itemMapper;
    private final CategoryMapper categoryMapper;
    private final GoodsMapper goodsMapper;

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
            BigDecimal diffAmount = diff.multiply(item.getCostPrice()).setScale(2, java.math.RoundingMode.HALF_UP);
            itemMapper.update(null, Wrappers.<StockCheckItem>lambdaUpdate()
                    .set(StockCheckItem::getActualQty, actual)
                    .set(StockCheckItem::getDiffQty, diff)
                    .set(StockCheckItem::getDiffAmount, diffAmount)
                    .set(StockCheckItem::getDiffReason, isBlank(line.diffReason()) ? null : line.diffReason().trim())
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

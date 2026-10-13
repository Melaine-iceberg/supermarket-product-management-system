package com.smms.inv.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.common.mybatis.ExportPage;
import com.smms.goods.entity.Category;
import com.smms.goods.entity.Goods;
import com.smms.goods.mapper.CategoryMapper;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.inv.dto.AlertItemVO;
import com.smms.inv.dto.AlertPageVO;
import com.smms.inv.dto.AlertQuery;
import com.smms.inv.dto.AlertSummaryVO;
import com.smms.inv.dto.InventoryItemVO;
import com.smms.inv.dto.InventoryPageVO;
import com.smms.inv.dto.InventoryQuery;
import com.smms.inv.dto.InventoryRecordQuery;
import com.smms.inv.dto.InventoryRecordVO;
import com.smms.inv.dto.StockChange;
import com.smms.inv.entity.Inventory;
import com.smms.inv.entity.InventoryRecord;
import com.smms.inv.mapper.InventoryMapper;
import com.smms.inv.mapper.InventoryRecordMapper;
import com.smms.sys.service.BusinessDayService;
import com.smms.sys.service.SysConfigService;
import com.smms.common.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 库存核心服务（FR-INV-01~04）："结存字段 + 不可变流水"双记录设计。
 * 结存字段用于快速查询与并发扣减，流水表用于追溯、成本核算与一致性校验（BR-INV-08）。
 * 所有库存增减必须经由 stockIn/stockOut（FR-INV-02 统一入口，BR-INV-11 禁止直改结存）。
 * 多商品单据请按 goodsId 升序逐个调用，避免并发交叉死锁。
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private static final long STORE_ID = 1L;
    /** 负库存开关参数（BR-INV-02） */
    private static final String CONFIG_ALLOW_NEGATIVE = "allow_negative_stock";
    /** 重算移动加权成本的入库类型（BR-INV-06）：仅采购入库；报溢按当前成本单价入账不重算（§8.2） */
    private static final Set<String> COST_RECALC_IN_TYPES = Set.of("PURCHASE_IN");
    /** 报溢入库：按当前成本单价入账，忽略传入单价（§8.2） */
    private static final String TYPE_OVERFLOW_IN = "OVERFLOW_IN";
    /** stockIn 允许的变动类型（INIT 由商品建档生成、CHECK_SURPLUS 由盘点审核覆盖式生成，不走本入口） */
    private static final Set<String> IN_TYPES = Set.of("PURCHASE_IN", "SALE_RETURN", "OVERFLOW_IN");
    /** stockOut 允许的变动类型（CHECK_SHORTAGE 由盘点审核覆盖式生成，不走本入口） */
    private static final Set<String> OUT_TYPES = Set.of("SALE_OUT", "PURCHASE_RETURN", "LOSS_OUT");

    private final InventoryMapper inventoryMapper;
    private final InventoryRecordMapper inventoryRecordMapper;
    private final CategoryMapper categoryMapper;
    private final GoodsMapper goodsMapper;
    private final SysConfigService configService;
    private final BusinessDayService businessDayService;

    // ==================== 库存变动统一入口（FR-INV-02/04） ====================

    /**
     * 入库：结存增加并写不可变流水（BR-INV-04/05）。
     * PURCHASE_IN 按移动加权平均重算成本单价（BR-INV-06）；
     * OVERFLOW_IN（报溢）按当前成本单价入账、不重算均价（§8.2）；
     * SALE_RETURN 仅回冲数量、成本取原销售明细快照，不改变当前移动平均成本。
     */
    @Transactional
    public void stockIn(StockChange cmd) {
        validate(cmd, IN_TYPES);
        inventoryMapper.ensureRow(cmd.goodsId(), STORE_ID);

        BigDecimal before;
        BigDecimal after;
        BigDecimal recordCost;
        if (COST_RECALC_IN_TYPES.contains(cmd.recordType())) {
            Inventory inv = requireRowForUpdate(cmd.goodsId());
            before = inv.getQuantity();
            after = before.add(cmd.qty());
            BigDecimal newCost = weightedAverageCost(before, inv.getCostPrice(), cmd.qty(), cmd.unitCost());
            inventoryMapper.updateQuantityAndCost(cmd.goodsId(), STORE_ID, after, newCost,
                    after.multiply(newCost).setScale(2, RoundingMode.HALF_UP));
            recordCost = cmd.unitCost();
        } else {
            inventoryMapper.increaseStock(cmd.goodsId(), STORE_ID, cmd.qty());
            Inventory afterRow = requireRow(cmd.goodsId());
            after = afterRow.getQuantity();
            before = after.subtract(cmd.qty());
            if (TYPE_OVERFLOW_IN.equals(cmd.recordType())) {
                // 报溢按当前成本单价入账（§8.2）：取结存行成本，忽略传入单价，均价保持不变
                recordCost = afterRow.getCostPrice();
            } else {
                // 销售退货按原销售明细成本快照回冲；未提供时按当前成本兜底
                recordCost = cmd.unitCost() != null ? cmd.unitCost() : afterRow.getCostPrice();
            }
        }
        insertRecord(cmd, before, after, recordCost, true);
    }

    /**
     * 出库：原子条件扣减（BR-INV-03，余量判定与扣减同一条语句），
     * 受影响行数为 0 即库存不足；是否放行负库存由参数 allow_negative_stock 决定（BR-INV-02）。
     * 出库按当前成本单价结转并快照进流水（BR-INV-06）；返回本次结转的成本单价快照，
     * 供报损等单据在审核时定稿金额（§8.2）。
     */
    @Transactional
    public BigDecimal stockOut(StockChange cmd) {
        validate(cmd, OUT_TYPES);
        boolean allowNegative = configService.getBool(CONFIG_ALLOW_NEGATIVE, false);
        int affected = inventoryMapper.decreaseStock(cmd.goodsId(), STORE_ID, cmd.qty(), allowNegative);
        if (affected == 0) {
            Inventory inv = inventoryMapper.selectById(cmd.goodsId());
            BigDecimal available = inv == null ? BigDecimal.ZERO : inv.getQuantity();
            if (!allowNegative) {
                throw new BusinessException(ErrorCode.STOCK_NOT_ENOUGH,
                        "商品【" + goodsName(cmd.goodsId()) + "】库存不足，当前可售 "
                                + available.stripTrailingZeros().toPlainString());
            }
            // 允许负库存（先卖后补录）：行不存在时补 0 行后无条件扣减
            inventoryMapper.ensureRow(cmd.goodsId(), STORE_ID);
            inventoryMapper.decreaseStock(cmd.goodsId(), STORE_ID, cmd.qty(), true);
        }
        // 同事务内读取扣减后结存（行锁保持至提交），保证流水前后值与结存强一致（BR-INV-08）
        Inventory afterRow = requireRow(cmd.goodsId());
        BigDecimal after = afterRow.getQuantity();
        BigDecimal recordCost = afterRow.getCostPrice() == null ? BigDecimal.ZERO : afterRow.getCostPrice();
        insertRecord(cmd, after.add(cmd.qty()), after, recordCost, false);
        return recordCost;
    }

    // ==================== 实时库存查询（FR-INV-01） ====================

    /** 分页 + 合计行：分类（含子树）/名称/编码/库存状态筛选；合计返回库存总成本金额 */
    public InventoryPageVO page(InventoryQuery q) {
        int pageNo = Math.max(q.page(), 1);
        int size = q.size() <= 0 ? 20 : Math.min(q.size(), 100);
        List<Long> categoryIds = q.categoryId() == null ? null : resolveCategoryTree(q.categoryId());
        IPage<InventoryItemVO> result =
                inventoryMapper.selectInventoryPage(new Page<>(pageNo, size), q, categoryIds);
        BigDecimal totalCost = inventoryMapper.selectInventorySummary(q, categoryIds);
        return new InventoryPageVO(result.getRecords(), result.getTotal(), totalCost);
    }

    // ==================== 库存流水查询（FR-INV-03） ====================

    /** 流水分页：商品/类型/时间区间/单据号/操作人组合筛选，时间倒序 */
    public PageVO<InventoryRecordVO> recordPage(InventoryRecordQuery q) {
        int pageNo = Math.max(q.page(), 1);
        int size = q.size() <= 0 ? 20 : Math.min(q.size(), 100);
        LocalDateTime beginTime = q.beginDate() == null ? null : q.beginDate().atStartOfDay();
        LocalDateTime endTime = q.endDate() == null ? null : q.endDate().plusDays(1).atStartOfDay();
        IPage<InventoryRecordVO> result =
                inventoryRecordMapper.selectRecordPage(new Page<>(pageNo, size), q, beginTime, endTime);
        return PageVO.of(result);
    }

    // ==================== 库存预警查询（FR-INV-05） ====================

    /**
     * 预警分页：缺货/预警/积压清单，默认按缺口量降序（最紧急优先）；
     * summary 为去掉类型筛选后的各类型数量（名称/编码/分类条件保留），页签切换不跳动。
     */
    public AlertPageVO alertPage(AlertQuery q) {
        int pageNo = Math.max(q.page(), 1);
        int size = q.size() <= 0 ? 20 : Math.min(q.size(), 100);
        List<Long> categoryIds = q.categoryId() == null ? null : resolveCategoryTree(q.categoryId());
        IPage<AlertItemVO> result =
                inventoryMapper.selectAlertPage(new Page<>(pageNo, size), q, categoryIds);
        AlertSummaryVO summary = inventoryMapper.selectAlertSummary(withoutAlertType(q), categoryIds);
        return new AlertPageVO(result.getRecords(), result.getTotal(), summary);
    }

    /** 预警数量汇总（FR-INV-05/FR-RPT-01）：首页看板角标口径，忽略类型条件 */
    public AlertSummaryVO alertSummary(AlertQuery q) {
        List<Long> categoryIds = q.categoryId() == null ? null : resolveCategoryTree(q.categoryId());
        return inventoryMapper.selectAlertSummary(withoutAlertType(q), categoryIds);
    }

    /**
     * 全量预警数量汇总（无筛选）：首页看板口径（FR-RPT-01 / GAP-P2-11），
     * 与预警页「全部」逐项一致（含无结存行按 0 计缺货、含积压）。
     */
    public AlertSummaryVO alertSummary() {
        return inventoryMapper.selectAlertSummary(
                new AlertQuery(null, null, null, null, 1, 20, null, null), null);
    }

    /** 预警导出查询（FR-INV-05）：与列表同一筛选口径，不分页、不查 count；行数上限由调用方控制（ExportPage 豁免单页 500 条保护，GAP-P0-01） */
    public List<AlertItemVO> listForAlertExport(AlertQuery q, int maxSize) {
        List<Long> categoryIds = q.categoryId() == null ? null : resolveCategoryTree(q.categoryId());
        return inventoryMapper.selectAlertPage(new ExportPage<>(maxSize), q, categoryIds).getRecords();
    }

    private AlertQuery withoutAlertType(AlertQuery q) {
        return new AlertQuery(q.name(), q.goodsCode(), q.categoryId(), null, 1, 1, null, null);
    }

    // ==================== 内部工具 ====================

    /** 移动加权平均成本（BR-INV-06）：原结存≤0 时直接取本次入库单价；结果 4 位小数 */
    private BigDecimal weightedAverageCost(BigDecimal beforeQty, BigDecimal beforeCost,
                                           BigDecimal inQty, BigDecimal inCost) {
        if (beforeQty.compareTo(BigDecimal.ZERO) <= 0) {
            return inCost.setScale(4, RoundingMode.HALF_UP);
        }
        return beforeQty.multiply(beforeCost)
                .add(inQty.multiply(inCost))
                .divide(beforeQty.add(inQty), 4, RoundingMode.HALF_UP);
    }

    /** 写入不可变流水（BR-INV-04/05）：入库 +、出库 -，前后结存与结存字段强一致（BR-INV-08） */
    private void insertRecord(StockChange cmd, BigDecimal before, BigDecimal after,
                              BigDecimal unitCost, boolean directionIn) {
        InventoryRecord record = new InventoryRecord();
        record.setGoodsId(cmd.goodsId());
        record.setStoreId(STORE_ID);
        record.setRecordType(cmd.recordType());
        record.setChangeQty(directionIn ? cmd.qty() : cmd.qty().negate());
        record.setBeforeQty(before);
        record.setAfterQty(after);
        record.setUnitCost(unitCost == null ? BigDecimal.ZERO : unitCost.setScale(4, RoundingMode.HALF_UP));
        record.setBizType(cmd.bizType());
        record.setBizNo(cmd.bizNo());
        record.setOperatorName(cmd.operator());
        LocalDateTime operateTime = LocalDateTime.now();
        record.setOperateTime(operateTime);
        // 营业日（BR-RPT-02/GAP-P1-05）：库存流水按记账时刻归属，库存变动/周转/期初报表按该字段聚合
        record.setBusinessDate(businessDayService.businessDate(operateTime));
        record.setRemark(TextSanitizer.sanitize(cmd.remark()));
        // 审计字段（DR-C-08/GAP-P2-28）：流水创建人=操作人（只增表，无更新语义）
        record.setCreateBy(cmd.operator());
        record.setCreateTime(operateTime);
        inventoryRecordMapper.insert(record);
    }

    private void validate(StockChange cmd, Set<String> allowedTypes) {
        if (cmd.goodsId() == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "商品ID不能为空");
        }
        if (cmd.recordType() == null || !allowedTypes.contains(cmd.recordType())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "不支持的库存变动类型：" + cmd.recordType());
        }
        if (cmd.qty() == null || cmd.qty().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "变动数量必须大于 0");
        }
        if (cmd.qty().scale() > 3) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "变动数量最多 3 位小数");
        }
        if (COST_RECALC_IN_TYPES.contains(cmd.recordType()) && cmd.unitCost() == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "入库单价不能为空");
        }
        if (isBlank(cmd.operator())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "操作人不能为空");
        }
    }

    private Inventory requireRow(Long goodsId) {
        Inventory inv = inventoryMapper.selectById(goodsId);
        if (inv == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "库存结存行不存在：" + goodsId);
        }
        return inv;
    }

    private Inventory requireRowForUpdate(Long goodsId) {
        Inventory inv = inventoryMapper.selectByGoodsIdForUpdate(goodsId, STORE_ID);
        if (inv == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "库存结存行不存在：" + goodsId);
        }
        return inv;
    }

    /** 失败提示用商品名（NFR-USE-04 业务语言），查不到时回退商品ID */
    private String goodsName(Long goodsId) {
        Goods goods = goodsMapper.selectById(goodsId);
        return goods == null ? String.valueOf(goodsId) : goods.getName();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 分类子树 id 集合（含自身）：按物化路径前缀匹配，分类量小采用全量内存过滤 */
    private List<Long> resolveCategoryTree(Long categoryId) {
        Category category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "所选分类不存在");
        }
        String prefix = category.getPath() + "/";
        return categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                        .select(Category::getId, Category::getPath))
                .stream()
                .filter(c -> c.getId().equals(categoryId) || c.getPath().startsWith(prefix))
                .map(Category::getId)
                .toList();
    }
}

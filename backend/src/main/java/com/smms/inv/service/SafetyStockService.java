package com.smms.inv.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.goods.entity.Category;
import com.smms.goods.entity.Goods;
import com.smms.goods.mapper.CategoryMapper;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.inv.dto.CategoryThresholdRequest;
import com.smms.inv.dto.SafetyStockItem;
import com.smms.inv.dto.SafetyStockUpdateRequest;
import com.smms.sys.entity.OperationLog;
import com.smms.sys.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 安全库存阈值维护服务（FR-INV-06）：
 * 单个/批量设置与按分类（含子分类）批量设置默认阈值；
 * 约束与商品档案一致（下限必填≥0、上限≥下限、精度 0.001、非称重商品整数，BR-GOODS-03）；
 * 修改逐商品留痕（操作人、前值、后值、时间 → 操作日志，FR-SYS-08）。
 * 阈值是档案属性，不触碰库存结存（BR-INV-11）。
 */
@Service
@RequiredArgsConstructor
public class SafetyStockService {

    /** 单次批量维护商品数上限，防误操作与请求过大 */
    private static final int MAX_ITEMS = 500;
    /** 批量更新分批大小（in 条件长度控制） */
    private static final int BATCH_SIZE = 500;

    private final GoodsMapper goodsMapper;
    private final CategoryMapper categoryMapper;
    private final OperationLogService operationLogService;

    // ==================== 单个/批量设置（FR-INV-06） ====================

    /**
     * 逐商品校验、更新、留痕；值未变化的商品跳过（不产生日志）。
     *
     * @return 实际变更的商品数
     */
    @Transactional(rollbackFor = Exception.class)
    public int updateItems(SafetyStockUpdateRequest req, String operator) {
        List<SafetyStockItem> items = req == null ? null : req.items();
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请至少选择一个商品");
        }
        if (items.size() > MAX_ITEMS) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "单次最多维护 " + MAX_ITEMS + " 个商品");
        }
        Set<Long> seen = new HashSet<>();
        int changed = 0;
        for (SafetyStockItem item : items) {
            if (item == null || item.goodsId() == null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "商品ID不能为空");
            }
            if (!seen.add(item.goodsId())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "同一商品不能重复提交");
            }
            Goods goods = requireGoods(item.goodsId());
            validateCommon(item.safetyStockLower(), item.safetyStockUpper());
            checkIntegralForGoods(goods.getIsWeighed(), goods.getName(),
                    item.safetyStockLower(), item.safetyStockUpper());
            if (thresholdSame(goods, item)) {
                continue;
            }
            goodsMapper.updateSafetyStock(item.goodsId(), item.safetyStockLower(),
                    item.safetyStockUpper(), operator);
            changed++;
            logChange(goods, item.safetyStockLower(), item.safetyStockUpper(), operator);
        }
        return changed;
    }

    // ==================== 按分类批量设置默认阈值（FR-INV-06） ====================

    /**
     * 作用于分类（含子分类）下全部商品（含停用，档案属性整体对齐）；
     * 仅更新阈值与目标值不同的商品并逐行留痕。
     *
     * @return 实际变更的商品数
     */
    @Transactional(rollbackFor = Exception.class)
    public int updateByCategory(CategoryThresholdRequest req, String operator) {
        if (req == null || req.categoryId() == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请选择分类");
        }
        validateCommon(req.safetyStockLower(), req.safetyStockUpper());
        List<Long> categoryIds = resolveCategoryTree(req.categoryId());
        List<Goods> targets = goodsMapper.selectThresholdDiff(categoryIds,
                req.safetyStockLower(), req.safetyStockUpper());
        // 先全量校验再写库：范围内存在非称重商品时阈值必须为整数，任一不合法整批拒绝
        for (Goods goods : targets) {
            checkIntegralForGoods(goods.getIsWeighed(), goods.getName(),
                    req.safetyStockLower(), req.safetyStockUpper());
        }
        for (int i = 0; i < targets.size(); i += BATCH_SIZE) {
            List<Long> ids = targets.subList(i, Math.min(i + BATCH_SIZE, targets.size()))
                    .stream().map(Goods::getId).toList();
            goodsMapper.updateSafetyStockBatch(ids, req.safetyStockLower(),
                    req.safetyStockUpper(), operator);
        }
        for (Goods goods : targets) {
            logChange(goods, req.safetyStockLower(), req.safetyStockUpper(), operator);
        }
        return targets.size();
    }

    // ==================== 校验 ====================

    /** 阈值通用约束（BR-GOODS-03）：下限必填≥0、上限≥下限、精度 0.001 */
    private void validateCommon(BigDecimal lower, BigDecimal upper) {
        if (lower == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "安全库存下限不能为空");
        }
        if (lower.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "安全库存下限不能为负数");
        }
        checkScale(lower, "安全库存下限");
        checkScale(upper, "库存上限");
        if (upper != null) {
            if (upper.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "库存上限不能为负数");
            }
            if (upper.compareTo(lower) < 0) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "库存上限不能小于安全库存下限");
            }
        }
    }

    private void checkScale(BigDecimal value, String field) {
        if (value != null && value.stripTrailingZeros().scale() > 3) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, field + "精度超限（最多3位小数）");
        }
    }

    /** 非称重商品的阈值必须为整数（称重商品允许 3 位小数，BR-INV-12） */
    private void checkIntegralForGoods(Integer isWeighed, String goodsName,
                                       BigDecimal lower, BigDecimal upper) {
        if (isWeighed != null && isWeighed == 1) {
            return;
        }
        if (lower != null && lower.stripTrailingZeros().scale() > 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "非称重商品【" + goodsName + "】的安全库存阈值必须为整数");
        }
        if (upper != null && upper.stripTrailingZeros().scale() > 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "非称重商品【" + goodsName + "】的安全库存阈值必须为整数");
        }
    }

    /** 阈值未变化判定：下限相等且上限同为空或相等（跳过更新与留痕） */
    private boolean thresholdSame(Goods goods, SafetyStockItem item) {
        if (goods.getSafetyStockLower() != null
                && goods.getSafetyStockLower().compareTo(item.safetyStockLower()) == 0) {
            BigDecimal oldUpper = goods.getSafetyStockUpper();
            BigDecimal newUpper = item.safetyStockUpper();
            return oldUpper == null ? newUpper == null
                    : newUpper != null && oldUpper.compareTo(newUpper) == 0;
        }
        return false;
    }

    private Goods requireGoods(Long goodsId) {
        Goods goods = goodsMapper.selectById(goodsId);
        if (goods == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在或已被删除：" + goodsId);
        }
        return goods;
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

    // ==================== 留痕（FR-INV-06：操作人、前值、后值、时间） ====================

    /** 逐商品写操作日志：前值 → 后值，异步执行不阻断业务 */
    private void logChange(Goods goods, BigDecimal newLower, BigDecimal newUpper, String operator) {
        OperationLog log = new OperationLog();
        log.setUsername(operator);
        log.setModule("库存管理");
        log.setAction("修改安全库存阈值");
        log.setTarget(goods.getGoodsCode());
        log.setParamSummary("商品【" + goods.getName() + "】安全库存 下限 " + fmt(goods.getSafetyStockLower())
                + " → " + fmt(newLower) + "；上限 " + fmt(goods.getSafetyStockUpper())
                + " → " + fmt(newUpper));
        log.setResult(1);
        log.setOperateTime(LocalDateTime.now());
        operationLogService.recordAsync(log);
    }

    private String fmt(BigDecimal v) {
        return v == null ? "未设置" : v.stripTrailingZeros().toPlainString();
    }
}

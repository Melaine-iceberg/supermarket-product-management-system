package com.smms.goods.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.common.util.Ean13Util;
import com.smms.common.util.PinyinUtils;
import com.smms.goods.dto.GoodsCreateRequest;
import com.smms.goods.dto.GoodsCreatedVO;
import com.smms.goods.dto.GoodsDetailVO;
import com.smms.goods.dto.GoodsListVO;
import com.smms.goods.dto.GoodsQuery;
import com.smms.goods.dto.GoodsUpdateRequest;
import com.smms.goods.dto.PurchasePriceVO;
import com.smms.goods.dto.SalesSummaryVO;
import com.smms.goods.dto.SupplierBriefVO;
import com.smms.goods.entity.Brand;
import com.smms.goods.entity.Category;
import com.smms.goods.entity.Goods;
import com.smms.goods.entity.PriceHistory;
import com.smms.goods.entity.Promotion;
import com.smms.goods.entity.Unit;
import com.smms.goods.mapper.BrandMapper;
import com.smms.goods.mapper.CategoryMapper;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.goods.mapper.PriceHistoryMapper;
import com.smms.goods.mapper.PromotionMapper;
import com.smms.goods.mapper.UnitMapper;
import com.smms.inv.entity.Inventory;
import com.smms.inv.entity.InventoryRecord;
import com.smms.inv.mapper.InventoryMapper;
import com.smms.inv.mapper.InventoryRecordMapper;
import com.smms.purchase.mapper.PurchaseOrderItemMapper;
import com.smms.sale.mapper.SalesOrderItemMapper;
import com.smms.sys.entity.OperationLog;
import com.smms.sys.service.OperationLogService;
import com.smms.sys.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品档案服务（FR-GOODS-01~08）：
 * 条码全局唯一（BR-GOODS-01）；编码规则 分类码+流水，创建后不可改（BR-GOODS-02）；
 * 价格与数量约束（BR-GOODS-03）；称重商品约束（BR-GOODS-05）；
 * 不可物理删除（BR-GOODS-06）；初始库存生成"期初建账"流水（BR-INV-01）。
 */
@Service
@RequiredArgsConstructor
public class GoodsService {

    /** 编码/店内码并发生成冲突重试次数 */
    private static final int MAX_GENERATE_RETRY = 5;
    /** 编码流水号位数（BR-GOODS-02：分类码2~3位 + 流水5位） */
    private static final int CODE_SERIAL_WIDTH = 5;
    /** 店内码序号位数：前缀2 + 序号10 + 校验位1 = EAN-13 */
    private static final int BARCODE_SERIAL_WIDTH = 10;
    private static final long STORE_ID = 1L;

    private final GoodsMapper goodsMapper;
    private final CategoryMapper categoryMapper;
    private final UnitMapper unitMapper;
    private final BrandMapper brandMapper;
    private final PriceHistoryMapper priceHistoryMapper;
    private final PromotionMapper promotionMapper;
    private final InventoryMapper inventoryMapper;
    private final InventoryRecordMapper inventoryRecordMapper;
    private final PurchaseOrderItemMapper purchaseOrderItemMapper;
    private final SalesOrderItemMapper salesOrderItemMapper;
    private final SysConfigService configService;
    private final OperationLogService operationLogService;

    // ==================== 列表查询（FR-GOODS-03） ====================

    /**
     * 分页列表：名称/编码/条码模糊、分类（含子树）、品牌/状态/库存状态/称重/退货组合筛选；
     * 默认每页 20 条，上限 100（FR-GOODS-03）。排序白名单由 XML 保证。
     */
    public PageVO<GoodsListVO> page(GoodsQuery q) {
        int page = Math.max(q.page(), 1);
        int size = q.size() <= 0 ? 20 : Math.min(q.size(), 100);
        List<Long> categoryIds = q.categoryId() == null ? null : resolveCategoryTree(q.categoryId());
        IPage<GoodsListVO> result = goodsMapper.selectGoodsPage(new Page<>(page, size), q, categoryIds);
        return PageVO.of(result);
    }

    /** 导出查询（FR-GOODS-12）：与列表同一筛选口径，不分页、不查 count，行数上限由调用方控制 */
    public List<GoodsListVO> listForExport(GoodsQuery q, int maxSize) {
        List<Long> categoryIds = q.categoryId() == null ? null : resolveCategoryTree(q.categoryId());
        return goodsMapper.selectGoodsPage(new Page<>(1, maxSize, false), q, categoryIds).getRecords();
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

    // ==================== 详情与价格轨迹（FR-GOODS-04 / FR-GOODS-10） ====================

    /**
     * 商品详情：完整档案 + 当前库存与成本 + 最近 10 条库存流水
     * + 最近 5 次采购价 + 近 30 天销量销售额 + 关联供应商 + 当前生效促销。
     */
    public GoodsDetailVO detail(Long id) {
        Goods goods = requireGoods(id);
        Category category = categoryMapper.selectById(goods.getCategoryId());
        Unit unit = unitMapper.selectById(goods.getUnitId());
        Brand brand = goods.getBrandId() == null ? null : brandMapper.selectById(goods.getBrandId());
        Inventory inventory = inventoryMapper.selectById(id);
        LocalDateTime now = LocalDateTime.now();
        Promotion activePromotion = promotionMapper.selectOne(Wrappers.<Promotion>lambdaQuery()
                .eq(Promotion::getGoodsId, id)
                .eq(Promotion::getStatus, "ACTIVE")
                .le(Promotion::getStartTime, now)
                .gt(Promotion::getEndTime, now)
                .orderByDesc(Promotion::getStartTime)
                .last("LIMIT 1"));
        List<InventoryRecord> recentRecords = inventoryRecordMapper.selectList(
                Wrappers.<InventoryRecord>lambdaQuery()
                        .eq(InventoryRecord::getGoodsId, id)
                        .orderByDesc(InventoryRecord::getOperateTime)
                        .orderByDesc(InventoryRecord::getId)
                        .last("LIMIT 10"));
        List<PurchasePriceVO> recentPrices = goodsMapper.selectRecentPurchasePrices(id, 5);
        SalesSummaryVO salesSummary = goodsMapper.selectSalesSummary(id, now.minusDays(30));
        List<SupplierBriefVO> suppliers = goodsMapper.selectSuppliersByGoods(id);
        return new GoodsDetailVO(goods,
                category == null ? null : category.getName(),
                unit == null ? null : unit.getName(),
                brand == null ? null : brand.getName(),
                inventory, activePromotion, recentRecords, recentPrices, salesSummary, suppliers);
    }

    /** 调价历史（FR-GOODS-10）：按商品查询完整价格轨迹，时间倒序 */
    public List<PriceHistory> priceHistory(Long id) {
        requireGoods(id);
        return priceHistoryMapper.selectList(Wrappers.<PriceHistory>lambdaQuery()
                .eq(PriceHistory::getGoodsId, id)
                .orderByDesc(PriceHistory::getCreateTime)
                .orderByDesc(PriceHistory::getId)
                .last("LIMIT 500"));
    }

    // ==================== 新增（FR-GOODS-01） ====================

    /**
     * 新增商品：编码/条码留空时自动生成；生成值与并发插入冲突时重试，
     * 用户提供值冲突时返回明确错误。
     */
    @Transactional(rollbackFor = Exception.class)
    public GoodsCreatedVO create(GoodsCreateRequest req, String operator) {
        Category category = validateCategory(req.categoryId());
        Unit unit = validateUnit(req.unitId(), req.isWeighed(), req.pluCode());
        validateBrand(req.brandId());
        validatePrices(req.purchasePrice(), req.retailPrice(), req.memberPrice());
        validateStockFields(req.isWeighed(), req.safetyStockLower(), req.safetyStockUpper(), req.initialStock());

        String reqCode = normalize(req.goodsCode());
        String reqBarcode = normalize(req.barcode());
        if (reqCode != null) {
            validateGoodsCodeFormat(reqCode);
            checkGoodsCodeUnique(reqCode, null);
        }
        if (reqBarcode != null) {
            validateBarcodeFormat(reqBarcode);
            checkBarcodeUnique(reqBarcode, null);
        }

        Goods goods = new Goods();
        goods.setName(req.name().trim());
        goods.setPinyinInitial(PinyinUtils.toInitials(req.name().trim()));
        goods.setCategoryId(req.categoryId());
        goods.setUnitId(req.unitId());
        goods.setBrandId(req.brandId());
        goods.setSpec(req.spec());
        goods.setOrigin(req.origin());
        goods.setPurchasePrice(req.purchasePrice());
        goods.setRetailPrice(req.retailPrice());
        goods.setMemberPrice(req.memberPrice());
        goods.setPluCode(normalize(req.pluCode()));
        goods.setIsWeighed(req.isWeighed());
        goods.setAllowReturn(req.allowReturn());
        goods.setSafetyStockLower(req.safetyStockLower() == null ? BigDecimal.ZERO : req.safetyStockLower());
        goods.setSafetyStockUpper(req.safetyStockUpper());
        goods.setImage(req.image());
        goods.setStatus(1);
        goods.setRemark(req.remark());
        goods.setCreateBy(operator);

        for (int attempt = 1; ; attempt++) {
            goods.setGoodsCode(reqCode != null ? reqCode : generateGoodsCode(category));
            goods.setBarcode(reqBarcode != null ? reqBarcode : generateInStoreBarcode());
            try {
                goodsMapper.insert(goods);
                break;
            } catch (DuplicateKeyException e) {
                if (attempt >= MAX_GENERATE_RETRY) {
                    throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT, "编码/条码生成冲突，请重试");
                }
                // 区分：用户提供值被并发占用（明确报错）vs 生成值冲突（换号重试）
                if (reqCode != null) {
                    checkGoodsCodeUnique(reqCode, null);
                }
                if (reqBarcode != null) {
                    checkBarcodeUnique(reqBarcode, null);
                }
            }
        }

        // 初始库存 > 0：生成"期初建账"流水（BR-INV-01）
        BigDecimal initialStock = req.initialStock() == null ? BigDecimal.ZERO : req.initialStock();
        if (initialStock.compareTo(BigDecimal.ZERO) > 0) {
            initInventory(goods, initialStock, req.purchasePrice(), operator);
        }
        return new GoodsCreatedVO(goods.getId(), goods.getGoodsCode(), goods.getBarcode());
    }

    // ==================== 编辑（FR-GOODS-02） ====================

    /** 编辑：编码不可改；条码修改须二次确认；价格变更记录调价历史（FR-GOODS-10）。 */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, GoodsUpdateRequest req, String operator) {
        Goods goods = requireGoods(id);
        Category category = validateCategory(req.categoryId());
        validateUnit(req.unitId(), req.isWeighed(), req.pluCode());
        validateBrand(req.brandId());
        validatePrices(req.purchasePrice(), req.retailPrice(), req.memberPrice());
        validateStockFields(req.isWeighed(), req.safetyStockLower(), req.safetyStockUpper(), null);

        String newBarcode = req.barcode().trim();
        String oldBarcode = goods.getBarcode();
        boolean barcodeChanged = !oldBarcode.equals(newBarcode);
        if (barcodeChanged) {
            // 条码修改需二次确认（FR-GOODS-02）
            if (!Boolean.TRUE.equals(req.barcodeConfirmed())) {
                throw new BusinessException(ErrorCode.GOODS_BARCODE_CONFIRM_REQUIRED,
                        "条码修改需二次确认，请在前端确认后重试");
            }
            validateBarcodeFormat(newBarcode);
            checkBarcodeUnique(newBarcode, id);
        }

        BigDecimal oldPurchase = goods.getPurchasePrice();
        BigDecimal oldRetail = goods.getRetailPrice();
        BigDecimal oldMember = goods.getMemberPrice();

        goods.setName(req.name().trim());
        goods.setPinyinInitial(PinyinUtils.toInitials(req.name().trim()));
        goods.setCategoryId(req.categoryId());
        goods.setUnitId(req.unitId());
        goods.setBrandId(req.brandId());
        goods.setSpec(req.spec());
        goods.setOrigin(req.origin());
        goods.setPurchasePrice(req.purchasePrice());
        goods.setRetailPrice(req.retailPrice());
        goods.setMemberPrice(req.memberPrice());
        goods.setBarcode(newBarcode);
        goods.setPluCode(normalize(req.pluCode()));
        goods.setIsWeighed(req.isWeighed());
        goods.setAllowReturn(req.allowReturn());
        goods.setSafetyStockLower(req.safetyStockLower() == null ? BigDecimal.ZERO : req.safetyStockLower());
        goods.setSafetyStockUpper(req.safetyStockUpper());
        goods.setImage(req.image());
        goods.setRemark(req.remark());
        goods.setUpdateBy(operator);
        goodsMapper.updateById(goods);

        if (barcodeChanged) {
            logBarcodeChange(goods, oldBarcode, newBarcode, operator);
        }
        recordPriceChange(goods.getId(), "PURCHASE", oldPurchase, req.purchasePrice(), operator, req.priceChangeReason());
        recordPriceChange(goods.getId(), "RETAIL", oldRetail, req.retailPrice(), operator, req.priceChangeReason());
        recordPriceChange(goods.getId(), "MEMBER", oldMember, req.memberPrice(), operator, req.priceChangeReason());
    }

    // ==================== 停用/启用（FR-GOODS-05） ====================

    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, int status, String operator) {
        Goods goods = requireGoods(id);
        if (goods.getStatus() != null && goods.getStatus() == status) {
            return;
        }
        goods.setStatus(status);
        goods.setUpdateBy(operator);
        goodsMapper.updateById(goods);
    }

    // ==================== 删除（FR-GOODS-06、BR-GOODS-06） ====================

    /** 仅允许删除从未发生任何业务的商品（无库存流水/销售明细/采购明细，结存为0）。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Goods goods = requireGoods(id);
        Long invRecords = inventoryRecordMapper.selectCount(Wrappers.<InventoryRecord>lambdaQuery()
                .eq(InventoryRecord::getGoodsId, id));
        if (invRecords != null && invRecords > 0) {
            throw new BusinessException(ErrorCode.GOODS_HAS_BUSINESS,
                    "该商品存在 " + invRecords + " 条库存流水，禁止删除，请改用停用");
        }
        Long saleItems = salesOrderItemMapper.selectCount(
                Wrappers.<com.smms.sale.entity.SalesOrderItem>lambdaQuery()
                        .eq(com.smms.sale.entity.SalesOrderItem::getGoodsId, id));
        if (saleItems != null && saleItems > 0) {
            throw new BusinessException(ErrorCode.GOODS_HAS_BUSINESS,
                    "该商品存在 " + saleItems + " 条销售明细，禁止删除，请改用停用");
        }
        Long purItems = purchaseOrderItemMapper.selectCount(
                Wrappers.<com.smms.purchase.entity.PurchaseOrderItem>lambdaQuery()
                        .eq(com.smms.purchase.entity.PurchaseOrderItem::getGoodsId, id));
        if (purItems != null && purItems > 0) {
            throw new BusinessException(ErrorCode.GOODS_HAS_BUSINESS,
                    "该商品存在 " + purItems + " 条采购明细，禁止删除，请改用停用");
        }
        Inventory inventory = inventoryMapper.selectById(id);
        if (inventory != null && inventory.getQuantity().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException(ErrorCode.GOODS_HAS_BUSINESS,
                    "该商品库存结存不为 0，禁止删除，请改用停用");
        }
        // 商品自有配置数据（调价历史/促销/空结存行）随档案一并清理
        priceHistoryMapper.delete(Wrappers.<PriceHistory>lambdaQuery().eq(PriceHistory::getGoodsId, id));
        promotionMapper.delete(Wrappers.<com.smms.goods.entity.Promotion>lambdaQuery()
                .eq(com.smms.goods.entity.Promotion::getGoodsId, id));
        if (inventory != null) {
            inventoryMapper.deleteById(id);
        }
        goodsMapper.deleteById(id);
    }

    // ==================== 编码与店内码生成（FR-GOODS-07） ====================

    /** 预览下一个店内码（不落库），供新增表单"一键生成"使用 */
    public String previewInStoreBarcode() {
        return generateInStoreBarcode();
    }

    /**
     * 商品编码：分类码(2~3位) + 流水号(5位)（BR-GOODS-02）。
     * 分类码取分类ID零填充；取该前缀下最大流水 +1。
     */
    private String generateGoodsCode(Category category) {
        String prefix = category.getId() < 100
                ? String.format("%02d", category.getId())
                : String.format("%03d", category.getId());
        long max = maxNumericSuffix(prefix, CODE_SERIAL_WIDTH);
        if (max >= 99_999L) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                    "分类【" + category.getName() + "】下编码流水号已用尽，请自定义编码");
        }
        return prefix + String.format("%0" + CODE_SERIAL_WIDTH + "d", max + 1);
    }

    /**
     * EAN-13 店内码：前缀(20~29，参数可配) + 商品序号(10位) + 校验位。
     */
    private String generateInStoreBarcode() {
        String prefix = configService.getStr("barcode_prefix", "20");
        if (prefix == null || prefix.length() != 2 || !prefix.chars().allMatch(Character::isDigit)
                || prefix.compareTo("20") < 0 || prefix.compareTo("29") > 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                    "系统参数 barcode_prefix 必须为 20~29 的两位数字");
        }
        long max = maxBarcodeSerial(prefix);
        if (max >= 9_999_999_999L) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "店内码序号已用尽");
        }
        return Ean13Util.buildInStoreCode(prefix, max + 1);
    }

    /** 该前缀 + 固定位数字后缀的最大流水号（严格匹配长度，避免前缀歧义） */
    private long maxNumericSuffix(String prefix, int suffixWidth) {
        List<String> values = goodsMapper.selectList(Wrappers.<Goods>lambdaQuery()
                        .select(Goods::getGoodsCode)
                        .likeRight(Goods::getGoodsCode, prefix))
                .stream().map(Goods::getGoodsCode).toList();
        return values.stream()
                .filter(v -> v.length() == prefix.length() + suffixWidth)
                .map(v -> v.substring(prefix.length()))
                .filter(s -> s.chars().allMatch(Character::isDigit))
                .mapToLong(Long::parseLong)
                .max().orElse(0L);
    }

    /** 店内码序号最大值：前缀2位 + 序号10位 + 校验位 = 13位数字条码 */
    private long maxBarcodeSerial(String prefix) {
        List<String> barcodes = goodsMapper.selectList(Wrappers.<Goods>lambdaQuery()
                        .select(Goods::getBarcode)
                        .likeRight(Goods::getBarcode, prefix))
                .stream().map(Goods::getBarcode).toList();
        return barcodes.stream()
                .filter(b -> b.length() == 13 && b.chars().allMatch(Character::isDigit))
                .map(b -> b.substring(2, 2 + BARCODE_SERIAL_WIDTH))
                .mapToLong(Long::parseLong)
                .max().orElse(0L);
    }

    // ==================== 私有校验 ====================

    /** 分类：存在、启用、叶子（BR-CAT-04、FR-CAT-04 停用分类不可选） */
    private Category validateCategory(Long categoryId) {
        Category category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "所属分类不存在或已被删除");
        }
        if (category.getStatus() == null || category.getStatus() != 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "分类【" + category.getName() + "】已停用，不可选择");
        }
        Long childCount = categoryMapper.selectCount(Wrappers.<Category>lambdaQuery()
                .eq(Category::getParentId, categoryId));
        if (childCount != null && childCount > 0) {
            throw new BusinessException(ErrorCode.GOODS_CATEGORY_NOT_LEAF,
                    "商品只能挂在叶子分类下，【" + category.getName() + "】存在子分类");
        }
        return category;
    }

    /** 单位：存在、启用；称重商品必须重量单位 + PLU 码（BR-GOODS-05） */
    private Unit validateUnit(Long unitId, Integer isWeighed, String pluCode) {
        Unit unit = unitMapper.selectById(unitId);
        if (unit == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "计量单位不存在或已被删除");
        }
        if (unit.getStatus() == null || unit.getStatus() != 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "计量单位【" + unit.getName() + "】已停用，不可选择");
        }
        boolean weighed = isWeighed != null && isWeighed == 1;
        if (weighed) {
            if (unit.getIsWeight() == null || unit.getIsWeight() != 1) {
                throw new BusinessException(ErrorCode.GOODS_WEIGHED_UNIT,
                        "称重商品的计量单位必须为重量单位（kg/g），当前为【" + unit.getName() + "】");
            }
            if (pluCode == null || pluCode.isBlank()) {
                throw new BusinessException(ErrorCode.GOODS_WEIGHED_PLU_REQUIRED,
                        "称重商品必须录入 PLU 码");
            }
        }
        return unit;
    }

    private void validateBrand(Long brandId) {
        if (brandId == null) {
            return;
        }
        Brand brand = brandMapper.selectById(brandId);
        if (brand == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "品牌不存在或已被删除");
        }
        if (brand.getStatus() == null || brand.getStatus() != 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "品牌【" + brand.getName() + "】已停用，不可选择");
        }
    }

    /** 价格约束（BR-GOODS-03）：进货≥0、零售>0、会员≤零售、金额精度到分 */
    private void validatePrices(BigDecimal purchasePrice, BigDecimal retailPrice, BigDecimal memberPrice) {
        if (retailPrice == null || retailPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "零售单价必须大于0");
        }
        if (purchasePrice != null && purchasePrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "进货参考价不能为负数");
        }
        if (memberPrice != null && memberPrice.compareTo(retailPrice) > 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "会员价不能高于零售单价");
        }
        checkScale(purchasePrice, 2, "进货参考价");
        checkScale(retailPrice, 2, "零售单价");
        checkScale(memberPrice, 2, "会员价");
    }

    /** 库存阈值约束：精度到 0.001；普通商品数量为整数；上限≥下限 */
    private void validateStockFields(Integer isWeighed, BigDecimal lower, BigDecimal upper, BigDecimal initialStock) {
        checkScale(lower, 3, "安全库存下限");
        checkScale(upper, 3, "库存上限");
        checkScale(initialStock, 3, "初始库存");
        boolean weighed = isWeighed != null && isWeighed == 1;
        if (!weighed) {
            checkIntegral(lower, "安全库存下限");
            checkIntegral(upper, "库存上限");
            checkIntegral(initialStock, "初始库存");
        }
        if (lower != null && lower.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "安全库存下限不能为负数");
        }
        if (upper != null && upper.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "库存上限不能为负数");
        }
        if (initialStock != null && initialStock.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "初始库存不能为负数");
        }
        if (lower != null && upper != null && upper.compareTo(lower) < 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "库存上限不能小于安全库存下限");
        }
    }

    private void checkScale(BigDecimal value, int maxScale, String field) {
        if (value != null && value.stripTrailingZeros().scale() > maxScale) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    field + "精度超限（最多" + maxScale + "位小数）");
        }
    }

    /** 普通商品数量精度为整数（BR-GOODS-03） */
    private void checkIntegral(BigDecimal value, String field) {
        if (value != null && value.stripTrailingZeros().scale() > 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "非称重商品的" + field + "必须为整数");
        }
    }

    /** 条码格式：数字、≤20位；13 位时校验 EAN-13 校验位（A-04） */
    private void validateBarcodeFormat(String barcode) {
        if (!barcode.chars().allMatch(Character::isDigit)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "条码只能包含数字");
        }
        if (barcode.length() == 13 && !Ean13Util.isValid(barcode)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "13 位条码的 EAN-13 校验位不正确");
        }
    }

    /** 自定义编码格式：字母/数字/中划线 */
    private void validateGoodsCodeFormat(String code) {
        if (!code.matches("[A-Za-z0-9\\-]+")) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "商品编码只能包含字母、数字或中划线");
        }
    }

    private void checkBarcodeUnique(String barcode, Long excludeId) {
        Long count = goodsMapper.selectCount(Wrappers.<Goods>lambdaQuery()
                .eq(Goods::getBarcode, barcode)
                .ne(excludeId != null, Goods::getId, excludeId));
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.GOODS_BARCODE_EXISTS,
                    "商品条码【" + barcode + "】已存在（含停用商品）");
        }
    }

    private void checkGoodsCodeUnique(String code, Long excludeId) {
        Long count = goodsMapper.selectCount(Wrappers.<Goods>lambdaQuery()
                .eq(Goods::getGoodsCode, code)
                .ne(excludeId != null, Goods::getId, excludeId));
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.GOODS_CODE_EXISTS,
                    "商品编码【" + code + "】已存在");
        }
    }

    private Goods requireGoods(Long id) {
        Goods goods = goodsMapper.selectById(id);
        if (goods == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在或已被删除");
        }
        return goods;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    // ==================== 期初建账与留痕 ====================

    /** 初始库存：写入结存与"期初建账"流水（BR-INV-01），成本取进货参考价 */
    private void initInventory(Goods goods, BigDecimal initialStock, BigDecimal purchasePrice, String operator) {
        LocalDateTime now = LocalDateTime.now();
        BigDecimal unitCost = purchasePrice == null ? BigDecimal.ZERO : purchasePrice;
        Inventory inventory = new Inventory();
        inventory.setGoodsId(goods.getId());
        inventory.setStoreId(STORE_ID);
        inventory.setQuantity(initialStock);
        inventory.setCostPrice(unitCost.setScale(4, RoundingMode.HALF_UP));
        inventory.setCostAmount(initialStock.multiply(unitCost).setScale(2, RoundingMode.HALF_UP));
        inventory.setLastChangeTime(now);
        inventory.setVersion(0);
        inventoryMapper.insert(inventory);

        InventoryRecord record = new InventoryRecord();
        record.setGoodsId(goods.getId());
        record.setStoreId(STORE_ID);
        record.setRecordType("INIT");
        record.setChangeQty(initialStock);
        record.setBeforeQty(BigDecimal.ZERO);
        record.setAfterQty(initialStock);
        record.setUnitCost(unitCost.setScale(4, RoundingMode.HALF_UP));
        record.setOperatorName(operator);
        record.setOperateTime(now);
        record.setRemark("商品建档期初建账");
        inventoryRecordMapper.insert(record);
    }

    /** 条码变更历史（FR-GOODS-02）：单独留操作日志，记录前值后值 */
    private void logBarcodeChange(Goods goods, String oldBarcode, String newBarcode, String operator) {
        OperationLog log = new OperationLog();
        log.setUsername(operator);
        log.setModule("商品管理");
        log.setAction("修改条码");
        log.setTarget(goods.getGoodsCode());
        log.setParamSummary("商品【" + goods.getName() + "】条码 " + oldBarcode + " → " + newBarcode);
        log.setResult(1);
        log.setOperateTime(LocalDateTime.now());
        operationLogService.recordAsync(log);
    }

    /**
     * 调价历史（FR-GOODS-10）：记录零售价/会员价/进货参考价每次变更的前值、后值；
     * 前值/后值可为空（新设会员价、清空价格均留痕），仅前后完全一致时跳过。
     */
    private void recordPriceChange(Long goodsId, String priceType, BigDecimal oldValue,
                                   BigDecimal newValue, String operator, String reason) {
        boolean unchanged = (oldValue == null && newValue == null)
                || (oldValue != null && newValue != null && oldValue.compareTo(newValue) == 0);
        if (unchanged) {
            return;
        }
        PriceHistory history = new PriceHistory();
        history.setGoodsId(goodsId);
        history.setPriceType(priceType);
        history.setOldValue(oldValue);
        history.setNewValue(newValue);
        history.setEffectiveTime(LocalDateTime.now());
        history.setOperatorName(operator);
        history.setReason(reason);
        history.setCreateTime(LocalDateTime.now());
        priceHistoryMapper.insert(history);
    }
}

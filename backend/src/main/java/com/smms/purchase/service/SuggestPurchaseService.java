package com.smms.purchase.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.goods.entity.Category;
import com.smms.goods.mapper.CategoryMapper;
import com.smms.purchase.dto.PurchaseOrderDetailVO;
import com.smms.purchase.dto.PurchaseSaveRequest;
import com.smms.purchase.dto.SuggestGenerateRequest;
import com.smms.purchase.dto.SuggestGenerateResultVO;
import com.smms.purchase.dto.SuggestItemVO;
import com.smms.purchase.mapper.SuggestPurchaseMapper;
import com.smms.supplier.entity.Supplier;
import com.smms.supplier.mapper.SupplierMapper;
import com.smms.sys.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 建议采购清单服务（FR-PUR-02，BR-INV-07）。
 * 建议采购量 = ceil( 安全库存 × K + 日均销量(N日) × 采购提前期 − 当前结存 − 在途采购量 )，
 * K 默认 1.5、N 默认 28 天、提前期取默认供应商配置（默认 3 天）；
 * 在途采购量 = 已审核待入库/部分入库采购单待入库数量之和（BR-PUR-08）；
 * 计算结果 ≤ 0 不建议采购；称重商品按重量向上取整到 0.1 kg。
 */
@Service
@RequiredArgsConstructor
public class SuggestPurchaseService {

    /** 建议采购量安全倍数 K（BR-INV-07，默认 1.5） */
    private static final String CFG_SUGGEST_K = "suggest_order_k";
    /** 日均销量统计天数 N（BR-INV-07，默认 28） */
    private static final String CFG_SUGGEST_DAYS = "suggest_order_days";
    /** 默认采购提前期（BR-INV-07，默认 3 天） */
    private static final String CFG_DEFAULT_LEAD_TIME = "default_lead_time_days";

    private final SuggestPurchaseMapper suggestMapper;
    private final SupplierMapper supplierMapper;
    private final CategoryMapper categoryMapper;
    private final SysConfigService configService;
    private final PurchaseOrderService purchaseOrderService;

    // ==================== 建议采购清单（FR-PUR-02） ====================

    /** 分页清单：缺货/预警商品 + 建议采购量及计算依据 + 默认供应商 + 最近采购价 */
    public PageVO<SuggestItemVO> page(String name, String goodsCode, Long categoryId,
                                      String alertType, int page, int size) {
        int pageNo = Math.max(page, 1);
        int pageSize = size <= 0 ? 20 : Math.min(size, 100);
        List<Long> categoryIds = categoryId == null ? null : resolveCategoryTree(categoryId);
        IPage<SuggestItemVO> result = suggestMapper.selectSuggestPage(
                new Page<>(pageNo, pageSize), name, goodsCode, categoryIds, alertType, suggestDays());
        result.setRecords(fillComputed(result.getRecords()));
        return PageVO.of(result);
    }

    // ==================== 一键生成草稿采购单（FR-PUR-02） ====================

    /** 按默认供应商分组生成草稿采购单；缺供应商或不建议采购的商品进入跳过清单 */
    @Transactional
    public SuggestGenerateResultVO generate(SuggestGenerateRequest req, String operator) {
        List<Long> goodsIds = req.getItems().stream()
                .map(SuggestGenerateRequest.ItemRequest::getGoodsId).distinct().toList();
        Map<Long, SuggestItemVO> suggestMap = fillComputed(suggestMapper
                .selectSuggestByGoodsIds(goodsIds, suggestDays())).stream()
                .collect(Collectors.toMap(SuggestItemVO::goodsId, v -> v));

        Map<Long, SuggestGenerateRequest.ItemRequest> requestMap = req.getItems().stream()
                .collect(Collectors.toMap(SuggestGenerateRequest.ItemRequest::getGoodsId,
                        v -> v, (a, b) -> a));

        // 按默认供应商分组
        Map<Long, List<SuggestItemVO>> bySupplier = new LinkedHashMap<>();
        List<SuggestGenerateResultVO.SkippedGoodsVO> skipped = new ArrayList<>();
        for (Long goodsId : goodsIds) {
            SuggestItemVO row = suggestMap.get(goodsId);
            if (row == null) {
                skipped.add(new SuggestGenerateResultVO.SkippedGoodsVO(
                        goodsId, "商品 " + goodsId, "商品不存在或已停用"));
                continue;
            }
            if (row.supplierId() == null) {
                skipped.add(new SuggestGenerateResultVO.SkippedGoodsVO(
                        goodsId, row.goodsName(), "未维护默认供应商（FR-SUP-04）"));
                continue;
            }
            SuggestGenerateRequest.ItemRequest itemReq = requestMap.get(goodsId);
            BigDecimal qty = itemReq != null && itemReq.getQuantity() != null
                    ? itemReq.getQuantity() : row.suggestQty();
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                skipped.add(new SuggestGenerateResultVO.SkippedGoodsVO(
                        goodsId, row.goodsName(), "建议采购量 ≤ 0，不建议采购（BR-INV-07）"));
                continue;
            }
            bySupplier.computeIfAbsent(row.supplierId(), k -> new ArrayList<>()).add(row);
        }

        List<SuggestGenerateResultVO.GeneratedOrderVO> orders = new ArrayList<>();
        int defaultLeadTime = configService.getInt(CFG_DEFAULT_LEAD_TIME, 3);
        for (Map.Entry<Long, List<SuggestItemVO>> entry : bySupplier.entrySet()) {
            Long supplierId = entry.getKey();
            List<SuggestItemVO> rows = entry.getValue();
            Supplier supplier = supplierMapper.selectById(supplierId);

            PurchaseSaveRequest saveReq = new PurchaseSaveRequest();
            saveReq.setSupplierId(supplierId);
            int leadTime = supplier != null && supplier.getLeadTimeDays() != null
                    ? supplier.getLeadTimeDays() : defaultLeadTime;
            saveReq.setExpectDate(LocalDate.now().plusDays(leadTime));
            saveReq.setRemark("建议采购清单生成（BR-INV-07）");
            saveReq.setItems(rows.stream().map(row -> {
                PurchaseSaveRequest.ItemRequest item = new PurchaseSaveRequest.ItemRequest();
                item.setGoodsId(row.goodsId());
                SuggestGenerateRequest.ItemRequest itemReq = requestMap.get(row.goodsId());
                item.setQuantity(itemReq != null && itemReq.getQuantity() != null
                        ? itemReq.getQuantity() : row.suggestQty());
                // 单价默认：请求覆盖 → 最近采购价 → 供货价 → 0
                BigDecimal price = itemReq != null && itemReq.getPrice() != null ? itemReq.getPrice()
                        : row.lastPurchasePrice() != null ? row.lastPurchasePrice()
                        : row.supplyPrice() != null ? row.supplyPrice() : BigDecimal.ZERO;
                item.setPrice(price);
                return item;
            }).toList());

            PurchaseOrderDetailVO created = purchaseOrderService.create(saveReq, operator);
            orders.add(new SuggestGenerateResultVO.GeneratedOrderVO(
                    created.id(), created.orderNo(), supplierId,
                    supplier == null ? null : supplier.getName(),
                    created.items().size(), created.totalAmount(), created.expectDate()));
        }
        return new SuggestGenerateResultVO(orders, skipped);
    }

    // ==================== 建议量计算（BR-INV-07） ====================

    /** 回填日均销量、建议采购量与计算依据（K/N/提前期参与计算），返回新列表 */
    private List<SuggestItemVO> fillComputed(List<SuggestItemVO> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        BigDecimal k = new BigDecimal(configService.getStr(CFG_SUGGEST_K, "1.5"));
        int days = suggestDays();
        int defaultLeadTime = configService.getInt(CFG_DEFAULT_LEAD_TIME, 3);
        // 全部行都无默认供应商时不得发起空 IN 查询（否则 SQL 语法错误 500）
        List<Long> supplierIds = rows.stream().map(SuggestItemVO::supplierId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, Supplier> supplierMap = supplierIds.isEmpty() ? Map.of()
                : supplierMapper.selectBatchIds(supplierIds).stream()
                .collect(Collectors.toMap(Supplier::getId, s -> s));

        List<SuggestItemVO> computed = new ArrayList<>(rows.size());
        for (SuggestItemVO row : rows) {
            BigDecimal avgDaily = row.soldQty().divide(BigDecimal.valueOf(days), 3, RoundingMode.HALF_UP);
            Supplier supplier = row.supplierId() == null ? null : supplierMap.get(row.supplierId());
            int leadTime = supplier != null && supplier.getLeadTimeDays() != null
                    ? supplier.getLeadTimeDays() : defaultLeadTime;

            BigDecimal raw = row.safetyStockLower().multiply(k)
                    .add(avgDaily.multiply(BigDecimal.valueOf(leadTime)))
                    .subtract(row.quantity())
                    .subtract(row.onWayQty());
            boolean weighed = Boolean.TRUE.equals(row.isWeighed());
            BigDecimal suggestQty = raw.compareTo(BigDecimal.ZERO) <= 0 ? BigDecimal.ZERO
                    : weighed ? raw.setScale(1, RoundingMode.CEILING)
                              : raw.setScale(0, RoundingMode.CEILING);

            String basis = "安全库存 " + row.safetyStockLower().stripTrailingZeros().toPlainString()
                    + " × K " + k.stripTrailingZeros().toPlainString()
                    + " + 日均销量 " + avgDaily.stripTrailingZeros().toPlainString()
                    + " × 提前期 " + leadTime + " 天 − 结存 "
                    + row.quantity().stripTrailingZeros().toPlainString()
                    + " − 在途 " + row.onWayQty().stripTrailingZeros().toPlainString()
                    + (weighed ? "（称重商品，向上取整到 0.1）" : "");

            computed.add(new SuggestItemVO(
                    row.goodsId(), row.goodsCode(), row.goodsName(), row.spec(),
                    row.categoryId(), row.categoryName(), row.unitName(), row.isWeighed(),
                    row.suggestType(), row.quantity(), row.safetyStockLower(), row.soldQty(),
                    avgDaily, row.onWayQty(), leadTime, suggestQty, basis,
                    row.supplierId(), supplier == null ? null : supplier.getName(),
                    row.supplyPrice(), row.lastPurchasePrice()));
        }
        return computed;
    }

    private int suggestDays() {
        return Math.max(configService.getInt(CFG_SUGGEST_DAYS, 28), 1);
    }

    /** 分类子树 id 集合（含自身）：与库存查询同款物化路径前缀实现 */
    private List<Long> resolveCategoryTree(Long categoryId) {
        Category category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "所选分类不存在");
        }
        String prefix = category.getPath() + "/";
        return categoryMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<Category>lambdaQuery().select(Category::getId, Category::getPath))
                .stream()
                .filter(c -> c.getId().equals(categoryId) || c.getPath().startsWith(prefix))
                .map(Category::getId)
                .toList();
    }
}

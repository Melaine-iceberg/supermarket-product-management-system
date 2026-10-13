package com.smms.goods;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
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
import com.smms.goods.entity.Unit;
import com.smms.goods.mapper.BrandMapper;
import com.smms.goods.mapper.CategoryMapper;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.goods.mapper.PriceHistoryMapper;
import com.smms.goods.mapper.PromotionMapper;
import com.smms.goods.mapper.UnitMapper;
import com.smms.goods.service.GoodsService;
import com.smms.inv.entity.Inventory;
import com.smms.inv.entity.InventoryRecord;
import com.smms.inv.mapper.InventoryMapper;
import com.smms.inv.mapper.InventoryRecordMapper;
import com.smms.purchase.entity.PurchaseOrderItem;
import com.smms.purchase.mapper.PurchaseOrderItemMapper;
import com.smms.sale.entity.SalesOrderItem;
import com.smms.sale.mapper.SalesOrderItemMapper;
import com.smms.sys.entity.OperationLog;
import com.smms.sys.service.BusinessDayService;
import com.smms.sys.service.OperationLogService;
import com.smms.sys.service.SysConfigService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品档案服务单元测试：覆盖 BR-GOODS-01（条码唯一）、BR-GOODS-02（编码生成）、
 * BR-GOODS-03（价格/数量约束）、BR-GOODS-05（称重约束）、BR-GOODS-06（删除保护）、
 * BR-INV-01（期初建账）、FR-GOODS-02（条码二次确认）、FR-GOODS-10（调价历史）。
 */
@ExtendWith(MockitoExtension.class)
class GoodsServiceTest {

    @Mock private GoodsMapper goodsMapper;
    @Mock private CategoryMapper categoryMapper;
    @Mock private UnitMapper unitMapper;
    @Mock private BrandMapper brandMapper;
    @Mock private PriceHistoryMapper priceHistoryMapper;
    @Mock private PromotionMapper promotionMapper;
    @Mock private InventoryMapper inventoryMapper;
    @Mock private InventoryRecordMapper inventoryRecordMapper;
    @Mock private PurchaseOrderItemMapper purchaseOrderItemMapper;
    @Mock private SalesOrderItemMapper salesOrderItemMapper;
    @Mock private SysConfigService configService;
    @Mock private OperationLogService operationLogService;

    private GoodsService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Goods.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Category.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Unit.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Brand.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PriceHistory.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), com.smms.goods.entity.Promotion.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Inventory.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InventoryRecord.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PurchaseOrderItem.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SalesOrderItem.class);
        service = new GoodsService(goodsMapper, categoryMapper, unitMapper, brandMapper,
                priceHistoryMapper, promotionMapper, inventoryMapper, inventoryRecordMapper,
                purchaseOrderItemMapper, salesOrderItemMapper, configService, operationLogService,
                new BusinessDayService(configService));
    }

    // ---------- 造数 ----------

    private Category leafCategory(long id) {
        Category c = new Category();
        c.setId(id);
        c.setParentId(1L);
        c.setName("叶子分类" + id);
        c.setLevel(3);
        c.setPath("1/2/" + id);
        c.setStatus(1);
        return c;
    }

    private Unit unit(long id, int isWeight) {
        Unit u = new Unit();
        u.setId(id);
        u.setName(isWeight == 1 ? "kg" : "瓶");
        u.setIsWeight(isWeight);
        u.setStatus(1);
        return u;
    }

    private GoodsCreateRequest createReq(String barcode, String goodsCode, BigDecimal initialStock,
                                         Integer isWeighed, String pluCode, BigDecimal memberPrice) {
        return new GoodsCreateRequest(
                "测试商品", 5L, 2L, null, "500ml", "上海",
                new BigDecimal("5.00"), new BigDecimal("10.00"), memberPrice,
                barcode, goodsCode, pluCode, isWeighed, 1,
                new BigDecimal("10"), new BigDecimal("100"), null, initialStock, null);
    }

    private Goods existingGoods() {
        Goods g = new Goods();
        g.setId(100L);
        g.setGoodsCode("0500001");
        g.setBarcode("2000000000015");
        g.setName("可乐");
        g.setCategoryId(5L);
        g.setUnitId(1L);
        g.setRetailPrice(new BigDecimal("10.00"));
        g.setMemberPrice(new BigDecimal("9.00"));
        g.setPurchasePrice(new BigDecimal("8.00"));
        g.setIsWeighed(0);
        g.setAllowReturn(1);
        g.setSafetyStockLower(BigDecimal.ZERO);
        g.setStatus(1);
        return g;
    }

    private GoodsUpdateRequest updateReq(String barcode, Boolean confirmed, BigDecimal retailPrice) {
        return new GoodsUpdateRequest(
                "可乐", 5L, 2L, null, "500ml", "上海",
                new BigDecimal("8.00"), retailPrice, new BigDecimal("9.00"),
                barcode, confirmed, null, 0, 1,
                BigDecimal.ZERO, new BigDecimal("100"), null, null, null);
    }

    /** 通用查询桩：分类为启用叶子、单位启用、品牌免检 */
    private void stubCommonLookups() {
        lenient().when(categoryMapper.selectById(5L)).thenReturn(leafCategory(5L));
        lenient().when(categoryMapper.selectCount(any())).thenReturn(0L);
        lenient().when(unitMapper.selectById(2L)).thenReturn(unit(2L, 1));   // kg
        lenient().when(unitMapper.selectById(1L)).thenReturn(unit(1L, 0));   // 瓶
        lenient().when(configService.getStr("barcode_prefix", "20")).thenReturn("20");
    }

    private Goods goodsWithCode(String code) {
        Goods g = new Goods();
        g.setGoodsCode(code);
        return g;
    }

    private Goods goodsWithBarcode(String barcode) {
        Goods g = new Goods();
        g.setBarcode(barcode);
        return g;
    }

    // ---------- 新增：编码与店内码生成（FR-GOODS-07、BR-GOODS-02） ----------

    @Test
    @DisplayName("新增：编码/条码留空时自动生成 分类码+流水 与 EAN-13 店内码")
    void create_autoGeneratesCodeAndBarcode() {
        stubCommonLookups();
        // 第一次 selectList：编码候选（05 前缀已有 00001/00002）→ 生成 0500003
        // 第二次 selectList：店内码候选（2000000000015 → 序号1）→ 生成 2000000000022
        when(goodsMapper.selectList(any())).thenReturn(
                List.of(goodsWithCode("0500001"), goodsWithCode("0500002")),
                List.of(goodsWithBarcode("2000000000015")));
        when(goodsMapper.insert(any(Goods.class))).thenAnswer(inv -> {
            inv.getArgument(0, Goods.class).setId(100L);
            return 1;
        });

        GoodsCreatedVO vo = service.create(createReq(null, null, null, 0, null, null), "admin");

        assertThat(vo.goodsCode()).isEqualTo("0500003");
        assertThat(vo.barcode()).isEqualTo("2000000000022");
    }

    @Test
    @DisplayName("BR-INV-01：初始库存>0 时生成结存与'期初建账'流水，成本取进货参考价")
    void create_initialStock_generatesInitRecord() {
        stubCommonLookups();
        when(goodsMapper.selectList(any())).thenReturn(List.<Goods>of(), List.<Goods>of());
        when(goodsMapper.insert(any(Goods.class))).thenAnswer(inv -> {
            inv.getArgument(0, Goods.class).setId(100L);
            return 1;
        });

        service.create(createReq(null, null, new BigDecimal("10.5"), 1, "1001", null), "admin");

        ArgumentCaptor<Inventory> inv = ArgumentCaptor.forClass(Inventory.class);
        verify(inventoryMapper).insert(inv.capture());
        assertThat(inv.getValue().getGoodsId()).isEqualTo(100L);
        assertThat(inv.getValue().getQuantity()).isEqualByComparingTo("10.5");
        assertThat(inv.getValue().getCostPrice()).isEqualByComparingTo("5.00");
        assertThat(inv.getValue().getCostAmount()).isEqualByComparingTo("52.50");

        ArgumentCaptor<InventoryRecord> rec = ArgumentCaptor.forClass(InventoryRecord.class);
        verify(inventoryRecordMapper).insert(rec.capture());
        assertThat(rec.getValue().getRecordType()).isEqualTo("INIT");
        assertThat(rec.getValue().getChangeQty()).isEqualByComparingTo("10.5");
        assertThat(rec.getValue().getBeforeQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(rec.getValue().getAfterQty()).isEqualByComparingTo("10.5");
        assertThat(rec.getValue().getOperatorName()).isEqualTo("admin");
    }

    @Test
    @DisplayName("生成值与并发插入冲突时自动换号重试成功")
    void create_duplicateKeyOnGenerated_retries() {
        stubCommonLookups();
        when(goodsMapper.selectList(any())).thenReturn(List.<Goods>of());
        when(goodsMapper.insert(any(Goods.class)))
                .thenThrow(new DuplicateKeyException("uk_goods_code"))
                .thenAnswer(inv -> {
                    inv.getArgument(0, Goods.class).setId(100L);
                    return 1;
                });

        GoodsCreatedVO vo = service.create(createReq(null, null, null, 0, null, null), "admin");

        assertThat(vo).isNotNull();
        verify(goodsMapper, org.mockito.Mockito.times(2)).insert(any(Goods.class));
    }

    @Test
    @DisplayName("自定义编码/条码被并发占用时返回明确错误而非换号")
    void create_duplicateKeyOnProvided_reportsExists() {
        stubCommonLookups();
        // 提供编码与条码 → 预检查通过（count=0×2），插入撞唯一键 → 复查编码 count=1 → 报"已存在"
        when(goodsMapper.selectCount(any())).thenReturn(0L, 0L, 1L);
        when(goodsMapper.insert(any(Goods.class))).thenThrow(new DuplicateKeyException("uk_goods_code"));

        assertThatThrownBy(() -> service.create(
                createReq("6901234567892", "C0001", null, 0, null, null), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
    }

    // ---------- 新增：唯一性与格式校验 ----------

    @Test
    @DisplayName("BR-GOODS-01：条码重复（含停用商品）拒绝新增")
    void create_duplicateBarcode_rejected() {
        stubCommonLookups();
        when(goodsMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(
                createReq("6901234567892", null, null, 0, null, null), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("条码")
                .hasMessageContaining("已存在");
        verify(goodsMapper, never()).insert(any(Goods.class));
    }

    @Test
    @DisplayName("BR-GOODS-02：自定义编码重复拒绝")
    void create_duplicateCode_rejected() {
        stubCommonLookups();
        when(goodsMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(
                createReq(null, "C0001", null, 0, null, null), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("编码")
                .hasMessageContaining("已存在");
    }

    @Test
    @DisplayName("A-04：13位条码校验位错误拒绝")
    void create_badEan13CheckDigit_rejected() {
        stubCommonLookups();

        assertThatThrownBy(() -> service.create(
                createReq("6901234567890", null, null, 0, null, null), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("校验位");
    }

    // ---------- 新增：关联档案校验 ----------

    @Test
    @DisplayName("BR-CAT-04：非叶子分类（存在子分类）拒绝建档")
    void create_nonLeafCategory_rejected() {
        stubCommonLookups();
        when(categoryMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.create(createReq(null, null, null, 0, null, null), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("叶子分类");
    }

    @Test
    @DisplayName("FR-CAT-04：停用分类拒绝建档")
    void create_disabledCategory_rejected() {
        Category disabled = leafCategory(5L);
        disabled.setStatus(0);
        when(categoryMapper.selectById(5L)).thenReturn(disabled);

        assertThatThrownBy(() -> service.create(createReq(null, null, null, 0, null, null), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已停用");
    }

    @Test
    @DisplayName("BR-GOODS-05：称重商品选非重量单位拒绝")
    void create_weighedWithNonWeightUnit_rejected() {
        stubCommonLookups();
        // 单位1=瓶（非重量），称重商品挂它 → 拒绝
        GoodsCreateRequest req = new GoodsCreateRequest(
                "苹果", 5L, 1L, null, null, null,
                new BigDecimal("5.00"), new BigDecimal("12.00"), null,
                null, null, "2001", 1, 1,
                new BigDecimal("10"), null, null, null, null);

        assertThatThrownBy(() -> service.create(req, "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重量单位");
    }

    @Test
    @DisplayName("BR-GOODS-05：称重商品缺少 PLU 码拒绝")
    void create_weighedWithoutPlu_rejected() {
        stubCommonLookups();

        assertThatThrownBy(() -> service.create(createReq(null, null, null, 1, null, null), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PLU");
    }

    // ---------- 新增：价格与数量约束（BR-GOODS-03） ----------

    @Test
    @DisplayName("BR-GOODS-03：会员价高于零售价拒绝")
    void create_memberPriceAboveRetail_rejected() {
        stubCommonLookups();

        assertThatThrownBy(() -> service.create(
                createReq(null, null, null, 0, null, new BigDecimal("10.01")), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("会员价");
    }

    @Test
    @DisplayName("BR-GOODS-03：非称重商品初始库存含小数拒绝")
    void create_fractionalStockForNormal_rejected() {
        stubCommonLookups();

        assertThatThrownBy(() -> service.create(
                createReq(null, null, new BigDecimal("1.5"), 0, null, null), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("整数");
    }

    @Test
    @DisplayName("BR-GOODS-03：价格精度超过2位小数拒绝")
    void create_priceScaleExceeded_rejected() {
        stubCommonLookups();
        GoodsCreateRequest req = new GoodsCreateRequest(
                "可乐", 5L, 1L, null, null, null,
                new BigDecimal("5.00"), new BigDecimal("10.999"), null,
                null, null, null, 0, 1,
                BigDecimal.ZERO, null, null, null, null);

        assertThatThrownBy(() -> service.create(req, "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("精度");
    }

    // ---------- 编辑（FR-GOODS-02） ----------

    @Test
    @DisplayName("FR-GOODS-02：条码修改未二次确认拒绝")
    void update_barcodeWithoutConfirm_rejected() {
        stubCommonLookups();
        when(goodsMapper.selectById(100L)).thenReturn(existingGoods());

        assertThatThrownBy(() -> service.update(100L,
                updateReq("6901234567892", false, new BigDecimal("10.00")), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("二次确认");
        verify(goodsMapper, never()).updateById(any(Goods.class));
    }

    @Test
    @DisplayName("FR-GOODS-02：条码修改确认后成功并留操作日志（前值→后值）")
    void update_barcodeWithConfirm_logsChange() {
        stubCommonLookups();
        when(goodsMapper.selectById(100L)).thenReturn(existingGoods());
        when(goodsMapper.selectCount(any())).thenReturn(0L);

        service.update(100L, updateReq("6901234567892", true, new BigDecimal("10.00")), "admin");

        ArgumentCaptor<Goods> goodsCaptor = ArgumentCaptor.forClass(Goods.class);
        verify(goodsMapper).updateById(goodsCaptor.capture());
        assertThat(goodsCaptor.getValue().getBarcode()).isEqualTo("6901234567892");

        ArgumentCaptor<OperationLog> logCaptor = ArgumentCaptor.forClass(OperationLog.class);
        verify(operationLogService).recordAsync(logCaptor.capture());
        assertThat(logCaptor.getValue().getAction()).isEqualTo("修改条码");
        assertThat(logCaptor.getValue().getParamSummary())
                .contains("2000000000015")
                .contains("6901234567892");
    }

    @Test
    @DisplayName("FR-GOODS-10：调价历史记录价格变更与清空（前值/后值/操作人/原因）")
    void update_priceChange_recordsHistory() {
        stubCommonLookups();
        when(goodsMapper.selectById(100L)).thenReturn(existingGoods());

        // 零售价 10→12（留痕）；会员价 9 不变（不留痕）；进货价 8→清空（留痕，后值为空）
        GoodsUpdateRequest req = new GoodsUpdateRequest(
                "可乐", 5L, 1L, null, "500ml", "上海",
                null, new BigDecimal("12.00"), new BigDecimal("9.00"),
                "2000000000015", true, null, 0, 1,
                BigDecimal.ZERO, new BigDecimal("100"), null, "促销备货", null);
        service.update(100L, req, "admin");

        ArgumentCaptor<PriceHistory> captor = ArgumentCaptor.forClass(PriceHistory.class);
        verify(priceHistoryMapper, times(2)).insert(captor.capture());
        List<PriceHistory> histories = captor.getAllValues();
        // 进货价清空：8.00 → 空
        PriceHistory purchase = histories.get(0);
        assertThat(purchase.getPriceType()).isEqualTo("PURCHASE");
        assertThat(purchase.getOldValue()).isEqualByComparingTo("8.00");
        assertThat(purchase.getNewValue()).isNull();
        // 零售价变更：10.00 → 12.00
        PriceHistory retail = histories.get(1);
        assertThat(retail.getPriceType()).isEqualTo("RETAIL");
        assertThat(retail.getOldValue()).isEqualByComparingTo("10.00");
        assertThat(retail.getNewValue()).isEqualByComparingTo("12.00");
        assertThat(retail.getOperatorName()).isEqualTo("admin");
        assertThat(retail.getReason()).isEqualTo("促销备货");
    }

    @Test
    @DisplayName("FR-GOODS-10：新设会员价（前值为空）同样留痕")
    void update_memberPriceFirstSet_recordsHistory() {
        stubCommonLookups();
        Goods goods = existingGoods();
        goods.setMemberPrice(null);
        when(goodsMapper.selectById(100L)).thenReturn(goods);

        GoodsUpdateRequest req = new GoodsUpdateRequest(
                "可乐", 5L, 1L, null, "500ml", "上海",
                new BigDecimal("8.00"), new BigDecimal("10.00"), new BigDecimal("9.50"),
                "2000000000015", true, null, 0, 1,
                BigDecimal.ZERO, new BigDecimal("100"), null, "新设会员价", null);
        service.update(100L, req, "admin");

        ArgumentCaptor<PriceHistory> captor = ArgumentCaptor.forClass(PriceHistory.class);
        verify(priceHistoryMapper).insert(captor.capture());
        PriceHistory history = captor.getValue();
        assertThat(history.getPriceType()).isEqualTo("MEMBER");
        assertThat(history.getOldValue()).isNull();
        assertThat(history.getNewValue()).isEqualByComparingTo("9.50");
        assertThat(history.getReason()).isEqualTo("新设会员价");
    }

    @Test
    @DisplayName("价格无变化时不产生调价历史")
    void update_noPriceChange_noHistory() {
        stubCommonLookups();
        when(goodsMapper.selectById(100L)).thenReturn(existingGoods());

        service.update(100L, updateReq("2000000000015", true, new BigDecimal("10.00")), "admin");

        verify(priceHistoryMapper, never()).insert(any(PriceHistory.class));
    }

    // ---------- 停用/启用（FR-GOODS-05） ----------

    @Test
    @DisplayName("停用/启用：状态落库，同状态幂等")
    void changeStatus_updatesAndIdempotent() {
        when(goodsMapper.selectById(100L)).thenReturn(existingGoods());

        service.changeStatus(100L, 0, "admin");
        ArgumentCaptor<Goods> captor = ArgumentCaptor.forClass(Goods.class);
        verify(goodsMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isZero();

        service.changeStatus(100L, 0, "admin"); // 幂等，不重复更新
        verify(goodsMapper, org.mockito.Mockito.times(1)).updateById(any(Goods.class));
    }

    // ---------- 删除（FR-GOODS-06、BR-GOODS-06） ----------

    @Test
    @DisplayName("BR-GOODS-06：存在库存流水禁止删除（提示改用停用）")
    void delete_hasInventoryRecords_rejected() {
        when(goodsMapper.selectById(100L)).thenReturn(existingGoods());
        when(inventoryRecordMapper.selectCount(any())).thenReturn(3L);

        assertThatThrownBy(() -> service.delete(100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存流水")
                .hasMessageContaining("改用停用");
        verify(goodsMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("BR-GOODS-06：存在销售/采购明细禁止删除")
    void delete_hasBusinessItems_rejected() {
        when(goodsMapper.selectById(100L)).thenReturn(existingGoods());
        when(inventoryRecordMapper.selectCount(any())).thenReturn(0L);
        when(salesOrderItemMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("销售明细");
    }

    @Test
    @DisplayName("BR-GOODS-06：结存不为0禁止删除")
    void delete_nonZeroStock_rejected() {
        when(goodsMapper.selectById(100L)).thenReturn(existingGoods());
        when(inventoryRecordMapper.selectCount(any())).thenReturn(0L);
        when(salesOrderItemMapper.selectCount(any())).thenReturn(0L);
        when(purchaseOrderItemMapper.selectCount(any())).thenReturn(0L);
        Inventory inventory = new Inventory();
        inventory.setGoodsId(100L);
        inventory.setQuantity(new BigDecimal("5"));
        when(inventoryMapper.selectById(100L)).thenReturn(inventory);

        assertThatThrownBy(() -> service.delete(100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("结存不为 0");
    }

    @Test
    @DisplayName("从未发生业务的商品允许删除，并清理自有配置数据")
    void delete_cleanGoods_ok() {
        when(goodsMapper.selectById(100L)).thenReturn(existingGoods());
        when(inventoryRecordMapper.selectCount(any())).thenReturn(0L);
        when(salesOrderItemMapper.selectCount(any())).thenReturn(0L);
        when(purchaseOrderItemMapper.selectCount(any())).thenReturn(0L);
        Inventory zero = new Inventory();
        zero.setGoodsId(100L);
        zero.setQuantity(BigDecimal.ZERO);
        when(inventoryMapper.selectById(100L)).thenReturn(zero);

        service.delete(100L);

        verify(priceHistoryMapper).delete(any());
        verify(promotionMapper).delete(any());
        verify(inventoryMapper).deleteById(100L);
        verify(goodsMapper).deleteById(100L);
    }

    // ---------- 兜底 ----------

    @Test
    @DisplayName("商品不存在统一返回 NOT_FOUND")
    void requireGoods_notFound() {
        when(goodsMapper.selectById(eq(99L))).thenReturn(null);

        assertThatThrownBy(() -> service.changeStatus(99L, 0, "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    // ---------- 列表查询（FR-GOODS-03） ----------

    @Test
    @DisplayName("分页默认值与上限：page<1 修正为 1，size 超 100 收敛为 100")
    void page_defaultsAndClamp() {
        when(goodsMapper.selectGoodsPage(any(), any(), any())).thenReturn(
                new Page<>(1, 20, 0));

        PageVO<GoodsListVO> vo = service.page(new GoodsQuery(
                null, null, null, null, null, null, null, null, null, 0, 500, null, null));

        ArgumentCaptor<Page<GoodsListVO>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(goodsMapper).selectGoodsPage(pageCaptor.capture(), any(), any());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(100);
        assertThat(vo.total()).isZero();
        // 未按分类筛选时不应触发分类子树解析
        verify(categoryMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("分类筛选含子树：按物化路径前缀收集自身与后代 id")
    void page_categoryTreeResolved() {
        Category parent = leafCategory(2L);
        parent.setParentId(0L);
        parent.setLevel(1);
        parent.setPath("1/2");
        Category child1 = leafCategory(5L);
        Category child2 = leafCategory(6L);
        Category other = leafCategory(7L);
        other.setPath("1/3/7");
        when(categoryMapper.selectById(2L)).thenReturn(parent);
        when(categoryMapper.selectList(any())).thenReturn(List.of(parent, child1, child2, other));
        when(goodsMapper.selectGoodsPage(any(), any(), any())).thenReturn(new Page<>(1, 20, 0));

        service.page(new GoodsQuery(
                null, null, null, 2L, null, null, null, null, null, 1, 20, null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(goodsMapper).selectGoodsPage(any(), any(), idsCaptor.capture());
        // child1/child2 的 path 为 "1/2/x"，other 为 "1/3/7" 不在子树内
        assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(2L, 5L, 6L);
    }

    @Test
    @DisplayName("分类筛选不存在时明确报错")
    void page_invalidCategory_rejected() {
        when(categoryMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.page(new GoodsQuery(
                null, null, null, 404L, null, null, null, null, null, 1, 20, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分类不存在");
    }

    // ---------- 详情与价格轨迹（FR-GOODS-04 / FR-GOODS-10） ----------

    @Test
    @DisplayName("详情聚合：档案+关联名+库存+生效促销+流水+采购价+销量+供应商")
    void detail_assemblesAll() {
        Goods goods = existingGoods();
        goods.setBrandId(null);
        when(goodsMapper.selectById(100L)).thenReturn(goods);
        when(categoryMapper.selectById(5L)).thenReturn(leafCategory(5L));
        when(unitMapper.selectById(1L)).thenReturn(unit(1L, 0));

        Inventory inventory = new Inventory();
        inventory.setGoodsId(100L);
        inventory.setQuantity(new BigDecimal("12.000"));
        when(inventoryMapper.selectById(100L)).thenReturn(inventory);

        com.smms.goods.entity.Promotion promotion = new com.smms.goods.entity.Promotion();
        promotion.setGoodsId(100L);
        promotion.setPromoPrice(new BigDecimal("8.50"));
        promotion.setStatus("ACTIVE");
        when(promotionMapper.selectOne(any())).thenReturn(promotion);

        InventoryRecord record = new InventoryRecord();
        record.setGoodsId(100L);
        record.setRecordType("INIT");
        when(inventoryRecordMapper.selectList(any())).thenReturn(List.of(record));

        PurchasePriceVO price = new PurchasePriceVO();
        price.setOrderNo("RK202601010001");
        price.setPrice(new BigDecimal("8.00"));
        when(goodsMapper.selectRecentPurchasePrices(100L, 5)).thenReturn(List.of(price));

        SalesSummaryVO summary = new SalesSummaryVO();
        summary.setTotalQuantity(new BigDecimal("33"));
        summary.setTotalAmount(new BigDecimal("300.00"));
        when(goodsMapper.selectSalesSummary(eq(100L), any())).thenReturn(summary);

        SupplierBriefVO supplier = new SupplierBriefVO();
        supplier.setSupplierId(1L);
        supplier.setSupplierName("华东饮品");
        when(goodsMapper.selectSuppliersByGoods(100L)).thenReturn(List.of(supplier));

        GoodsDetailVO vo = service.detail(100L);

        assertThat(vo.goods().getGoodsCode()).isEqualTo("0500001");
        assertThat(vo.categoryName()).isEqualTo("叶子分类5");
        assertThat(vo.unitName()).isEqualTo("瓶");
        assertThat(vo.brandName()).isNull();
        assertThat(vo.inventory().getQuantity()).isEqualByComparingTo("12");
        assertThat(vo.activePromotion().getPromoPrice()).isEqualByComparingTo("8.50");
        assertThat(vo.recentInventoryRecords()).hasSize(1);
        assertThat(vo.recentPurchasePrices().get(0).getOrderNo()).isEqualTo("RK202601010001");
        assertThat(vo.salesSummary30d().getTotalQuantity()).isEqualByComparingTo("33");
        assertThat(vo.suppliers().get(0).getSupplierName()).isEqualTo("华东饮品");
    }

    @Test
    @DisplayName("调价历史（FR-GOODS-10）：按商品查询并倒序返回")
    void priceHistory_returnsList() {
        when(goodsMapper.selectById(100L)).thenReturn(existingGoods());
        PriceHistory history = new PriceHistory();
        history.setGoodsId(100L);
        history.setPriceType("RETAIL");
        history.setOldValue(new BigDecimal("10.00"));
        history.setNewValue(new BigDecimal("12.00"));
        when(priceHistoryMapper.selectList(any())).thenReturn(List.of(history));

        List<PriceHistory> result = service.priceHistory(100L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPriceType()).isEqualTo("RETAIL");
        assertThat(result.get(0).getNewValue()).isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("详情/调价历史对不存在商品返回 NOT_FOUND")
    void detail_notFound() {
        when(goodsMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.detail(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.priceHistory(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }
}

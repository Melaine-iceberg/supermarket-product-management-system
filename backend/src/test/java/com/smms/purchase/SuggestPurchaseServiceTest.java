package com.smms.purchase;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.PageVO;
import com.smms.goods.entity.Category;
import com.smms.goods.mapper.CategoryMapper;
import com.smms.purchase.dto.PurchaseOrderDetailVO;
import com.smms.purchase.dto.PurchaseSaveRequest;
import com.smms.purchase.dto.SuggestGenerateRequest;
import com.smms.purchase.dto.SuggestGenerateResultVO;
import com.smms.purchase.dto.SuggestItemVO;
import com.smms.purchase.mapper.SuggestPurchaseMapper;
import com.smms.purchase.service.PurchaseOrderService;
import com.smms.purchase.service.SuggestPurchaseService;
import com.smms.supplier.entity.Supplier;
import com.smms.supplier.mapper.SupplierMapper;
import com.smms.sys.service.SysConfigService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 建议采购清单服务单元测试：覆盖 BR-INV-07（建议量公式
 * ceil(安全库存×K + 日均销量×提前期 − 结存 − 在途)，K=1.5、N=28、提前期取供应商配置）、
 * FR-PUR-02（分页清单计算依据、按默认供应商分组一键生成草稿、无默认供应商跳过）。
 */
@ExtendWith(MockitoExtension.class)
class SuggestPurchaseServiceTest {

    @Mock private SuggestPurchaseMapper suggestMapper;
    @Mock private SupplierMapper supplierMapper;
    @Mock private CategoryMapper categoryMapper;
    @Mock private SysConfigService configService;
    @Mock private PurchaseOrderService purchaseOrderService;

    private SuggestPurchaseService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Supplier.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Category.class);
        service = new SuggestPurchaseService(suggestMapper, supplierMapper, categoryMapper,
                configService, purchaseOrderService);
    }

    // ==================== 建议量计算（BR-INV-07） ====================

    @Test
    @DisplayName("建议量公式：ceil(安全库存×1.5 + 日均×提前期 − 结存 − 在途)，提前期取供应商配置")
    void pageComputesSuggestQtyByFormula() {
        // 安全库存 10、近 28 日销量 28（日均 1）、结存 5、在途 3、供应商提前期 5 天
        // raw = 10×1.5 + 1×5 − 5 − 3 = 12
        SuggestItemVO row = row("10", "28", "5", "3", false, 1L);
        Page<SuggestItemVO> page = new Page<>(1, 20);
        page.setRecords(List.of(row));
        when(suggestMapper.selectSuggestPage(any(), any(), any(), any(), any(), eq(28))).thenReturn(page);
        when(supplierMapper.selectBatchIds(anyList())).thenReturn(List.of(supplier(5)));
        stubConfig();

        PageVO<SuggestItemVO> vo = service.page(null, null, null, null, 1, 20);

        SuggestItemVO computed = vo.list().get(0);
        assertThat(computed.avgDailySales()).isEqualByComparingTo("1.000");
        assertThat(computed.leadTimeDays()).isEqualTo(5);
        assertThat(computed.suggestQty()).isEqualByComparingTo("12");
        assertThat(computed.calcBasis()).contains("K 1.5").contains("提前期 5 天");
    }

    @Test
    @DisplayName("建议量 ≤ 0 不建议采购（BR-INV-07）")
    void pageReturnsZeroWhenStockSufficient() {
        // raw = 10×1.5 + 1×5 − 20 − 3 = −3
        SuggestItemVO row = row("10", "28", "20", "3", false, null);
        Page<SuggestItemVO> page = new Page<>(1, 20);
        page.setRecords(List.of(row));
        when(suggestMapper.selectSuggestPage(any(), any(), any(), any(), any(), eq(28))).thenReturn(page);
        stubConfig();

        PageVO<SuggestItemVO> vo = service.page(null, null, null, null, 1, 20);

        assertThat(vo.list().get(0).suggestQty()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("称重商品建议量向上取整到 0.1（BR-INV-07）")
    void pageRoundsWeighedGoodsToTenth() {
        // raw = 7.49×1.5 + 0×5 − 0 − 0 = 11.235 → 11.3
        SuggestItemVO row = row("7.49", "0", "0", "0", true, null);
        Page<SuggestItemVO> page = new Page<>(1, 20);
        page.setRecords(List.of(row));
        when(suggestMapper.selectSuggestPage(any(), any(), any(), any(), any(), eq(28))).thenReturn(page);
        stubConfig();

        PageVO<SuggestItemVO> vo = service.page(null, null, null, null, 1, 20);

        assertThat(vo.list().get(0).suggestQty()).isEqualByComparingTo("11.3");
    }

    @Test
    @DisplayName("全部明细无默认供应商：跳过供应商批量查询（空 IN 会致 SQL 500）")
    void pageSkipsSupplierBatchQueryWhenAllSupplierIdsNull() {
        SuggestItemVO row = row("10", "28", "0", "0", false, null);
        Page<SuggestItemVO> page = new Page<>(1, 20);
        page.setRecords(List.of(row));
        when(suggestMapper.selectSuggestPage(any(), any(), any(), any(), any(), eq(28))).thenReturn(page);
        stubConfig();

        PageVO<SuggestItemVO> vo = service.page(null, null, null, null, 1, 20);

        // raw = 10×1.5 + 1×3（默认提前期）− 0 − 0 = 18
        assertThat(vo.list().get(0).suggestQty()).isEqualByComparingTo("18");
        assertThat(vo.list().get(0).leadTimeDays()).isEqualTo(3);
        verifyNoInteractions(supplierMapper);
    }

    // ==================== 一键生成草稿（FR-PUR-02） ====================

    @Test
    @DisplayName("一键生成：按默认供应商分组生成草稿，单价默认取最近采购价（FR-PUR-02）")
    void generateCreatesDraftOrderByDefaultSupplier() {
        SuggestItemVO row = row("10", "28", "5", "3", false, 1L);
        when(suggestMapper.selectSuggestByGoodsIds(anyList(), eq(28))).thenReturn(List.of(row));
        stubConfig();
        Supplier supplier = supplier(5);
        when(supplierMapper.selectBatchIds(anyList())).thenReturn(List.of(supplier));
        when(supplierMapper.selectById(1L)).thenReturn(supplier);
        when(purchaseOrderService.create(any(), eq("张店长"))).thenReturn(detailVO());

        SuggestGenerateRequest req = new SuggestGenerateRequest();
        SuggestGenerateRequest.ItemRequest item = new SuggestGenerateRequest.ItemRequest();
        item.setGoodsId(101L);
        req.setItems(List.of(item));

        SuggestGenerateResultVO result = service.generate(req, "张店长");

        assertThat(result.orders()).hasSize(1);
        assertThat(result.skippedGoods()).isEmpty();
        SuggestGenerateResultVO.GeneratedOrderVO generated = result.orders().get(0);
        assertThat(generated.orderNo()).isEqualTo("PO202601010005");
        assertThat(generated.supplierName()).isEqualTo("华东饮品");
        assertThat(generated.itemCount()).isEqualTo(1);

        // 草稿参数：数量取建议量 12，单价取最近采购价 9.8（优先于供货价 9.0），预计到货 = 今日+提前期 5 天
        ArgumentCaptor<PurchaseSaveRequest> captor = ArgumentCaptor.forClass(PurchaseSaveRequest.class);
        verify(purchaseOrderService).create(captor.capture(), eq("张店长"));
        PurchaseSaveRequest saved = captor.getValue();
        assertThat(saved.getSupplierId()).isEqualTo(1L);
        assertThat(saved.getExpectDate()).isEqualTo(LocalDate.now().plusDays(5));
        assertThat(saved.getItems()).hasSize(1);
        assertThat(saved.getItems().get(0).getQuantity()).isEqualByComparingTo("12");
        assertThat(saved.getItems().get(0).getPrice()).isEqualByComparingTo("9.8");
    }

    @Test
    @DisplayName("一键生成：无默认供应商的商品进入跳过清单并说明原因（FR-SUP-04）")
    void generateSkipsGoodsWithoutDefaultSupplier() {
        SuggestItemVO row = row("10", "28", "5", "3", false, null); // 无默认供应商
        when(suggestMapper.selectSuggestByGoodsIds(anyList(), eq(28))).thenReturn(List.of(row));
        stubConfig();

        SuggestGenerateRequest req = new SuggestGenerateRequest();
        SuggestGenerateRequest.ItemRequest item = new SuggestGenerateRequest.ItemRequest();
        item.setGoodsId(101L);
        req.setItems(List.of(item));

        SuggestGenerateResultVO result = service.generate(req, "张店长");

        assertThat(result.orders()).isEmpty();
        assertThat(result.skippedGoods()).hasSize(1);
        assertThat(result.skippedGoods().get(0).reason()).contains("默认供应商");
    }

    // ==================== 测试数据 ====================

    private void stubConfig() {
        lenient().when(configService.getStr(eq("suggest_order_k"), anyString())).thenReturn("1.5");
        lenient().when(configService.getInt(eq("suggest_order_days"), eq(28))).thenReturn(28);
        lenient().when(configService.getInt(eq("default_lead_time_days"), eq(3))).thenReturn(3);
    }

    /** 建议清单原始行：安全库存 / 近 N 日销量 / 结存 / 在途 / 是否称重 / 默认供应商 */
    private SuggestItemVO row(String safetyStock, String soldQty, String quantity,
                              String onWayQty, boolean weighed, Long supplierId) {
        return new SuggestItemVO(101L, "SP0001", "可乐 330ml", "330ml", 5L, "碳酸饮料",
                "罐", weighed, "WARNING", new BigDecimal(quantity), new BigDecimal(safetyStock),
                new BigDecimal(soldQty), null, new BigDecimal(onWayQty), null, null, null,
                supplierId, null, new BigDecimal("9.0"), new BigDecimal("9.8"));
    }

    private Supplier supplier(int leadTimeDays) {
        Supplier supplier = new Supplier();
        supplier.setId(1L);
        supplier.setName("华东饮品");
        supplier.setStatus(1);
        supplier.setLeadTimeDays(leadTimeDays);
        return supplier;
    }

    private PurchaseOrderDetailVO detailVO() {
        PurchaseOrderDetailVO.ItemVO item = new PurchaseOrderDetailVO.ItemVO(
                600L, 101L, "SP0001", "可乐 330ml", "330ml", "罐",
                new BigDecimal("12"), new BigDecimal("9.8"), new BigDecimal("117.60"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("12"), null);
        return new PurchaseOrderDetailVO(500L, "PO202601010005", 1L, "华东饮品",
                "DRAFT", "张店长", null, null, null, null, null,
                LocalDate.now().plusDays(5), new BigDecimal("117.60"),
                BigDecimal.ZERO, BigDecimal.ZERO, "UNSETTLED", null, null, null, null,
                null, 0, "建议采购清单生成（BR-INV-07）", List.of(item));
    }
}

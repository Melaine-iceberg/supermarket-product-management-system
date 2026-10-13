package com.smms.supplier;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.goods.entity.Goods;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.supplier.dto.SupplierGoodsSaveRequest;
import com.smms.supplier.dto.SupplierListVO;
import com.smms.supplier.dto.SupplierQuery;
import com.smms.supplier.dto.SupplierSaveRequest;
import com.smms.supplier.dto.SupplierStatVO;
import com.smms.supplier.entity.Supplier;
import com.smms.supplier.entity.SupplierGoods;
import com.smms.supplier.mapper.SupplierGoodsMapper;
import com.smms.supplier.mapper.SupplierMapper;
import com.smms.supplier.service.SupplierService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 供应商档案服务单元测试：覆盖 BR-SUP-01（名称唯一、电话格式、敏感字段脱敏）、
 * BR-SUP-03（编码 S+5位流水生成）、FR-SUP-01（账期天数必填）、
 * FR-SUP-02（业务统计回填）、FR-SUP-04（供货商品全量覆盖、默认供应商唯一）。
 */
@ExtendWith(MockitoExtension.class)
class SupplierServiceTest {

    @Mock private SupplierMapper supplierMapper;
    @Mock private SupplierGoodsMapper supplierGoodsMapper;
    @Mock private GoodsMapper goodsMapper;
    @Mock private com.smms.sys.service.SysDictService dictService;

    private SupplierService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Supplier.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SupplierGoods.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Goods.class);
        service = new SupplierService(supplierMapper, supplierGoodsMapper, goodsMapper, dictService);
    }

    // ==================== 新增（FR-SUP-01 / BR-SUP-03） ====================

    @Test
    @DisplayName("新增供应商：首条编码 S00001，初始启用且累计采购额为 0")
    void createFirstGeneratesS00001() {
        when(supplierMapper.selectCount(any())).thenReturn(0L);
        when(supplierMapper.selectLastSupplierCodeForUpdate()).thenReturn(null);
        when(supplierMapper.insert(any(Supplier.class))).thenAnswer(inv -> {
            inv.getArgument(0, Supplier.class).setId(1L);
            return 1;
        });

        SupplierSaveRequest req = new SupplierSaveRequest();
        req.setName("华东饮品");
        req.setPhone("13812345678");
        req.setSettleType("MONTHLY");
        req.setLeadTimeDays(5);
        SupplierListVO vo = service.create(req, "张店长");

        assertThat(vo.getSupplierCode()).isEqualTo("S00001");
        assertThat(vo.getStatus()).isEqualTo(1);
        assertThat(vo.getTotalPurchaseAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(vo.getLeadTimeDays()).isEqualTo(5);
        assertThat(vo.getSettleType()).isEqualTo("MONTHLY");
        assertThat(vo.getCreditDays()).isNull();
        assertThat(vo.getBankAccount()).isNull(); // 未录入账号 → 脱敏后仍为 null
    }

    @Test
    @DisplayName("新增供应商：编码从当前最大号 +1（BR-SUP-03）")
    void createIncrementsCode() {
        when(supplierMapper.selectCount(any())).thenReturn(0L);
        when(supplierMapper.selectLastSupplierCodeForUpdate()).thenReturn("S00099");
        when(supplierMapper.insert(any(Supplier.class))).thenReturn(1);

        SupplierSaveRequest req = new SupplierSaveRequest();
        req.setName("第二供应商");
        SupplierListVO vo = service.create(req, "张店长");
        assertThat(vo.getSupplierCode()).isEqualTo("S00100");
    }

    @Test
    @DisplayName("新增供应商：名称重复被拒（BR-SUP-01）")
    void createRejectsDuplicateName() {
        when(supplierMapper.selectCount(any())).thenReturn(1L);

        SupplierSaveRequest req = new SupplierSaveRequest();
        req.setName("华东饮品");
        assertThatThrownBy(() -> service.create(req, "张店长"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SUPPLIER_NAME_EXISTS.getCode());
    }

    @Test
    @DisplayName("GAP-P1-09：结算方式须为启用中的数据字典项（停用项不可被新档案选择）")
    void createRejectsDisabledSettleType() {
        when(supplierMapper.selectCount(any())).thenReturn(0L);
        when(dictService.enabledValues("settle_type")).thenReturn(java.util.Set.of("MONTHLY", "CREDIT"));

        SupplierSaveRequest req = new SupplierSaveRequest();
        req.setName("停用结算方式供应商");
        req.setSettleType("CASH"); // 字典未启用 → 视为停用项
        assertThatThrownBy(() -> service.create(req, "张店长"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("settle_type");
    }

    @Test
    @DisplayName("新增供应商：固话格式合法通过（BR-SUP-01）")
    void createAcceptsLandlinePhone() {        when(supplierMapper.selectCount(any())).thenReturn(0L);
        when(supplierMapper.selectLastSupplierCodeForUpdate()).thenReturn(null);
        when(supplierMapper.insert(any(Supplier.class))).thenReturn(1);

        SupplierSaveRequest req = new SupplierSaveRequest();
        req.setName("固话供应商");
        req.setPhone("0755-1234567");
        service.create(req, "张店长");
    }

    @Test
    @DisplayName("新增供应商：电话格式非法被拒（BR-SUP-01）")
    void createRejectsInvalidPhone() {
        when(supplierMapper.selectCount(any())).thenReturn(0L);

        SupplierSaveRequest req = new SupplierSaveRequest();
        req.setName("无效电话供应商");
        req.setPhone("12345");
        assertThatThrownBy(() -> service.create(req, "张店长"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SUPPLIER_PHONE_INVALID.getCode());
    }

    @Test
    @DisplayName("新增供应商：账期结算缺账期天数被拒（FR-SUP-01）")
    void createRejectsCreditWithoutDays() {
        when(supplierMapper.selectCount(any())).thenReturn(0L);

        SupplierSaveRequest req = new SupplierSaveRequest();
        req.setName("账期供应商");
        req.setSettleType("CREDIT");
        assertThatThrownBy(() -> service.create(req, "张店长"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SUPPLIER_CREDIT_DAYS_REQUIRED.getCode());
    }

    @Test
    @DisplayName("新增供应商：银行账号对无敏感权限上下文脱敏展示（BR-SUP-01，FR-SYS-09）")
    void createMasksBankAccountWithoutPrivilege() {
        when(supplierMapper.selectCount(any())).thenReturn(0L);
        when(supplierMapper.selectLastSupplierCodeForUpdate()).thenReturn(null);
        ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
        when(supplierMapper.insert(captor.capture())).thenReturn(1);

        SupplierSaveRequest req = new SupplierSaveRequest();
        req.setName("华东饮品");
        req.setBankAccount("6222020200112233445");
        req.setTaxNo("91310000MA1FL0000X");
        SupplierListVO vo = service.create(req, "张店长");

        // 库中保存明文
        assertThat(captor.getValue().getBankAccount()).isEqualTo("6222020200112233445");
        // 未登录/无权限上下文：账号脱敏、识别号不返回
        assertThat(vo.getBankAccount()).isEqualTo("****3445");
        assertThat(vo.getTaxNo()).isNull();
    }

    // ==================== 查询统计（FR-SUP-02） ====================

    @Test
    @DisplayName("分页查询：回填在途/未结算/最近合作/供货商品数统计")
    void pageFillsBusinessStats() {
        Supplier s = new Supplier();
        s.setId(7L);
        s.setSupplierCode("S00007");
        s.setName("华东饮品");
        s.setSettleType("CREDIT");
        s.setCreditDays(30);
        s.setLeadTimeDays(3);
        s.setTotalPurchaseAmount(new BigDecimal("12000.50"));
        s.setStatus(1);
        Page<Supplier> page = new Page<>(1, 20);
        page.setRecords(List.of(s));
        page.setTotal(1);
        when(supplierMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        SupplierStatVO stat = new SupplierStatVO();
        stat.setSupplierId(7L);
        stat.setInTransitCount(2L);
        stat.setUnsettledAmount(new BigDecimal("3000.00"));
        stat.setLastCoopTime(LocalDateTime.of(2026, 9, 20, 10, 0));
        stat.setGoodsCount(15L);
        when(supplierMapper.selectStatsBySupplierIds(List.of(7L))).thenReturn(List.of(stat));

        PageVO<SupplierListVO> result = service.page(new SupplierQuery(null, null, null, null, null, null, 1, 20));
        SupplierListVO vo = result.list().get(0);
        assertThat(vo.getInTransitCount()).isEqualTo(2L);
        assertThat(vo.getUnsettledAmount()).isEqualByComparingTo(new BigDecimal("3000.00"));
        assertThat(vo.getLastCoopTime()).isEqualTo(LocalDateTime.of(2026, 9, 20, 10, 0));
        assertThat(vo.getGoodsCount()).isEqualTo(15L);
    }

    @Test
    @DisplayName("详情：供应商不存在抛业务异常")
    void detailRejectsMissing() {
        when(supplierMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.detail(99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SUPPLIER_NOT_FOUND.getCode());
    }

    // ==================== 供货商品（FR-SUP-04） ====================

    private SupplierGoodsSaveRequest.Item item(Long goodsId, String price, int isDefault) {
        SupplierGoodsSaveRequest.Item it = new SupplierGoodsSaveRequest.Item();
        it.setGoodsId(goodsId);
        it.setSupplyPrice(price == null ? null : new BigDecimal(price));
        it.setIsDefault(isDefault);
        return it;
    }

    @Test
    @DisplayName("维护供货商品：全量覆盖并保留入库回写的最近供货时间")
    void saveGoodsOverwritesAndKeepsLastSupplyTime() {
        Supplier s = new Supplier();
        s.setId(1L);
        when(supplierMapper.selectById(1L)).thenReturn(s);
        when(goodsMapper.selectCount(any())).thenReturn(2L);

        SupplierGoods old = new SupplierGoods();
        old.setSupplierId(1L);
        old.setGoodsId(100L);
        old.setLastSupplyTime(LocalDateTime.of(2026, 9, 1, 8, 0));
        when(supplierGoodsMapper.selectList(any(Wrapper.class))).thenReturn(List.of(old));

        SupplierGoodsSaveRequest req = new SupplierGoodsSaveRequest();
        req.setItems(List.of(item(100L, "12.50", 1), item(200L, null, 0)));
        service.saveGoods(1L, req, "李采购");

        // 先删旧关联再插入
        verify(supplierGoodsMapper).delete(any(Wrapper.class));
        ArgumentCaptor<SupplierGoods> captor = ArgumentCaptor.forClass(SupplierGoods.class);
        verify(supplierGoodsMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        List<SupplierGoods> inserted = captor.getAllValues();
        assertThat(inserted).extracting(SupplierGoods::getGoodsId)
                .containsExactlyInAnyOrder(100L, 200L);
        // 100 的最近供货时间保留；200 无历史为 null
        assertThat(inserted.stream().filter(g -> g.getGoodsId().equals(100L)).findFirst().orElseThrow()
                .getLastSupplyTime()).isEqualTo(LocalDateTime.of(2026, 9, 1, 8, 0));
        assertThat(inserted.stream().filter(g -> g.getGoodsId().equals(200L)).findFirst().orElseThrow()
                .getLastSupplyTime()).isNull();
        // 默认供应商唯一：清除该商品在其他供应商的默认标记
        verify(supplierGoodsMapper).update(eq(null), any(Wrapper.class));
    }

    @Test
    @DisplayName("维护供货商品：清单内商品重复被拒")
    void saveGoodsRejectsDuplicatedGoods() {
        Supplier s = new Supplier();
        s.setId(1L);
        when(supplierMapper.selectById(1L)).thenReturn(s);

        SupplierGoodsSaveRequest req = new SupplierGoodsSaveRequest();
        req.setItems(List.of(item(100L, "1.00", 0), item(100L, "2.00", 0)));
        assertThatThrownBy(() -> service.saveGoods(1L, req, "李采购"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SUPPLIER_GOODS_DUPLICATED.getCode());
        verify(supplierGoodsMapper, never()).delete(any(Wrapper.class));
    }

    @Test
    @DisplayName("维护供货商品：商品不存在被拒")
    void saveGoodsRejectsMissingGoods() {
        Supplier s = new Supplier();
        s.setId(1L);
        when(supplierMapper.selectById(1L)).thenReturn(s);
        // 传 2 个商品但库中只匹配到 1 个
        when(goodsMapper.selectCount(any())).thenReturn(1L);

        SupplierGoodsSaveRequest req = new SupplierGoodsSaveRequest();
        req.setItems(List.of(item(100L, "1.00", 0), item(200L, "2.00", 0)));
        assertThatThrownBy(() -> service.saveGoods(1L, req, "李采购"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SUPPLIER_GOODS_INVALID.getCode());
    }

    @Test
    @DisplayName("维护供货商品：空清单 = 清空该供应商全部关联")
    void saveGoodsWithEmptyListClearsAll() {
        Supplier s = new Supplier();
        s.setId(1L);
        when(supplierMapper.selectById(1L)).thenReturn(s);
        when(supplierGoodsMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.saveGoods(1L, new SupplierGoodsSaveRequest(), "李采购");
        verify(supplierGoodsMapper).delete(any(Wrapper.class));
        verify(supplierGoodsMapper, never()).insert(any(SupplierGoods.class));
    }

    // ==================== 停用/启用（FR-SUP-03） ====================

    @Test
    @DisplayName("停用供应商：档案保留仅更新状态")
    void changeStatusUpdatesOnlyStatus() {
        Supplier s = new Supplier();
        s.setId(1L);
        s.setName("华东饮品");
        s.setStatus(1);
        when(supplierMapper.selectById(1L)).thenReturn(s);

        service.changeStatus(1L, 0);

        ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(0);
        assertThat(captor.getValue().getName()).isEqualTo("华东饮品");
    }

    @Test
    @DisplayName("编辑供应商：名称查重排除自身")
    void updateExcludesSelfInNameCheck() {
        Supplier old = new Supplier();
        old.setId(1L);
        old.setName("华东饮品");
        old.setSupplierCode("S00001");
        old.setLeadTimeDays(3);
        old.setStatus(1);
        when(supplierMapper.selectById(1L)).thenReturn(old);
        // 第二次 selectCount（排除自身）返回 0
        lenient().when(supplierMapper.selectCount(any())).thenReturn(0L);

        SupplierSaveRequest req = new SupplierSaveRequest();
        req.setName("华东饮品");
        req.setSettleType("CREDIT");
        req.setCreditDays(30);
        req.setLeadTimeDays(3);
        service.update(1L, req, "李采购");
        verify(supplierMapper).update(eq(null), any(Wrapper.class));
    }
}

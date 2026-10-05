package com.smms.inv;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.goods.entity.Goods;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.inv.dto.LossAuditRequest;
import com.smms.inv.dto.LossOrderCreateRequest;
import com.smms.inv.dto.LossOrderVO;
import com.smms.inv.dto.StockChange;
import com.smms.inv.entity.Inventory;
import com.smms.inv.entity.LossOrder;
import com.smms.inv.mapper.InventoryMapper;
import com.smms.inv.mapper.LossOrderMapper;
import com.smms.inv.service.InventoryService;
import com.smms.inv.service.LossOrderService;
import com.smms.sys.entity.SysDict;
import com.smms.sys.entity.SysDictItem;
import com.smms.sys.mapper.SysDictItemMapper;
import com.smms.sys.mapper.SysDictMapper;
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
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报损/报溢登记单元测试（FR-INV-10）：
 * 登记校验（类型/数量/原因字典启用项/报溢成本单价必填）；
 * 审核通过生成 LOSS_OUT/OVERFLOW_IN 流水（报损按当前成本结转并定稿金额，
 * 报溢按当前成本入账不重算均价 §8.2）；报损不足即拒绝（BR-INV-02）；
 * 驳回意见必填；并发状态冲突防护。
 */
@ExtendWith(MockitoExtension.class)
class LossOrderServiceTest {

    private static final long GOODS_ID = 9L;
    private static final long ORDER_ID = 1L;
    private static final String APPLIER = "keeper01";
    private static final String AUDITOR = "manager01";

    @Mock private LossOrderMapper lossOrderMapper;
    @Mock private GoodsMapper goodsMapper;
    @Mock private InventoryMapper inventoryMapper;
    @Mock private InventoryService inventoryService;
    @Mock private SysDictMapper dictMapper;
    @Mock private SysDictItemMapper dictItemMapper;

    private LossOrderService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), LossOrder.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysDict.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysDictItem.class);
        service = new LossOrderService(lossOrderMapper, goodsMapper, inventoryMapper,
                inventoryService, dictMapper, dictItemMapper);
    }

    // ---------- 造数 ----------

    private Goods goods() {
        Goods goods = new Goods();
        goods.setId(GOODS_ID);
        goods.setName("可乐 330ml");
        return goods;
    }

    private Inventory inv(String quantity, String costPrice) {
        Inventory inventory = new Inventory();
        inventory.setGoodsId(GOODS_ID);
        inventory.setStoreId(1L);
        inventory.setQuantity(new BigDecimal(quantity));
        inventory.setCostPrice(new BigDecimal(costPrice));
        return inventory;
    }

    private SysDict lossReasonDict() {
        SysDict dict = new SysDict();
        dict.setId(3L);
        dict.setDictType("loss_reason");
        dict.setStatus(1);
        return dict;
    }

    private LossOrder pendingOrder(String bizType, String quantity, String costPrice) {
        LossOrder order = new LossOrder();
        order.setId(ORDER_ID);
        order.setOrderNo(("LOSS".equals(bizType) ? "BS" : "BY") + "202610050001");
        order.setStoreId(1L);
        order.setBizType(bizType);
        order.setGoodsId(GOODS_ID);
        order.setQuantity(new BigDecimal(quantity));
        order.setCostPrice(new BigDecimal(costPrice));
        order.setAmount(new BigDecimal(quantity).multiply(new BigDecimal(costPrice)));
        order.setReason("EXPIRED");
        order.setApplyName(APPLIER);
        order.setStatus("PENDING");
        return order;
    }

    private LossOrderVO vo(String status) {
        return new LossOrderVO(ORDER_ID, "BS202610050001", "LOSS", GOODS_ID, "6901", null,
                "可乐 330ml", "饮料", "瓶", "330ml", 0, new BigDecimal("3"), new BigDecimal("2.5"),
                new BigDecimal("7.50"), "EXPIRED", "过期", APPLIER, status, AUDITOR,
                LocalDateTime.now(), null, null, LocalDateTime.now());
    }

    /** 登记公共桩：商品存在、原因字典选中项启用、单号取号无当日单据、insert 回填自增 ID、详情回读 */
    private void stubCreateDependencies() {
        when(goodsMapper.selectById(GOODS_ID)).thenReturn(goods());
        when(dictMapper.selectOne(any())).thenReturn(lossReasonDict());
        when(dictItemMapper.selectCount(any())).thenReturn(1L);
        when(lossOrderMapper.selectList(any())).thenReturn(List.of());
        when(lossOrderMapper.insert(any(LossOrder.class))).thenAnswer(inv -> {
            ((LossOrder) inv.getArgument(0)).setId(ORDER_ID);
            return 1;
        });
        when(lossOrderMapper.selectLossDetail(any())).thenReturn(vo("PENDING"));
    }

    private LossOrder capturedInsert() {
        ArgumentCaptor<LossOrder> captor = ArgumentCaptor.forClass(LossOrder.class);
        verify(lossOrderMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ---------- 登记（FR-INV-10） ----------

    @Test
    @DisplayName("登记报损：成本按当前成本快照，金额=数量×成本，单号 BS+日期+万级流水，状态待审核")
    void create_loss_snapshotsCurrentCost() {
        stubCreateDependencies();
        when(inventoryMapper.selectById(GOODS_ID)).thenReturn(inv("10", "2.5000"));

        service.create(new LossOrderCreateRequest("LOSS", GOODS_ID, new BigDecimal("3"),
                null, "EXPIRED", " 过期清理 "), APPLIER);

        LossOrder order = capturedInsert();
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        assertThat(order.getOrderNo()).isEqualTo("BS" + today + "0001");
        assertThat(order.getStoreId()).isEqualTo(1L);
        assertThat(order.getBizType()).isEqualTo("LOSS");
        assertThat(order.getQuantity()).isEqualByComparingTo("3");
        assertThat(order.getCostPrice()).isEqualByComparingTo("2.5");
        assertThat(order.getAmount()).isEqualByComparingTo("7.50");
        assertThat(order.getReason()).isEqualTo("EXPIRED");
        assertThat(order.getApplyName()).isEqualTo(APPLIER);
        assertThat(order.getStatus()).isEqualTo("PENDING");
        assertThat(order.getRemark()).isEqualTo("过期清理");
    }

    @Test
    @DisplayName("登记报损：商品从未发生变动（无结存行）时成本按 0 快照")
    void create_loss_missingInventoryRow() {
        stubCreateDependencies();
        when(inventoryMapper.selectById(GOODS_ID)).thenReturn(null);

        service.create(new LossOrderCreateRequest("LOSS", GOODS_ID, new BigDecimal("1"),
                null, "DAMAGED", null), APPLIER);

        LossOrder order = capturedInsert();
        assertThat(order.getCostPrice()).isEqualByComparingTo("0");
        assertThat(order.getAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("登记报溢：成本单价必录入（4位小数规范化），金额=数量×录入价")
    void create_overflow_usesEnteredCost() {
        stubCreateDependencies();

        service.create(new LossOrderCreateRequest("OVERFLOW", GOODS_ID, new BigDecimal("2"),
                new BigDecimal("3.2"), "OTHER", null), APPLIER);

        LossOrder order = capturedInsert();
        assertThat(order.getOrderNo()).startsWith("BY");
        assertThat(order.getBizType()).isEqualTo("OVERFLOW");
        assertThat(order.getCostPrice()).isEqualByComparingTo("3.2000");
        assertThat(order.getAmount()).isEqualByComparingTo("6.40");
        // 报损成本快照仅针对 LOSS，报溢不回读结存行
        verify(inventoryMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("登记报溢：未录入成本单价或小于等于 0 时拒绝（FR-INV-10）")
    void create_overflow_costRequired() {
        when(goodsMapper.selectById(GOODS_ID)).thenReturn(goods());
        when(dictMapper.selectOne(any())).thenReturn(lossReasonDict());
        when(dictItemMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(new LossOrderCreateRequest("OVERFLOW", GOODS_ID,
                new BigDecimal("2"), null, "OTHER", null), APPLIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("报溢必须录入大于 0 的成本单价");
        assertThatThrownBy(() -> service.create(new LossOrderCreateRequest("OVERFLOW", GOODS_ID,
                new BigDecimal("2"), BigDecimal.ZERO, "OTHER", null), APPLIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("成本单价");
        verify(lossOrderMapper, never()).insert(any(LossOrder.class));
    }

    @Test
    @DisplayName("登记：原因须为启用中的 loss_reason 字典项，停用/不存在即拒绝（FR-SYS-07）")
    void create_reasonMustBeEnabledDictItem() {
        when(goodsMapper.selectById(GOODS_ID)).thenReturn(goods());
        when(dictMapper.selectOne(any())).thenReturn(lossReasonDict());
        when(dictItemMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.create(new LossOrderCreateRequest("LOSS", GOODS_ID,
                BigDecimal.ONE, null, "UNKNOWN", null), APPLIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("原因不合法");
        verify(lossOrderMapper, never()).insert(any(LossOrder.class));
    }

    @Test
    @DisplayName("登记：类型/数量/原因基础校验")
    void create_basicValidation() {
        assertThatThrownBy(() -> service.create(new LossOrderCreateRequest("SALE", GOODS_ID,
                BigDecimal.ONE, null, "EXPIRED", null), APPLIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的业务类型");

        when(goodsMapper.selectById(GOODS_ID)).thenReturn(goods());
        assertThatThrownBy(() -> service.create(new LossOrderCreateRequest("LOSS", GOODS_ID,
                BigDecimal.ZERO, null, "EXPIRED", null), APPLIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("数量必须大于 0");
        assertThatThrownBy(() -> service.create(new LossOrderCreateRequest("LOSS", GOODS_ID,
                new BigDecimal("1.2345"), null, "EXPIRED", null), APPLIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多 3 位小数");
        assertThatThrownBy(() -> service.create(new LossOrderCreateRequest("LOSS", GOODS_ID,
                BigDecimal.ONE, null, "  ", null), APPLIER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("原因不能为空");
    }

    // ---------- 审核（FR-INV-10） ----------

    @Test
    @DisplayName("审核通过（报损）：生成 LOSS_OUT 流水（BS+单号），金额按实际结转成本定稿")
    void audit_approveLoss_generatesLOSS_OUT() {
        LossOrder order = pendingOrder("LOSS", "3", "2.5000");
        when(lossOrderMapper.selectById(ORDER_ID)).thenReturn(order);
        // 审核时点当前成本已变为 2.4，单据金额按流水实际结转成本定稿（BR-INV-06）
        when(inventoryService.stockOut(any())).thenReturn(new BigDecimal("2.4"));
        when(lossOrderMapper.update(any(), any())).thenReturn(1);
        when(lossOrderMapper.selectLossDetail(ORDER_ID)).thenReturn(vo("AUDITED"));

        service.audit(ORDER_ID, new LossAuditRequest(true, "同意报废"), AUDITOR);

        ArgumentCaptor<StockChange> changeCaptor = ArgumentCaptor.forClass(StockChange.class);
        verify(inventoryService).stockOut(changeCaptor.capture());
        StockChange change = changeCaptor.getValue();
        assertThat(change.recordType()).isEqualTo("LOSS_OUT");
        assertThat(change.goodsId()).isEqualTo(GOODS_ID);
        assertThat(change.qty()).isEqualByComparingTo("3");
        assertThat(change.unitCost()).isNull();
        assertThat(change.bizType()).isEqualTo("BS");
        assertThat(change.bizNo()).isEqualTo("BS202610050001");
        assertThat(change.operator()).isEqualTo(AUDITOR);
        assertThat(change.remark()).contains("同意报废");

        ArgumentCaptor<LossOrder> patchCaptor = ArgumentCaptor.forClass(LossOrder.class);
        verify(lossOrderMapper).update(patchCaptor.capture(), any());
        LossOrder patch = patchCaptor.getValue();
        assertThat(patch.getStatus()).isEqualTo("AUDITED");
        assertThat(patch.getAuditorName()).isEqualTo(AUDITOR);
        assertThat(patch.getCostPrice()).isEqualByComparingTo("2.4");
        assertThat(patch.getAmount()).isEqualByComparingTo("7.20");
        assertThat(patch.getAuditRemark()).isEqualTo("同意报废");
    }

    @Test
    @DisplayName("审核通过（报溢）：生成 OVERFLOW_IN 流水（BY+单号），成本按当前成本入账不改单据金额（§8.2）")
    void audit_approveOverflow_generatesOVERFLOW_IN() {
        LossOrder order = pendingOrder("OVERFLOW", "5", "3.0000");
        when(lossOrderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(lossOrderMapper.update(any(), any())).thenReturn(1);
        when(lossOrderMapper.selectLossDetail(ORDER_ID)).thenReturn(vo("AUDITED"));

        service.audit(ORDER_ID, new LossAuditRequest(true, null), AUDITOR);

        ArgumentCaptor<StockChange> changeCaptor = ArgumentCaptor.forClass(StockChange.class);
        verify(inventoryService).stockIn(changeCaptor.capture());
        StockChange change = changeCaptor.getValue();
        assertThat(change.recordType()).isEqualTo("OVERFLOW_IN");
        assertThat(change.qty()).isEqualByComparingTo("5");
        // 库存服务按结存行当前成本入账：不传单价、不重算移动加权（§8.2）
        assertThat(change.unitCost()).isNull();
        assertThat(change.bizType()).isEqualTo("BY");
        assertThat(change.bizNo()).isEqualTo("BY202610050001");
        verify(inventoryService, never()).stockOut(any());

        ArgumentCaptor<LossOrder> patchCaptor = ArgumentCaptor.forClass(LossOrder.class);
        verify(lossOrderMapper).update(patchCaptor.capture(), any());
        LossOrder patch = patchCaptor.getValue();
        assertThat(patch.getStatus()).isEqualTo("AUDITED");
        // 报溢单据成本/金额以录入为准，审核不改写
        assertThat(patch.getCostPrice()).isNull();
        assertThat(patch.getAmount()).isNull();
    }

    @Test
    @DisplayName("审核通过（报损）：库存不足且禁止负库存时拒绝，单据不变更（BR-INV-02）")
    void audit_lossInsufficientStock_rejected() {
        LossOrder order = pendingOrder("LOSS", "99", "2.5000");
        when(lossOrderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(inventoryService.stockOut(any())).thenThrow(
                new BusinessException(ErrorCode.STOCK_NOT_ENOUGH, "商品【可乐 330ml】库存不足"));

        assertThatThrownBy(() -> service.audit(ORDER_ID, new LossAuditRequest(true, null), AUDITOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存不足");

        verify(lossOrderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("驳回：意见必填；驳回后不产生库存变动（货损审计链保留）")
    void audit_reject_remarkRequired() {
        LossOrder order = pendingOrder("LOSS", "3", "2.5000");
        when(lossOrderMapper.selectById(ORDER_ID)).thenReturn(order);

        assertThatThrownBy(() -> service.audit(ORDER_ID, new LossAuditRequest(false, " "), AUDITOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("驳回意见不能为空");

        when(lossOrderMapper.update(any(), any())).thenReturn(1);
        when(lossOrderMapper.selectLossDetail(ORDER_ID)).thenReturn(vo("REJECTED"));
        service.audit(ORDER_ID, new LossAuditRequest(false, "数量与实物不符"), AUDITOR);

        ArgumentCaptor<LossOrder> patchCaptor = ArgumentCaptor.forClass(LossOrder.class);
        verify(lossOrderMapper).update(patchCaptor.capture(), any());
        assertThat(patchCaptor.getValue().getStatus()).isEqualTo("REJECTED");
        assertThat(patchCaptor.getValue().getAuditRemark()).isEqualTo("数量与实物不符");
        verify(inventoryService, never()).stockIn(any());
        verify(inventoryService, never()).stockOut(any());
    }

    @Test
    @DisplayName("审核：仅待审核状态可审核（已审核/已驳回均拒绝）")
    void audit_statusGuard() {
        LossOrder order = pendingOrder("LOSS", "3", "2.5000");
        order.setStatus("AUDITED");
        when(lossOrderMapper.selectById(ORDER_ID)).thenReturn(order);

        assertThatThrownBy(() -> service.audit(ORDER_ID, new LossAuditRequest(true, null), AUDITOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅待审核的单据可审核");

        verify(inventoryService, never()).stockOut(any());
    }

    @Test
    @DisplayName("审核：条件更新失败（并发重复审核）返回冲突")
    void audit_concurrentConflict() {
        LossOrder order = pendingOrder("LOSS", "3", "2.5000");
        when(lossOrderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(inventoryService.stockOut(any())).thenReturn(new BigDecimal("2.5"));
        when(lossOrderMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.audit(ORDER_ID, new LossAuditRequest(true, null), AUDITOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请刷新后重试");
    }
}

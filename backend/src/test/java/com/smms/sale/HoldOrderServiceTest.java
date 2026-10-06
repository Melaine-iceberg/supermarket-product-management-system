package com.smms.sale;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.sale.dto.HoldCreateCommand;
import com.smms.sale.dto.HoldDetailVO;
import com.smms.sale.dto.HoldOrderVO;
import com.smms.sale.entity.HoldOrder;
import com.smms.sale.mapper.HoldOrderMapper;
import com.smms.sale.service.HoldOrderService;
import com.smms.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 挂单服务单元测试（FR-SALE-08）：件数/金额摘要后端计算（改价按改价价、称重计 1 件）、
 * 明细快照落库与取单还原、状态条件更新防并发取单、上限与校验、24 小时超时清理（IR-SW-06）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HoldOrderServiceTest {

    private static final LoginUser CASHIER = new LoginUser(5L, "cashier", "陈收银",
            List.of("CASHIER"), List.of("收银员"), Set.of("sys:cashier:enter"), null, false);

    @Mock private HoldOrderMapper holdMapper;

    private HoldOrderService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), HoldOrder.class);
        service = new HoldOrderService(holdMapper, JsonMapper.builder().build());
    }

    // ---------- 造数 ----------

    private HoldCreateCommand.HoldItem normal(long goodsId, String retail, String qty,
                                              String override, String reason) {
        return new HoldCreateCommand.HoldItem("NORMAL", goodsId, "商品" + goodsId, "规格", "690000000000" + goodsId,
                "瓶", new BigDecimal(retail), 0, new BigDecimal(qty), null,
                override == null ? null : new BigDecimal(override), reason);
    }

    private HoldCreateCommand cmdWith(HoldCreateCommand.HoldItem... items) {
        return new HoldCreateCommand(1, null, null, null, null, null, null, List.of(items));
    }

    // ---------- 挂单 ----------

    @Test
    @DisplayName("挂单：件数/金额由后端计算（改价按改价价、称重计1件），明细与折扣信息落库（FR-SALE-08）")
    void create_summaryAndSnapshot() {
        when(holdMapper.selectCount(any())).thenReturn(0L);

        HoldCreateCommand cmd = new HoldCreateCommand(3, 10L, "张三", new BigDecimal("0.9"), "店庆促销",
                "manager", "常客托付",
                List.of(
                        new HoldCreateCommand.HoldItem("NORMAL", 1L, "可口可乐 500ml", "500ml", "2000000000015",
                                "瓶", new BigDecimal("3.80"), 0, new BigDecimal("2"), null,
                                new BigDecimal("2.50"), "临期折让"),
                        new HoldCreateCommand.HoldItem("NORMAL", 2L, "红富士苹果", null, null,
                                "kg", new BigDecimal("12.00"), 1, new BigDecimal("0.556"), null, null, null),
                        new HoldCreateCommand.HoldItem("TEMP", null, "散装糖果", null, null,
                                "袋", new BigDecimal("15.00"), 0, BigDecimal.ONE, "客制", null, null)));

        HoldOrderVO vo = service.create(cmd, CASHIER);

        assertThat(vo.itemCount()).isEqualTo(4);
        // 2.50×2 + 12.00×0.556 + 15.00 = 26.672 → 26.67
        assertThat(vo.amount()).isEqualByComparingTo("26.67");
        assertThat(vo.memberName()).isEqualTo("张三");

        ArgumentCaptor<HoldOrder> captor = ArgumentCaptor.forClass(HoldOrder.class);
        verify(holdMapper).insert(captor.capture());
        HoldOrder saved = captor.getValue();
        assertThat(saved.getCashierId()).isEqualTo(5L);
        assertThat(saved.getCashierName()).isEqualTo("陈收银");
        assertThat(saved.getStatus()).isEqualTo(HoldOrder.STATUS_ACTIVE);
        assertThat(saved.getOrderRate()).isEqualByComparingTo("0.9");
        assertThat(saved.getDiscountReason()).isEqualTo("店庆促销");
        assertThat(saved.getItemsJson())
                .contains("临期折让").contains("2.50").contains("散装糖果");
    }

    @Test
    @DisplayName("挂单上限：未取挂单达 50 张拒绝（每收银员，临时表防膨胀）")
    void create_limitExceeded() {
        when(holdMapper.selectCount(any())).thenReturn(50L);

        assertThatThrownBy(() -> service.create(cmdWith(normal(1L, "3.80", "1", null, null)), CASHIER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.HOLD_LIMIT_EXCEED.getCode());
        verify(holdMapper, never()).insert(any(HoldOrder.class));
    }

    @Test
    @DisplayName("挂单校验：无码改价/档案缺商品ID/改价缺原因均拒绝（FR-SALE-07/13）")
    void create_itemValidation() {
        when(holdMapper.selectCount(any())).thenReturn(0L);

        // 无码商品不支持改价（结算同样口径）
        HoldCreateCommand.HoldItem tempOverride = new HoldCreateCommand.HoldItem("TEMP", null, "散装点心",
                null, null, "袋", new BigDecimal("10.00"), 0, BigDecimal.ONE, null,
                new BigDecimal("8.00"), "议价");
        assertThatThrownBy(() -> service.create(cmdWith(tempOverride), CASHIER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.HOLD_ITEM_INVALID.getCode());

        // 档案商品必须携带商品ID
        HoldCreateCommand.HoldItem noGoodsId = new HoldCreateCommand.HoldItem("NORMAL", null, "无ID商品",
                null, null, "瓶", new BigDecimal("3.80"), 0, BigDecimal.ONE, null, null, null);
        assertThatThrownBy(() -> service.create(cmdWith(noGoodsId), CASHIER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.HOLD_ITEM_INVALID.getCode());

        // 改价必须填写原因
        assertThatThrownBy(() -> service.create(cmdWith(normal(1L, "3.80", "1", "2.50", null)), CASHIER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.HOLD_ITEM_INVALID.getCode());

        verify(holdMapper, never()).insert(any(HoldOrder.class));
    }

    // ---------- 取单 / 删除 ----------

    @Test
    @DisplayName("取单：ACTIVE→TAKEN 条件更新后返回明细快照供恢复购物车（FR-SALE-08）")
    void take_success() {
        when(holdMapper.update(any(), any())).thenReturn(1);
        HoldOrder hold = new HoldOrder();
        hold.setId(7L);
        hold.setItemsJson(JsonMapper.builder().build().writeValueAsString(List.of(
                new HoldDetailVO.Item("NORMAL", 1L, "可口可乐 500ml", "500ml", "2000000000015",
                        "瓶", new BigDecimal("3.80"), 0, new BigDecimal("2"), null,
                        new BigDecimal("2.50"), "临期折让"))));
        hold.setOrderRate(new BigDecimal("0.9"));
        hold.setMemberName("张三");
        when(holdMapper.selectById(7L)).thenReturn(hold);

        HoldDetailVO detail = service.take(7L, CASHIER);

        assertThat(detail.items()).hasSize(1);
        assertThat(detail.items().get(0).name()).isEqualTo("可口可乐 500ml");
        assertThat(detail.items().get(0).overridePrice()).isEqualByComparingTo("2.50");
        assertThat(detail.orderRate()).isEqualByComparingTo("0.9");
        assertThat(detail.memberName()).isEqualTo("张三");
    }

    @Test
    @DisplayName("取单防并发：条件更新未命中（已取/已删/非本人）提示挂单不存在（FR-SALE-08）")
    void take_conflict() {
        when(holdMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.take(7L, CASHIER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.HOLD_NOT_FOUND.getCode());
        verify(holdMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("删除挂单：ACTIVE→DELETED 条件更新，未命中提示不存在（FR-SALE-08）")
    void delete_byStateGuard() {
        when(holdMapper.update(any(), any())).thenReturn(1);
        service.delete(7L, CASHIER);
        verify(holdMapper).update(any(), any());

        when(holdMapper.update(any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.delete(8L, CASHIER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.HOLD_NOT_FOUND.getCode());
    }

    // ---------- 超时清理（IR-SW-06） ----------

    @Test
    @DisplayName("超时清理：删除 24 小时前的挂单（IR-SW-06）")
    void cleanupExpired_deletesOlderThan24h() {
        when(holdMapper.delete(any())).thenReturn(3);

        service.cleanupExpired();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<HoldOrder>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(holdMapper).delete(captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("hold_time");
        LocalDateTime cutoff = (LocalDateTime) wrapper.getParamNameValuePairs().values().iterator().next();
        assertThat(cutoff).isBetween(LocalDateTime.now().minusHours(24).minusMinutes(1),
                LocalDateTime.now().minusHours(24).plusMinutes(1));
    }
}

package com.smms.goods;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smms.common.exception.BusinessException;
import com.smms.goods.dto.PromotionCreateRequest;
import com.smms.goods.entity.Goods;
import com.smms.goods.entity.Promotion;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.goods.mapper.PromotionMapper;
import com.smms.goods.service.PromotionService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 促销服务单元测试（FR-GOODS-09）：
 * 促销价<零售价（BR-GOODS-03）、时间段不可重叠（BR-GOODS-04）、
 * 批量设置、停用幂等、到期自动失效（IR-SW-06）。
 */
@ExtendWith(MockitoExtension.class)
class PromotionServiceTest {

    @Mock private PromotionMapper promotionMapper;
    @Mock private GoodsMapper goodsMapper;

    private PromotionService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Promotion.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Goods.class);
        service = new PromotionService(promotionMapper, goodsMapper);
    }

    // ---------- 造数 ----------

    private Goods goods(long id, String retailPrice) {
        Goods g = new Goods();
        g.setId(id);
        g.setGoodsCode("050000" + id);
        g.setName("商品" + id);
        g.setRetailPrice(new BigDecimal(retailPrice));
        return g;
    }

    private PromotionCreateRequest createReq(List<Long> goodsIds, String promoPrice,
                                             LocalDateTime start, LocalDateTime end) {
        return new PromotionCreateRequest(goodsIds, new BigDecimal(promoPrice), start, end, "双11活动");
    }

    // ---------- 设置促销 ----------

    @Test
    @DisplayName("批量设置促销：全部校验通过后逐商品写入 ACTIVE 记录")
    void create_batch_ok() {
        LocalDateTime start = LocalDateTime.now().minusHours(1);
        LocalDateTime end = LocalDateTime.now().plusDays(3);
        when(goodsMapper.selectById(100L)).thenReturn(goods(100L, "10.00"));
        when(goodsMapper.selectById(101L)).thenReturn(goods(101L, "20.00"));
        when(promotionMapper.selectCount(any())).thenReturn(0L);

        service.create(createReq(List.of(100L, 101L), "8.50", start, end), "admin");

        ArgumentCaptor<Promotion> captor = ArgumentCaptor.forClass(Promotion.class);
        verify(promotionMapper, times(2)).insert(captor.capture());
        List<Promotion> inserted = captor.getAllValues();
        assertThat(inserted).extracting(Promotion::getGoodsId)
                .containsExactlyInAnyOrder(100L, 101L);
        inserted.forEach(p -> {
            assertThat(p.getPromoPrice()).isEqualByComparingTo("8.50");
            assertThat(p.getStartTime()).isEqualTo(start);
            assertThat(p.getEndTime()).isEqualTo(end);
            assertThat(p.getStatus()).isEqualTo("ACTIVE");
            assertThat(p.getCreateBy()).isEqualTo("admin");
            assertThat(p.getRemark()).isEqualTo("双11活动");
        });
    }

    @Test
    @DisplayName("BR-GOODS-03：促销价不低于零售价时拒绝并指明商品")
    void create_priceNotBelowRetail_rejected() {
        when(goodsMapper.selectById(100L)).thenReturn(goods(100L, "10.00"));

        assertThatThrownBy(() -> service.create(createReq(List.of(100L), "10.00",
                LocalDateTime.now(), LocalDateTime.now().plusDays(1)), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须低于零售价");
        verify(promotionMapper, never()).insert(any(Promotion.class));
    }

    @Test
    @DisplayName("时间区间非法：结束早于开始 / 结束早于当前时间 时拒绝")
    void create_timeRange_rejected() {
        LocalDateTime now = LocalDateTime.now();
        assertThatThrownBy(() -> service.create(
                createReq(List.of(100L), "8.50", now.plusDays(2), now.plusDays(1)), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("结束时间必须晚于开始时间");

        assertThatThrownBy(() -> service.create(
                createReq(List.of(100L), "8.50", now.minusDays(2), now.minusDays(1)), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("结束时间必须晚于当前时间");
        verify(promotionMapper, never()).insert(any(Promotion.class));
    }

    @Test
    @DisplayName("促销价精度超限（>2位小数）时拒绝")
    void create_priceScale_rejected() {
        assertThatThrownBy(() -> service.create(createReq(List.of(100L), "8.555",
                LocalDateTime.now(), LocalDateTime.now().plusDays(1)), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("精度超限");
    }

    @Test
    @DisplayName("BR-GOODS-04：与尚未结束的生效促销时间段重叠时拒绝")
    void create_overlap_rejected() {
        when(goodsMapper.selectById(100L)).thenReturn(goods(100L, "10.00"));
        when(promotionMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(createReq(List.of(100L), "8.50",
                LocalDateTime.now(), LocalDateTime.now().plusDays(1)), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在生效促销");
        verify(promotionMapper, never()).insert(any(Promotion.class));
    }

    @Test
    @DisplayName("任一商品不存在时整体拒绝，不产生部分写入")
    void create_goodsNotFound_rejected() {
        when(goodsMapper.selectById(100L)).thenReturn(goods(100L, "10.00"));
        when(goodsMapper.selectById(404L)).thenReturn(null);
        when(promotionMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.create(createReq(List.of(100L, 404L), "8.50",
                LocalDateTime.now(), LocalDateTime.now().plusDays(1)), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
        verify(promotionMapper, never()).insert(any(Promotion.class));
    }

    // ---------- 停用 ----------

    @Test
    @DisplayName("停用生效中的促销：状态置为 DISABLED")
    void disable_ok() {
        Promotion promotion = new Promotion();
        promotion.setId(1L);
        promotion.setGoodsId(100L);
        promotion.setStatus("ACTIVE");
        when(promotionMapper.selectById(1L)).thenReturn(promotion);

        service.disable(1L);

        ArgumentCaptor<Promotion> captor = ArgumentCaptor.forClass(Promotion.class);
        verify(promotionMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("DISABLED");
    }

    @Test
    @DisplayName("停用幂等：已过期/已停用的促销不再变更")
    void disable_idempotent() {
        Promotion promotion = new Promotion();
        promotion.setId(1L);
        promotion.setStatus("EXPIRED");
        when(promotionMapper.selectById(1L)).thenReturn(promotion);

        service.disable(1L);

        verify(promotionMapper, never()).updateById(any(Promotion.class));
    }

    @Test
    @DisplayName("停用不存在的促销返回 NOT_FOUND")
    void disable_notFound() {
        when(promotionMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.disable(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    // ---------- 查询与到期失效 ----------

    @Test
    @DisplayName("按商品查询促销记录")
    void listByGoods_ok() {
        Promotion p = new Promotion();
        p.setGoodsId(100L);
        p.setStatus("ACTIVE");
        when(promotionMapper.selectList(any())).thenReturn(List.of(p));

        List<Promotion> result = service.listByGoods(100L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getGoodsId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("IR-SW-06：到期任务将过期 ACTIVE 促销逐条置为 EXPIRED")
    void expireOutdated_updatesExpired() {
        Promotion expired = new Promotion();
        expired.setId(1L);
        expired.setGoodsId(100L);
        expired.setStatus("ACTIVE");
        expired.setEndTime(LocalDateTime.now().minusMinutes(5));
        when(promotionMapper.selectList(any())).thenReturn(List.of(expired));

        service.expireOutdated();

        ArgumentCaptor<Promotion> captor = ArgumentCaptor.forClass(Promotion.class);
        verify(promotionMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("EXPIRED");
    }
}

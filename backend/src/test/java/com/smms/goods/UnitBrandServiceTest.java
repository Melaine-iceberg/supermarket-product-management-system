package com.smms.goods;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smms.common.exception.BusinessException;
import com.smms.goods.dto.BrandSaveRequest;
import com.smms.goods.dto.UnitSaveRequest;
import com.smms.goods.entity.Goods;
import com.smms.goods.entity.Unit;
import com.smms.goods.mapper.BrandMapper;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.goods.mapper.UnitMapper;
import com.smms.goods.service.BrandService;
import com.smms.goods.service.UnitService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计量单位与品牌服务单元测试（FR-GOODS-08/13）：
 * 名称唯一、重量属性变更保护。
 */
@ExtendWith(MockitoExtension.class)
class UnitBrandServiceTest {

    @Mock private UnitMapper unitMapper;
    @Mock private GoodsMapper goodsMapper;
    @Mock private BrandMapper brandMapper;

    private UnitService unitService;
    private BrandService brandService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Unit.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Goods.class);
        unitService = new UnitService(unitMapper, goodsMapper);
        brandService = new BrandService(brandMapper);
    }

    // ---------- 单位 ----------

    @Test
    @DisplayName("FR-GOODS-08：单位名称重复拒绝新增")
    void createUnit_duplicateName_rejected() {
        when(unitMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> unitService.create(new UnitSaveRequest("kg", "kg", 1, 1), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
        verify(unitMapper, never()).insert(any(Unit.class));
    }

    @Test
    @DisplayName("被商品引用的单位禁止变更重量属性")
    void updateUnit_weightFlagChangeWithGoods_rejected() {
        Unit unit = new Unit();
        unit.setId(5L);
        unit.setName("瓶");
        unit.setIsWeight(0);
        when(unitMapper.selectById(5L)).thenReturn(unit);
        when(unitMapper.selectCount(any())).thenReturn(0L);   // 名称唯一通过
        when(goodsMapper.selectCount(any())).thenReturn(3L);  // 被3个商品引用

        assertThatThrownBy(() -> unitService.update(5L, new UnitSaveRequest("瓶", "瓶", 1, 1), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("3");
    }

    // ---------- 品牌 ----------

    @Test
    @DisplayName("FR-GOODS-13：品牌名称重复拒绝")
    void createBrand_duplicateName_rejected() {
        when(brandMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> brandService.create(new BrandSaveRequest("可口可乐", "K", null, 1), "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
        verify(brandMapper, never()).insert(any(com.smms.goods.entity.Brand.class));
    }
}

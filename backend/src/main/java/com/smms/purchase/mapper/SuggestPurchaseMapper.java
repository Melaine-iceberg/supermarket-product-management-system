package com.smms.purchase.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.smms.purchase.dto.SuggestItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 建议采购清单 Mapper（FR-PUR-02，BR-INV-07）：
 * 缺货/预警商品的结存、近 N 日销量、在途量、默认供应商与最近采购价一次聚合。
 */
@Mapper
public interface SuggestPurchaseMapper {

    /** 建议采购清单分页：categoryIds 为分类子树（含自身） */
    IPage<SuggestItemVO> selectSuggestPage(IPage<SuggestItemVO> page,
                                           @Param("name") String name,
                                           @Param("goodsCode") String goodsCode,
                                           @Param("categoryIds") List<Long> categoryIds,
                                           @Param("alertType") String alertType,
                                           @Param("days") int days);

    /** 按商品 ID 批量取建议行（一键生成草稿用，不走分页） */
    List<SuggestItemVO> selectSuggestByGoodsIds(@Param("goodsIds") List<Long> goodsIds,
                                                @Param("days") int days);
}

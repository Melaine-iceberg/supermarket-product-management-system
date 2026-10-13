package com.smms.goods.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.goods.dto.GoodsListVO;
import com.smms.goods.dto.GoodsQuery;
import com.smms.goods.dto.PurchasePriceVO;
import com.smms.goods.dto.SalesSummaryVO;
import com.smms.goods.dto.SupplierBriefVO;
import com.smms.goods.entity.Goods;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品档案 Mapper：分页多维检索（FR-GOODS-03）与详情聚合查询（FR-GOODS-04）
 * 以商品为中心的跨表只读查询，SQL 见 mapper/goods/GoodsMapper.xml。
 */
@Mapper
public interface GoodsMapper extends BaseMapper<Goods> {

    /** 分页列表：join 分类/单位/品牌/库存结存/生效促销，支持库存状态筛选（FR-GOODS-03） */
    IPage<GoodsListVO> selectGoodsPage(Page<GoodsListVO> page,
                                        @Param("q") GoodsQuery q,
                                        @Param("categoryIds") List<Long> categoryIds);

    /** 最近 N 次采购价（入库单明细 join 入库单，按入库时间倒序） */
    List<PurchasePriceVO> selectRecentPurchasePrices(@Param("goodsId") Long goodsId,
                                                     @Param("limit") int limit);

    /** 指定时间起的销量与销售额汇总（销售明细 join 销售单） */
    SalesSummaryVO selectSalesSummary(@Param("goodsId") Long goodsId,
                                      @Param("since") LocalDateTime since);

    /** 关联供应商清单（供应商商品关联 join 供应商，默认供应商优先） */
    List<SupplierBriefVO> selectSuppliersByGoods(@Param("goodsId") Long goodsId);

    /** 单商品更新安全库存阈值（FR-INV-06），仅更新阈值字段与更新人 */
    int updateSafetyStock(@Param("goodsId") Long goodsId,
                          @Param("lower") BigDecimal lower,
                          @Param("upper") BigDecimal upper,
                          @Param("operator") String operator);

    /** 分类（含子分类）下阈值与目标值不同的商品（FR-INV-06 批量设置默认阈值，NULL 安全比较） */
    List<Goods> selectThresholdDiff(@Param("categoryIds") List<Long> categoryIds,
                                    @Param("lower") BigDecimal lower,
                                    @Param("upper") BigDecimal upper);

    /** 按 id 批量更新安全库存阈值（FR-INV-06） */
    int updateSafetyStockBatch(@Param("ids") List<Long> ids,
                               @Param("lower") BigDecimal lower,
                               @Param("upper") BigDecimal upper,
                               @Param("operator") String operator);

    /** 收银台关键字检索（FR-SALE-02）：条码/编码/PLU 精确优先，名称/拼音首字母模糊次之，仅启用商品 */
    List<com.smms.sale.dto.CashierGoodsVO> searchForCashier(@Param("keyword") String keyword,
                                                            @Param("limit") int limit);

    /** 收银台分类浏览（GAP-P0-06）：启用商品按分类子树分页，仅返回录入所需字段 */
    IPage<com.smms.sale.dto.CashierGoodsVO> selectCashierBrowsePage(
            IPage<com.smms.sale.dto.CashierGoodsVO> page,
            @Param("categoryIds") List<Long> categoryIds);
}

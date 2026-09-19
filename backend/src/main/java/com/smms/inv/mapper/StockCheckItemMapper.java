package com.smms.inv.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.inv.dto.CheckItemVO;
import com.smms.inv.dto.CheckScopeGoods;
import com.smms.inv.entity.StockCheckItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 盘点明细 Mapper（DR-14，FR-INV-08）：创建时批量快照入库、明细分页（扫码定位），
 * SQL 见 mapper/inv/StockCheckMapper.xml。
 */
@Mapper
public interface StockCheckItemMapper extends BaseMapper<StockCheckItem> {

    /**
     * 解析盘点范围商品并快照账面数量与成本单价（FR-INV-07）：
     * scopeType=ALL全店 / CATEGORY分类（categoryIds 含子树）/ GOODS指定商品 / WARNING预警商品；
     * 结存行缺失按 0 处理；WARNING 口径与库存预警一致（缺货/预警/积压，仅启用商品）。
     */
    List<CheckScopeGoods> selectScopeGoods(@Param("scopeType") String scopeType,
                                            @Param("categoryIds") List<Long> categoryIds,
                                            @Param("goodsIds") List<Long> goodsIds);

    /** 明细分页（FR-INV-08）：关联商品档案（编码/条码/名称/单位），支持关键字定位行（扫码/名称） */
    IPage<CheckItemVO> selectItemPage(Page<CheckItemVO> page,
                                       @Param("orderId") Long orderId,
                                       @Param("onlyDiff") boolean onlyDiff,
                                       @Param("keyword") String keyword);

    /** 这些盘点单全部明细的商品ID（BR-INV-09 防重复盘点：与新建范围求交集） */
    List<Long> selectGoodsIdsOfOrders(@Param("orderIds") List<Long> orderIds);

    /** 批量插入盘点明细（快照行），service 层按 500 行/批分片调用 */
    int insertBatch(@Param("items") List<StockCheckItem> items);
}

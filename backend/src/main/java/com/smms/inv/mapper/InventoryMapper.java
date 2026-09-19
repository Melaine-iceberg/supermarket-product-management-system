package com.smms.inv.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.inv.dto.AlertItemVO;
import com.smms.inv.dto.AlertQuery;
import com.smms.inv.dto.AlertSummaryVO;
import com.smms.inv.dto.InventoryItemVO;
import com.smms.inv.dto.InventoryQuery;
import com.smms.inv.entity.Inventory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * 库存结存 Mapper：实时库存查询（FR-INV-01）与原子变动语句（BR-INV-03）。
 * 结存是派生数据，只能由本 Mapper 的变动语句 + 库存流水驱动（BR-INV-11），
 * SQL 见 mapper/inv/InventoryMapper.xml。
 */
@Mapper
public interface InventoryMapper extends BaseMapper<Inventory> {

    /** 实时库存分页：join 分类/单位/结存，结存缺失按 0 处理，库存状态口径见 FR-INV-05 */
    IPage<InventoryItemVO> selectInventoryPage(Page<InventoryItemVO> page,
                                               @Param("q") InventoryQuery q,
                                               @Param("categoryIds") List<Long> categoryIds);

    /** 合计行（FR-INV-01）：当前筛选口径下的库存总成本金额（SKU 数即分页 total） */
    BigDecimal selectInventorySummary(@Param("q") InventoryQuery q,
                                      @Param("categoryIds") List<Long> categoryIds);

    /** 预警分页（FR-INV-05）：仅启用商品（FR-GOODS-05），缺货含负库存（BR-INV-02），支持按缺口量排序 */
    IPage<AlertItemVO> selectAlertPage(Page<AlertItemVO> page,
                                       @Param("q") AlertQuery q,
                                       @Param("categoryIds") List<Long> categoryIds);

    /** 预警汇总（FR-INV-05）：各预警类型数量，角标口径见 AlertSummaryVO */
    AlertSummaryVO selectAlertSummary(@Param("q") AlertQuery q,
                                      @Param("categoryIds") List<Long> categoryIds);

    /** 确保结存行存在：建档时初始库存为 0 的商品无结存行，首次变动前补 0 行 */
    int ensureRow(@Param("goodsId") Long goodsId, @Param("storeId") Long storeId);

    /**
     * 原子条件扣减（BR-INV-03）：余量判定与扣减在同一条语句内，
     * 受影响行数为 0 即库存不足；allowNegative=true 时跳过余量判定（BR-INV-02）。
     */
    int decreaseStock(@Param("goodsId") Long goodsId,
                      @Param("storeId") Long storeId,
                      @Param("qty") BigDecimal qty,
                      @Param("allowNegative") boolean allowNegative);

    /** 加库存（不重算成本）：数量增加、成本单价保持不变（销售退货回冲等，BR-INV-06） */
    int increaseStock(@Param("goodsId") Long goodsId,
                      @Param("storeId") Long storeId,
                      @Param("qty") BigDecimal qty);

    /** 行锁读取结存（事务内，移动加权成本重算 / 盘点覆盖用） */
    Inventory selectByGoodsIdForUpdate(@Param("goodsId") Long goodsId,
                                       @Param("storeId") Long storeId);

    /** 覆盖式更新结存与成本（入库成本重算 / 盘点覆盖实盘数） */
    int updateQuantityAndCost(@Param("goodsId") Long goodsId,
                              @Param("storeId") Long storeId,
                              @Param("quantity") BigDecimal quantity,
                              @Param("costPrice") BigDecimal costPrice,
                              @Param("costAmount") BigDecimal costAmount);
}

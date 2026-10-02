package com.smms.rpt.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.rpt.dto.DashboardVO;
import com.smms.rpt.dto.DrillOrderQuery;
import com.smms.rpt.dto.DrillOrderRow;
import com.smms.rpt.dto.GoodsRankRow;
import com.smms.rpt.dto.NegativeProfitRow;
import com.smms.rpt.dto.PeriodAggRow;
import com.smms.rpt.dto.SalesGroupRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 报表统计聚合查询（FR-RPT-01/02/03/05）：SQL 见 mapper/rpt/ReportMapper.xml。
 * 数据来源仅为已完成正式单据（BR-RPT-03）：销售单按 business_date 聚合（BR-RPT-02）。
 */
@Mapper
public interface ReportMapper {

    // ==================== 看板（FR-RPT-01） ====================

    /** 区间销售汇总（BR-RPT-01 口径原始值） */
    PeriodAggRow selectSalesAgg(@Param("begin") LocalDate begin, @Param("end") LocalDate end);

    /** 区间退货汇总：退款额与退货成本回冲 */
    PeriodAggRow selectReturnAgg(@Param("begin") LocalDate begin, @Param("end") LocalDate end);

    /** 按营业日聚合（趋势折线/日报/月报共用，granularity=day|month） */
    List<SalesGroupRow> selectDailySales(@Param("begin") LocalDate begin, @Param("end") LocalDate end,
                                         @Param("monthly") boolean monthly);

    /** 按营业日聚合退货（与 selectDailySales 对齐合并） */
    List<SalesGroupRow> selectDailyReturns(@Param("begin") LocalDate begin, @Param("end") LocalDate end,
                                           @Param("monthly") boolean monthly);

    /** TOP N 热销商品（看板，按销售额） */
    List<DashboardVO.TopGoodsVO> selectTopGoods(@Param("begin") LocalDate begin,
                                                @Param("end") LocalDate end,
                                                @Param("topN") int topN);

    /** 待审核采购单数（看板待办） */
    long countPendingPurchase();

    /** 待审核盘点单数（看板待办） */
    long countPendingCheck();

    /** 缺货商品数：结存 ≤ 0 且商品启用（看板待办/KPI） */
    long countStockoutGoods();

    /** 超期未到货采购单数：已审核待入库且预计到货日早于今日 */
    long countOverduePurchase();

    /** 库存预警数：结存 ≤ 安全库存下限且商品启用（FR-INV-05 角标） */
    long countWarningGoods();

    /** 今日新增会员数（营业日窗口由服务层计算，BR-RPT-02） */
    long countNewMembers(@Param("beginTime") LocalDateTime beginTime,
                         @Param("endTime") LocalDateTime endTime);

    // ==================== 销售报表（FR-RPT-02） ====================

    /** 按收款方式汇总 */
    List<SalesGroupRow> selectByPayMethod(@Param("begin") LocalDate begin, @Param("end") LocalDate end);

    /** 按小时分布（排班分析） */
    List<SalesGroupRow> selectByHour(@Param("begin") LocalDate begin, @Param("end") LocalDate end);

    /** 按收银员业绩汇总（销售侧） */
    List<SalesGroupRow> selectByCashier(@Param("begin") LocalDate begin, @Param("end") LocalDate end);

    /** 按收银台业绩汇总 */
    List<SalesGroupRow> selectByCounter(@Param("begin") LocalDate begin, @Param("end") LocalDate end);

    /** 按收银员汇总退货（业绩冲减） */
    List<SalesGroupRow> selectReturnsByCashier(@Param("begin") LocalDate begin, @Param("end") LocalDate end);

    /** 会员单 / 散客单占比 */
    List<SalesGroupRow> selectMemberRatio(@Param("begin") LocalDate begin, @Param("end") LocalDate end);

    // ==================== 商品销售分析（FR-RPT-03） ====================

    /** 商品销售排行分页（按销量/销售额/毛利额，TOP N 与全量） */
    IPage<GoodsRankRow> selectGoodsRanking(Page<GoodsRankRow> page,
                                           @Param("begin") LocalDate begin,
                                           @Param("end") LocalDate end,
                                           @Param("orderBy") String orderBy,
                                           @Param("categoryId") Long categoryId);

    /** 品类销售结构（销售额与占比） */
    List<GoodsRankRow> selectCategoryStructure(@Param("begin") LocalDate begin,
                                               @Param("end") LocalDate end);

    /** 品牌销售分析 */
    List<GoodsRankRow> selectBrandSales(@Param("begin") LocalDate begin, @Param("end") LocalDate end);

    /** 滞销商品数（分页总数用） */
    long countSlowMoving(@Param("begin") LocalDate begin, @Param("end") LocalDate end,
                         @Param("threshold") java.math.BigDecimal threshold,
                         @Param("categoryId") Long categoryId);

    /** 滞销商品清单分页（销量为 0 或低于阈值，含当前库存量与占用资金） */
    IPage<GoodsRankRow> selectSlowMoving(Page<GoodsRankRow> page,
                                         @Param("begin") LocalDate begin,
                                         @Param("end") LocalDate end,
                                         @Param("threshold") java.math.BigDecimal threshold,
                                         @Param("categoryId") Long categoryId);

    // ==================== 毛利分析（FR-RPT-05） ====================
    // 分类维度毛利复用 selectCategoryStructure（同列结构，含毛利额）

    /** 负毛利明细清单（成交价低于成本价，发现定价错误与异常改价） */
    List<NegativeProfitRow> selectNegativeProfit(@Param("begin") LocalDate begin,
                                                 @Param("end") LocalDate end,
                                                 @Param("limit") int limit);

    // ==================== 下钻明细（BR-RPT-06） ====================

    /** 下钻单据数（分页总数用） */
    long countDrillOrders(DrillOrderQuery q);

    /** 汇总数字下钻：构成它的销售单列表（可再打开单据详情） */
    IPage<DrillOrderRow> selectDrillOrders(Page<DrillOrderRow> page, @Param("q") DrillOrderQuery q);
}

package com.smms.rpt.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.rpt.dto.InvChangeRow;
import com.smms.rpt.dto.InvSummaryRow;
import com.smms.rpt.dto.LossRow;
import com.smms.rpt.dto.TurnoverRow;
import com.smms.rpt.dto.WarningRptRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 库存报表聚合查询（FR-RPT-04）：库存汇总、预警（含建议采购量）、
 * 变动明细汇总、周转率与周转天数、损耗报表。SQL 见 mapper/rpt/InventoryReportMapper.xml。
 */
@Mapper
public interface InventoryReportMapper {

    /** 库存汇总（商品维度）总数 */
    long countGoodsSummary(@Param("categoryId") Long categoryId);

    /** 库存汇总表（商品维度）：结存数量、成本单价、库存成本金额、零售价值、库存状态 */
    IPage<InvSummaryRow> selectGoodsSummary(Page<InvSummaryRow> page,
                                            @Param("categoryId") Long categoryId);

    /** 库存汇总（分类维度）：分类商品数、结存数量、成本金额、零售价值合计 */
    List<InvSummaryRow> selectCategorySummary();

    /** 预警报表总数（缺货/低库存/积压） */
    long countWarnings(@Param("alertType") String alertType);

    /** 预警报表（FR-RPT-04②）：缺货/低库存/积压清单，含建议采购量（BR-INV-07 公式参数由服务层传入） */
    IPage<WarningRptRow> selectWarnings(Page<WarningRptRow> page,
                                        @Param("alertType") String alertType,
                                        @Param("suggestK") BigDecimal suggestK,
                                        @Param("avgDays") int avgDays,
                                        @Param("leadDays") int leadDays);

    /** 库存变动明细汇总（按变动类型汇总数量与金额） */
    List<InvChangeRow> selectChangeSummary(@Param("beginTime") LocalDateTime beginTime,
                                           @Param("endTime") LocalDateTime endTime);

    /** 区间流水金额合计（周转率期初库存金额推算用：期初 = 期末成本 − Σ(变动数量×单位成本)） */
    List<TurnoverRow> selectFlowAmountByGoods(@Param("beginTime") LocalDateTime beginTime,
                                              @Param("endTime") LocalDateTime endTime);

    /** 区间销售成本（按商品，周转率分子） */
    List<TurnoverRow> selectSaleCostByGoods(@Param("begin") java.time.LocalDate begin,
                                            @Param("end") java.time.LocalDate end);

    /** 区间退货成本回冲（按商品） */
    List<TurnoverRow> selectReturnCostByGoods(@Param("begin") java.time.LocalDate begin,
                                              @Param("end") java.time.LocalDate end);

    /** 周转率商品维度分页（当前结存成本，从库存表取） */
    IPage<TurnoverRow> selectTurnoverPage(Page<TurnoverRow> page,
                                          @Param("categoryId") Long categoryId);

    /** 损耗报表：报损/报溢单按商品汇总（店长已审核） */
    List<LossRow> selectLossByGoods(@Param("beginTime") LocalDateTime beginTime,
                                    @Param("endTime") LocalDateTime endTime);

    /** 损耗报表：盘亏按商品汇总（已审核盘点单） */
    List<LossRow> selectShortageByGoods(@Param("beginTime") LocalDateTime beginTime,
                                        @Param("endTime") LocalDateTime endTime);

    /** 期初库存金额 = Σ当前库存成本 − Σ(区间流水变动金额)（损耗率分母） */
    BigDecimal selectBeginInventoryAmount(@Param("beginTime") LocalDateTime beginTime,
                                          @Param("endTime") LocalDateTime endTime);
}

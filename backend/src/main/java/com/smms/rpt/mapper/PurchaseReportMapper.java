package com.smms.rpt.mapper;

import com.smms.rpt.dto.ArrivalRow;
import com.smms.rpt.dto.PriceTrendRow;
import com.smms.rpt.dto.PurchSummaryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 采购报表聚合查询（FR-RPT-06）：采购汇总（供应商/商品）、供应商排行与占比、
 * 采购价格波动、到货及时率。SQL 见 mapper/rpt/PurchaseReportMapper.xml。
 */
@Mapper
public interface PurchaseReportMapper {

    /** 采购汇总（供应商维度）：采购数量/金额、已入库、在途、退货、结算状态 */
    List<PurchSummaryRow> selectSupplierSummary(@Param("beginTime") LocalDateTime beginTime,
                                                @Param("endTime") LocalDateTime endTime);

    /** 采购汇总（商品维度） */
    List<PurchSummaryRow> selectGoodsSummary(@Param("beginTime") LocalDateTime beginTime,
                                             @Param("endTime") LocalDateTime endTime);

    /** 采购价格波动：同商品多次采购单价趋势（按下单时间） */
    List<PriceTrendRow> selectPriceTrend(@Param("goodsId") Long goodsId,
                                         @Param("beginTime") LocalDateTime beginTime,
                                         @Param("endTime") LocalDateTime endTime);

    /** 到货及时率：按供应商统计按期到货占比（实收日 ≤ 预计到货日） */
    List<ArrivalRow> selectArrival(@Param("beginTime") LocalDateTime beginTime,
                                   @Param("endTime") LocalDateTime endTime);
}

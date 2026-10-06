package com.smms.rpt.mapper;

import com.smms.rpt.dto.ArrivalRow;
import com.smms.rpt.dto.DrillPurchaseQuery;
import com.smms.rpt.dto.DrillPurchaseRow;
import com.smms.rpt.dto.DrillReceiptQuery;
import com.smms.rpt.dto.DrillReceiptRow;
import com.smms.rpt.dto.PriceTrendRow;
import com.smms.rpt.dto.PurchSummaryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 采购报表聚合查询（FR-RPT-06）：采购汇总（供应商/商品）、供应商排行与占比、
 * 采购价格波动、到货及时率；下钻明细（BR-RPT-06）。
 * SQL 见 mapper/rpt/PurchaseReportMapper.xml。
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

    // ==================== 下钻明细（BR-RPT-06） ====================

    /** 采购汇总下钻：构成该供应商/商品汇总的采购单数（分页总数用） */
    long countPurchaseDrill(@Param("q") DrillPurchaseQuery q);

    /** 采购汇总下钻：构成该供应商/商品汇总的采购单列表（可再打开采购单详情） */
    IPage<DrillPurchaseRow> selectPurchaseDrill(Page<DrillPurchaseRow> page,
                                                @Param("q") DrillPurchaseQuery q);

    /** 到货及时率下钻：构成该供应商统计的入库单数（分页总数用） */
    long countReceiptDrill(@Param("q") DrillReceiptQuery q);

    /** 到货及时率下钻：构成该供应商统计的入库单列表（含是否按期标记） */
    IPage<DrillReceiptRow> selectReceiptDrill(Page<DrillReceiptRow> page,
                                              @Param("q") DrillReceiptQuery q);
}

package com.smms.rpt.service;

import com.smms.rpt.dto.ArrivalRow;
import com.smms.rpt.dto.PriceTrendRow;
import com.smms.rpt.dto.PurchSummaryRow;
import com.smms.rpt.dto.ReportRange;
import com.smms.rpt.mapper.PurchaseReportMapper;
import com.smms.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 采购报表（FR-RPT-06）：采购汇总表（供应商/商品维度）、供应商采购排行与占比、
 * 采购价格波动分析、到货及时率统计。
 */
@Service
@RequiredArgsConstructor
public class PurchaseReportService {

    private final PurchaseReportMapper mapper;

    /** 采购汇总表（①）：dim=supplier 供应商 / goods 商品 */
    public List<PurchSummaryRow> summary(ReportRange range, String dim, LoginUser user) {
        LocalDateTime beginTime = range.beginDate().atStartOfDay();
        LocalDateTime endTime = range.endDate().plusDays(1).atStartOfDay();
        if ("goods".equals(dim)) {
            return mapper.selectGoodsSummary(beginTime, endTime);
        }
        return mapper.selectSupplierSummary(beginTime, endTime);
    }

    /** 供应商采购排行与占比（②）：按采购金额倒序并计算占比 */
    public List<PurchSummaryRow> supplierRanking(ReportRange range, LoginUser user) {
        LocalDateTime beginTime = range.beginDate().atStartOfDay();
        LocalDateTime endTime = range.endDate().plusDays(1).atStartOfDay();
        List<PurchSummaryRow> rows = new ArrayList<>(mapper.selectSupplierSummary(beginTime, endTime));
        BigDecimal total = rows.stream()
                .map(PurchSummaryRow::purchaseAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(BigDecimal.ZERO) != 0) {
            List<PurchSummaryRow> ranked = new ArrayList<>(rows.size());
            for (PurchSummaryRow r : rows) {
                BigDecimal share = r.purchaseAmount().multiply(BigDecimal.valueOf(100))
                        .divide(total, 2, RoundingMode.HALF_UP);
                ranked.add(new PurchSummaryRow(r.supplierId(), r.supplierName(),
                        r.goodsId(), r.goodsCode(), r.goodsName(), r.categoryName(),
                        r.orderCount(), r.purchaseQty(), r.purchaseAmount(), r.receivedQty(),
                        r.inTransitQty(), r.returnQty(), r.returnAmount(),
                        r.settledAmount(), r.partialSettledAmount(), share));
            }
            return ranked;
        }
        return rows;
    }

    /** 采购价格波动分析（③）：同商品多次采购单价趋势 */
    public List<PriceTrendRow> priceTrend(Long goodsId, ReportRange range, LoginUser user) {
        LocalDateTime beginTime = range.beginDate().atStartOfDay();
        LocalDateTime endTime = range.endDate().plusDays(1).atStartOfDay();
        return mapper.selectPriceTrend(goodsId, beginTime, endTime);
    }

    /** 到货及时率统计（④）：按期 = 实收日期 ≤ 预计到货日期 */
    public List<ArrivalRow> arrival(ReportRange range, LoginUser user) {
        LocalDateTime beginTime = range.beginDate().atStartOfDay();
        LocalDateTime endTime = range.endDate().plusDays(1).atStartOfDay();
        List<ArrivalRow> rows = mapper.selectArrival(beginTime, endTime);
        List<ArrivalRow> result = new ArrayList<>(rows.size());
        for (ArrivalRow r : rows) {
            BigDecimal rate = r.receiptCount() == 0 ? null
                    : BigDecimal.valueOf(r.onTimeCount() * 100L)
                        .divide(BigDecimal.valueOf(r.receiptCount()), 2, RoundingMode.HALF_UP);
            result.add(new ArrivalRow(r.supplierId(), r.supplierName(),
                    r.receiptCount(), r.onTimeCount(), rate));
        }
        return result;
    }
}

package com.smms.rpt.service;

import com.smms.rpt.dto.ArrivalRow;
import com.smms.rpt.dto.PriceTrendRow;
import com.smms.rpt.dto.PurchSummaryRow;
import com.smms.rpt.dto.ReportRange;
import com.smms.rpt.dto.RptRoles;
import com.smms.rpt.mapper.PurchaseReportMapper;
import com.smms.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 采购报表（FR-RPT-06）：采购汇总表（供应商/商品维度）、供应商采购排行与占比、
 * 采购价格波动分析、到货及时率统计。
 *
 * <p>口径（BR-RPT-01/BR-RPT-02）：按各单据营业日 business_date 过滤（GAP-P1-05）；
 * 采购金额/退货金额/结算金额/采购单价等成本类字段按 BR-RPT-07 由服务层按角色置空
 * （GAP-P0-10③），导出复用同一服务方法，不因导出通道而绕过掩码。
 */
@Service
@RequiredArgsConstructor
public class PurchaseReportService {

    private final PurchaseReportMapper mapper;

    /** 采购汇总表（①）：dim=supplier 供应商 / goods 商品 */
    public List<PurchSummaryRow> summary(ReportRange range, String dim, LoginUser user) {
        return summary(range, dim, user, null);
    }

    /** 采购汇总导出（FR-RPT-08）：取前 limit 行，超限由调用方校验 */
    public List<PurchSummaryRow> summaryForExport(ReportRange range, String dim, LoginUser user, int limit) {
        return summary(range, dim, user, limit);
    }

    private List<PurchSummaryRow> summary(ReportRange range, String dim, LoginUser user, Integer limit) {
        List<PurchSummaryRow> rows = "goods".equals(dim)
                ? mapper.selectGoodsSummary(range.beginDate(), range.endDate(), limit)
                : mapper.selectSupplierSummary(range.beginDate(), range.endDate(), limit);
        boolean canProfit = RptRoles.canViewProfit(user);
        return rows.stream().map(r -> mask(r, canProfit)).toList();
    }

    /** 供应商采购排行与占比（②）：按采购金额倒序并计算占比 */
    public List<PurchSummaryRow> supplierRanking(ReportRange range, LoginUser user) {
        return supplierRanking(range, user, null);
    }

    /** 供应商采购排行导出（FR-RPT-08）：取前 limit 行，超限由调用方校验 */
    public List<PurchSummaryRow> supplierRankingForExport(ReportRange range, LoginUser user, int limit) {
        return supplierRanking(range, user, limit);
    }

    private List<PurchSummaryRow> supplierRanking(ReportRange range, LoginUser user, Integer limit) {
        List<PurchSummaryRow> rows = new ArrayList<>(
                mapper.selectSupplierSummary(range.beginDate(), range.endDate(), limit));
        BigDecimal total = rows.stream()
                .map(PurchSummaryRow::purchaseAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<PurchSummaryRow> ranked = rows;
        if (total.compareTo(BigDecimal.ZERO) != 0) {
            ranked = new ArrayList<>(rows.size());
            for (PurchSummaryRow r : rows) {
                BigDecimal share = r.purchaseAmount().multiply(BigDecimal.valueOf(100))
                        .divide(total, 2, RoundingMode.HALF_UP);
                ranked.add(new PurchSummaryRow(r.supplierId(), r.supplierName(),
                        r.goodsId(), r.goodsCode(), r.goodsName(), r.categoryName(),
                        r.orderCount(), r.purchaseQty(), r.purchaseAmount(), r.receivedQty(),
                        r.inTransitQty(), r.returnQty(), r.returnAmount(),
                        r.settledAmount(), r.partialSettledAmount(), share));
            }
        }
        boolean canProfit = RptRoles.canViewProfit(user);
        return ranked.stream().map(r -> mask(r, canProfit)).toList();
    }

    /** 采购价格波动分析（③）：同商品多次采购单价趋势 */
    public List<PriceTrendRow> priceTrend(Long goodsId, ReportRange range, LoginUser user) {
        return priceTrend(goodsId, range, user, null);
    }

    /** 采购价格波动导出（FR-RPT-08）：取前 limit 行，超限由调用方校验 */
    public List<PriceTrendRow> priceTrendForExport(Long goodsId, ReportRange range, LoginUser user, int limit) {
        return priceTrend(goodsId, range, user, limit);
    }

    private List<PriceTrendRow> priceTrend(Long goodsId, ReportRange range, LoginUser user, Integer limit) {
        // 未选商品时返回空序列，由前端提示“请选择商品”，避免空参进入 SQL 触发 500
        if (goodsId == null) {
            return List.of();
        }
        List<PriceTrendRow> rows = mapper.selectPriceTrend(
                goodsId, range.beginDate(), range.endDate(), limit);
        boolean canProfit = RptRoles.canViewProfit(user);
        return rows.stream().map(r -> mask(r, canProfit)).toList();
    }

    /** 到货及时率统计（④）：按期 = 实收日期 ≤ 预计到货日期（数量类指标，不涉成本掩码） */
    public List<ArrivalRow> arrival(ReportRange range, LoginUser user) {
        return arrival(range, user, null);
    }

    /** 到货及时率导出（FR-RPT-08）：取前 limit 行，超限由调用方校验 */
    public List<ArrivalRow> arrivalForExport(ReportRange range, LoginUser user, int limit) {
        return arrival(range, user, limit);
    }

    private List<ArrivalRow> arrival(ReportRange range, LoginUser user, Integer limit) {
        List<ArrivalRow> rows = mapper.selectArrival(range.beginDate(), range.endDate(), limit);
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

    /** BR-RPT-07/GAP-P0-10③：无毛利权限时采购金额/退货金额/结算金额/占比不返回（而非前端隐藏） */
    private PurchSummaryRow mask(PurchSummaryRow r, boolean canProfit) {
        if (canProfit) {
            return r;
        }
        return new PurchSummaryRow(r.supplierId(), r.supplierName(),
                r.goodsId(), r.goodsCode(), r.goodsName(), r.categoryName(),
                r.orderCount(), r.purchaseQty(), null, r.receivedQty(), r.inTransitQty(),
                r.returnQty(), null, null, null, null);
    }

    /** BR-RPT-07/GAP-P0-10③：无毛利权限时采购单价不返回 */
    private PriceTrendRow mask(PriceTrendRow r, boolean canProfit) {
        if (canProfit) {
            return r;
        }
        return new PriceTrendRow(r.orderId(), r.orderNo(), r.supplierId(), r.supplierName(),
                r.orderTime(), r.quantity(), null);
    }
}

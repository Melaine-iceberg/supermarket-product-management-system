package com.smms.rpt.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.common.mybatis.ExportPage;
import com.smms.rpt.dto.GoodsRankRow;
import com.smms.rpt.dto.ReportRange;
import com.smms.rpt.dto.RptRoles;
import com.smms.rpt.mapper.ReportMapper;
import com.smms.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/**
 * 商品销售分析（FR-RPT-03）：商品销售排行（销量/销售额/毛利额，TOP N 与全量）、
 * 品类销售结构、滞销商品清单、品牌销售分析。
 * 毛利额为快照口径（AC-17）：Σ(实付金额 − 成本快照 × 数量)。
 */
@Service
@RequiredArgsConstructor
public class GoodsReportService {

    /** 排行排序白名单（防 SQL 注入，${} 仅接收白名单值） */
    private static final Set<String> ORDER_BY = Set.of("qty", "salesAmount", "profitAmount", "netSalesAmount");

    private final ReportMapper reportMapper;

    /** 商品销售排行（①）：orderBy=qty|salesAmount|profitAmount，支持 TOP N 与全量分页 */
    public PageVO<GoodsRankRow> ranking(ReportRange range, String orderBy, Long categoryId,
                                        int page, int size, LoginUser user) {
        String order = normalize(orderBy);
        boolean canProfit = RptRoles.canViewProfit(user);
        IPage<GoodsRankRow> result = reportMapper.selectGoodsRanking(
                new Page<>(Math.max(page, 1), size <= 0 ? 20 : Math.min(size, 200)),
                range.beginDate(), range.endDate(), order, categoryId);
        result.setRecords(result.getRecords().stream()
                .map(r -> withShare(r, null))
                .map(r -> mask(r, canProfit)).toList());
        return PageVO.of(result);
    }

    /** 商品销售排行导出（FR-RPT-08）：不分页，取前 limit 行，超限由调用方校验（ExportPage 豁免单页 500 条保护，GAP-P0-01） */
    public List<GoodsRankRow> rankingForExport(ReportRange range, String orderBy, Long categoryId,
                                               int limit, LoginUser user) {
        String order = normalize(orderBy);
        boolean canProfit = RptRoles.canViewProfit(user);
        IPage<GoodsRankRow> result = reportMapper.selectGoodsRanking(
                new ExportPage<>(limit),
                range.beginDate(), range.endDate(), order, categoryId);
        return result.getRecords().stream()
                .map(r -> withShare(r, null))
                .map(r -> mask(r, canProfit)).toList();
    }

    /** 品类销售结构（②）：各分类销售额与占比（饼图/矩形树图） */
    public List<GoodsRankRow> categoryStructure(ReportRange range, LoginUser user) {
        List<GoodsRankRow> rows = reportMapper.selectCategoryStructure(range.beginDate(), range.endDate());
        BigDecimal total = rows.stream()
                .map(GoodsRankRow::salesAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean canProfit = RptRoles.canViewProfit(user);
        return rows.stream()
                .map(r -> withShare(r, share(r.salesAmount(), total)))
                .map(r -> mask(r, canProfit))
                .toList();
    }

    /** 品牌销售分析（④） */
    public List<GoodsRankRow> brandSales(ReportRange range, LoginUser user) {
        boolean canProfit = RptRoles.canViewProfit(user);
        return reportMapper.selectBrandSales(range.beginDate(), range.endDate())
                .stream().map(r -> mask(r, canProfit)).toList();
    }

    /** 滞销商品清单（③）：区间销量低于阈值（默认 1 即销量为 0），含当前库存量与占用资金 */
    public PageVO<GoodsRankRow> slowMoving(ReportRange range, BigDecimal threshold, Long categoryId,
                                           int page, int size, LoginUser user) {
        BigDecimal th = threshold == null || threshold.compareTo(BigDecimal.ZERO) < 0
                ? BigDecimal.ONE : threshold;
        int pageNo = Math.max(page, 1);
        int pageSize = size <= 0 ? 20 : Math.min(size, 200);
        long total = reportMapper.countSlowMoving(range.beginDate(), range.endDate(), th, categoryId);
        if (total == 0) {
            return new PageVO<>(List.of(), 0);
        }
        Page<GoodsRankRow> p = new Page<>(pageNo, pageSize, false);
        p.setTotal(total);
        IPage<GoodsRankRow> result = reportMapper.selectSlowMoving(
                p, range.beginDate(), range.endDate(), th, categoryId);
        boolean canProfit = RptRoles.canViewProfit(user);
        result.setRecords(result.getRecords().stream()
                .map(r -> mask(r, canProfit)).toList());
        return PageVO.of(result);
    }

    /** 滞销商品清单导出（FR-RPT-08）：不分页，取前 limit 行，超限由调用方校验（ExportPage 豁免单页 500 条保护，GAP-P0-01） */
    public List<GoodsRankRow> slowMovingForExport(ReportRange range, BigDecimal threshold, Long categoryId,
                                                  int limit, LoginUser user) {
        BigDecimal th = threshold == null || threshold.compareTo(BigDecimal.ZERO) < 0
                ? BigDecimal.ONE : threshold;
        boolean canProfit = RptRoles.canViewProfit(user);
        IPage<GoodsRankRow> result = reportMapper.selectSlowMoving(
                new ExportPage<>(limit), range.beginDate(), range.endDate(), th, categoryId);
        return result.getRecords().stream()
                .map(r -> mask(r, canProfit)).toList();
    }

    /** 汇总数字下钻（BR-RPT-06）：见 {@link DrillDownService} */

    private String normalize(String orderBy) {
        if (orderBy == null || orderBy.isBlank()) {
            return "salesAmount";
        }
        if (!ORDER_BY.contains(orderBy)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "排序字段不合法");
        }
        return orderBy;
    }

    private GoodsRankRow withShare(GoodsRankRow r, BigDecimal shareRate) {
        return new GoodsRankRow(r.goodsId(), r.goodsCode(), r.goodsName(),
                r.categoryId(), r.categoryName(), r.brandId(), r.brandName(),
                r.orderCount(), r.qty(), r.salesAmount(), r.discountAmount(),
                r.netSalesAmount(), r.refundAmount(), r.costAmount(), r.returnCostAmount(),
                r.profitAmount(), profitRate(r), shareRate,
                r.stockQty(), r.costPrice(), r.occupiedAmount(), r.retailPrice());
    }

    /** 毛利率 = 毛利额 / 净销售 × 100%（净销售为 0 显示 "—"，BR-RPT-01） */
    private BigDecimal profitRate(GoodsRankRow r) {
        BigDecimal net = nvl(r.netSalesAmount());
        if (net.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return nvl(r.profitAmount()).multiply(BigDecimal.valueOf(100))
                .divide(net, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal share(BigDecimal part, BigDecimal total) {
        if (total == null || total.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return nvl(part).multiply(BigDecimal.valueOf(100))
                .divide(total, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** BR-RPT-07：无毛利权限时成本/毛利字段不返回（而非前端隐藏） */
    private GoodsRankRow mask(GoodsRankRow r, boolean canProfit) {
        if (canProfit) {
            return r;
        }
        return new GoodsRankRow(r.goodsId(), r.goodsCode(), r.goodsName(),
                r.categoryId(), r.categoryName(), r.brandId(), r.brandName(),
                r.orderCount(), r.qty(), r.salesAmount(), r.discountAmount(),
                r.netSalesAmount(), r.refundAmount(), null, null,
                null, null, r.shareRate(), r.stockQty(), null, r.occupiedAmount(), r.retailPrice());
    }
}

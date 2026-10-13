package com.smms.rpt.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.PageVO;
import com.smms.common.mybatis.ExportPage;
import com.smms.rpt.dto.InvChangeRow;
import com.smms.rpt.dto.InvSummaryRow;
import com.smms.rpt.dto.LossRow;
import com.smms.rpt.dto.ReportRange;
import com.smms.rpt.dto.RptRoles;
import com.smms.rpt.dto.TurnoverRow;
import com.smms.rpt.dto.WarningRptRow;
import com.smms.rpt.mapper.InventoryReportMapper;
import com.smms.security.LoginUser;
import com.smms.sys.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 库存报表（FR-RPT-04）：库存汇总表、库存预警报表（含建议采购量）、
 * 库存变动明细报表、库存周转率与周转天数、损耗报表。
 * 期初库存金额 = 期末库存成本 − Σ(区间流水变动数量×单位成本)（库存只能由流水驱动，BR-INV-11）。
 * 营业日口径（BR-RPT-02/GAP-P1-05）：变动/周转/损耗按库存流水、报损单、盘点单的
 * business_date 聚合，不再运行时按 operate_time/audit_time 计算。
 * 成本类字段按 BR-RPT-07 由服务层按角色置空（GAP-P0-10③），导出复用同一方法不绕过掩码。
 */
@Service
@RequiredArgsConstructor
public class InventoryReportService {

    private final InventoryReportMapper mapper;
    private final SysConfigService configService;

    /** 库存汇总表（①，商品维度）：结存数量、成本单价、库存成本金额、零售价值、库存状态 */
    public PageVO<InvSummaryRow> summaryByGoods(Long categoryId, int page, int size, LoginUser user) {
        int pageNo = Math.max(page, 1);
        int pageSize = size <= 0 ? 20 : Math.min(size, 200);
        long total = mapper.countGoodsSummary(categoryId);
        if (total == 0) {
            return new PageVO<>(List.of(), 0);
        }
        Page<InvSummaryRow> p = new Page<>(pageNo, pageSize, false);
        p.setTotal(total);
        IPage<InvSummaryRow> result = mapper.selectGoodsSummary(p, categoryId);
        result.setRecords(maskSummaries(result.getRecords(), canProfit(user)));
        return PageVO.of(result);
    }

    /** 库存汇总表导出（FR-RPT-08）：商品维度全量，取前 limit 行，超限由调用方校验（ExportPage 豁免单页 500 条保护，GAP-P0-01） */
    public List<InvSummaryRow> summaryByGoodsForExport(Long categoryId, int limit, LoginUser user) {
        List<InvSummaryRow> rows = mapper.selectGoodsSummary(new ExportPage<>(limit), categoryId).getRecords();
        return maskSummaries(rows, canProfit(user));
    }

    /** 库存汇总表（①，分类维度） */
    public List<InvSummaryRow> summaryByCategory(LoginUser user) {
        return maskSummaries(mapper.selectCategorySummary(), canProfit(user));
    }

    /** 库存预警报表（②）：缺货/低库存/积压清单，含建议采购量（BR-INV-07 公式参数由系统参数读取） */
    public PageVO<WarningRptRow> warnings(String alertType, int page, int size) {
        int pageNo = Math.max(page, 1);
        int pageSize = size <= 0 ? 20 : Math.min(size, 200);
        long total = mapper.countWarnings(alertType);
        if (total == 0) {
            return new PageVO<>(List.of(), 0);
        }
        Page<WarningRptRow> p = new Page<>(pageNo, pageSize, false);
        p.setTotal(total);
        return PageVO.of(mapper.selectWarnings(p, alertType, suggestK(), avgDays(), leadDays()));
    }

    /** 库存预警报表导出（FR-RPT-08）：取前 limit 行，超限由调用方校验（ExportPage 豁免单页 500 条保护，GAP-P0-01） */
    public List<WarningRptRow> warningsForExport(String alertType, int limit) {
        return mapper.selectWarnings(new ExportPage<>(limit),
                alertType, suggestK(), avgDays(), leadDays()).getRecords();
    }

    /** 库存变动明细报表（③）：按变动类型汇总数量与金额（营业日区间） */
    public List<InvChangeRow> changes(ReportRange range, LoginUser user) {
        List<InvChangeRow> rows = mapper.selectChangeSummary(range.beginDate(), range.endDate());
        boolean canProfit = canProfit(user);
        return rows.stream().map(r -> mask(r, canProfit)).toList();
    }

    /** 库存周转率与周转天数（④，商品维度分页） */
    public PageVO<TurnoverRow> turnoverByGoods(ReportRange range, Long categoryId, int page, int size, LoginUser user) {
        Context ctx = context(range);
        int pageNo = Math.max(page, 1);
        int pageSize = size <= 0 ? 20 : Math.min(size, 200);
        long total = mapper.countGoodsSummary(categoryId);
        if (total == 0) {
            return new PageVO<>(List.of(), 0);
        }
        Page<TurnoverRow> p = new Page<>(pageNo, pageSize, false);
        p.setTotal(total);
        IPage<TurnoverRow> result = mapper.selectTurnoverPage(p, categoryId);
        boolean canProfit = canProfit(user);
        result.setRecords(result.getRecords().stream()
                .map(r -> ctx.compute(r))
                .map(r -> ctx.withRate(r))
                .map(r -> mask(r, canProfit))
                .toList());
        return PageVO.of(result);
    }

    /** 库存周转导出（FR-RPT-08）：商品维度，取前 limit 行，超限由调用方校验（ExportPage 豁免单页 500 条保护，GAP-P0-01） */
    public List<TurnoverRow> turnoverByGoodsForExport(ReportRange range, Long categoryId, int limit, LoginUser user) {
        Context ctx = context(range);
        IPage<TurnoverRow> result = mapper.selectTurnoverPage(new ExportPage<>(limit), categoryId);
        boolean canProfit = canProfit(user);
        return result.getRecords().stream()
                .map(r -> ctx.compute(r))
                .map(r -> ctx.withRate(r))
                .map(r -> mask(r, canProfit))
                .toList();
    }

    /**
     * 库存周转率与周转天数（④，分类维度汇总）：全量商品行内存聚合。
     * GAP-P1-06：去除此前硬编码 5000 行静默截断的导出行为——导出传入 limit（export_max_rows+1）
     * 后由调用方 checkLimit 校验，超限明确提示缩小范围；交互查询默认沿用 5000 行内存保护。
     */
    public List<TurnoverRow> turnoverByCategory(ReportRange range, Long categoryId, Integer limit, LoginUser user) {
        Context ctx = context(range);
        Page<TurnoverRow> full = new ExportPage<>(limit == null ? 5000 : limit);
        IPage<TurnoverRow> base = mapper.selectTurnoverPage(full, categoryId);
        Map<Long, List<TurnoverRow>> byCat = base.getRecords().stream()
                .map(ctx::compute)
                .collect(Collectors.groupingBy(TurnoverRow::categoryId, LinkedHashMap::new, Collectors.toList()));
        List<TurnoverRow> rows = new ArrayList<>();
        for (Map.Entry<Long, List<TurnoverRow>> e : byCat.entrySet()) {
            List<TurnoverRow> goods = e.getValue();
            String name = goods.get(0).categoryName();
            TurnoverRow agg = new TurnoverRow(null, null, null, e.getKey(), name,
                    sum(goods, TurnoverRow::stockQty),
                    sum(goods, TurnoverRow::costAmount),
                    sum(goods, TurnoverRow::beginAmount),
                    sum(goods, TurnoverRow::avgAmount),
                    sum(goods, TurnoverRow::saleCostAmount),
                    sum(goods, TurnoverRow::returnCostAmount),
                    sum(goods, TurnoverRow::flowAmount), null, null);
            rows.add(ctx.withRate(agg));
        }
        rows.sort((a, b) -> nvl(b.costAmount()).compareTo(nvl(a.costAmount())));
        boolean canProfit = canProfit(user);
        return rows.stream().map(r -> mask(r, canProfit)).toList();
    }

    /**
     * 损耗报表（⑤）：报损 + 盘亏按商品汇总（与销售退货分开统计，FR-INV-10）；
     * 损耗率 = 损耗金额 / 期初库存金额 × 100%；按审核营业日区间聚合。
     * 导出行数由调用方按合并后的商品行数 checkLimit（FR-RPT-08）。
     */
    public LossReportVO loss(ReportRange range, LoginUser user) {
        List<LossRow> losses = mapper.selectLossByGoods(range.beginDate(), range.endDate());
        List<LossRow> shortages = mapper.selectShortageByGoods(range.beginDate(), range.endDate());

        Map<Long, LossGoodsRow> merged = new LinkedHashMap<>();
        for (LossRow l : losses) {
            LossGoodsRow r = merged.computeIfAbsent(l.goodsId(),
                    k -> new LossGoodsRow(k, l.goodsName(), l.categoryName(),
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
            if ("OVERFLOW".equals(l.bizType())) {
                continue;
            }
            BigDecimal lossQty = r.lossQty().add(l.lossQty());
            BigDecimal lossAmount = r.lossAmount().add(l.lossAmount());
            merged.put(l.goodsId(), new LossGoodsRow(r.goodsId(), r.goodsName(), r.categoryName(),
                    lossQty, lossAmount, r.checkQty(), r.checkAmount(),
                    lossAmount.add(r.checkAmount())));
        }
        for (LossRow s : shortages) {
            LossGoodsRow r = merged.computeIfAbsent(s.goodsId(),
                    k -> new LossGoodsRow(k, s.goodsName(), s.categoryName(),
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
            BigDecimal checkQty = r.checkQty().add(s.lossQty());
            BigDecimal checkAmount = r.checkAmount().add(s.lossAmount());
            merged.put(s.goodsId(), new LossGoodsRow(r.goodsId(), r.goodsName(), r.categoryName(),
                    r.lossQty(), r.lossAmount(), checkQty, checkAmount,
                    r.lossAmount().add(checkAmount)));
        }
        List<LossGoodsRow> rows = new ArrayList<>(merged.values());
        rows.sort((a, b) -> b.totalAmount().compareTo(a.totalAmount()));

        BigDecimal totalLoss = rows.stream()
                .map(LossGoodsRow::totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalOverflow = losses.stream()
                .filter(l -> "OVERFLOW".equals(l.bizType()))
                .map(LossRow::lossAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal beginAmount = mapper.selectBeginInventoryAmount(range.beginDate(), range.endDate());
        BigDecimal rate = null;
        if (beginAmount != null && beginAmount.compareTo(BigDecimal.ZERO) != 0) {
            rate = totalLoss.multiply(BigDecimal.valueOf(100))
                    .divide(beginAmount, 2, RoundingMode.HALF_UP);
        }
        return mask(new LossReportVO(rows, totalLoss, totalOverflow, beginAmount, rate),
                canProfit(user));
    }

    private Context context(ReportRange range) {
        Map<Long, TurnoverRow> flows = index(mapper.selectFlowAmountByGoods(range.beginDate(), range.endDate()));
        Map<Long, TurnoverRow> saleCosts = index(mapper.selectSaleCostByGoods(range.beginDate(), range.endDate()));
        Map<Long, TurnoverRow> returnCosts = index(mapper.selectReturnCostByGoods(range.beginDate(), range.endDate()));
        return new Context(flows, saleCosts, returnCosts, range.days());
    }

    private Map<Long, TurnoverRow> index(List<TurnoverRow> rows) {
        return rows.stream().collect(Collectors.toMap(TurnoverRow::goodsId, r -> r, (a, b) -> a));
    }

    private static BigDecimal sum(List<TurnoverRow> rows, Function<TurnoverRow, BigDecimal> f) {
        return rows.stream().map(f).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal suggestK() {
        try {
            return new BigDecimal(configService.getStr("suggest_order_k", "1.5"));
        } catch (Exception e) {
            return new BigDecimal("1.5");
        }
    }

    private int avgDays() {
        return configService.getInt("suggest_order_days", 28);
    }

    private int leadDays() {
        return configService.getInt("default_lead_time_days", 3);
    }

    private static boolean canProfit(LoginUser user) {
        return RptRoles.canViewProfit(user);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    // ---------- BR-RPT-07/GAP-P0-10③：无毛利权限时成本类字段不返回（而非前端隐藏） ----------

    private List<InvSummaryRow> maskSummaries(List<InvSummaryRow> rows, boolean canProfit) {
        if (canProfit) {
            return rows;
        }
        return rows.stream()
                .map(r -> new InvSummaryRow(r.categoryId(), r.categoryName(), r.goodsId(),
                        r.goodsCode(), r.goodsName(), r.goodsCount(), r.stockQty(),
                        null, null, r.retailValue(), r.stockStatus()))
                .toList();
    }

    private InvChangeRow mask(InvChangeRow r, boolean canProfit) {
        if (canProfit) {
            return r;
        }
        return new InvChangeRow(r.recordType(), r.recordCount(), r.changeQty(), null);
    }

    private TurnoverRow mask(TurnoverRow r, boolean canProfit) {
        if (canProfit) {
            return r;
        }
        return new TurnoverRow(r.goodsId(), r.goodsCode(), r.goodsName(), r.categoryId(), r.categoryName(),
                r.stockQty(), null, null, null, null, null, null, r.turnoverRate(), r.turnoverDays());
    }

    private LossReportVO mask(LossReportVO vo, boolean canProfit) {
        if (canProfit) {
            return vo;
        }
        List<LossGoodsRow> rows = vo.rows().stream()
                .map(r -> new LossGoodsRow(r.goodsId(), r.goodsName(), r.categoryName(),
                        r.lossQty(), null, r.checkQty(), null, null))
                .toList();
        return new LossReportVO(rows, null, null, null, null);
    }

    /** 周转计算上下文 */
    private final class Context {
        private final Map<Long, TurnoverRow> flows;
        private final Map<Long, TurnoverRow> saleCosts;
        private final Map<Long, TurnoverRow> returnCosts;
        private final long days;

        private Context(Map<Long, TurnoverRow> flows, Map<Long, TurnoverRow> saleCosts,
                        Map<Long, TurnoverRow> returnCosts, long days) {
            this.flows = flows;
            this.saleCosts = saleCosts;
            this.returnCosts = returnCosts;
            this.days = days;
        }

        /** 期初 = 期末结存成本 − Σ(区间流水金额)；平均库存 = (期初+期末)/2 */
        private TurnoverRow compute(TurnoverRow row) {
            BigDecimal flow = val(row.goodsId(), flows, TurnoverRow::flowAmount);
            BigDecimal saleCost = val(row.goodsId(), saleCosts, TurnoverRow::saleCostAmount);
            BigDecimal returnCost = val(row.goodsId(), returnCosts, TurnoverRow::returnCostAmount);
            BigDecimal costAmount = nvl(row.costAmount());
            BigDecimal begin = costAmount.subtract(flow);
            BigDecimal avg = begin.add(costAmount)
                    .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
            return new TurnoverRow(row.goodsId(), row.goodsCode(), row.goodsName(),
                    row.categoryId(), row.categoryName(), row.stockQty(),
                    costAmount, begin, avg, saleCost, returnCost, flow, null, null);
        }

        /** 周转率 = 销售成本(净) / 平均库存金额；周转天数 = 区间天数 / 周转率 */
        private TurnoverRow withRate(TurnoverRow row) {
            BigDecimal netCost = nvl(row.saleCostAmount()).subtract(nvl(row.returnCostAmount()));
            BigDecimal avgAmount = nvl(row.avgAmount());
            BigDecimal rate = null;
            BigDecimal dayCount = null;
            if (avgAmount.compareTo(BigDecimal.ZERO) > 0) {
                rate = netCost.divide(avgAmount, 4, RoundingMode.HALF_UP);
                if (rate.compareTo(BigDecimal.ZERO) > 0) {
                    dayCount = BigDecimal.valueOf(days).divide(rate, 2, RoundingMode.HALF_UP);
                }
            }
            return new TurnoverRow(row.goodsId(), row.goodsCode(), row.goodsName(),
                    row.categoryId(), row.categoryName(), row.stockQty(),
                    row.costAmount(), row.beginAmount(), row.avgAmount(),
                    row.saleCostAmount(), row.returnCostAmount(), row.flowAmount(), rate, dayCount);
        }

        private BigDecimal val(Long goodsId, Map<Long, TurnoverRow> map, Function<TurnoverRow, BigDecimal> f) {
            TurnoverRow row = map.get(goodsId);
            return row == null ? BigDecimal.ZERO : nvl(f.apply(row));
        }
    }

    /** 损耗商品行：报损与盘亏分开列示，合计为损耗金额（合计作为分量随 JSON 输出，供列表/打印使用） */
    public record LossGoodsRow(
            Long goodsId,
            String goodsName,
            String categoryName,
            BigDecimal lossQty,
            BigDecimal lossAmount,
            BigDecimal checkQty,
            BigDecimal checkAmount,
            BigDecimal totalAmount) {
    }

    /** 损耗报表结果（FR-RPT-04⑤） */
    public record LossReportVO(
            List<LossGoodsRow> rows,
            /** 损耗金额合计 = 报损 + 盘亏 */
            BigDecimal totalLossAmount,
            /** 报溢金额合计（参考） */
            BigDecimal totalOverflowAmount,
            /** 期初库存金额 */
            BigDecimal beginInventoryAmount,
            /** 损耗率 = 损耗金额 / 期初库存金额 × 100% */
            BigDecimal lossRate) {
    }
}

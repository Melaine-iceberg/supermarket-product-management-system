package com.smms.rpt.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.PageVO;
import com.smms.rpt.dto.InvChangeRow;
import com.smms.rpt.dto.InvSummaryRow;
import com.smms.rpt.dto.LossRow;
import com.smms.rpt.dto.ReportRange;
import com.smms.rpt.dto.TurnoverRow;
import com.smms.rpt.dto.WarningRptRow;
import com.smms.rpt.mapper.InventoryReportMapper;
import com.smms.security.LoginUser;
import com.smms.sys.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 库存报表（FR-RPT-04）：库存汇总表、库存预警报表（含建议采购量）、
 * 库存变动明细报表、库存周转率与周转天数、损耗报表。
 * 期初库存金额 = 期末库存成本 − Σ(区间流水变动数量×单位成本)（库存只能由流水驱动，BR-INV-11）。
 */
@Service
@RequiredArgsConstructor
public class InventoryReportService {

    private final InventoryReportMapper mapper;
    private final SysConfigService configService;

    /** 库存汇总表（①，商品维度）：结存数量、成本单价、库存成本金额、零售价值、库存状态 */
    public PageVO<InvSummaryRow> summaryByGoods(Long categoryId, int page, int size) {
        int pageNo = Math.max(page, 1);
        int pageSize = size <= 0 ? 20 : Math.min(size, 200);
        long total = mapper.countGoodsSummary(categoryId);
        if (total == 0) {
            return new PageVO<>(List.of(), 0);
        }
        Page<InvSummaryRow> p = new Page<>(pageNo, pageSize, false);
        p.setTotal(total);
        return PageVO.of(mapper.selectGoodsSummary(p, categoryId));
    }

    /** 库存汇总表导出（FR-RPT-08）：商品维度全量，取前 limit 行，超限由调用方校验 */
    public List<InvSummaryRow> summaryByGoodsForExport(Long categoryId, int limit) {
        return mapper.selectGoodsSummary(new Page<>(1, limit, false), categoryId).getRecords();
    }

    /** 库存汇总表（①，分类维度） */
    public List<InvSummaryRow> summaryByCategory() {
        return mapper.selectCategorySummary();
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

    /** 库存预警报表导出（FR-RPT-08）：取前 limit 行，超限由调用方校验 */
    public List<WarningRptRow> warningsForExport(String alertType, int limit) {
        return mapper.selectWarnings(new Page<>(1, limit, false),
                alertType, suggestK(), avgDays(), leadDays()).getRecords();
    }

    /** 库存变动明细报表（③）：按变动类型汇总数量与金额 */
    public List<InvChangeRow> changes(ReportRange range) {
        return mapper.selectChangeSummary(range.beginDate().atStartOfDay(),
                range.endDate().plusDays(1).atStartOfDay());
    }

    /** 库存周转率与周转天数（④，商品维度分页） */
    public PageVO<TurnoverRow> turnoverByGoods(ReportRange range, Long categoryId, int page, int size) {
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
        result.setRecords(result.getRecords().stream()
                .map(r -> ctx.compute(r))
                .map(r -> ctx.withRate(r))
                .toList());
        return PageVO.of(result);
    }

    /** 库存周转导出（FR-RPT-08）：商品维度，取前 limit 行，超限由调用方校验 */
    public List<TurnoverRow> turnoverByGoodsForExport(ReportRange range, Long categoryId, int limit) {
        Context ctx = context(range);
        IPage<TurnoverRow> result = mapper.selectTurnoverPage(new Page<>(1, limit, false), categoryId);
        return result.getRecords().stream()
                .map(r -> ctx.compute(r))
                .map(r -> ctx.withRate(r))
                .toList();
    }

    /** 库存周转率与周转天数（④，分类维度汇总） */
    public List<TurnoverRow> turnoverByCategory(ReportRange range, Long categoryId) {
        Context ctx = context(range);
        Page<TurnoverRow> full = new Page<>(1, 5000, false);
        full.setTotal(5000);
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
        rows.sort((a, b) -> b.costAmount().compareTo(a.costAmount()));
        return rows;
    }

    /**
     * 损耗报表（⑤）：报损 + 盘亏按商品汇总（与销售退货分开统计，FR-INV-10）；
     * 损耗率 = 损耗金额 / 期初库存金额 × 100%。
     */
    public LossReportVO loss(ReportRange range) {
        LocalDateTime beginTime = range.beginDate().atStartOfDay();
        LocalDateTime endTime = range.endDate().plusDays(1).atStartOfDay();
        List<LossRow> losses = mapper.selectLossByGoods(beginTime, endTime);
        List<LossRow> shortages = mapper.selectShortageByGoods(beginTime, endTime);

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
        BigDecimal beginAmount = mapper.selectBeginInventoryAmount(beginTime, endTime);
        BigDecimal rate = null;
        if (beginAmount != null && beginAmount.compareTo(BigDecimal.ZERO) != 0) {
            rate = totalLoss.multiply(BigDecimal.valueOf(100))
                    .divide(beginAmount, 2, RoundingMode.HALF_UP);
        }
        return new LossReportVO(rows, totalLoss, totalOverflow, beginAmount, rate);
    }

    private Context context(ReportRange range) {
        LocalDateTime beginTime = range.beginDate().atStartOfDay();
        LocalDateTime endTime = range.endDate().plusDays(1).atStartOfDay();
        Map<Long, TurnoverRow> flows = index(mapper.selectFlowAmountByGoods(beginTime, endTime));
        Map<Long, TurnoverRow> saleCosts = index(mapper.selectSaleCostByGoods(range.beginDate(), range.endDate()));
        Map<Long, TurnoverRow> returnCosts = index(mapper.selectReturnCostByGoods(range.beginDate(), range.endDate()));
        return new Context(flows, saleCosts, returnCosts, range.days());
    }

    private Map<Long, TurnoverRow> index(List<TurnoverRow> rows) {
        return rows.stream().collect(Collectors.toMap(TurnoverRow::goodsId, r -> r, (a, b) -> a));
    }

    private static BigDecimal sum(List<TurnoverRow> rows, Function<TurnoverRow, BigDecimal> f) {
        return rows.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
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
            BigDecimal begin = row.costAmount().subtract(flow);
            BigDecimal avg = begin.add(row.costAmount())
                    .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
            return new TurnoverRow(row.goodsId(), row.goodsCode(), row.goodsName(),
                    row.categoryId(), row.categoryName(), row.stockQty(),
                    row.costAmount(), begin, avg, saleCost, returnCost, flow, null, null);
        }

        /** 周转率 = 销售成本(净) / 平均库存金额；周转天数 = 区间天数 / 周转率 */
        private TurnoverRow withRate(TurnoverRow row) {
            BigDecimal netCost = row.saleCostAmount().subtract(row.returnCostAmount());
            BigDecimal rate = null;
            BigDecimal dayCount = null;
            if (row.avgAmount().compareTo(BigDecimal.ZERO) > 0) {
                rate = netCost.divide(row.avgAmount(), 4, RoundingMode.HALF_UP);
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
            return row == null ? BigDecimal.ZERO : f.apply(row);
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

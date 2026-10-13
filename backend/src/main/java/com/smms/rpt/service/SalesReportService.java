package com.smms.rpt.service;

import com.smms.rpt.dto.ReportRange;
import com.smms.rpt.dto.RptRoles;
import com.smms.rpt.dto.SalesGroupRow;
import com.smms.rpt.dto.SalesMetrics;
import com.smms.rpt.dto.SalesReportRow;
import com.smms.rpt.dto.SalesReportVO;
import com.smms.rpt.mapper.ReportMapper;
import com.smms.security.LoginUser;
import com.smms.sys.service.SysDictService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 销售报表服务（FR-RPT-02）：日报/月报、按收款方式、按时段（小时）、
 * 按收银员/收银台业绩、会员单与散客单占比。
 * 口径统一见 BR-RPT-01；毛利类字段按角色过滤（BR-RPT-07）。
 */
@Service
@RequiredArgsConstructor
public class SalesReportService {

    private final ReportMapper reportMapper;
    private final SysDictService dictService;

    /** 销售日报/月报（①）：日期、单数、件数、销售额、优惠额、净销售额、退货额、净销售、销售成本、毛利额、毛利率、客单价 */
    public SalesReportVO daily(ReportRange range, boolean monthly, LoginUser user) {
        List<SalesGroupRow> sales = reportMapper.selectDailySales(range.beginDate(), range.endDate(), monthly);
        List<SalesGroupRow> returns = reportMapper.selectDailyReturns(range.beginDate(), range.endDate(), monthly);

        Map<String, Cell> merged = new TreeMap<>();
        for (SalesGroupRow s : sales) {
            Cell c = merged.computeIfAbsent(s.groupKey(), k -> new Cell());
            c.sales = s;
            c.date = s.bizDate();
        }
        for (SalesGroupRow r : returns) {
            Cell c = merged.computeIfAbsent(r.groupKey(), k -> new Cell());
            c.returns = r;
            if (c.date == null) {
                c.date = r.bizDate();
            }
        }
        // 缺失日期补零（日报连续，便于画趋势图）
        if (!monthly) {
            LocalDate d = range.beginDate();
            while (!d.isAfter(range.endDate())) {
                merged.computeIfAbsent(d.toString(), k -> new Cell()).date = d;
                d = d.plusDays(1);
            }
        }
        List<SalesReportRow> rows = new ArrayList<>();
        for (Map.Entry<String, Cell> e : merged.entrySet()) {
            Cell c = e.getValue();
            rows.add(toRow(e.getKey(), c.date, c.sales, c.returns, null, canProfit(user)));
        }
        return new SalesReportVO(mask(rows, canProfit(user)), total(rows, canProfit(user)));
    }

    /** 按收款方式汇总（②） */
    public List<SalesReportRow> byPayMethod(ReportRange range, LoginUser user) {
        boolean canProfit = canProfit(user);
        return mask(reportMapper.selectByPayMethod(range.beginDate(), range.endDate()).stream()
                .map(r -> toRow(payLabel(r.groupKey()), null, r, null, null, canProfit)).toList(), canProfit);
    }

    /** 按时段（小时）分布（③）：0~23 小时补零，用于排班分析 */
    public List<SalesReportRow> byHour(ReportRange range, LoginUser user) {
        Map<Integer, SalesGroupRow> byHour = new TreeMap<>();
        for (SalesGroupRow r : reportMapper.selectByHour(range.beginDate(), range.endDate())) {
            byHour.put(Integer.parseInt(r.groupKey()), r);
        }
        List<SalesReportRow> rows = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            SalesGroupRow r = byHour.get(h);
            rows.add(toRow(h + "时", null, r, null, null, canProfit(user)));
        }
        return mask(rows, canProfit(user));
    }

    /** 按收银员业绩汇总（④）：销售与退货合并冲减 */
    public List<SalesReportRow> byCashier(ReportRange range, LoginUser user) {
        Map<String, Cell> merged = new LinkedHashMap<>();
        for (SalesGroupRow s : reportMapper.selectByCashier(range.beginDate(), range.endDate())) {
            merged.computeIfAbsent(s.groupKey(), k -> new Cell()).sales = s;
        }
        for (SalesGroupRow r : reportMapper.selectReturnsByCashier(range.beginDate(), range.endDate())) {
            merged.computeIfAbsent(r.groupKey(), k -> new Cell()).returns = r;
        }
        List<SalesReportRow> rows = merged.entrySet().stream()
                .map(e -> toRow(e.getKey(), null, e.getValue().sales, e.getValue().returns, null, canProfit(user)))
                .toList();
        // 按净销售倒序
        rows = new ArrayList<>(rows);
        rows.sort((a, b) -> b.netSalesAmount().compareTo(a.netSalesAmount()));
        return mask(rows, canProfit(user));
    }

    /** 按收银台业绩汇总（④） */
    public List<SalesReportRow> byCounter(ReportRange range, LoginUser user) {
        boolean canProfit = canProfit(user);
        List<SalesReportRow> rows = reportMapper.selectByCounter(range.beginDate(), range.endDate()).stream()
                .map(r -> toRow("0" + r.groupKey() + "号台", null, r, null, null, canProfit)).toList();
        return mask(rows, canProfit);
    }

    /** 会员单与散客单占比（⑤） */
    public List<SalesReportRow> memberRatio(ReportRange range, LoginUser user) {
        List<SalesGroupRow> raw = reportMapper.selectMemberRatio(range.beginDate(), range.endDate());
        BigDecimal totalNet = raw.stream()
                .map(SalesGroupRow::netSalesAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<SalesReportRow> rows = new ArrayList<>();
        for (SalesGroupRow r : raw) {
            SalesReportRow row = toRow("MEMBER".equals(r.groupKey()) ? "会员单" : "散客单",
                    null, r, null, totalNet, false);
            rows.add(row);
        }
        return rows;
    }

    /** 合计行：与逐行之和一致（AC-16） */
    private SalesReportRow total(List<SalesReportRow> rows, boolean canProfit) {
        SalesMetrics m = new SalesMetrics(
                rows.stream().mapToLong(SalesReportRow::orderCount).sum(),
                sum(rows, SalesReportRow::salesAmount),
                sum(rows, SalesReportRow::discountAmount),
                sum(rows, SalesReportRow::netSalesAmount),
                sum(rows, SalesReportRow::refundAmount),
                BigDecimal.ZERO,
                sum(rows, SalesReportRow::costAmount));
        SalesReportRow total = toRow("TOTAL", null, m, null, null);
        return canProfit ? total : maskRow(total);
    }

    /** 分组行 → 报表行（BR-RPT-01 口径派生） */
    private SalesReportRow toRow(String groupKey, LocalDate bizDate, SalesGroupRow s, SalesGroupRow r,
                                 BigDecimal totalNet, boolean withProfit) {
        SalesMetrics m = metrics(s, r);
        BigDecimal shareRate = SalesMetrics.share(m.netSalesAmount(), totalNet);
        return new SalesReportRow(groupKey, bizDate,
                m.orderCount(),
                sumItem(s),
                m.salesAmount(), m.discountAmount(), m.netSalesAmount(),
                m.refundAmount(), r == null ? null : r.returnCount(),
                m.netAmount(),
                withProfit ? m.netCostAmount() : null,
                withProfit ? m.profitAmount() : null,
                withProfit ? m.profitRate() : null,
                m.avgOrderAmount(),
                shareRate);
    }

    private SalesReportRow toRow(String groupKey, LocalDate bizDate, SalesMetrics m, SalesGroupRow s, SalesGroupRow r) {
        return new SalesReportRow(groupKey, bizDate, m.orderCount(), sumItem(s),
                m.salesAmount(), m.discountAmount(), m.netSalesAmount(), m.refundAmount(),
                r == null ? null : r.returnCount(), m.netAmount(),
                m.netCostAmount(), m.profitAmount(), m.profitRate(), m.avgOrderAmount(), null);
    }

    private SalesMetrics metrics(SalesGroupRow s, SalesGroupRow r) {
        return new SalesMetrics(
                s == null ? 0 : s.orderCount(),
                s == null ? null : s.salesAmount(),
                s == null ? null : s.discountAmount(),
                s == null ? null : s.netSalesAmount(),
                r == null ? null : r.refundAmount(),
                r == null ? null : r.returnCostAmount(),
                s == null ? null : s.costAmount() == null ? BigDecimal.ZERO : s.costAmount());
    }

    private Long sumItem(SalesGroupRow s) {
        return s == null || s.itemCount() == null ? null : s.itemCount();
    }

    private static BigDecimal sum(List<SalesReportRow> rows, java.util.function.Function<SalesReportRow, BigDecimal> f) {
        return rows.stream().map(f).filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 收款方式编码 → 中文（GAP-P1-09：优先取数据字典 pay_type，含停用项；字典缺失时回退内置标签） */
    private String payLabel(String code) {
        if (code == null || code.isBlank()) {
            return code;
        }
        String label = dictService.valueLabels("pay_type").get(code);
        if (label != null) {
            return label;
        }
        return switch (code) {
            case "CASH" -> "现金";
            case "BANK" -> "银行卡";
            case "MOBILE" -> "移动支付";
            case "BALANCE" -> "会员储值";
            default -> code;
        };
    }

    /** BR-RPT-07：收银员等无毛利权限时，成本/毛利字段不返回 */
    private List<SalesReportRow> mask(List<SalesReportRow> rows, boolean canProfit) {
        if (canProfit) {
            return rows;
        }
        return rows.stream().map(this::maskRow).toList();
    }

    private SalesReportRow maskRow(SalesReportRow row) {
        return new SalesReportRow(row.groupKey(), row.bizDate(), row.orderCount(), row.itemCount(),
                row.salesAmount(), row.discountAmount(), row.netSalesAmount(), row.refundAmount(),
                row.returnCount(), row.netAmount(), null, null, null, row.avgOrderAmount(), row.shareRate());
    }

    private static boolean canProfit(LoginUser user) {
        return RptRoles.canViewProfit(user);
    }

    /** 销售行与退货行的合并单元 */
    private static final class Cell {
        SalesGroupRow sales;
        SalesGroupRow returns;
        LocalDate date;
    }
}

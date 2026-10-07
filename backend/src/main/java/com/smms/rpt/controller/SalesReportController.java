package com.smms.rpt.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.common.task.AsyncTaskService;
import com.smms.common.task.AsyncTaskVO;
import com.smms.common.task.TaskParams;
import com.smms.rpt.dto.DrillOrderQuery;
import com.smms.rpt.dto.DrillOrderRow;
import com.smms.rpt.dto.ReportRange;
import com.smms.rpt.dto.SalesReportRow;
import com.smms.rpt.dto.SalesReportVO;
import com.smms.rpt.service.DrillDownService;
import com.smms.rpt.service.ReportExcelService;
import com.smms.rpt.service.SalesReportService;
import com.smms.security.LoginUser;
import com.smms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.smms.rpt.service.ReportExcelService.amount;
import static com.smms.rpt.service.ReportExcelService.percent;
import static com.smms.rpt.service.ReportExcelService.text;

/**
 * 销售报表接口（FR-RPT-02）：日报/月报、按收款方式、按时段（小时）、
 * 按收银员/收银台业绩、会员单与散客单占比；支持导出 Excel（FR-RPT-08）。
 * 查询区间默认"今日"，最大跨度 1 年（BR-RPT-04）；口径统一见 BR-RPT-01。
 */
@RestController
@RequestMapping("/api/v1/report/sales")
@RequiredArgsConstructor
public class SalesReportController {

    private static final String BIZ_EXPORT = "SALES_REPORT_EXPORT";

    private static final String[] COLUMNS = {
            "日期/分组", "单数", "件数", "销售额", "优惠额", "净销售额", "退货额", "退货单数",
            "净销售", "销售成本", "毛利额", "毛利率", "客单价", "占比"
    };

    private final SalesReportService salesReportService;
    private final DrillDownService drillDownService;
    private final ReportExcelService excelService;
    private final AsyncTaskService taskService;

    /** 销售日报/月报（①） */
    @GetMapping("/daily")
    @RequirePerm("sys:rpt:sales")
    public ApiResponse<SalesReportVO> daily(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "false") boolean monthly) {
        return ApiResponse.ok(salesReportService.daily(
                new ReportRange(beginDate, endDate), monthly, SecurityUtils.getLoginUser()));
    }

    /** 按收款方式汇总（②） */
    @GetMapping("/by-pay-method")
    @RequirePerm("sys:rpt:sales")
    public ApiResponse<List<SalesReportRow>> byPayMethod(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(salesReportService.byPayMethod(
                new ReportRange(beginDate, endDate), SecurityUtils.getLoginUser()));
    }

    /** 按时段（小时）分布（③）：用于排班分析 */
    @GetMapping("/by-hour")
    @RequirePerm("sys:rpt:sales")
    public ApiResponse<List<SalesReportRow>> byHour(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(salesReportService.byHour(
                new ReportRange(beginDate, endDate), SecurityUtils.getLoginUser()));
    }

    /** 按收银员业绩汇总（④）：销售与退货合并冲减 */
    @GetMapping("/by-cashier")
    @RequirePerm("sys:rpt:sales")
    public ApiResponse<List<SalesReportRow>> byCashier(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(salesReportService.byCashier(
                new ReportRange(beginDate, endDate), SecurityUtils.getLoginUser()));
    }

    /** 按收银台业绩汇总（④） */
    @GetMapping("/by-counter")
    @RequirePerm("sys:rpt:sales")
    public ApiResponse<List<SalesReportRow>> byCounter(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(salesReportService.byCounter(
                new ReportRange(beginDate, endDate), SecurityUtils.getLoginUser()));
    }

    /** 会员单与散客单占比（⑤） */
    @GetMapping("/member-ratio")
    @RequirePerm("sys:rpt:sales")
    public ApiResponse<List<SalesReportRow>> memberRatio(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(salesReportService.memberRatio(
                new ReportRange(beginDate, endDate), SecurityUtils.getLoginUser()));
    }

    /** 汇总数字下钻（BR-RPT-06）：构成该汇总的销售单列表，逐单金额与汇总一致（AC-16）；
     *  支持按收款方式/时段/收银员/收银台/会员单筛选，与对应报表行同口径 */
    @GetMapping("/drill-orders")
    @RequirePerm("sys:rpt:sales")
    public ApiResponse<PageVO<DrillOrderRow>> drillOrders(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String payType,
            @RequestParam(required = false) Integer hour,
            @RequestParam(required = false) String cashierName,
            @RequestParam(required = false) Integer counterNo,
            @RequestParam(required = false) Boolean memberOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        ReportRange range = new ReportRange(beginDate, endDate);
        DrillOrderQuery q = new DrillOrderQuery(range.beginDate(), range.endDate(),
                null, null, cashierName, counterNo, memberOnly, null,
                payType, hour, page, size);
        return ApiResponse.ok(drillDownService.orders(q, SecurityUtils.getLoginUser()));
    }

    /** 销售报表导出（FR-RPT-08）：view=daily|pay-method|hour|cashier|counter|member-ratio。后台异步生成 */
    @GetMapping("/export")
    @RequirePerm("sys:rpt:sales")
    @OpLog(module = "统计报表", action = "导出销售报表", target = "#view")
    public ApiResponse<AsyncTaskVO> export(
            @RequestParam(defaultValue = "daily") String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "false") boolean monthly) {
        return ApiResponse.ok(taskService.submitExport(BIZ_EXPORT, "销售报表导出",
                TaskParams.of("view", view, "beginDate", beginDate, "endDate", endDate, "monthly", monthly),
                ctx -> {
                    LoginUser user = ctx.user();
                    ReportRange range = new ReportRange(beginDate, endDate);
                    String sheet = "销售报表";
                    List<String[]> rows = new ArrayList<>();
                    if ("daily".equals(view)) {
                        sheet = monthly ? "销售月报" : "销售日报";
                        SalesReportVO vo = salesReportService.daily(range, monthly, user);
                        for (SalesReportRow r : vo.rows()) {
                            rows.add(line(monthly ? r.groupKey() : text(r.bizDate()), r));
                        }
                        if (vo.total() != null) {
                            rows.add(line("合计", vo.total()));
                        }
                    } else {
                        List<SalesReportRow> list = switch (view) {
                            case "pay-method" -> {
                                sheet = "收款方式汇总";
                                yield salesReportService.byPayMethod(range, user);
                            }
                            case "hour" -> {
                                sheet = "时段分布";
                                yield salesReportService.byHour(range, user);
                            }
                            case "cashier" -> {
                                sheet = "收银员业绩";
                                yield salesReportService.byCashier(range, user);
                            }
                            case "counter" -> {
                                sheet = "收银台业绩";
                                yield salesReportService.byCounter(range, user);
                            }
                            case "member-ratio" -> {
                                sheet = "会员单占比";
                                yield salesReportService.memberRatio(range, user);
                            }
                            default -> throw new BusinessException(ErrorCode.PARAM_INVALID, "导出视图不合法");
                        };
                        for (SalesReportRow r : list) {
                            rows.add(line(r.groupKey(), r));
                        }
                    }
                    excelService.checkLimit(rows.size());
                    ctx.fileName("sales-" + view + ".xlsx");
                    return excelService.build(new ReportExcelService.Table(sheet, COLUMNS, rows));
                }));
    }

    private String[] line(String group, SalesReportRow r) {        return new String[]{
                group,
                String.valueOf(r.orderCount()),
                text(r.itemCount()),
                amount(r.salesAmount()),
                amount(r.discountAmount()),
                amount(r.netSalesAmount()),
                amount(r.refundAmount()),
                text(r.returnCount()),
                amount(r.netAmount()),
                amount(r.costAmount()),
                amount(r.profitAmount()),
                percent(r.profitRate()),
                amount(r.avgOrderAmount()),
                percent(r.shareRate())
        };
    }
}

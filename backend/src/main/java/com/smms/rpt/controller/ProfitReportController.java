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
import com.smms.rpt.dto.GoodsRankRow;
import com.smms.rpt.dto.NegativeProfitRow;
import com.smms.rpt.dto.ReportRange;
import com.smms.rpt.dto.SalesReportVO;
import com.smms.rpt.service.DrillDownService;
import com.smms.rpt.service.ProfitReportService;
import com.smms.rpt.service.ReportExcelService;
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
import static com.smms.rpt.service.ReportExcelService.qty;
import static com.smms.rpt.service.ReportExcelService.text;

/**
 * 毛利分析接口（FR-RPT-05）：按商品/分类/时段分析毛利额与毛利率、
 * 负毛利商品清单（成交价低于成本价）。毛利类指标仅店长及以上角色可见
 * （BR-RPT-07，菜单已限制，服务层再校验）。
 */
@RestController
@RequestMapping("/api/v1/report/profit")
@RequiredArgsConstructor
public class ProfitReportController {

    private static final String BIZ_EXPORT = "PROFIT_REPORT_EXPORT";


    private static final String[] GOODS_COLUMNS = {
            "商品编码", "商品名称", "分类", "品牌", "单数", "销量", "销售额", "优惠额",
            "净销售额", "退货额", "销售成本", "毛利额", "毛利率", "占比"
    };

    private static final String[] DAY_COLUMNS = {
            "日期", "单数", "件数", "销售额", "优惠额", "净销售额", "退货额",
            "净销售", "销售成本", "毛利额", "毛利率", "客单价"
    };

    private static final String[] NEGATIVE_COLUMNS = {
            "商品编码", "商品名称", "分类", "成交单价", "成本单价", "单位毛利",
            "销量", "单数", "实付金额", "销售成本", "毛利额"
    };

    private final ProfitReportService profitReportService;
    private final DrillDownService drillDownService;
    private final ReportExcelService excelService;
    private final AsyncTaskService taskService;

    /** 按商品分析毛利（默认按毛利额倒序，含毛利率） */
    @GetMapping("/by-goods")
    @RequirePerm("sys:rpt:profit")
    public ApiResponse<PageVO<GoodsRankRow>> byGoods(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(profitReportService.byGoods(
                new ReportRange(beginDate, endDate), categoryId, page, size,
                SecurityUtils.getLoginUser()));
    }

    /** 按分类分析毛利（含毛利率与占比） */
    @GetMapping("/by-category")
    @RequirePerm("sys:rpt:profit")
    public ApiResponse<List<GoodsRankRow>> byCategory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(profitReportService.byCategory(
                new ReportRange(beginDate, endDate), SecurityUtils.getLoginUser()));
    }

    /** 按时段（营业日）分析毛利：与销售日报同口径 */
    @GetMapping("/by-day")
    @RequirePerm("sys:rpt:profit")
    public ApiResponse<SalesReportVO> byDay(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(profitReportService.byDay(
                new ReportRange(beginDate, endDate), SecurityUtils.getLoginUser()));
    }

    /** 负毛利商品清单：成交价低于成本价，用于发现定价错误与异常改价 */
    @GetMapping("/negative")
    @RequirePerm("sys:rpt:profit")
    public ApiResponse<List<NegativeProfitRow>> negative(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(profitReportService.negative(
                new ReportRange(beginDate, endDate), SecurityUtils.getLoginUser()));
    }

    /** 汇总数字下钻（BR-RPT-06）：构成毛利汇总的销售单列表（成本/毛利按角色过滤，BR-RPT-07） */
    @GetMapping("/drill-orders")
    @RequirePerm("sys:rpt:profit")
    public ApiResponse<PageVO<DrillOrderRow>> drillOrders(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long goodsId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        ReportRange range = new ReportRange(beginDate, endDate);
        DrillOrderQuery q = new DrillOrderQuery(range.beginDate(), range.endDate(),
                goodsId, categoryId, null, null, null, null, null, null, page, size);
        return ApiResponse.ok(drillDownService.orders(q, SecurityUtils.getLoginUser()));
    }

    /** 毛利分析导出（FR-RPT-08）：view=by-goods|by-category|by-day|negative */
    @GetMapping("/export")
    @RequirePerm("sys:rpt:profit")
    @OpLog(module = "统计报表", action = "导出毛利分析", target = "#view")
    public ApiResponse<AsyncTaskVO> export(
            @RequestParam(defaultValue = "by-goods") String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long categoryId) {
        return ApiResponse.ok(taskService.submitExport(BIZ_EXPORT, "毛利报表导出",
                TaskParams.of("view", view, "beginDate", beginDate, "endDate", endDate, "categoryId", categoryId),
                ctx -> {
            ReportRange range = new ReportRange(beginDate, endDate);
            List<String[]> rows = new ArrayList<>();
            String sheet;
            String[] columns;
            switch (view) {
                case "by-goods" -> {
                    sheet = "商品毛利分析";
                    columns = GOODS_COLUMNS;
                    List<GoodsRankRow> list = profitReportService.byGoodsForExport(
                            range, categoryId, excelService.maxRows() + 1, ctx.user());
                    excelService.checkLimit(list.size());
                    list.forEach(r -> rows.add(new String[]{
                            text(r.goodsCode()), text(r.goodsName()), text(r.categoryName()), text(r.brandName()),
                            String.valueOf(r.orderCount()), qty(r.qty()), amount(r.salesAmount()),
                            amount(r.discountAmount()), amount(r.netSalesAmount()), amount(r.refundAmount()),
                            amount(r.costAmount()), amount(r.profitAmount()), percent(r.profitRate()),
                            percent(r.shareRate())}));
                }
                case "by-category" -> {
                    sheet = "分类毛利分析";
                    columns = GOODS_COLUMNS;
                    profitReportService.byCategory(range, ctx.user())
                            .forEach(r -> rows.add(new String[]{
                                    "", text(r.categoryName()), "", "", String.valueOf(r.orderCount()),
                                    qty(r.qty()), amount(r.salesAmount()), amount(r.discountAmount()),
                                    amount(r.netSalesAmount()), amount(r.refundAmount()), amount(r.costAmount()),
                                    amount(r.profitAmount()), percent(r.profitRate()), percent(r.shareRate())}));
                }
                case "by-day" -> {
                    sheet = "时段毛利分析";
                    columns = DAY_COLUMNS;
                    SalesReportVO vo = profitReportService.byDay(range, ctx.user());
                    vo.rows().forEach(r -> rows.add(new String[]{
                            text(r.bizDate()), String.valueOf(r.orderCount()), text(r.itemCount()),
                            amount(r.salesAmount()), amount(r.discountAmount()), amount(r.netSalesAmount()),
                            amount(r.refundAmount()), amount(r.netAmount()), amount(r.costAmount()),
                            amount(r.profitAmount()), percent(r.profitRate()), amount(r.avgOrderAmount())}));
                }
                case "negative" -> {
                    sheet = "负毛利商品清单";
                    columns = NEGATIVE_COLUMNS;
                    profitReportService.negative(range, ctx.user())
                            .forEach(r -> rows.add(new String[]{
                                    text(r.goodsCode()), text(r.goodsName()), text(r.categoryName()),
                                    amount(r.unitPrice()), amount(r.costPrice()), amount(r.unitProfit()),
                                    qty(r.qty()), String.valueOf(r.orderCount()), amount(r.paidAmount()),
                                    amount(r.costAmount()), amount(r.profitAmount())}));
                }
                default -> throw new BusinessException(ErrorCode.PARAM_INVALID, "导出视图不合法");
            }
            ctx.fileName("profit-" + view + ".xlsx");
            return excelService.build(new ReportExcelService.Table(sheet, columns, rows));
                }));
    }
}

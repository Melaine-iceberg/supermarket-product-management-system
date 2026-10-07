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
import com.smms.rpt.dto.DrillInvRecordQuery;
import com.smms.rpt.dto.DrillInvRecordRow;
import com.smms.rpt.dto.DrillLossQuery;
import com.smms.rpt.dto.DrillLossRow;
import com.smms.rpt.dto.InvChangeRow;
import com.smms.rpt.dto.InvSummaryRow;
import com.smms.rpt.dto.ReportRange;
import com.smms.rpt.dto.TurnoverRow;
import com.smms.rpt.dto.WarningRptRow;
import com.smms.rpt.service.DrillDownService;
import com.smms.rpt.service.InventoryReportService;
import com.smms.rpt.service.ReportExcelService;
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
import static com.smms.rpt.service.ReportExcelService.qty;
import static com.smms.rpt.service.ReportExcelService.text;
import static com.smms.rpt.service.ReportExcelService.time;

/**
 * 库存报表接口（FR-RPT-04）：库存汇总表、库存预警报表（含建议采购量）、
 * 库存变动明细、库存周转率与周转天数、损耗报表；支持导出 Excel（FR-RPT-08）。
 */
@RestController
@RequestMapping("/api/v1/report/inventory")
@RequiredArgsConstructor
public class InventoryReportController {

    private static final String BIZ_EXPORT = "INVENTORY_REPORT_EXPORT";


    private static final String[] SUMMARY_COLUMNS = {
            "分类", "商品编码", "商品名称", "商品数", "结存数量", "成本单价", "库存成本金额",
            "零售价值", "库存状态"
    };

    private static final String[] WARNING_COLUMNS = {
            "商品编码", "商品名称", "分类", "预警类型", "结存数量", "安全库存下限", "库存上限",
            "缺口量", "建议采购量", "最近变动时间"
    };

    private static final String[] CHANGE_COLUMNS = {"变动类型", "单据数", "变动数量", "金额"};

    private static final String[] TURNOVER_COLUMNS = {
            "商品编码", "商品名称", "分类", "结存数量", "期末库存金额", "期初库存金额", "平均库存金额",
            "销售成本", "退货成本回冲", "周转率", "周转天数"
    };

    private final InventoryReportService inventoryReportService;
    private final DrillDownService drillDownService;
    private final ReportExcelService excelService;
    private final AsyncTaskService taskService;

    /** 库存汇总表（①，商品维度） */
    @GetMapping("/summary/goods")
    @RequirePerm("sys:rpt:inventory")
    public ApiResponse<PageVO<InvSummaryRow>> summaryByGoods(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(inventoryReportService.summaryByGoods(categoryId, page, size));
    }

    /** 库存汇总表（①，分类维度） */
    @GetMapping("/summary/category")
    @RequirePerm("sys:rpt:inventory")
    public ApiResponse<List<InvSummaryRow>> summaryByCategory() {
        return ApiResponse.ok(inventoryReportService.summaryByCategory());
    }

    /** 库存预警报表（②）：alertType=OUT缺货/WARNING预警/OVERSTOCK积压 */
    @GetMapping("/warnings")
    @RequirePerm("sys:rpt:inventory")
    public ApiResponse<PageVO<WarningRptRow>> warnings(
            @RequestParam(required = false) String alertType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(inventoryReportService.warnings(alertType, page, size));
    }

    /** 库存变动明细报表（③）：按变动类型汇总数量与金额 */
    @GetMapping("/changes")
    @RequirePerm("sys:rpt:inventory")
    public ApiResponse<List<InvChangeRow>> changes(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(inventoryReportService.changes(new ReportRange(beginDate, endDate)));
    }

    /** 库存周转率与周转天数（④，商品维度） */
    @GetMapping("/turnover/goods")
    @RequirePerm("sys:rpt:inventory")
    public ApiResponse<PageVO<TurnoverRow>> turnoverByGoods(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(inventoryReportService.turnoverByGoods(
                new ReportRange(beginDate, endDate), categoryId, page, size));
    }

    /** 库存周转率与周转天数（④，分类维度） */
    @GetMapping("/turnover/category")
    @RequirePerm("sys:rpt:inventory")
    public ApiResponse<List<TurnoverRow>> turnoverByCategory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long categoryId) {
        return ApiResponse.ok(inventoryReportService.turnoverByCategory(
                new ReportRange(beginDate, endDate), categoryId));
    }

    /** 损耗报表（⑤）：报损 + 盘亏汇总，损耗率 = 损耗金额 / 期初库存金额 × 100% */
    @GetMapping("/loss")
    @RequirePerm("sys:rpt:inventory")
    public ApiResponse<InventoryReportService.LossReportVO> loss(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(inventoryReportService.loss(new ReportRange(beginDate, endDate)));
    }

    /** 汇总数字下钻（BR-RPT-06）：变动类型/周转商品行 → 构成汇总的库存流水明细（含单据号回溯） */
    @GetMapping("/drill-records")
    @RequirePerm("sys:rpt:inventory")
    public ApiResponse<PageVO<DrillInvRecordRow>> drillRecords(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String recordType,
            @RequestParam(required = false) Long goodsId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        ReportRange range = new ReportRange(beginDate, endDate);
        return ApiResponse.ok(drillDownService.inventoryRecords(new DrillInvRecordQuery(
                range.beginDate(), range.endDate(), recordType, goodsId, page, size)));
    }

    /** 汇总数字下钻（BR-RPT-06）：损耗商品行 → 报损单（kind=loss）/盘亏差异（kind=check）明细 */
    @GetMapping("/drill-loss")
    @RequirePerm("sys:rpt:inventory")
    public ApiResponse<PageVO<DrillLossRow>> drillLoss(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "loss") String kind,
            @RequestParam(required = false) Long goodsId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        ReportRange range = new ReportRange(beginDate, endDate);
        return ApiResponse.ok(drillDownService.lossDocuments(new DrillLossQuery(
                range.beginDate(), range.endDate(), kind, goodsId, page, size)));
    }

    /** 库存报表导出（FR-RPT-08）：view=summary-goods|summary-category|warnings|changes|turnover-goods|turnover-category|loss */
    @GetMapping("/export")
    @RequirePerm("sys:rpt:inventory")
    @OpLog(module = "统计报表", action = "导出库存报表", target = "#view")
    public ApiResponse<AsyncTaskVO> export(
            @RequestParam(defaultValue = "summary-goods") String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String alertType,
            @RequestParam(required = false) Long categoryId) {
        return ApiResponse.ok(taskService.submitExport(BIZ_EXPORT, "库存报表导出",
                TaskParams.of("view", view, "beginDate", beginDate, "endDate", endDate, "alertType", alertType, "categoryId", categoryId),
                ctx -> {
            List<String[]> rows = new ArrayList<>();
            String sheet;
            String[] columns;
            switch (view) {
                case "summary-goods" -> {
                    sheet = "库存汇总表";
                    columns = SUMMARY_COLUMNS;
                    List<InvSummaryRow> list = inventoryReportService.summaryByGoodsForExport(
                            categoryId, excelService.maxRows() + 1);
                    excelService.checkLimit(list.size());
                    list.forEach(r -> rows.add(new String[]{
                            text(r.categoryName()), text(r.goodsCode()), text(r.goodsName()),
                            String.valueOf(r.goodsCount()), qty(r.stockQty()), amount(r.costPrice()),
                            amount(r.costAmount()), amount(r.retailValue()), statusLabel(r.stockStatus())}));
                }
                case "summary-category" -> {
                    sheet = "库存汇总表（分类）";
                    columns = SUMMARY_COLUMNS;
                    inventoryReportService.summaryByCategory().forEach(r -> rows.add(new String[]{
                            text(r.categoryName()), "", "", String.valueOf(r.goodsCount()),
                            qty(r.stockQty()), "", amount(r.costAmount()),
                            amount(r.retailValue()), ""}));
                }
                case "warnings" -> {
                    sheet = "库存预警报表";
                    columns = WARNING_COLUMNS;
                    List<WarningRptRow> list = inventoryReportService.warningsForExport(
                            alertType, excelService.maxRows() + 1);
                    excelService.checkLimit(list.size());
                    list.forEach(r -> rows.add(new String[]{
                            text(r.goodsCode()), text(r.goodsName()), text(r.categoryName()),
                            alertLabel(r.alertType()), qty(r.stockQty()), qty(r.safetyStockLower()),
                            qty(r.safetyStockUpper()), qty(r.gapQty()), qty(r.suggestQty()),
                            time(r.lastChangeTime())}));
                }
                case "changes" -> {
                    sheet = "库存变动明细";
                    columns = CHANGE_COLUMNS;
                    inventoryReportService.changes(new ReportRange(beginDate, endDate))
                            .forEach(r -> rows.add(new String[]{
                                    changeLabel(r.recordType()), String.valueOf(r.recordCount()),
                                    qty(r.changeQty()), amount(r.amount())}));
                }
                case "turnover-goods" -> {
                    sheet = "库存周转（商品）";
                    columns = TURNOVER_COLUMNS;
                    List<TurnoverRow> list = inventoryReportService.turnoverByGoodsForExport(
                            new ReportRange(beginDate, endDate), categoryId, excelService.maxRows() + 1);
                    excelService.checkLimit(list.size());
                    list.forEach(r -> rows.add(turnoverLine(r)));
                }
                case "turnover-category" -> {
                    sheet = "库存周转（分类）";
                    columns = TURNOVER_COLUMNS;
                    inventoryReportService.turnoverByCategory(new ReportRange(beginDate, endDate), categoryId)
                            .forEach(r -> rows.add(turnoverLine(r)));
                }
                case "loss" -> {
                    sheet = "损耗报表";
                    columns = new String[]{"商品名称", "分类", "报损数量", "报损金额", "盘亏数量", "盘亏金额", "损耗金额"};
                    InventoryReportService.LossReportVO vo =
                            inventoryReportService.loss(new ReportRange(beginDate, endDate));
                    vo.rows().forEach(r -> rows.add(new String[]{
                            text(r.goodsName()), text(r.categoryName()), qty(r.lossQty()),
                            amount(r.lossAmount()), qty(r.checkQty()), amount(r.checkAmount()),
                            amount(r.totalAmount())}));
                }
                default -> throw new BusinessException(ErrorCode.PARAM_INVALID, "导出视图不合法");
            }
            ctx.fileName("inventory-" + view + ".xlsx");
            return excelService.build(new ReportExcelService.Table(sheet, columns, rows));
                }));
    }

    private String[] turnoverLine(TurnoverRow r) {
        return new String[]{
                text(r.goodsCode()), text(r.goodsName()), text(r.categoryName()),
                qty(r.stockQty()), amount(r.costAmount()), amount(r.beginAmount()),
                amount(r.avgAmount()), amount(r.saleCostAmount()), amount(r.returnCostAmount()),
                qty(r.turnoverRate()), qty(r.turnoverDays())};
    }

    private String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "NORMAL" -> "正常";
            case "OUT" -> "缺货";
            case "WARNING" -> "预警";
            case "OVERSTOCK" -> "积压";
            default -> "";
        };
    }

    private String alertLabel(String type) {
        return switch (type == null ? "" : type) {
            case "OUT" -> "缺货";
            case "WARNING" -> "预警";
            case "OVERSTOCK" -> "积压";
            default -> "";
        };
    }

    private String changeLabel(String recordType) {
        return switch (recordType == null ? "" : recordType) {
            case "INIT" -> "期初建账";
            case "PURCHASE_IN" -> "采购入库";
            case "PURCHASE_RETURN" -> "采购退货";
            case "SALE_OUT" -> "销售出库";
            case "SALE_RETURN" -> "销售退货";
            case "CHECK_SURPLUS" -> "盘盈";
            case "CHECK_SHORTAGE" -> "盘亏";
            case "LOSS_OUT" -> "报损";
            case "OVERFLOW_IN" -> "报溢";
            default -> "";
        };
    }
}

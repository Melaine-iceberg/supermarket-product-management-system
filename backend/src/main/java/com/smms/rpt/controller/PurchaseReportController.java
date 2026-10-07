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
import com.smms.rpt.dto.ArrivalRow;
import com.smms.rpt.dto.DrillPurchaseQuery;
import com.smms.rpt.dto.DrillPurchaseRow;
import com.smms.rpt.dto.DrillReceiptQuery;
import com.smms.rpt.dto.DrillReceiptRow;
import com.smms.rpt.dto.PriceTrendRow;
import com.smms.rpt.dto.PurchSummaryRow;
import com.smms.rpt.dto.ReportRange;
import com.smms.rpt.service.DrillDownService;
import com.smms.rpt.service.PurchaseReportService;
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
import static com.smms.rpt.service.ReportExcelService.time;

/**
 * 采购报表接口（FR-RPT-06）：采购汇总表（供应商/商品维度）、供应商采购排行与占比、
 * 采购价格波动分析、到货及时率统计；支持导出 Excel（FR-RPT-08）。
 */
@RestController
@RequestMapping("/api/v1/report/purchase")
@RequiredArgsConstructor
public class PurchaseReportController {

    private static final String BIZ_EXPORT = "PURCHASE_REPORT_EXPORT";


    private static final String[] SUPPLIER_COLUMNS = {
            "供应商", "单数", "采购数量", "采购金额", "已入库数量", "在途数量",
            "退货数量", "退货金额", "已结算金额", "部分结算金额", "占比"
    };

    private static final String[] GOODS_COLUMNS = {
            "商品编码", "商品名称", "分类", "单数", "采购数量", "采购金额", "已入库数量",
            "在途数量", "退货数量", "退货金额", "已结算金额", "部分结算金额"
    };

    private static final String[] PRICE_COLUMNS = {
            "采购单号", "供应商", "采购时间", "数量", "单价"
    };

    private static final String[] ARRIVAL_COLUMNS = {
            "供应商", "到货次数", "按期次数", "到货及时率"
    };

    private final PurchaseReportService purchaseReportService;
    private final DrillDownService drillDownService;
    private final ReportExcelService excelService;
    private final AsyncTaskService taskService;

    /** 采购汇总表（①）：dim=supplier 供应商 / goods 商品 */
    @GetMapping("/summary")
    @RequirePerm("sys:rpt:purchase")
    public ApiResponse<List<PurchSummaryRow>> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "supplier") String dim) {
        return ApiResponse.ok(purchaseReportService.summary(
                new ReportRange(beginDate, endDate), dim, SecurityUtils.getLoginUser()));
    }

    /** 供应商采购排行与占比（②） */
    @GetMapping("/supplier-ranking")
    @RequirePerm("sys:rpt:purchase")
    public ApiResponse<List<PurchSummaryRow>> supplierRanking(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(purchaseReportService.supplierRanking(
                new ReportRange(beginDate, endDate), SecurityUtils.getLoginUser()));
    }

    /** 采购价格波动分析（③）：同商品多次采购单价趋势 */
    @GetMapping("/price-trend")
    @RequirePerm("sys:rpt:purchase")
    public ApiResponse<List<PriceTrendRow>> priceTrend(
            @RequestParam Long goodsId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(purchaseReportService.priceTrend(
                goodsId, new ReportRange(beginDate, endDate), SecurityUtils.getLoginUser()));
    }

    /** 到货及时率统计（④）：按期 = 实收日期 ≤ 预计到货日期 */
    @GetMapping("/arrival")
    @RequirePerm("sys:rpt:purchase")
    public ApiResponse<List<ArrivalRow>> arrival(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(purchaseReportService.arrival(
                new ReportRange(beginDate, endDate), SecurityUtils.getLoginUser()));
    }

    /** 汇总数字下钻（BR-RPT-06）：供应商/商品汇总行 → 构成它的采购单列表（同口径：正式单据） */
    @GetMapping("/drill-orders")
    @RequirePerm("sys:rpt:purchase")
    public ApiResponse<PageVO<DrillPurchaseRow>> drillOrders(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) Long goodsId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        ReportRange range = new ReportRange(beginDate, endDate);
        return ApiResponse.ok(drillDownService.purchaseOrders(new DrillPurchaseQuery(
                range.beginDate(), range.endDate(), supplierId, goodsId, page, size)));
    }

    /** 汇总数字下钻（BR-RPT-06）：到货及时率供应商行 → 构成统计的入库单列表（含按期标记） */
    @GetMapping("/drill-receipts")
    @RequirePerm("sys:rpt:purchase")
    public ApiResponse<PageVO<DrillReceiptRow>> drillReceipts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        ReportRange range = new ReportRange(beginDate, endDate);
        return ApiResponse.ok(drillDownService.receipts(new DrillReceiptQuery(
                range.beginDate(), range.endDate(), supplierId, page, size)));
    }

    /** 采购报表导出（FR-RPT-08）：view=summary-supplier|summary-goods|supplier-ranking|price-trend|arrival */
    @GetMapping("/export")
    @RequirePerm("sys:rpt:purchase")
    @OpLog(module = "统计报表", action = "导出采购报表", target = "#view")
    public ApiResponse<AsyncTaskVO> export(
            @RequestParam(defaultValue = "summary-supplier") String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long goodsId) {
        return ApiResponse.ok(taskService.submitExport(BIZ_EXPORT, "采购报表导出",
                TaskParams.of("view", view, "beginDate", beginDate, "endDate", endDate, "goodsId", goodsId),
                ctx -> {
            ReportRange range = new ReportRange(beginDate, endDate);
            List<String[]> rows = new ArrayList<>();
            String sheet;
            String[] columns;
            switch (view) {
                case "summary-supplier" -> {
                    sheet = "采购汇总表（供应商）";
                    columns = SUPPLIER_COLUMNS;
                    purchaseReportService.summary(range, "supplier", ctx.user())
                            .forEach(r -> rows.add(new String[]{
                                    text(r.supplierName()), String.valueOf(r.orderCount()),
                                    qty(r.purchaseQty()), amount(r.purchaseAmount()), qty(r.receivedQty()),
                                    qty(r.inTransitQty()), qty(r.returnQty()), amount(r.returnAmount()),
                                    amount(r.settledAmount()), amount(r.partialSettledAmount()), ""}));
                }
                case "summary-goods" -> {
                    sheet = "采购汇总表（商品）";
                    columns = GOODS_COLUMNS;
                    purchaseReportService.summary(range, "goods", ctx.user())
                            .forEach(r -> rows.add(new String[]{
                                    text(r.goodsCode()), text(r.goodsName()), text(r.categoryName()),
                                    String.valueOf(r.orderCount()), qty(r.purchaseQty()),
                                    amount(r.purchaseAmount()), qty(r.receivedQty()), qty(r.inTransitQty()),
                                    qty(r.returnQty()), amount(r.returnAmount()), amount(r.settledAmount()),
                                    amount(r.partialSettledAmount())}));
                }
                case "supplier-ranking" -> {
                    sheet = "供应商采购排行";
                    columns = SUPPLIER_COLUMNS;
                    purchaseReportService.supplierRanking(range, ctx.user())
                            .forEach(r -> rows.add(new String[]{
                                    text(r.supplierName()), String.valueOf(r.orderCount()),
                                    qty(r.purchaseQty()), amount(r.purchaseAmount()), qty(r.receivedQty()),
                                    qty(r.inTransitQty()), qty(r.returnQty()), amount(r.returnAmount()),
                                    amount(r.settledAmount()), amount(r.partialSettledAmount()),
                                    percent(r.shareRate())}));
                }
                case "price-trend" -> {
                    sheet = "采购价格波动";
                    columns = PRICE_COLUMNS;
                    if (goodsId == null) {
                        throw new BusinessException(ErrorCode.PARAM_INVALID, "请选择商品");
                    }
                    purchaseReportService.priceTrend(goodsId, range, ctx.user())
                            .forEach(r -> rows.add(new String[]{
                                    text(r.orderNo()), text(r.supplierName()), time(r.orderTime()),
                                    qty(r.quantity()), amount(r.price())}));
                }
                case "arrival" -> {
                    sheet = "到货及时率";
                    columns = ARRIVAL_COLUMNS;
                    purchaseReportService.arrival(range, ctx.user())
                            .forEach(r -> rows.add(new String[]{
                                    text(r.supplierName()), String.valueOf(r.receiptCount()),
                                    String.valueOf(r.onTimeCount()), percent(r.onTimeRate())}));
                }
                default -> throw new BusinessException(ErrorCode.PARAM_INVALID, "导出视图不合法");
            }
            ctx.fileName("purchase-" + view + ".xlsx");
            return excelService.build(new ReportExcelService.Table(sheet, columns, rows));
                }));
    }
}

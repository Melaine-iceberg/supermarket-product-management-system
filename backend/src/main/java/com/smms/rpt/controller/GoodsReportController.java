package com.smms.rpt.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.rpt.dto.DrillOrderQuery;
import com.smms.rpt.dto.DrillOrderRow;
import com.smms.rpt.dto.GoodsRankRow;
import com.smms.rpt.dto.ReportRange;
import com.smms.rpt.service.GoodsReportService;
import com.smms.rpt.service.ReportExcelService;
import com.smms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.smms.rpt.service.ReportExcelService.amount;
import static com.smms.rpt.service.ReportExcelService.percent;
import static com.smms.rpt.service.ReportExcelService.qty;
import static com.smms.rpt.service.ReportExcelService.text;

/**
 * 商品销售分析接口（FR-RPT-03）：商品销售排行（销量/销售额/毛利额，TOP N 与全量）、
 * 品类销售结构、滞销商品清单、品牌销售分析；汇总数字下钻（BR-RPT-06）；
 * 支持导出 Excel（FR-RPT-08）。
 */
@RestController
@RequestMapping("/api/v1/report/goods")
@RequiredArgsConstructor
public class GoodsReportController {

    private static final MediaType XLSX =
            new MediaType("application", "vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private static final String[] RANK_COLUMNS = {
            "商品编码", "商品名称", "分类", "品牌", "单数", "销量", "销售额", "优惠额",
            "净销售额", "退货额", "销售成本", "毛利额", "毛利率", "占比", "当前库存", "零售价"
    };

    private static final String[] SLOW_COLUMNS = {
            "商品编码", "商品名称", "分类", "品牌", "销量", "销售额", "净销售额",
            "当前库存", "成本单价", "占用资金", "零售价"
    };

    private final GoodsReportService goodsReportService;
    private final ReportExcelService excelService;

    /** 商品销售排行（①）：orderBy=qty|salesAmount|profitAmount|netSalesAmount */
    @GetMapping("/ranking")
    @RequirePerm("sys:rpt:goods")
    public ApiResponse<PageVO<GoodsRankRow>> ranking(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "salesAmount") String orderBy,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(goodsReportService.ranking(
                new ReportRange(beginDate, endDate), orderBy, categoryId, page, size,
                SecurityUtils.getLoginUser()));
    }

    /** 品类销售结构（②）：各分类销售额与占比（饼图/矩形树图） */
    @GetMapping("/category-structure")
    @RequirePerm("sys:rpt:goods")
    public ApiResponse<List<GoodsRankRow>> categoryStructure(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(goodsReportService.categoryStructure(
                new ReportRange(beginDate, endDate), SecurityUtils.getLoginUser()));
    }

    /** 滞销商品清单（③）：区间销量低于阈值（默认 1 即销量为 0） */
    @GetMapping("/slow-moving")
    @RequirePerm("sys:rpt:goods")
    public ApiResponse<PageVO<GoodsRankRow>> slowMoving(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) BigDecimal threshold,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(goodsReportService.slowMoving(
                new ReportRange(beginDate, endDate), threshold, categoryId, page, size,
                SecurityUtils.getLoginUser()));
    }

    /** 品牌销售分析（④） */
    @GetMapping("/brand-sales")
    @RequirePerm("sys:rpt:goods")
    public ApiResponse<List<GoodsRankRow>> brandSales(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(goodsReportService.brandSales(
                new ReportRange(beginDate, endDate), SecurityUtils.getLoginUser()));
    }

    /** 汇总数字下钻（BR-RPT-06）：构成该汇总的销售单列表，逐单金额与汇总一致（AC-16） */
    @GetMapping("/drill-orders")
    @RequirePerm("sys:rpt:goods")
    public ApiResponse<PageVO<DrillOrderRow>> drillOrders(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long goodsId,
            @RequestParam(required = false) String cashierName,
            @RequestParam(required = false) Integer counterNo,
            @RequestParam(required = false) Boolean memberOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        ReportRange range = new ReportRange(beginDate, endDate);
        DrillOrderQuery q = new DrillOrderQuery(range.beginDate(), range.endDate(),
                goodsId, cashierName, counterNo, memberOnly, page, size);
        return ApiResponse.ok(goodsReportService.drillOrders(range, q, SecurityUtils.getLoginUser()));
    }

    /** 商品分析导出（FR-RPT-08）：view=ranking|category-structure|slow-moving|brand-sales */
    @GetMapping("/export")
    @RequirePerm("sys:rpt:goods")
    @OpLog(module = "统计报表", action = "导出商品分析", target = "#view")
    public ResponseEntity<byte[]> export(
            @RequestParam(defaultValue = "ranking") String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "salesAmount") String orderBy,
            @RequestParam(required = false) BigDecimal threshold,
            @RequestParam(required = false) Long categoryId) {
        ReportRange range = new ReportRange(beginDate, endDate);
        int limit = excelService.maxRows() + 1;
        List<String[]> rows = new ArrayList<>();
        String sheet;
        String[] columns;
        switch (view) {
            case "ranking" -> {
                sheet = "商品销售排行";
                columns = RANK_COLUMNS;
                List<GoodsRankRow> list = goodsReportService.rankingForExport(
                        range, orderBy, categoryId, limit, SecurityUtils.getLoginUser());
                excelService.checkLimit(list.size());
                list.forEach(r -> rows.add(new String[]{
                        text(r.goodsCode()), text(r.goodsName()), text(r.categoryName()), text(r.brandName()),
                        String.valueOf(r.orderCount()), qty(r.qty()), amount(r.salesAmount()),
                        amount(r.discountAmount()), amount(r.netSalesAmount()), amount(r.refundAmount()),
                        amount(r.costAmount()), amount(r.profitAmount()), percent(r.profitRate()),
                        percent(r.shareRate()), qty(r.stockQty()), amount(r.retailPrice())}));
            }
            case "category-structure" -> {
                sheet = "品类销售结构";
                columns = RANK_COLUMNS;
                List<GoodsRankRow> list = goodsReportService.categoryStructure(range, SecurityUtils.getLoginUser());
                excelService.checkLimit(list.size());
                list.forEach(r -> rows.add(new String[]{
                        "", text(r.categoryName()), "", "", String.valueOf(r.orderCount()),
                        qty(r.qty()), amount(r.salesAmount()), amount(r.discountAmount()),
                        amount(r.netSalesAmount()), amount(r.refundAmount()), amount(r.costAmount()),
                        amount(r.profitAmount()), percent(r.profitRate()), percent(r.shareRate()),
                        "", ""}));
            }
            case "slow-moving" -> {
                sheet = "滞销商品清单";
                columns = SLOW_COLUMNS;
                List<GoodsRankRow> list = goodsReportService.slowMovingForExport(
                        range, threshold, categoryId, limit, SecurityUtils.getLoginUser());
                excelService.checkLimit(list.size());
                list.forEach(r -> rows.add(new String[]{
                        text(r.goodsCode()), text(r.goodsName()), text(r.categoryName()), text(r.brandName()),
                        qty(r.qty()), amount(r.salesAmount()), amount(r.netSalesAmount()),
                        qty(r.stockQty()), amount(r.costPrice()), amount(r.occupiedAmount()),
                        amount(r.retailPrice())}));
            }
            case "brand-sales" -> {
                sheet = "品牌销售分析";
                columns = RANK_COLUMNS;
                List<GoodsRankRow> list = goodsReportService.brandSales(range, SecurityUtils.getLoginUser());
                excelService.checkLimit(list.size());
                list.forEach(r -> rows.add(new String[]{
                        "", "", "", text(r.brandName()), String.valueOf(r.orderCount()),
                        qty(r.qty()), amount(r.salesAmount()), amount(r.discountAmount()),
                        amount(r.netSalesAmount()), amount(r.refundAmount()), amount(r.costAmount()),
                        amount(r.profitAmount()), percent(r.profitRate()), "",
                        qty(r.stockQty()), amount(r.retailPrice())}));
            }
            default -> throw new BusinessException(ErrorCode.PARAM_INVALID, "导出视图不合法");
        }
        return xlsx(excelService.build(new ReportExcelService.Table(sheet, columns, rows)),
                "goods-" + view + ".xlsx");
    }

    private ResponseEntity<byte[]> xlsx(byte[] body, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(XLSX);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(body);
    }
}

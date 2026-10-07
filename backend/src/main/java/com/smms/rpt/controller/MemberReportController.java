package com.smms.rpt.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.PageVO;
import com.smms.common.task.AsyncTaskService;
import com.smms.common.task.AsyncTaskVO;
import com.smms.common.task.TaskParams;
import com.smms.rpt.dto.DrillOrderQuery;
import com.smms.rpt.dto.DrillOrderRow;
import com.smms.rpt.dto.MemberRptRow;
import com.smms.rpt.dto.ReportRange;
import com.smms.rpt.service.DrillDownService;
import com.smms.rpt.service.MemberReportService;
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
import static com.smms.rpt.service.ReportExcelService.text;
import static com.smms.rpt.service.ReportExcelService.time;

/**
 * 会员报表接口（FR-RPT-07）：会员增长趋势、消费排行 TOP N、等级分布、
 * 会员消费贡献占比、沉睡会员清单、生日提醒清单；支持导出 Excel（FR-RPT-08）。
 * 手机号按角色脱敏（BR-MEM-08，导出文件同样脱敏）。
 */
@RestController
@RequestMapping("/api/v1/report/member")
@RequiredArgsConstructor
public class MemberReportController {

    private static final String BIZ_EXPORT = "MEMBER_REPORT_EXPORT";


    private static final String[] GROWTH_COLUMNS = {"日期", "当日新增", "累计会员数"};

    private static final String[] CONSUME_COLUMNS = {
            "会员编号", "姓名", "手机号", "等级", "积分", "累计消费", "消费次数", "最近消费时间"
    };

    private static final String[] LEVEL_COLUMNS = {"等级", "会员数"};

    private static final String[] SLEEPING_COLUMNS = {
            "会员编号", "姓名", "手机号", "等级", "累计消费", "消费次数", "最近消费时间", "注册时间", "沉睡天数"
    };

    private static final String[] BIRTHDAY_COLUMNS = {
            "会员编号", "姓名", "手机号", "等级", "生日"
    };

    private final MemberReportService memberReportService;
    private final DrillDownService drillDownService;
    private final ReportExcelService excelService;
    private final AsyncTaskService taskService;

    /** 会员增长趋势（①）：每日新增与累计 */
    @GetMapping("/growth")
    @RequirePerm("sys:rpt:member")
    public ApiResponse<List<MemberRptRow>> growth(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(memberReportService.growth(
                new ReportRange(beginDate, endDate), SecurityUtils.getLoginUser()));
    }

    /** 会员消费排行 TOP N（②）：topN 默认 10，上限 100 */
    @GetMapping("/consumption-ranking")
    @RequirePerm("sys:rpt:member")
    public ApiResponse<List<MemberRptRow>> consumptionRanking(
            @RequestParam(defaultValue = "10") int topN) {
        return ApiResponse.ok(memberReportService.consumptionRanking(
                topN, SecurityUtils.getLoginUser()));
    }

    /** 会员等级分布（③） */
    @GetMapping("/level-distribution")
    @RequirePerm("sys:rpt:member")
    public ApiResponse<List<MemberRptRow>> levelDistribution() {
        return ApiResponse.ok(memberReportService.levelDistribution(SecurityUtils.getLoginUser()));
    }

    /** 会员消费贡献占比（④）：会员销售额 / 总销售额 */
    @GetMapping("/contribution")
    @RequirePerm("sys:rpt:member")
    public ApiResponse<MemberReportService.ContributionVO> contribution(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(memberReportService.contribution(
                new ReportRange(beginDate, endDate), SecurityUtils.getLoginUser()));
    }

    /** 沉睡会员清单（⑤）：N 天未消费（默认 90 天） */
    @GetMapping("/sleeping")
    @RequirePerm("sys:rpt:member")
    public ApiResponse<PageVO<MemberRptRow>> sleeping(
            @RequestParam(defaultValue = "90") int days,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(memberReportService.sleeping(
                days, page, size, SecurityUtils.getLoginUser()));
    }

    /** 会员生日提醒清单（⑥）：scope=month 当月 / week 未来 7 天（含今日） */
    @GetMapping("/birthday")
    @RequirePerm("sys:rpt:member")
    public ApiResponse<List<MemberRptRow>> birthday(
            @RequestParam(defaultValue = "week") String scope) {
        return ApiResponse.ok(memberReportService.birthday(scope, SecurityUtils.getLoginUser()));
    }

    /** 汇总数字下钻（BR-RPT-06）：会员消费排行/贡献行 → 构成它的销售单列表；
     *  日期为空时全量下钻（与消费排行口径一致），memberOnly=true 仅会员单 / false 仅散客单 / 空为全部 */
    @GetMapping("/drill-orders")
    @RequirePerm("sys:rpt:member")
    public ApiResponse<PageVO<DrillOrderRow>> drillOrders(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) Boolean memberOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        DrillOrderQuery q = new DrillOrderQuery(beginDate, endDate,
                null, null, null, null, memberOnly, memberId, null, null, page, size);
        return ApiResponse.ok(drillDownService.orders(q, SecurityUtils.getLoginUser()));
    }

    /** 会员报表导出（FR-RPT-08）：view=growth|consumption-ranking|level-distribution|sleeping|birthday */
    @GetMapping("/export")
    @RequirePerm("sys:rpt:member")
    @OpLog(module = "统计报表", action = "导出会员报表", target = "#view")
    public ApiResponse<AsyncTaskVO> export(
            @RequestParam(defaultValue = "growth") String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "10") int topN,
            @RequestParam(defaultValue = "90") int days,
            @RequestParam(defaultValue = "week") String scope) {
        return ApiResponse.ok(taskService.submitExport(BIZ_EXPORT, "会员报表导出",
                TaskParams.of("view", view, "beginDate", beginDate, "endDate", endDate, "topN", topN, "days", days, "scope", scope),
                ctx -> {
            ReportRange range = new ReportRange(beginDate, endDate);
            List<String[]> rows = new ArrayList<>();
            String sheet;
            String[] columns;
            switch (view) {
                case "growth" -> {
                    sheet = "会员增长趋势";
                    columns = GROWTH_COLUMNS;
                    memberReportService.growth(range, ctx.user())
                            .forEach(r -> rows.add(new String[]{
                                    text(r.bizDate()), String.valueOf(r.newCount()),
                                    String.valueOf(r.cumulativeCount())}));
                }
                case "consumption-ranking" -> {
                    sheet = "会员消费排行";
                    columns = CONSUME_COLUMNS;
                    memberReportService.consumptionRanking(topN, ctx.user())
                            .forEach(r -> rows.add(new String[]{
                                    text(r.memberNo()), text(r.name()), text(r.phone()),
                                    text(r.levelName()), text(r.points()), amount(r.totalConsume()),
                                    text(r.consumeTimes()), time(r.lastConsumeTime())}));
                }
                case "level-distribution" -> {
                    sheet = "会员等级分布";
                    columns = LEVEL_COLUMNS;
                    memberReportService.levelDistribution(ctx.user())
                            .forEach(r -> rows.add(new String[]{
                                    text(r.levelName()), String.valueOf(r.memberCount())}));
                }
                case "sleeping" -> {
                    sheet = "沉睡会员清单";
                    columns = SLEEPING_COLUMNS;
                    List<MemberRptRow> list = memberReportService.sleepingForExport(
                            days, excelService.maxRows() + 1, ctx.user());
                    excelService.checkLimit(list.size());
                    list.forEach(r -> rows.add(new String[]{
                            text(r.memberNo()), text(r.name()), text(r.phone()), text(r.levelName()),
                            amount(r.totalConsume()), text(r.consumeTimes()),
                            time(r.lastConsumeTime()), time(r.registerTime()), text(r.sleepingDays())}));
                }
                case "birthday" -> {
                    sheet = "生日提醒清单";
                    columns = BIRTHDAY_COLUMNS;
                    memberReportService.birthday(scope, ctx.user())
                            .forEach(r -> rows.add(new String[]{
                                    text(r.memberNo()), text(r.name()), text(r.phone()),
                                    text(r.levelName()), text(r.birthday())}));
                }
                default -> throw new com.smms.common.exception.BusinessException(
                        com.smms.common.api.ErrorCode.PARAM_INVALID, "导出视图不合法");
            }
            ctx.fileName("member-" + view + ".xlsx");
            return excelService.build(new ReportExcelService.Table(sheet, columns, rows));
                }));
    }
}

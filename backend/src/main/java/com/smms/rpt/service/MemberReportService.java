package com.smms.rpt.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.PageVO;
import com.smms.common.mybatis.ExportPage;
import com.smms.common.util.MaskUtils;
import com.smms.rpt.dto.MemberRptRow;
import com.smms.rpt.dto.ReportRange;
import com.smms.rpt.mapper.MemberReportMapper;
import com.smms.rpt.mapper.ReportMapper;
import com.smms.rpt.dto.SalesGroupRow;
import com.smms.security.LoginUser;
import com.smms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会员报表（FR-RPT-07）：会员增长趋势、消费排行、等级分布、
 * 会员消费贡献占比、沉睡会员清单、生日提醒清单。
 * 手机号按角色脱敏（BR-MEM-08：完整手机号仅店长及以上可见）。
 */
@Service
@RequiredArgsConstructor
public class MemberReportService {

    private final MemberReportMapper mapper;
    private final ReportMapper reportMapper;

    /** 会员增长趋势（①）：每日新增与累计（缺失日期补零） */
    public List<MemberRptRow> growth(ReportRange range, LoginUser user) {
        LocalDateTime beginTime = range.beginDate().atStartOfDay();
        LocalDateTime endTime = range.endDate().plusDays(1).atStartOfDay();
        List<MemberRptRow> daily = mapper.selectDailyNewMembers(beginTime, endTime);
        long cumulative = mapper.countMembersBefore(beginTime);
        List<MemberRptRow> points = new ArrayList<>();
        LocalDate d = range.beginDate();
        while (!d.isAfter(range.endDate())) {
            long newCount = 0;
            for (MemberRptRow r : daily) {
                if (d.equals(r.bizDate())) {
                    newCount = r.newCount() == null ? 0 : r.newCount();
                    break;
                }
            }
            cumulative += newCount;
            points.add(new MemberRptRow(null, null, null, null, null, null, null, null,
                    null, null, null, d, newCount, cumulative, null, null));
            d = d.plusDays(1);
        }
        return points;
    }

    /** 会员消费排行 TOP N（②）：按累计消费额 */
    public List<MemberRptRow> consumptionRanking(int topN, LoginUser user) {
        int n = topN <= 0 ? 10 : Math.min(topN, 100);
        return mask(mapper.selectConsumptionRanking(n), user);
    }

    /** 会员等级分布（③） */
    public List<MemberRptRow> levelDistribution(LoginUser user) {
        return mapper.selectLevelDistribution();
    }

    /** 会员消费贡献占比（④）：会员销售额 / 总销售额（BR-RPT-01 口径） */
    public ContributionVO contribution(ReportRange range, LoginUser user) {
        List<SalesGroupRow> raw = reportMapper.selectMemberRatio(range.beginDate(), range.endDate());
        BigDecimal memberNet = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;
        long memberCount = 0;
        long totalCount = 0;
        for (SalesGroupRow r : raw) {
            BigDecimal net = r.netSalesAmount() == null ? BigDecimal.ZERO : r.netSalesAmount();
            totalNet = totalNet.add(net);
            totalCount += r.orderCount();
            if ("MEMBER".equals(r.groupKey())) {
                memberNet = net;
                memberCount = r.orderCount();
            }
        }
        BigDecimal shareRate = null;
        if (totalNet.compareTo(BigDecimal.ZERO) != 0) {
            shareRate = memberNet.multiply(BigDecimal.valueOf(100))
                    .divide(totalNet, 2, RoundingMode.HALF_UP);
        }
        return new ContributionVO(memberNet, totalNet, memberCount, totalCount, shareRate);
    }

    /** 沉睡会员清单（⑤）：N 天未消费（从未消费的按注册时间起算） */
    public PageVO<MemberRptRow> sleeping(int days, int page, int size, LoginUser user) {
        int n = days <= 0 ? 90 : Math.min(days, 3650);
        LocalDateTime deadline = LocalDateTime.now().minusDays(n);
        long total = mapper.countSleeping(deadline);
        if (total == 0) {
            return new PageVO<>(List.of(), 0);
        }
        Page<MemberRptRow> p = new Page<>(Math.max(page, 1), size <= 0 ? 20 : Math.min(size, 200), false);
        p.setTotal(total);
        IPage<MemberRptRow> result = mapper.selectSleeping(p, deadline);
        List<MemberRptRow> rows = mask(result.getRecords().stream()
                .map(r -> withSleepingDays(r, n))
                .toList(), user);
        result.setRecords(rows);
        return PageVO.of(result);
    }

    /** 沉睡会员清单导出（FR-RPT-08）：取前 limit 行，超限由调用方校验（ExportPage 豁免单页 500 条保护，GAP-P0-01） */
    public List<MemberRptRow> sleepingForExport(int days, int limit, LoginUser user) {
        int n = days <= 0 ? 90 : Math.min(days, 3650);
        LocalDateTime deadline = LocalDateTime.now().minusDays(n);
        IPage<MemberRptRow> result = mapper.selectSleeping(new ExportPage<>(limit), deadline);
        return mask(result.getRecords().stream()
                .map(r -> withSleepingDays(r, n))
                .toList(), user);
    }

    /** 生日提醒清单（⑥）：scope=month 当月 / week 未来 7 天（含今日） */
    public List<MemberRptRow> birthday(String scope, LoginUser user) {
        String s = "week".equals(scope) ? "week" : "month";
        return mask(mapper.selectBirthday(s), user);
    }

    /** 沉睡天数 = 今天 − 最近消费（或注册）日期 */
    private MemberRptRow withSleepingDays(MemberRptRow r, int defaultDays) {
        LocalDateTime last = r.lastConsumeTime() != null ? r.lastConsumeTime() : r.registerTime();
        int days = defaultDays;
        if (last != null) {
            days = (int) Math.max(0, java.time.Duration.between(last, LocalDateTime.now()).toDays());
        }
        return new MemberRptRow(r.id(), r.memberNo(), r.name(), r.phone(), r.levelName(),
                r.points(), r.totalConsume(), r.consumeTimes(), r.lastConsumeTime(),
                r.registerTime(), r.birthday(), r.bizDate(), r.newCount(),
                r.cumulativeCount(), r.memberCount(), days);
    }

    /** 手机号按角色脱敏（BR-MEM-08） */
    private List<MemberRptRow> mask(List<MemberRptRow> rows, LoginUser user) {
        if (SecurityUtils.canViewMemberPhone()) {
            return rows;
        }
        return rows.stream()
                .map(r -> new MemberRptRow(r.id(), r.memberNo(), r.name(),
                        MaskUtils.maskPhone(r.phone()), r.levelName(),
                        r.points(), r.totalConsume(), r.consumeTimes(), r.lastConsumeTime(),
                        r.registerTime(), r.birthday(), r.bizDate(), r.newCount(),
                        r.cumulativeCount(), r.memberCount(), r.sleepingDays()))
                .toList();
    }

    /** 会员消费贡献（FR-RPT-07④） */
    public record ContributionVO(
            BigDecimal memberNetAmount,
            BigDecimal totalNetAmount,
            long memberOrderCount,
            long totalOrderCount,
            /** 会员销售额 / 总销售额 × 100% */
            BigDecimal shareRate) {
    }
}

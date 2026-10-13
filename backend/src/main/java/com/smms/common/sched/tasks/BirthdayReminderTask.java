package com.smms.common.sched.tasks;

import com.smms.common.sched.ScheduledTask;
import com.smms.rpt.dto.MemberRptRow;
import com.smms.rpt.mapper.MemberReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 会员生日提醒生成（IR-SW-06 / GAP-P1-03）：每日 07:00 汇总当日生日会员，
 * 结果（人数 + 名单摘要）写入执行日志，供生日关怀与报表核对。
 * 幂等：只读查询，无副作用，可随时补跑。
 */
@Component
@RequiredArgsConstructor
public class BirthdayReminderTask implements ScheduledTask {

    /** 单日名单摘要展示上限（超出仅记人数） */
    private static final int NAME_LIMIT = 10;

    private final MemberReportMapper memberReportMapper;

    @Override
    public String code() {
        return "BIRTHDAY_REMINDER";
    }

    @Override
    public String name() {
        return "会员生日提醒生成";
    }

    @Override
    public String description() {
        return "汇总当日生日会员（启用状态）并生成提醒摘要；只读查询，可随时补跑（FR-MEM 生日关怀）";
    }

    @Override
    public String cron() {
        return "0 0 7 * * ?";
    }

    @Override
    public String execute() {
        List<MemberRptRow> members = memberReportMapper.selectBirthday("day", 200);
        if (members == null || members.isEmpty()) {
            return "今日无生日会员";
        }
        String names = members.stream().limit(NAME_LIMIT)
                .map(MemberRptRow::name)
                .collect(Collectors.joining("、"));
        String suffix = members.size() > NAME_LIMIT ? " 等" : "";
        return "今日生日会员 " + members.size() + " 人：" + names + suffix;
    }
}

package com.smms.common.sched.tasks;

import com.smms.rpt.dto.MemberRptRow;
import com.smms.rpt.mapper.MemberReportMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 会员生日提醒生成任务（IR-SW-06 / GAP-P1-03）：只读汇总当日生日会员，摘要含人数与名单。
 */
@ExtendWith(MockitoExtension.class)
class BirthdayReminderTaskTest {

    @Mock
    private MemberReportMapper memberReportMapper;

    @InjectMocks
    private BirthdayReminderTask task;

    private static MemberRptRow row(String name) {
        return new MemberRptRow(null, "M001", name, "13800000000", null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("无生日会员：返回明确摘要")
    void execute_nobody() {
        when(memberReportMapper.selectBirthday(eq("day"), eq(200))).thenReturn(List.of());

        assertThat(task.execute()).isEqualTo("今日无生日会员");
    }

    @Test
    @DisplayName("有生日会员：摘要含人数与名单（超 10 名以“等”截断）")
    void execute_summary() {
        when(memberReportMapper.selectBirthday(eq("day"), eq(200))).thenReturn(List.of(row("张三"), row("李四")));

        assertThat(task.execute()).isEqualTo("今日生日会员 2 人：张三、李四");
    }

    @Test
    @DisplayName("任务元信息：代码/名称/cron 与需求一致（每日 07:00）")
    void meta() {
        assertThat(task.code()).isEqualTo("BIRTHDAY_REMINDER");
        assertThat(task.cron()).isEqualTo("0 0 7 * * ?");
        assertThat(task.description()).isNotBlank();
    }
}

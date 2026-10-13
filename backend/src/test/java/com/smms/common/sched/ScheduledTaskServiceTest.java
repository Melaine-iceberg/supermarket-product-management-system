package com.smms.common.sched;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.common.sched.mapper.ScheduledTaskLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定时任务执行框架用例（IR-SW-06 / GAP-P1-03）：
 * 成功/失败/重试/摘要落库、手工触发人、任务一览与日志分页。
 */
@ExtendWith(MockitoExtension.class)
class ScheduledTaskServiceTest {

    @Mock
    private ScheduledTaskLogMapper logMapper;

    /** 可编程假任务：前 failTimes 次抛异常 */
    private static class FakeTask implements ScheduledTask {
        final AtomicInteger calls = new AtomicInteger();
        final int failTimes;

        FakeTask(int failTimes) {
            this.failTimes = failTimes;
        }

        @Override
        public String code() {
            return "FAKE_TASK";
        }

        @Override
        public String name() {
            return "假任务";
        }

        @Override
        public String description() {
            return "测试用";
        }

        @Override
        public String cron() {
            return "0 0 1 * * ?";
        }

        @Override
        public String execute() {
            if (calls.incrementAndGet() <= failTimes) {
                throw new IllegalStateException("boom-" + calls.get());
            }
            return "处理 3 条";
        }
    }

    private FakeTask task;
    private ScheduledTaskService service;

    @BeforeEach
    void setUp() {
        task = new FakeTask(0);
        service = new ScheduledTaskService(List.of(task), logMapper);
    }

    @Test
    @DisplayName("执行成功：结果摘要/耗时/触发人落库，状态 SUCCESS")
    void run_success() {
        ScheduledTaskLogVO vo = service.run("FAKE_TASK", "MANUAL", "admin");

        ArgumentCaptor<ScheduledTaskLog> captor = ArgumentCaptor.forClass(ScheduledTaskLog.class);
        verify(logMapper).insert(captor.capture());
        ScheduledTaskLog entry = captor.getValue();
        assertThat(entry.getStatus()).isEqualTo("SUCCESS");
        assertThat(entry.getTriggerType()).isEqualTo("MANUAL");
        assertThat(entry.getTriggerBy()).isEqualTo("admin");
        assertThat(entry.getResultSummary()).isEqualTo("处理 3 条");
        assertThat(entry.getRetryCount()).isZero();
        assertThat(entry.getDurationMs()).isGreaterThanOrEqualTo(0);
        assertThat(vo.status()).isEqualTo("SUCCESS");
        assertThat(task.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("失败自动重试：第 3 次成功，retry_count=2 且状态 SUCCESS")
    void run_retriesThenSuccess() {
        task = new FakeTask(2);
        service = new ScheduledTaskService(List.of(task), logMapper);

        ScheduledTaskLogVO vo = service.run("FAKE_TASK", "SCHEDULED", null);

        assertThat(vo.status()).isEqualTo("SUCCESS");
        assertThat(vo.retryCount()).isEqualTo(2);
        assertThat(vo.resultSummary()).isEqualTo("处理 3 条");
        assertThat(task.calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("重试耗尽失败：状态 FAILED、原因含最后一次错误、retry_count=2")
    void run_failsAfterMaxRetries() {
        task = new FakeTask(99);
        service = new ScheduledTaskService(List.of(task), logMapper);

        ScheduledTaskLogVO vo = service.run("FAKE_TASK", "SCHEDULED", null);

        assertThat(vo.status()).isEqualTo("FAILED");
        assertThat(vo.retryCount()).isEqualTo(ScheduledTaskService.MAX_RETRIES);
        assertThat(vo.errorMessage()).contains("boom-3");
        assertThat(task.calls.get()).isEqualTo(ScheduledTaskService.MAX_RETRIES + 1);
    }

    @Test
    @DisplayName("未知任务代码：返回 404 业务异常")
    void run_unknownCode() {
        assertThatThrownBy(() -> service.run("NOT_EXISTS", "MANUAL", "admin"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("任务代码重复：构造即报错（防止注册歧义）")
    void duplicateCode_rejected() {
        assertThatThrownBy(() -> new ScheduledTaskService(List.of(new FakeTask(0), new FakeTask(0)), logMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复");
    }

    @Test
    @DisplayName("任务一览：合并定义与最近一次执行结果")
    void overview_mergesLatestLog() {
        ScheduledTaskLog last = new ScheduledTaskLog();
        last.setTaskCode("FAKE_TASK");
        last.setStatus("SUCCESS");
        last.setStartedAt(LocalDateTime.now());
        last.setDurationMs(12L);
        last.setResultSummary("处理 3 条");
        when(logMapper.selectOne(any())).thenReturn(last);

        List<ScheduledTaskVO> overview = service.overview();

        assertThat(overview).hasSize(1);
        assertThat(overview.get(0).code()).isEqualTo("FAKE_TASK");
        assertThat(overview.get(0).cron()).isEqualTo("0 0 1 * * ?");
        assertThat(overview.get(0).lastStatus()).isEqualTo("SUCCESS");
        assertThat(overview.get(0).lastDurationMs()).isEqualTo(12L);
    }

    @Test
    @DisplayName("执行日志分页：实体映射为视图且返回总数")
    void logPage_mapsToVO() {
        ScheduledTaskLog entry = new ScheduledTaskLog();
        entry.setId(9L);
        entry.setTaskCode("FAKE_TASK");
        entry.setStatus("FAILED");
        Page<ScheduledTaskLog> page = new Page<>(1, 20);
        page.setRecords(List.of(entry));
        page.setTotal(1);
        when(logMapper.selectPage(any(), any())).thenReturn(page);

        PageVO<ScheduledTaskLogVO> result = service.logPage("FAKE_TASK", "FAILED", 1, 20);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.list()).hasSize(1);
        assertThat(result.list().get(0).id()).isEqualTo(9L);
        assertThat(result.list().get(0).status()).isEqualTo("FAILED");
    }
}

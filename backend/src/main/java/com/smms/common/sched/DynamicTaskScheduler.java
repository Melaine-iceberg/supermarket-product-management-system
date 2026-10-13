package com.smms.common.sched;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 定时任务动态注册（IR-SW-06 / GAP-P1-03）：
 * 按各 {@link ScheduledTask} 的 cron 表达式统一注册到调度器；cron 定义单点维护，
 * 触发时统一进入 {@link ScheduledTaskService#run}（执行日志、重试、手工补跑同源）。
 */
@Component
@RequiredArgsConstructor
public class DynamicTaskScheduler implements SchedulingConfigurer {

    private final ScheduledTaskService taskService;
    private final List<ScheduledTask> tasks;
    private final TaskScheduler taskScheduler;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(taskScheduler);
        for (ScheduledTask task : tasks) {
            registrar.addCronTask(() -> taskService.run(task.code(), "SCHEDULED", null), task.cron());
        }
    }
}

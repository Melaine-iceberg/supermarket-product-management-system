package com.smms.common.sched;

import java.time.LocalDateTime;

/** 定时任务一览（定义 + 最近一次执行结果，IR-SW-06） */
public record ScheduledTaskVO(
        String code,
        String name,
        String description,
        String cron,
        /** 最近一次执行结果：SUCCESS / FAILED / null（尚未执行） */
        String lastStatus,
        LocalDateTime lastStartedAt,
        Long lastDurationMs,
        String lastResultSummary,
        String lastError) {

    public static ScheduledTaskVO of(ScheduledTask task, ScheduledTaskLog last) {
        return new ScheduledTaskVO(task.code(), task.name(), task.description(), task.cron(),
                last == null ? null : last.getStatus(),
                last == null ? null : last.getStartedAt(),
                last == null ? null : last.getDurationMs(),
                last == null ? null : last.getResultSummary(),
                last == null ? null : last.getErrorMessage());
    }
}

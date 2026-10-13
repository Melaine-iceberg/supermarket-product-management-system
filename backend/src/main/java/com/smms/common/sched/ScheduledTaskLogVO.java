package com.smms.common.sched;

import java.time.LocalDateTime;

/** 定时任务执行日志视图（IR-SW-06） */
public record ScheduledTaskLogVO(
        Long id,
        String taskCode,
        String taskName,
        /** SCHEDULED 定时 / MANUAL 手工补跑 */
        String triggerType,
        String triggerBy,
        /** SUCCESS / FAILED */
        String status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Long durationMs,
        String resultSummary,
        String errorMessage,
        Integer retryCount) {

    public static ScheduledTaskLogVO of(ScheduledTaskLog log) {
        return new ScheduledTaskLogVO(log.getId(), log.getTaskCode(), log.getTaskName(),
                log.getTriggerType(), log.getTriggerBy(), log.getStatus(), log.getStartedAt(),
                log.getFinishedAt(), log.getDurationMs(), log.getResultSummary(),
                log.getErrorMessage(), log.getRetryCount());
    }
}

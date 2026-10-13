package com.smms.common.sched;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务执行日志（IR-SW-06）：每次执行（定时/手工补跑）落一条，含结果、耗时与重试次数。
 */
@Data
@TableName("scheduled_task_log")
public class ScheduledTaskLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskCode;
    private String taskName;
    /** SCHEDULED 定时 / MANUAL 手工补跑 */
    private String triggerType;
    /** 手工触发人（定时触发为空） */
    private String triggerBy;
    /** SUCCESS / FAILED */
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private String resultSummary;
    private String errorMessage;
    private Integer retryCount;
    private LocalDateTime createTime;
}

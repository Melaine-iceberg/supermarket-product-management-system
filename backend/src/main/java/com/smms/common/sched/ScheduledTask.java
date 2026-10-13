package com.smms.common.sched;

/**
 * 定时任务定义（IR-SW-06 / GAP-P1-03）：
 * 统一由 {@link DynamicTaskScheduler} 按 cron 注册调度，执行经 {@link ScheduledTaskService}
 * 记录结果、耗时与重试；实现须保证幂等（重复执行 / 手工补跑不产生重复数据）。
 */
public interface ScheduledTask {

    /** 任务代码（唯一，手工触发与日志检索用；命名如 PROMOTION_EXPIRE） */
    String code();

    /** 任务名称（前端展示） */
    String name();

    /** 任务说明（含幂等语义，便于运维理解手工补跑的影响） */
    String description();

    /** Spring cron 表达式（调度注册的唯一来源） */
    String cron();

    /**
     * 执行任务，返回结果摘要（如"处理 3 条"）。
     * 抛异常视为失败，由框架按 {@link ScheduledTaskService#MAX_RETRIES} 重试后记 FAILED。
     */
    String execute() throws Exception;
}

package com.smms.common.sched.tasks;

import com.smms.common.sched.ScheduledTask;
import com.smms.common.task.AsyncTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 异步任务与结果文件清理（IR-SW-06）：删除过期记录与遗留文件（幂等，可补跑） */
@Component
@RequiredArgsConstructor
public class AsyncTaskCleanupTask implements ScheduledTask {

    private final AsyncTaskService asyncTaskService;

    @Override
    public String code() {
        return "ASYNC_TASK_CLEANUP";
    }

    @Override
    public String name() {
        return "异步任务清理";
    }

    @Override
    public String description() {
        return "删除超过保留期（默认 24 小时）的异步任务记录与结果文件；按过期时间条件清理，重复执行不产生副作用";
    }

    @Override
    public String cron() {
        return "0 17 * * * *";
    }

    @Override
    public String execute() {
        int cleaned = asyncTaskService.cleanExpiredTasks();
        return cleaned == 0 ? "无过期任务" : "清理记录/文件 " + cleaned + " 项";
    }
}

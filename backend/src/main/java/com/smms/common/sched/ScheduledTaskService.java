package com.smms.common.sched;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.common.sched.mapper.ScheduledTaskLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 定时任务执行框架（IR-SW-06 / GAP-P1-03）：
 * 统一执行入口——调度触发与手工补跑共用；失败自动重试（{@value #MAX_RETRIES} 次），
 * 每次执行（含重试次数）落 scheduled_task_log，支持按任务/结果检索与耗时对比。
 * 各任务自身须幂等，重复执行/补跑不产生重复数据（见 ScheduledTask#execute 说明）。
 */
@Slf4j
@Service
public class ScheduledTaskService {

    /** 失败自动重试次数（首次执行外最多重试 2 次） */
    public static final int MAX_RETRIES = 2;
    /** 重试间隔（毫秒），避免瞬时故障连续失败 */
    static final long RETRY_BACKOFF_MS = 500;
    /** 摘要/错误信息落库长度上限 */
    static final int TEXT_LIMIT = 500;

    private final ScheduledTaskLogMapper logMapper;
    private final Map<String, ScheduledTask> taskMap;

    public ScheduledTaskService(List<ScheduledTask> tasks, ScheduledTaskLogMapper logMapper) {
        this.logMapper = logMapper;
        this.taskMap = tasks.stream().collect(Collectors.toMap(ScheduledTask::code, Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException("定时任务代码重复: " + a.code());
                }, LinkedHashMap::new));
    }

    /** 任务定义列表（按注册顺序） */
    public List<ScheduledTask> definitions() {
        return List.copyOf(taskMap.values());
    }

    /** 执行任务（调度触发或手工补跑）：失败重试 + 落执行日志 + 输出业务日志 */
    public ScheduledTaskLogVO run(String code, String triggerType, String triggerBy) {
        ScheduledTask task = requireTask(code);
        LocalDateTime startedAt = LocalDateTime.now();
        long start = System.currentTimeMillis();
        String summary = null;
        String error = null;
        int retries = 0;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                summary = task.execute();
                error = null;
                break;
            } catch (Exception e) {
                error = truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                if (attempt < MAX_RETRIES) {
                    retries++;
                    sleepQuietly();
                }
            }
        }
        boolean success = error == null;
        ScheduledTaskLog entry = new ScheduledTaskLog();
        entry.setTaskCode(task.code());
        entry.setTaskName(task.name());
        entry.setTriggerType(triggerType);
        entry.setTriggerBy(triggerBy);
        entry.setStatus(success ? "SUCCESS" : "FAILED");
        entry.setStartedAt(startedAt);
        entry.setFinishedAt(LocalDateTime.now());
        entry.setDurationMs(System.currentTimeMillis() - start);
        entry.setResultSummary(truncate(summary));
        entry.setErrorMessage(error);
        entry.setRetryCount(retries);
        logMapper.insert(entry);
        if (success) {
            log.info("定时任务执行成功: code={}, name={}, 触发={}, 重试={}, 耗时={}ms, 结果={}",
                    task.code(), task.name(), triggerType, retries, entry.getDurationMs(), summary);
        } else {
            log.error("定时任务执行失败: code={}, name={}, 触发={}, 重试={}, 耗时={}ms, 原因={}",
                    task.code(), task.name(), triggerType, retries, entry.getDurationMs(), error);
        }
        return ScheduledTaskLogVO.of(entry);
    }

    /** 任务一览：定义 + 最近一次执行结果（任务数有限，逐任务取最新一条） */
    public List<ScheduledTaskVO> overview() {
        return taskMap.values().stream()
                .map(task -> ScheduledTaskVO.of(task, latest(task.code())))
                .toList();
    }

    /** 执行日志分页：可按任务代码/结果过滤 */
    public PageVO<ScheduledTaskLogVO> logPage(String taskCode, String status, int page, int size) {
        Page<ScheduledTaskLog> p = new Page<>(Math.max(page, 1), size <= 0 ? 20 : Math.min(size, 100));
        Page<ScheduledTaskLog> result = logMapper.selectPage(p, Wrappers.<ScheduledTaskLog>lambdaQuery()
                .eq(taskCode != null && !taskCode.isBlank(), ScheduledTaskLog::getTaskCode, taskCode)
                .eq(status != null && !status.isBlank(), ScheduledTaskLog::getStatus, status)
                .orderByDesc(ScheduledTaskLog::getStartedAt)
                .orderByDesc(ScheduledTaskLog::getId));
        return new PageVO<>(result.getRecords().stream().map(ScheduledTaskLogVO::of).toList(),
                result.getTotal());
    }

    private ScheduledTaskLog latest(String code) {
        return logMapper.selectOne(Wrappers.<ScheduledTaskLog>lambdaQuery()
                .eq(ScheduledTaskLog::getTaskCode, code)
                .orderByDesc(ScheduledTaskLog::getStartedAt)
                .orderByDesc(ScheduledTaskLog::getId)
                .last("LIMIT 1"));
    }

    private ScheduledTask requireTask(String code) {
        ScheduledTask task = code == null ? null : taskMap.get(code);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "定时任务不存在：" + code);
        }
        return task;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= TEXT_LIMIT ? text : text.substring(0, TEXT_LIMIT);
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(RETRY_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

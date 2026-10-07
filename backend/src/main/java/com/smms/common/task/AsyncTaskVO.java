package com.smms.common.task;

import java.time.LocalDateTime;

/**
 * 异步任务视图对象（提交响应与轮询查询共用）。
 */
public record AsyncTaskVO(
        String taskNo,
        String taskType,
        String bizType,
        String bizName,
        String status,
        Integer progress,
        String phase,
        String fileName,
        Long fileSize,
        String errorMessage,
        /** 导入类任务的结果摘要（已解析为对象），导出类为 null */
        Object result,
        LocalDateTime createTime,
        LocalDateTime finishTime,
        LocalDateTime expiresAt
) {
}

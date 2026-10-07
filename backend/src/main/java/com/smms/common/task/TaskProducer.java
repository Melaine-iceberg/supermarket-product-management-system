package com.smms.common.task;

/**
 * 任务生产者：在后台线程中执行真正的重活。
 * 导出任务返回 Excel 字节；导入任务返回 null 并通过 {@link TaskContext#result(Object)} 提供结果摘要。
 */
@FunctionalInterface
public interface TaskProducer {

    byte[] produce(TaskContext ctx) throws Exception;
}

package com.smms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

/**
 * 异步与定时任务配置：日志写入使用独立线程池（FR-SYS-08），
 * 队列满时由调用线程执行，保证审计日志不丢失；
 * @EnableScheduling 支撑促销到期自动失效等定时任务（IR-SW-06）。
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean(name = "logExecutor")
    public Executor logExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("smms-log-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(2000);
        // 审计日志不可丢：拒绝时退回调用线程同步执行
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 重导出/导入后台任务线程池（2026-10 新增）。
     * 队列满时直接拒绝（AbortPolicy），由业务层转为 TASK_QUEUE_FULL 提示，
     * 避免请求线程被长任务占用而重新触发反向代理超时。
     */
    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor(
            @Value("${smms.task.core-pool-size:2}") int corePoolSize,
            @Value("${smms.task.max-pool-size:4}") int maxPoolSize,
            @Value("${smms.task.queue-capacity:50}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("smms-task-");
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 定时任务调度器（IR-SW-06 / GAP-P1-03）：多线程并行，
     * 避免长任务（如每日备份）阻塞短周期任务（如促销到期失效）；cron 注册见 DynamicTaskScheduler。
     */
    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("smms-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}

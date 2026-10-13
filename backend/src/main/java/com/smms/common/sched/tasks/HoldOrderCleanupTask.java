package com.smms.common.sched.tasks;

import com.smms.common.sched.ScheduledTask;
import com.smms.sale.service.HoldOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 挂单超时清理（IR-SW-06）：删除 24 小时前的挂单（幂等，可补跑） */
@Component
@RequiredArgsConstructor
public class HoldOrderCleanupTask implements ScheduledTask {

    private final HoldOrderService holdOrderService;

    @Override
    public String code() {
        return "HOLD_ORDER_CLEANUP";
    }

    @Override
    public String name() {
        return "挂单超时清理";
    }

    @Override
    public String description() {
        return "删除 24 小时前的挂单（含已取/已删终态）；按时间条件删除，重复执行不产生副作用";
    }

    @Override
    public String cron() {
        return "0 */10 * * * ?";
    }

    @Override
    public String execute() {
        int removed = holdOrderService.cleanupExpired();
        return removed == 0 ? "无过期挂单" : "删除 " + removed + " 条";
    }
}

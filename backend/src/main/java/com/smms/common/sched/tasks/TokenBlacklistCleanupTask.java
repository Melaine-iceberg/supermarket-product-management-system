package com.smms.common.sched.tasks;

import com.smms.common.sched.ScheduledTask;
import com.smms.sys.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Token 黑名单过期清理（IR-SW-06 / GAP-P1-11）：按过期时间删除（幂等，可补跑） */
@Component
@RequiredArgsConstructor
public class TokenBlacklistCleanupTask implements ScheduledTask {

    private final AuthService authService;

    @Override
    public String code() {
        return "TOKEN_BLACKLIST_CLEANUP";
    }

    @Override
    public String name() {
        return "Token 黑名单清理";
    }

    @Override
    public String description() {
        return "删除已自然过期的黑名单记录；按过期时间条件删除，重复执行不产生副作用";
    }

    @Override
    public String cron() {
        return "0 41 * * * *";
    }

    @Override
    public String execute() {
        int deleted = authService.cleanExpiredBlacklist();
        return deleted == 0 ? "无过期黑名单记录" : "清理 " + deleted + " 条";
    }
}

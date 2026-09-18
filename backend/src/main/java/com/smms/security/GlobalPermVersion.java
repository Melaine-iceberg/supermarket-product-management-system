package com.smms.security;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局权限版本号（NFR-SEC-02，IR-SW-03 载荷中的"权限版本号"）：
 * 任何用户/角色/权限变更都会自增版本并清空用户上下文缓存，
 * 携带旧版本号的 Token 在下一次请求时强制从数据库重建上下文，
 * 保证权限变更 ≤ 1 分钟（实际即时）对已登录用户生效。
 */
@Component
public class GlobalPermVersion {

    private final AtomicLong version = new AtomicLong(System.currentTimeMillis() / 1000);

    public long current() {
        return version.get();
    }

    public long bump() {
        return version.incrementAndGet();
    }
}

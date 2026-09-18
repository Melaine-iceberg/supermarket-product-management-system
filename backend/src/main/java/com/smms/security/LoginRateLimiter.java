package com.smms.security;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录接口频率限制（NFR-SEC-03）：同 IP 每分钟 ≤ 20 次，防止暴力破解。
 * 单实例内存实现，满足本期部署形态。
 */
@Component
public class LoginRateLimiter {

    private static final int LIMIT_PER_MINUTE = 20;
    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_TRACKED_IPS = 10_000;

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public boolean tryAcquire(String ip) {
        long now = System.currentTimeMillis();
        if (hits.size() > MAX_TRACKED_IPS) {
            hits.clear(); // 防御性清理，正常流量不会触达
        }
        Deque<Long> window = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MILLIS) {
                window.pollFirst();
            }
            if (window.size() >= LIMIT_PER_MINUTE) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }
}

package com.smms.security;

import java.time.LocalDateTime;

/**
 * 当前请求 Token 信息持有器：登出/修改密码时将 jti 写入黑名单。
 */
public final class IssuedTokenHolder {

    public record CurrentToken(String jti, LocalDateTime expireAt) {
    }

    private static final ThreadLocal<CurrentToken> HOLDER = new ThreadLocal<>();

    private IssuedTokenHolder() {
    }

    public static void set(String jti, LocalDateTime expireAt) {
        HOLDER.set(new CurrentToken(jti, expireAt));
    }

    public static CurrentToken current() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}

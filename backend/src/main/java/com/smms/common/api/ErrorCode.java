package com.smms.common.api;

import lombok.Getter;

/**
 * 统一错误码体系（NFR-REL-06：可预期异常返回明确业务错误码与中文提示）。
 * 约定：0 成功；4xx 通用客户端错误；分段号位保留给各业务模块扩展。
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "成功"),

    // ---------- 通用客户端错误 ----------
    PARAM_INVALID(400, "请求参数不合法"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权限执行该操作"),
    NOT_FOUND(404, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后重试"),

    // ---------- 通用服务端错误 ----------
    SYSTEM_ERROR(500, "系统繁忙，请稍后重试"),
    SERVICE_UNAVAILABLE(503, "服务暂不可用，请稍后重试"),

    // ---------- 通用业务错误（各模块错误码后续按段扩展） ----------
    BUSINESS_ERROR(1000, "业务处理失败"),
    CONCURRENT_CONFLICT(1001, "操作冲突，请刷新后重试"),

    // ---------- SYS 系统模块（1100 ~ 1199，FR-SYS-01 ~ 09） ----------
    USERNAME_PASSWORD_ERROR(1101, "用户名或密码错误"),
    ACCOUNT_LOCKED(1102, "账号已锁定，请稍后重试"),
    ACCOUNT_DISABLED(1103, "账号已停用，请联系管理员"),
    USERNAME_EXISTS(1104, "用户名已存在"),
    ROLE_CODE_EXISTS(1105, "角色代码已存在"),
    WEAK_PASSWORD(1106, "密码强度不足"),
    OLD_PASSWORD_ERROR(1107, "原密码不正确"),
    BUILTIN_PROTECTED(1108, "内置对象受保护，不允许该操作");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}

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

    // ---------- 通用服务端错误 ----------
    SYSTEM_ERROR(500, "系统繁忙，请稍后重试"),
    SERVICE_UNAVAILABLE(503, "服务暂不可用，请稍后重试"),

    // ---------- 通用业务错误（各模块错误码后续按段扩展） ----------
    BUSINESS_ERROR(1000, "业务处理失败"),
    CONCURRENT_CONFLICT(1001, "操作冲突，请刷新后重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}

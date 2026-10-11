package com.smms.sys.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 微信登录请求（V1.2）。
 *
 * <p>前端扫码后从回调地址拿到 {@code code} 与 {@code state}，回传后端换取登录态。
 * state 由后端下发并缓存，用于防 CSRF 与串联「谁在扫」。</p>
 */
public record WechatLoginRequest(
        @NotBlank(message = "微信授权码不能为空") String code,
        @NotBlank(message = "微信登录会话标识不能为空") String state
) {
}

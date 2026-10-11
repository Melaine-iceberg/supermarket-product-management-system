package com.smms.sys.dto;

import java.util.List;

/**
 * 当前开放的登录方式（V1.2）。前端据此渲染登录页 Tab，
 * 未配置的渠道（如未填微信 AppID）不会出现在列表里。
 *
 * @param password    账号密码登录是否开放
 * @param phone       手机号验证码登录是否开放
 * @param email       邮箱验证码登录是否开放
 * @param wechat      微信登录是否开放
 * @param register    自助注册是否开放
 * @param wechatAppId 微信开放平台 AppID（供前端拼接扫码地址，未开启时为空）
 * @param wechatScope 微信授权 scope
 */
public record LoginMethodsVO(
        boolean password,
        boolean phone,
        boolean email,
        boolean wechat,
        boolean register,
        String wechatAppId,
        String wechatScope,
        List<String> codeChannels
) {
}

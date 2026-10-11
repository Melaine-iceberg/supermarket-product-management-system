package com.smms.sys.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 多方式登录配置（V1.2，前缀 {@code smms.login}）。
 *
 * <p>每一项都是「总开关 + 凭证」的组合：开关为 true 且凭证齐全时，
 * 对应登录方式才会出现在 {@code /auth/login-methods} 返回里、登录页才会显示该 Tab。
 * 这样开发环境默认只有密码登录与验证码（日志模式），生产环境按需逐项打开。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "smms.login")
public class LoginProperties {

    /** 账号密码登录开关（V1.2 保留为兜底入口，管理员与未绑定手机号的旧账号依赖它） */
    private boolean password = true;

    /** 手机号 + 短信验证码登录开关 */
    private boolean phone = false;

    /** 邮箱 + 邮件验证码登录开关 */
    private boolean email = false;

    /** 自助注册开关 */
    private boolean register = false;

    /** 自助注册用户的默认角色 ID（sys_role.id），默认 5=收银员（权限最小的岗位） */
    private Long defaultRoleId = 5L;

    /** 验证码有效期（分钟） */
    private int codeExpireMinutes = 5;

    /** 同一手机号/邮箱两次发送验证码的最小间隔（秒） */
    private int codeIntervalSeconds = 60;

    /** 同一手机号/邮箱每日最多发送次数 */
    private int codeDailyLimit = 10;

    /** 一条验证码最多允许校验失败次数，超出作废 */
    private int codeMaxAttempts = 5;

    /** 开发模式是否在响应里直接回显验证码（仅本地调试用，生产必须为 false） */
    private boolean exposeCodeInResponse = false;

    private Wechat wechat = new Wechat();

    /** 微信开放平台配置（登录开关与凭证同属一节，避免 smms.login.wechat 同时是布尔与对象导致绑定冲突） */
    @Data
    public static class Wechat {
        /** 微信登录总开关，需同时配置 app-id / app-secret 才真正可用 */
        private boolean enabled = false;
        private String appId;
        private String appSecret;
        /** 网页授权回调地址，需与开放平台配置一致 */
        private String redirectUri;
        /** snsapi_login（扫码）或 snsapi_userinfo（公众号内） */
        private String scope = "snsapi_login";
    }

    /** 微信登录是否具备可用条件 */
    public boolean wechatReady() {
        return wechat.isEnabled()
                && wechat.getAppId() != null && !wechat.getAppId().isBlank()
                && wechat.getAppSecret() != null && !wechat.getAppSecret().isBlank();
    }
}

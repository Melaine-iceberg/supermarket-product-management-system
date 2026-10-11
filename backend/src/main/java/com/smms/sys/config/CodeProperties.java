package com.smms.sys.config;

import com.smms.sys.entity.SysAuthCode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 验证码外发渠道配置（V1.3）。
 *
 * <p>与 {@link LoginProperties} 的分工：{@code smms.login.*} 管「有哪些登录方式、验证码
 * 多久过期、多久能重发」等<b>策略</b>；{@code smms.code.*} 管「验证码通过哪条通道真正
 * 发出去」的<b>投递</b>配置。</p>
 *
 * <p><b>短信与邮件可以分别选 provider</b>（{@code sms.provider} / {@code email.provider}），
 * 因为业务上两者要同时可用：例如短信走阿里云、邮件走企业 SMTP。可选值：</p>
 * <ul>
 *   <li>{@code log}（默认）——不真实外发，验证码打到后端日志，用于本地开发与演示；</li>
 *   <li>{@code aliyun}——阿里云短信（仅 SMS 渠道）；</li>
 *   <li>{@code smtp}——标准 SMTP 邮件（仅 EMAIL 渠道），兼容 QQ/163/企业邮/Gmail/Outlook。</li>
 * </ul>
 *
 * <p>选定的真实 provider 若凭证不全，{@code available()} 会返回 false，登录页自动隐藏该
 * 页签，同时启动日志会给出告警——避免「按钮点了必然失败」。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "smms.code")
public class CodeProperties {

    private Sms sms = new Sms();
    private Email email = new Email();

    /** 取某个渠道配置的 provider 名。 */
    public String providerFor(String channel) {
        return SysAuthCode.CHANNEL_SMS.equals(channel) ? sms.getProvider() : email.getProvider();
    }

    @Data
    public static class Sms {
        /** 短信 provider：log / aliyun */
        private String provider = "log";
        private Aliyun aliyun = new Aliyun();
    }

    @Data
    public static class Aliyun {
        /** RAM 用户的 AccessKeyId（建议只授予 AliyunDysmsFullAccess 或更小权限） */
        private String accessKeyId = "";
        /** RAM 用户的 AccessKeySecret */
        private String accessKeySecret = "";
        /** 已报备并通过审核的短信签名，如「好又多超市」 */
        private String signName = "";
        /** 登录场景模板 CODE，模板内容需含 ${code} 占位符 */
        private String templateCodeLogin = "";
        /** 注册场景模板 CODE，未单独申请时留空则复用登录模板 */
        private String templateCodeRegister = "";
        /** 服务接入点，国内短信固定为 dysmsapi.aliyuncs.com */
        private String endpoint = "dysmsapi.aliyuncs.com";
        /** 连接与读取超时（毫秒） */
        private int timeoutMs = 10000;
    }

    @Data
    public static class Email {
        /** 邮件 provider：log / smtp */
        private String provider = "log";
        private Smtp smtp = new Smtp();
    }

    @Data
    public static class Smtp {
        /** SMTP 服务器，如 smtp.qq.com / smtp.163.com / smtp.exmail.qq.com */
        private String host = "";
        /** 465=SSL 直连；587=STARTTLS；25=明文（不推荐） */
        private int port = 465;
        /** 登录用户名，通常是完整邮箱地址 */
        private String username = "";
        /** 授权码/密码。QQ/163 等需在邮箱设置里开启 SMTP 并生成「授权码」，不是登录密码 */
        private String password = "";
        /** 发件人地址，留空则用 username */
        private String from = "";
        /** 发件人显示名 */
        private String fromName = "超市商品管理系统";
        /** 是否启用 SSL（465 端口用 true） */
        private boolean sslEnabled = true;
        /** 是否启用 STARTTLS（587 端口用 true，且此时 sslEnabled 应为 false） */
        private boolean starttlsEnabled = false;
        private int connectionTimeoutMs = 10000;
        private int timeoutMs = 10000;
        private int writeTimeoutMs = 10000;

        /** 凭证是否齐全。 */
        /**
         * 是否启用 SMTP AUTH。
         *
         * <p>QQ/163/企业邮/Gmail 均必须认证，但本机或内网中继（postfix/localhost）
         * 通常不允许认证。若硬编码 true，这类中继会因发空凭证而失败，故按凭证是否存在自动判定。</p>
         */
        public boolean hasCredentials() {
            return notBlank(username) && notBlank(password);
        }

        public boolean isConfigured() {
            return notBlank(host) && hasCredentials();
        }

        /** 实际发件人地址，未单独配置时退回 username。 */
        public String effectiveFrom() {
            return notBlank(from) ? from : username;
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }
}

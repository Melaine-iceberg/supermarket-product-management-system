package com.smms.sys.service;

import com.smms.sys.config.CodeProperties;
import com.smms.sys.entity.SysAuthCode;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * SMTP 邮件验证码发送器（V1.3）。
 *
 * <p>走标准 SMTP，因此<b>一套实现同时支持主流邮箱</b>：</p>
 * <table>
 *   <tr><th>服务商</th><th>host</th><th>port</th><th>说明</th></tr>
 *   <tr><td>QQ 邮箱</td><td>smtp.qq.com</td><td>465</td><td>需在设置→账户开启 SMTP，密码填「授权码」</td></tr>
 *   <tr><td>163 邮箱</td><td>smtp.163.com</td><td>465</td><td>同上，用授权码</td></tr>
 *   <tr><td>腾讯企业邮</td><td>smtp.exmail.qq.com</td><td>465</td><td>可用自有域名发件</td></tr>
 *   <tr><td>阿里企业邮</td><td>smtp.qiye.aliyun.com</td><td>465</td><td>可用自有域名发件</td></tr>
 *   <tr><td>Gmail</td><td>smtp.gmail.com</td><td>587</td><td>需用「应用专用密码」，ssl=false + starttls=true</td></tr>
 *   <tr><td>Outlook</td><td>smtp-mail.outlook.com</td><td>587</td><td>ssl=false + starttls=true</td></tr>
 * </table>
 *
 * <p>启用条件：{@code smms.code.email.provider=smtp}。凭证不全时
 * {@link #available(String)} 返回 false，由 {@link CodeSenderRouter} 回退到日志模式。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "smms.code.email.provider", havingValue = SmtpAuthCodeSender.PROVIDER)
public class SmtpAuthCodeSender implements AuthCodeSender {

    public static final String PROVIDER = "smtp";

    private final CodeProperties.Smtp smtp;
    private final JavaMailSenderImpl mailSender;

    public SmtpAuthCodeSender(CodeProperties codeProperties) {
        this.smtp = codeProperties.getEmail().getSmtp();
        this.mailSender = buildMailSender(this.smtp);
        if (smtp.isConfigured()) {
            log.info("SMTP 邮件发送器已就绪：host={} port={} ssl={} starttls={} from={}",
                    smtp.getHost(), smtp.getPort(), smtp.isSslEnabled(), smtp.isStarttlsEnabled(),
                    smtp.effectiveFrom());
        }
    }

    private static JavaMailSenderImpl buildMailSender(CodeProperties.Smtp cfg) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(cfg.getHost());
        sender.setPort(cfg.getPort());
        sender.setUsername(cfg.getUsername());
        sender.setPassword(cfg.getPassword());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(cfg.hasCredentials()));
        props.put("mail.smtp.ssl.enable", String.valueOf(cfg.isSslEnabled()));
        props.put("mail.smtp.starttls.enable", String.valueOf(cfg.isStarttlsEnabled()));
        props.put("mail.smtp.connectiontimeout", String.valueOf(cfg.getConnectionTimeoutMs()));
        props.put("mail.smtp.timeout", String.valueOf(cfg.getTimeoutMs()));
        props.put("mail.smtp.writetimeout", String.valueOf(cfg.getWriteTimeoutMs()));
        return sender;
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public boolean supports(String channel) {
        return SysAuthCode.CHANNEL_EMAIL.equals(channel);
    }

    @Override
    public boolean available(String channel) {
        return supports(channel) && smtp.isConfigured();
    }

    @Override
    public void send(String channel, String target, String code, String scene, int expireMinutes) {
        if (!smtp.isConfigured()) {
            throw new IllegalStateException("SMTP 邮件通道未配置完整（需要 host/username/password）");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            // 第二个参数 true 表示 multipart，下面只放 HTML 正文，便于控制排版
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(smtp.effectiveFrom(), smtp.getFromName());
            helper.setTo(target);
            helper.setSubject(subject(scene));
            helper.setText(htmlBody(code, scene, expireMinutes), true);
            mailSender.send(message);
            log.info("验证码邮件已发送：to={} scene={}", mask(target), scene);
        } catch (MailException | MessagingException | java.io.UnsupportedEncodingException e) {
            // 交给上层统一包装成业务错误码，这里补充上下文便于排查
            log.error("验证码邮件发送失败：to={} host={} port={} cause={}",
                    mask(target), smtp.getHost(), smtp.getPort(), e.getMessage());
            throw new IllegalStateException("验证码邮件发送失败", e);
        }
    }

    private String subject(String scene) {
        return SysAuthCode.SCENE_REGISTER.equals(scene)
                ? "【超市商品管理系统】注册验证码"
                : "【超市商品管理系统】登录验证码";
    }

    private String htmlBody(String code, String scene, int expireMinutes) {
        String action = SysAuthCode.SCENE_REGISTER.equals(scene) ? "完成注册" : "登录系统";
        return """
                <div style="font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;max-width:520px;margin:0 auto;padding:24px;color:#303133">
                  <h2 style="margin:0 0 16px;font-size:18px">验证码</h2>
                  <p style="margin:0 0 16px;color:#606266">你正在%s，请在页面中输入以下验证码：</p>
                  <div style="font-size:32px;font-weight:700;letter-spacing:8px;padding:16px 0;text-align:center;background:#f5f7fa;border-radius:8px">%s</div>
                  <p style="margin:16px 0 0;color:#909399;font-size:13px">验证码 %d 分钟内有效，请勿转发给他人。</p>
                  <p style="margin:8px 0 0;color:#909399;font-size:13px">如果这不是你本人的操作，请忽略本邮件。</p>
                </div>
                """.formatted(action, code, expireMinutes);
    }

    /** 日志里不打印完整邮箱，避免泄露。 */
    private static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "-";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}

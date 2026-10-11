package com.smms.sys.service;

import com.smms.sys.config.CodeProperties;
import com.smms.sys.entity.SysAuthCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 验证码发送路由器（V1.3）。
 *
 * <p>短信与邮件要能同时可用，所以不能「全局只选一个 sender」。本类按渠道分别解析：
 * 读取 {@code smms.code.sms.provider} 或 {@code smms.code.email.provider}，
 * 在已注册的 {@link AuthCodeSender} 中选出「既支持该渠道、且 provider 名匹配」的实现。</p>
 *
 * <p>兜底策略：目标 provider 未注册、或凭证不全（{@code available} 为 false）时，
 * 回退到 {@link LoggingAuthCodeSender} 把验证码写日志，并在日志给出明确告警。
 * 这样配置写错不会让整个登录功能不可用，但管理员能立刻从日志发现问题。</p>
 *
 * <p>注意：本类<b>故意不实现</b> {@link AuthCodeSender}——否则注入
 * {@code List<AuthCodeSender>} 时会把自己也注入进来，形成循环依赖。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodeSenderRouter {

    private final List<AuthCodeSender> senders;
    private final CodeProperties codeProperties;
    private final LoggingAuthCodeSender fallback;

    /** 启动时自检：把配置与实现不匹配的情况一次性说清楚，避免上线后才发现发不出验证码。 */
    @PostConstruct
    void checkConfiguration() {
        checkChannel(SysAuthCode.CHANNEL_SMS, "短信", codeProperties.getSms().getProvider());
        checkChannel(SysAuthCode.CHANNEL_EMAIL, "邮件", codeProperties.getEmail().getProvider());
    }

    private void checkChannel(String channel, String label, String want) {
        if (isDevMode(want)) {
            log.warn("{}验证码为开发模式（provider=log）：不会真实外发，验证码仅打印到本日志", label);
            return;
        }
        AuthCodeSender sender = match(channel, want);
        if (sender == null) {
            log.error("{}验证码配置了 provider={}，但没有找到对应实现，已回退为开发模式（仅记日志）",
                    label, want);
        } else if (!sender.available(channel)) {
            log.error("{}验证码配置了 provider={}，但凭证不完整，已回退为开发模式（仅记日志）；"
                            + "请补全 smms.code.{} 下的配置", label, want,
                    SysAuthCode.CHANNEL_SMS.equals(channel) ? "sms" : "email");
        } else {
            log.info("{}验证码已启用真实发送：provider={}", label, want);
        }
    }

    private boolean isDevMode(String providerName) {
        return LoggingAuthCodeSender.PROVIDER.equals(providerName);
    }

    private AuthCodeSender match(String channel, String providerName) {
        return senders.stream()
                .filter(s -> s.supports(channel))
                .filter(s -> s.providerName().equals(providerName))
                .findFirst()
                .orElse(null);
    }

    /**
     * 该渠道是否应该开放。
     *
     * <p>开发模式（provider=log）下返回 true——这是刻意的：本地要能走通完整登录流程，
     * 验证码从日志里取。只有在「配了真实 provider 却不可用」时才返回 false，
     * 以免登录页展示一个点了必然失败的入口。</p>
     */
    public boolean available(String channel) {
        String want = codeProperties.providerFor(channel);
        if (isDevMode(want)) {
            return true;
        }
        AuthCodeSender sender = match(channel, want);
        return sender != null && sender.available(channel);
    }

    /** 发送验证码；配置不可用时退化为写日志，保证调用方不必兜底。 */
    public void send(String channel, String target, String code, String scene, int expireMinutes) {
        String want = codeProperties.providerFor(channel);
        AuthCodeSender sender = isDevMode(want) ? null : match(channel, want);
        if (sender == null || !sender.available(channel)) {
            fallback.send(channel, target, code, scene, expireMinutes);
            return;
        }
        sender.send(channel, target, code, scene, expireMinutes);
    }
}

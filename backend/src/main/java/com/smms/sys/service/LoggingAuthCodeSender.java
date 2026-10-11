package com.smms.sys.service;

import com.smms.sys.entity.SysAuthCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 开发模式验证码发送器（V1.2）：不真正外发，把验证码打到后端日志。
 *
 * <p>该实现<strong>始终注册为 Bean</strong>，兼两个作用：一是作为
 * {@code provider=log} 时的选定实现（默认，开发模式不真实外发），
 * 二是作为 {@link CodeSenderRouter} 的兜底：当某个渠道指定的真实 provider
 * 未注册或凭证不全时，回退到此处把验证码写日志，保证流程不中断。</p>
 */
@Slf4j
@Component
public class LoggingAuthCodeSender implements AuthCodeSender {

    public static final String PROVIDER = "log";

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public boolean supports(String channel) {
        return SysAuthCode.CHANNEL_SMS.equals(channel) || SysAuthCode.CHANNEL_EMAIL.equals(channel);
    }

    /** 开发模式恒为可用，方便本地跑通全流程 */
    @Override
    public boolean available(String channel) {
        return supports(channel);
    }

    @Override
    public void send(String channel, String target, String code, String scene, int expireMinutes) {
        log.warn("""
                ========== [开发模式] 验证码未真实发送 ==========
                  渠道   : {}
                  接收方 : {}
                  场景   : {}
                  验证码 : {}
                  有效期 : {} 分钟
                  提示   : 配置 smms.code.sms.provider / smms.code.email.provider
                           接入真实短信/邮件服务后即可外发，详见《部署与运维手册》
                ===============================================""",
                channel, target, scene, code, expireMinutes);
    }
}

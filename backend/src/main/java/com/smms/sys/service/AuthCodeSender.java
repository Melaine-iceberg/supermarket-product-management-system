package com.smms.sys.service;

import com.smms.sys.entity.SysAuthCode;

/**
 * 验证码发送扩展点（V1.2）。
 *
 * <p>当前默认实现为 {@link LoggingAuthCodeSender}（开发模式：验证码只写后端日志，不外发），
 * 接入真实短信/邮件服务时只需新增一个实现类并把它注册为 Bean，同时把
 * {@code smms.code.provider} 改成对应的值，业务代码无需改动。</p>
 *
 * <p>接入真实渠道的步骤：</p>
 * <ol>
 *   <li>新建 {@code AliyunSmsAuthCodeSender implements AuthCodeSender}，
 *       {@code supports()} 对 {@link SysAuthCode#CHANNEL_SMS} 返回 true；</li>
 *   <li>类上加 {@code @ConditionalOnProperty(name = "smms.code.provider", havingValue = "aliyun")}；</li>
 *   <li>在 {@code application.yml} 填 {@code smms.sms.access-key-id / access-key-secret / sign-name / template-code}；</li>
 *   <li>把 {@code smms.code.provider} 从 {@code log} 改为 {@code aliyun}。</li>
 * </ol>
 *
 * <p>注意：真实渠道必须让 {@link #available()} 只在凭证齐全时返回 true，
 * 否则登录页会显示一个点了必然失败的按钮。</p>
 */
public interface AuthCodeSender {

    /**
     * 本实现对应的 provider 名，与 {@code smms.code.sms.provider} /
     * {@code smms.code.email.provider} 的取值比较，由
     * {@link CodeSenderRouter} 决定某个渠道最终用哪个实现。
     *
     * @return {@code log}（开发模式）、{@code aliyun}（短信）、{@code smtp}（邮件）
     */
    String providerName();

    /**
     * 是否能处理该渠道。
     *
     * @param channel {@link SysAuthCode#CHANNEL_SMS} 或 {@link SysAuthCode#CHANNEL_EMAIL}
     */
    boolean supports(String channel);

    /**
     * 该渠道是否已配置可用。未配置时登录页不展示对应登录方式。
     */
    boolean available(String channel);

    /**
     * 发送验证码。
     *
     * @param target        手机号或邮箱
     * @param code          明文验证码（仅在此处出现，不入库）
     * @param scene         LOGIN / REGISTER / BIND，用于选择短信模板或邮件标题
     * @param expireMinutes 有效期（分钟），用于文案
     */
    void send(String channel, String target, String code, String scene, int expireMinutes);
}

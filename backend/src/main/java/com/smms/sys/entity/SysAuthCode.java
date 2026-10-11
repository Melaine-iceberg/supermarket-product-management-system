package com.smms.sys.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 短信/邮件验证码（V1.2 多方式登录，DR-32）。
 *
 * <p>验证码不落明文：{@code code_hash} 存 BCrypt 哈希，即使库被读取也无法直接使用。</p>
 */
@Data
@TableName("sys_auth_code")
public class SysAuthCode {

    /** 渠道：手机短信 */
    public static final String CHANNEL_SMS = "SMS";

    /** 渠道：邮件 */
    public static final String CHANNEL_EMAIL = "EMAIL";

    /** 场景：登录 */
    public static final String SCENE_LOGIN = "LOGIN";

    /** 场景：注册 */
    public static final String SCENE_REGISTER = "REGISTER";

    /** 场景：绑定手机号/邮箱 */
    public static final String SCENE_BIND = "BIND";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** SMS / EMAIL */
    private String channel;

    /** 手机号或邮箱 */
    private String target;

    /** BCrypt 哈希 */
    private String codeHash;

    /** LOGIN / REGISTER / BIND */
    private String scene;

    /** 0 未使用 1 已使用 */
    private Integer used;

    /** 已校验失败次数 */
    private Integer attemptCount;

    private String requestIp;

    private LocalDateTime expireTime;

    /** 最近一次发送时间，用于发送频率限制 */
    private LocalDateTime sendTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

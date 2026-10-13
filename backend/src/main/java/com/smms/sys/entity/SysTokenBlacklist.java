package com.smms.sys.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Token 黑名单（IR-SW-03，GAP-P1-11）：
 * 仅登出（LOGOUT）与改密（PWD_CHANGE）需要按 jti 拉黑；
 * 用户停用/权限变更通过"用户状态实时校验 + JWT ver 版本号强制重建上下文"即时生效，不写黑名单；
 * 过期记录由 AuthService 定时任务清理。
 */
@Data
@TableName("sys_token_blacklist")
public class SysTokenBlacklist {

    public static final String REASON_LOGOUT = "LOGOUT";
    public static final String REASON_PWD_CHANGE = "PWD_CHANGE";

    /** JWT ID */
    @TableId(type = IdType.INPUT)
    private String jti;

    /** Token 原始过期时间，过期后可清理 */
    private LocalDateTime expireTime;

    /** 拉黑原因 */
    private String reason;

    private LocalDateTime createTime;
}

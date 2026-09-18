package com.smms.sys.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Token 黑名单（IR-SW-03）：登出、停用、密码修改后即时失效。
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

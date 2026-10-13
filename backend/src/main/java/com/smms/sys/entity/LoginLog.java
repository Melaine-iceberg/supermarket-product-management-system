package com.smms.sys.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录日志（DR-34，FR-SYS-01）：记录成功/失败、IP、浏览器、操作系统、时间。
 */
@Data
@TableName("login_log")
public class LoginLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private Long userId;

    private String ip;

    private String browser;

    private String os;

    /** 1 成功 0 失败 */
    private Integer status;

    /** 失败原因/锁定提示 */
    private String message;

    private LocalDateTime loginTime;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}

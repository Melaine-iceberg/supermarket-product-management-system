package com.smms.sys.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户（DR-28，FR-SYS-02）：用户不可物理删除，只可停用。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 强度 10 哈希（BR-SYS-02） */
    private String password;

    private String realName;

    private String phone;

    /** 1 启用 0 停用 */
    private Integer status;

    private LocalDateTime lastLoginTime;

    /** 连续登录失败次数（FR-SYS-01） */
    private Integer failCount;

    /** 锁定到期时间（FR-SYS-01） */
    private LocalDateTime lockUntil;

    /** 重置密码后强制下次登录修改（BR-SYS-02） */
    private Integer mustChangePwd;

    private String remark;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}

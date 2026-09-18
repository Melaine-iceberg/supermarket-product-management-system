package com.smms.sys.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统角色（DR-29，FR-SYS-04）：内置角色不可删除，权限可调整。
 */
@Data
@TableName("sys_role")
public class SysRole {

    /** 超级管理员角色 ID（BR-SYS-03） */
    public static final long SUPER_ADMIN_ROLE_ID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String roleName;

    private String roleCode;

    private String description;

    /** 1 内置 0 自定义 */
    private Integer isBuiltin;

    /** 1 启用 0 停用 */
    private Integer status;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}

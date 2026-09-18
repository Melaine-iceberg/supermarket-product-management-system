package com.smms.sys.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统权限（DR-30，FR-SYS-05）：目录/菜单/按钮（接口）三级粒度。
 */
@Data
@TableName("sys_permission")
public class SysPermission {

    public static final String TYPE_DIR = "D";
    public static final String TYPE_MENU = "M";
    public static final String TYPE_BUTTON = "B";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父权限 ID，0 = 根 */
    private Long parentId;

    private String name;

    /** D 目录 / M 菜单 / B 按钮 */
    private String permType;

    /** 权限标识（如 sys:user:create），目录/菜单对应列表权限 */
    private String permCode;

    private String routePath;

    private String component;

    private String icon;

    private Integer sortNo;

    /** 1 启用 0 停用 */
    private Integer status;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}

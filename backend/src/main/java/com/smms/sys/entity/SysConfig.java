package com.smms.sys.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统参数（DR-35，FR-SYS-06）：键值对业务参数。
 */
@Data
@TableName("sys_config")
public class SysConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String configName;

    private String configKey;

    private String configValue;

    /** STRING / NUMBER / BOOLEAN / TIME */
    private String valueType;

    private String configGroup;

    /** 1 可修改 0 只读 */
    private Integer editable;

    /** 1 立即生效 0 重启生效 */
    private Integer instantEffect;

    private String remark;

    private String updateBy;

    private LocalDateTime updateTime;

    private String createBy;

    private LocalDateTime createTime;
}

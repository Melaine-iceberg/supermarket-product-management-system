package com.smms.sys.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据字典项（DR-36，FR-SYS-07）：单据存代码，展示时关联字典；
 * 停用项不可被新单据选择，历史数据仍能正确显示名称。
 */
@Data
@TableName("sys_dict_item")
public class SysDictItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long dictId;

    /** 显示名 */
    private String label;

    /** 存储代码 */
    private String value;

    private Integer sortNo;

    /** 1 启用 0 停用（停用项不可被新单据选择） */
    private Integer status;

    private String remark;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}

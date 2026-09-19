package com.smms.goods.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计量单位（DR-04，FR-GOODS-08）：单位字典；
 * 称重商品只能选择重量单位（BR-GOODS-05）。
 */
@Data
@TableName("unit")
public class Unit {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 单位名称（唯一，≤10字符） */
    private String name;

    /** 单位符号 */
    private String symbol;

    /** 是否重量单位：1是 0否 */
    private Integer isWeight;

    /** 状态：1启用 0停用（停用后不可被新商品选择） */
    private Integer status;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}

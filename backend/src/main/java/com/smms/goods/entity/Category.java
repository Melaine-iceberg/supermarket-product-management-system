package com.smms.goods.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品分类（DR-01，FR-CAT-01~07）：树形结构，最多 3 级；
 * 商品必须挂在叶子分类上（BR-CAT-04）。
 */
@Data
@TableName("category")
public class Category {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父分类ID，0=根节点 */
    private Long parentId;

    /** 分类名称（同级唯一，BR-CAT-01） */
    private String name;

    /** 层级：1~3（BR-CAT-04） */
    private Integer level;

    /** 物化路径，如 1/5/12 */
    private String path;

    /** 同级排序号 */
    private Integer sortNo;

    /** 状态：1启用 0停用（FR-CAT-04） */
    private Integer status;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}

package com.smms.goods.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品品牌（DR-03，FR-GOODS-13）：品牌字典，供商品档案下拉选择。
 */
@Data
@TableName("brand")
public class Brand {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 品牌名称（唯一） */
    private String name;

    /** 首字母（拼音，1~2位） */
    private String firstLetter;

    /** Logo 路径 */
    private String logo;

    /** 状态：1启用 0停用 */
    private Integer status;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}

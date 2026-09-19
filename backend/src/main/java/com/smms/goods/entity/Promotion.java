package com.smms.goods.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单品限时促销（DR-27，FR-GOODS-09）：同一商品同一时间段
 * 仅允许一条生效促销（BR-GOODS-04，应用层校验）；到期由定时任务失效（IR-SW-06）。
 */
@Data
@TableName("promotion")
public class Promotion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long goodsId;

    /** 促销价（须低于零售价，BR-GOODS-03） */
    private BigDecimal promoPrice;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 状态：ACTIVE生效 / EXPIRED过期 / DISABLED停用 */
    private String status;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;

    private String remark;
}

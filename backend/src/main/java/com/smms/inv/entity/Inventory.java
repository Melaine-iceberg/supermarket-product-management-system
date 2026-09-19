package com.smms.inv.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存结存（DR-11）：与商品 1:1，派生数据，只能由库存流水驱动变更（BR-INV-11）。
 */
@Data
@TableName("inventory")
public class Inventory {

    /** 商品ID（主键，与商品1:1） */
    @TableId
    private Long goodsId;

    /** 门店ID（多门店预留，本期恒1） */
    private Long storeId;

    /** 结存数量（仅当允许负库存时可为负，BR-INV-02） */
    private BigDecimal quantity;

    /** 成本单价（移动加权平均，BR-INV-06） */
    private BigDecimal costPrice;

    /** 库存成本金额 = 结存×成本单价 */
    private BigDecimal costAmount;

    /** 最近变动时间 */
    private LocalDateTime lastChangeTime;

    /** 版本号（乐观锁，BR-INV-04） */
    @Version
    private Integer version;

    private LocalDateTime updateTime;
}

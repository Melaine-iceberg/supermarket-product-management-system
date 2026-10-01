package com.smms.supplier.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 供应商-商品关联及供货价（DR-06，FR-SUP-04）：
 * 一个商品可对应多个供应商并指定默认供应商（唯一键：supplier_id+goods_id）。
 */
@Data
@TableName("supplier_goods")
public class SupplierGoods {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long supplierId;

    private Long goodsId;

    /** 供货单价 */
    private BigDecimal supplyPrice;

    /** 是否默认供应商：1是 0否（每个商品至多一个默认供应商） */
    private Integer isDefault;

    /** 最近供货时间（入库验收时回写，人工维护不可修改） */
    private LocalDateTime lastSupplyTime;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}

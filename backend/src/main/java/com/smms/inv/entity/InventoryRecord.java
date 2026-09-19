package com.smms.inv.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存流水（DR-12）：不可变，只增不改不删（BR-INV-04），
 * 审计与成本核算核心；结存 = 期初 + Σ流水数量（BR-INV-08）。
 */
@Data
@TableName("inventory_record")
public class InventoryRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long goodsId;

    /** 门店ID（预留） */
    private Long storeId;

    /** 变动类型：INIT/PURCHASE_IN/PURCHASE_RETURN/SALE_OUT/SALE_RETURN/CHECK_SURPLUS/CHECK_SHORTAGE/LOSS_OUT/OVERFLOW_IN（§8.2） */
    private String recordType;

    /** 变动数量（入库+ 出库-，BR-INV-05） */
    private BigDecimal changeQty;

    /** 变动前结存 */
    private BigDecimal beforeQty;

    /** 变动后结存 */
    private BigDecimal afterQty;

    /** 单位成本 */
    private BigDecimal unitCost;

    /** 关联单据类型：PO/RK/PT/XS/XT/PD/SZ/BY */
    private String bizType;

    /** 关联单据号 */
    private String bizNo;

    /** 批次号（预留，本期恒空） */
    private String batchNo;

    /** 操作人 */
    private String operatorName;

    /** 操作时间 */
    private LocalDateTime operateTime;

    private String remark;
}

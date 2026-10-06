package com.smms.sale.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 销售明细（DR-17）：全部为快照字段。商品删除校验（FR-GOODS-06）
 * 依赖本表统计，销售模块完整实现见后续步骤。
 */
@Data
@TableName("sales_order_item")
public class SalesOrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    /** 商品ID（无码商品为空） */
    private Long goodsId;

    /** 商品名称快照 */
    private String goodsName;

    /** 条码快照 */
    private String barcode;

    /** 成交取价（促销价/会员价/零售价；改价行为店长改价单价，BR-GOODS-04） */
    private BigDecimal unitPrice;

    /** 改价前按取价规则解析的单价（未改价为空，FR-SALE-07） */
    private BigDecimal originalUnitPrice;

    /** 数量（普通正整数/称重3位小数） */
    private BigDecimal quantity;

    /** 是否称重商品：1是 0否 */
    private Integer isWeighed;

    /** 净重（称重商品） */
    private BigDecimal netWeight;

    /** 行金额（折扣前） */
    private BigDecimal amount;

    /** 行最终折扣率 */
    private BigDecimal discountRate;

    /** 行优惠额 */
    private BigDecimal discountAmount;

    /** 行实付金额 */
    private BigDecimal paidAmount;

    /** 成本单价快照 */
    private BigDecimal costPrice;

    /** 成本额快照 */
    private BigDecimal costAmount;

    /** 毛利额 */
    private BigDecimal profitAmount;

    /** 已退数量 */
    private BigDecimal returnedQty;

    /** 行类型：NORMAL档案商品 / TEMP无码商品 */
    private String lineType;

    private String remark;

    /** 改价原因（FR-SALE-07） */
    private String priceReason;
}

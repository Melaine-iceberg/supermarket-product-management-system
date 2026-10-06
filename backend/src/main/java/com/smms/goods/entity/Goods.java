package com.smms.goods.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品档案（DR-02，FR-GOODS-01~13）：核心主数据，一 SKU 一条。
 * 本步骤先建立映射供分类模块做关联校验（BR-CAT-03），档案维护在 GOODS 步骤实现。
 */
@Data
@TableName("goods")
public class Goods {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 商品编码：分类码+流水（BR-GOODS-02），创建后不可修改 */
    private String goodsCode;

    /** 商品条码（EAN-13/店内码，全局唯一，BR-GOODS-01） */
    private String barcode;

    private String name;

    /** 名称拼音首字母简称（收银台关键字检索，FR-SALE-02；随名称自动生成） */
    private String pinyinInitial;

    /** 所属分类（必须是叶子分类，BR-CAT-04） */
    private Long categoryId;

    private Long brandId;

    private Long unitId;

    /** 规格 */
    private String spec;

    /** 产地 */
    private String origin;

    /** 进货参考价 ≥0 */
    private BigDecimal purchasePrice;

    /** 零售单价 >0 */
    private BigDecimal retailPrice;

    /** 会员价 ≤零售价 */
    private BigDecimal memberPrice;

    /** PLU 码（称重商品必填，BR-GOODS-05） */
    private String pluCode;

    /** 是否称重商品：1是 0否 */
    private Integer isWeighed;

    /** 是否允许退货：1是 0否（BR-SALE-07） */
    private Integer allowReturn;

    /** 安全库存下限 */
    private BigDecimal safetyStockLower;

    /** 库存上限（积压预警） */
    private BigDecimal safetyStockUpper;

    /** 图片相对路径 */
    private String image;

    /** 状态：1启用 0停用 */
    private Integer status;

    private String remark;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}

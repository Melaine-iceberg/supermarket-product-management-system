package com.smms.sale.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 销售单（DR-16）：仅"已完成"的正式单据落库（§2.2.2），
 * 结算成功后不可修改、不可删除（BR-SALE-08），错误只能通过退货单冲正。
 */
@Data
@TableName("sales_order")
public class SalesOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 销售单号：XS+yyyyMMdd+收银台2位+4位流水（BR-SALE-02） */
    private String orderNo;

    /** 门店ID（预留，本期恒为 1） */
    private Long storeId;

    /** 收银台号（01~99） */
    private Integer counterNo;

    private Long cashierId;

    private String cashierName;

    /** 班次ID（交班模块启用前为空） */
    private Long shiftId;

    /** 会员ID（散客为空） */
    private Long memberId;

    /** 销售时间（结算时间） */
    private LocalDateTime saleTime;

    /** 营业日（BR-RPT-02） */
    private LocalDate businessDate;

    /** 件数 */
    private Integer itemCount;

    /** 商品总额（折扣前） */
    private BigDecimal goodsAmount;

    /** 会员优惠额 */
    private BigDecimal memberDiscountAmount;

    /** 店长整单优惠额 */
    private BigDecimal orderDiscountAmount;

    /** 优惠合计 = Σ明细优惠金额 */
    private BigDecimal discountAmount;

    /** 应收金额 = Σ行实付金额 */
    private BigDecimal payableAmount;

    /** 实收金额 = Σ收款记录金额 */
    private BigDecimal paidAmount;

    /** 找零 = 实收 − 应收 */
    private BigDecimal changeAmount;

    /** 店长整单折扣率（≥最低折扣率参数） */
    private BigDecimal orderDiscountRate;

    /** 整单折扣原因（FR-SALE-07） */
    private String discountReason;

    /** 成本合计（快照） */
    private BigDecimal costAmount;

    /** 毛利额 = 应收 − 成本合计 */
    private BigDecimal profitAmount;

    /** 退货状态：NONE无/PARTIAL部分/ALL全部 */
    private String returnStatus;

    /** 店长授权人（整单折扣时） */
    private String authorizerName;

    private String remark;

    /** 请求令牌（提交防重，NFR-REL-02；uk_so_token 唯一键兜底并发重放） */
    private String requestToken;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}

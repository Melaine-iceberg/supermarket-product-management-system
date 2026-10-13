package com.smms.sys.dto;

import java.math.BigDecimal;

/**
 * 前端业务参数（BR-SALE-05/10、FR-SALE-11）：折扣下限、退货授权阈值、退货有效期，
 * 与后端校验同源（min_discount_rate / return_auth_amount / return_valid_days），
 * 任意已登录用户可读，避免前端硬编码导致与参数设置不一致。
 */
public record BusinessParamsVO(
        /** 整单折扣率下限（如 0.7，BR-SALE-10） */
        BigDecimal minDiscountRate,
        /** 退款金额超过该阈值须店长授权（元，FR-SALE-11） */
        BigDecimal returnAuthAmount,
        /** 退货有效期（天，BR-SALE-05） */
        Integer returnValidDays,
        /** 无结束符扫码枪：按输入间隔 ≤50ms 判定扫码结束后自动查询（IR-HW-01③） */
        Boolean barcodeAutoQuery
) {
}

package com.smms.sys.dto;

/**
 * 打印页眉参数（IR-UI-08）：超市名称/地址/电话取自系统参数，小票纸宽 58 或 80 mm。
 * 任意已登录用户可读，收银员无 sys:config:list 权限也能打印带页眉的小票。
 */
public record PrintInfoVO(
        String storeName,
        String storeAddress,
        String storePhone,
        Integer receiptPaperWidth
) {
}
